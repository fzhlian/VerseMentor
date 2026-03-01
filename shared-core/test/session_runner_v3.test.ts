import { describe, expect, test, vi } from 'vitest'
import type {
  AsrEvent,
  ISpeechIOv3,
  ListenOptions,
  SpeakEvent,
  SpeakOptions
} from '../src/ports/speech_v3'
import { SessionRunnerV3 } from '../src/bridge/session_runner_v3'

class MockSpeechIOv3 implements ISpeechIOv3 {
  listening = false
  speaking = false
  activeProvider = 'mock'
  startListeningCalls: ListenOptions[] = []
  speakCalls: Array<{ text: string; opts: SpeakOptions }> = []
  stopListeningCalls = 0
  stopSpeakCalls = 0

  private readonly asrListeners = new Set<(event: AsrEvent) => void>()
  private readonly speakListeners = new Set<(event: SpeakEvent) => void>()

  async startListening(opts: ListenOptions): Promise<void> {
    this.listening = true
    this.startListeningCalls.push(opts)
  }

  async stopListening(): Promise<void> {
    this.listening = false
    this.stopListeningCalls += 1
  }

  onAsrEvent(cb: (event: AsrEvent) => void): () => void {
    this.asrListeners.add(cb)
    return () => {
      this.asrListeners.delete(cb)
    }
  }

  async speak(text: string, opts: SpeakOptions): Promise<void> {
    this.speaking = true
    this.speakCalls.push({ text, opts })
    this.emitSpeak({ type: 'start' })
  }

  async stopSpeak(): Promise<void> {
    this.stopSpeakCalls += 1
    this.speaking = false
    this.emitSpeak({ type: 'end' })
  }

  onSpeakEvent(cb: (event: SpeakEvent) => void): () => void {
    this.speakListeners.add(cb)
    return () => {
      this.speakListeners.delete(cb)
    }
  }

  async listVoices(): Promise<Array<{ id: string; name: string }>> {
    return [{ id: 'v1', name: 'Voice 1' }]
  }

  async getStatus(): Promise<{
    asrReady: boolean
    ttsReady: boolean
    activeProvider: string
    lastError?: string
  }> {
    return {
      asrReady: true,
      ttsReady: true,
      activeProvider: this.activeProvider
    }
  }

  emitAsr(event: AsrEvent): void {
    for (const listener of this.asrListeners) {
      listener(event)
    }
  }

  emitSpeak(event: SpeakEvent): void {
    if (event.type === 'end' || event.type === 'error') {
      this.speaking = false
    }
    for (const listener of this.speakListeners) {
      listener(event)
    }
  }
}

const BASE_LISTEN_OPTIONS: ListenOptions = {
  lang: 'zh-CN',
  enablePartial: true,
  vad: {},
  fullDuplex: {
    allowListeningDuringSpeaking: true,
    bargeInMode: 'duck_tts',
    duckVolume: 0.25
  },
  aec: false,
  ns: true
}

const BASE_SPEAK_OPTIONS: SpeakOptions = {
  voiceId: 'v1'
}

describe('SessionRunnerV3 integration', () => {
  test('prompt is spoken and final ASR during prompt transitions FSM state', async () => {
    const io = new MockSpeechIOv3()
    const runner = new SessionRunnerV3(io, {
      listenOptions: BASE_LISTEN_OPTIONS,
      speakOptions: BASE_SPEAK_OPTIONS
    })

    await runner.startSession(1700000000000)
    await runner.flush()

    expect(io.speakCalls.length).toBeGreaterThan(0)
    expect(io.speaking).toBe(true)
    expect(io.listening).toBe(true)

    io.emitAsr({ type: 'final', text: '静夜思', confidence: 0.96 })
    await runner.flush()

    const snapshot = runner.getSnapshot()
    expect(snapshot.state.type).toBe('WAIT_DYNASTY_AUTHOR')
    expect(io.listening).toBe(true)
    expect(io.speakCalls.some((call) => call.text.includes('已选择《静夜思》。请说出朝代和作者。'))).toBe(
      true
    )

    await runner.dispose()
  })

  test('ASR error recovery uses exponential backoff and keeps session active', async () => {
    vi.useFakeTimers()
    const io = new MockSpeechIOv3()
    const runner = new SessionRunnerV3(io, {
      listenOptions: {
        ...BASE_LISTEN_OPTIONS,
        vad: { speechEndMs: 80 }
      },
      speakOptions: BASE_SPEAK_OPTIONS
    })

    try {
      await runner.startSession(1700000000000)
      await runner.flush()

      io.emitAsr({ type: 'error', code: 'asr-failure', message: 'broken stream' })
      await runner.flush()

      expect(io.stopListeningCalls).toBeGreaterThan(0)
      const startsAfterErrorAction = io.startListeningCalls.length
      await vi.advanceTimersByTimeAsync(149)
      expect(io.startListeningCalls.length).toBe(startsAfterErrorAction)
      await vi.advanceTimersByTimeAsync(1)
      await Promise.resolve()
      expect(io.startListeningCalls.length).toBe(startsAfterErrorAction + 1)

      io.emitAsr({ type: 'error', code: 'asr-failure', message: 'broken stream again' })
      await runner.flush()
      const startsAfterSecondErrorAction = io.startListeningCalls.length
      await vi.advanceTimersByTimeAsync(299)
      expect(io.startListeningCalls.length).toBe(startsAfterSecondErrorAction)
      await vi.advanceTimersByTimeAsync(1)
      await Promise.resolve()
      expect(io.startListeningCalls.length).toBe(startsAfterSecondErrorAction + 1)

      const snapshot = runner.getSnapshot()
      expect(snapshot.state.type).not.toBe('IDLE')
      expect(snapshot.state.type).not.toBe('EXIT')
    } finally {
      await runner.dispose()
      vi.useRealTimers()
    }
  })

  test('silence-timeout recovery restarts listening without terminating the FSM session', async () => {
    vi.useFakeTimers()
    const io = new MockSpeechIOv3()
    const runner = new SessionRunnerV3(io, {
      listenOptions: {
        ...BASE_LISTEN_OPTIONS,
        vad: { speechEndMs: 300 }
      },
      speakOptions: BASE_SPEAK_OPTIONS
    })

    try {
      await runner.startSession(1700000000000)
      await runner.flush()
      io.emitSpeak({ type: 'end' })

      const startsBeforeTimeout = io.startListeningCalls.length
      await vi.advanceTimersByTimeAsync(300)

      expect(io.stopListeningCalls).toBeGreaterThan(0)
      await vi.advanceTimersByTimeAsync(149)
      expect(io.startListeningCalls.length).toBe(startsBeforeTimeout)
      await vi.advanceTimersByTimeAsync(1)
      await Promise.resolve()
      expect(io.startListeningCalls.length).toBe(startsBeforeTimeout + 1)

      const snapshot = runner.getSnapshot()
      expect(snapshot.state.type).not.toBe('IDLE')
      expect(snapshot.state.type).not.toBe('EXIT')
    } finally {
      await runner.dispose()
      vi.useRealTimers()
    }
  })

  test('full-duplex echo policy enables AEC for iFlytek and prefers ducking for non-AEC providers', async () => {
    const iflytekIo = new MockSpeechIOv3()
    iflytekIo.activeProvider = 'iflytek'
    const iflytekRunner = new SessionRunnerV3(iflytekIo, {
      listenOptions: {
        ...BASE_LISTEN_OPTIONS,
        aec: false,
        fullDuplex: {
          ...BASE_LISTEN_OPTIONS.fullDuplex,
          bargeInMode: 'none'
        }
      },
      speakOptions: BASE_SPEAK_OPTIONS
    })

    await iflytekRunner.startSession()
    await iflytekRunner.flush()
    expect(iflytekIo.startListeningCalls[0]?.aec).toBe(true)
    await iflytekRunner.dispose()

    const volcIo = new MockSpeechIOv3()
    volcIo.activeProvider = 'volc'
    const volcRunner = new SessionRunnerV3(volcIo, {
      listenOptions: {
        ...BASE_LISTEN_OPTIONS,
        aec: false,
        fullDuplex: {
          ...BASE_LISTEN_OPTIONS.fullDuplex,
          bargeInMode: 'none'
        }
      },
      speakOptions: BASE_SPEAK_OPTIONS
    })

    await volcRunner.startSession()
    await volcRunner.flush()
    expect(volcIo.startListeningCalls[0]?.fullDuplex.bargeInMode).toBe('duck_tts')
    expect(volcIo.startListeningCalls[0]?.fullDuplex.duckVolume).toBe(0.25)
    await volcRunner.dispose()
  })
})
