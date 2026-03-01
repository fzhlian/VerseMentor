import { describe, expect, test } from 'vitest'
import type {
  AsrEvent,
  ISpeechIOv3,
  ListenOptions,
  SpeakEvent,
  SpeakOptions
} from '../src/ports/speech_v3'
import type { IAudioOutput } from '../src/speech/audio_ports'
import { FullDuplexArbiter } from '../src/speech/full_duplex_arbiter'

class MockSpeechIOv3 implements ISpeechIOv3 {
  listening = false
  speaking = false
  stopSpeakCalls = 0
  stopListeningCalls = 0

  private asrHandlers = new Set<(event: AsrEvent) => void>()
  private speakHandlers = new Set<(event: SpeakEvent) => void>()

  async startListening(_opts: ListenOptions): Promise<void> {
    this.listening = true
  }

  async stopListening(): Promise<void> {
    this.listening = false
    this.stopListeningCalls += 1
  }

  onAsrEvent(cb: (event: AsrEvent) => void): () => void {
    this.asrHandlers.add(cb)
    return () => {
      this.asrHandlers.delete(cb)
    }
  }

  async speak(_text: string, _opts: SpeakOptions): Promise<void> {
    this.speaking = true
    this.emitSpeak({ type: 'start' })
  }

  async stopSpeak(): Promise<void> {
    this.stopSpeakCalls += 1
    if (!this.speaking) return
    this.speaking = false
    this.emitSpeak({ type: 'end' })
  }

  onSpeakEvent(cb: (event: SpeakEvent) => void): () => void {
    this.speakHandlers.add(cb)
    return () => {
      this.speakHandlers.delete(cb)
    }
  }

  async listVoices(): Promise<Array<{ id: string; name: string }>> {
    return []
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
      activeProvider: 'mock'
    }
  }

  emitAsr(event: AsrEvent): void {
    for (const handler of this.asrHandlers) {
      handler(event)
    }
  }

  emitSpeak(event: SpeakEvent): void {
    if (event.type === 'end' || event.type === 'error') {
      this.speaking = false
    }
    for (const handler of this.speakHandlers) {
      handler(event)
    }
  }
}

class MockAudioOutput implements IAudioOutput {
  readonly volumes: number[] = []

  async playPcmStream(): Promise<void> {}

  async playUrl(): Promise<void> {}

  async setVolume(vol: number): Promise<void> {
    this.volumes.push(vol)
  }

  async stop(): Promise<void> {}
}

const BASE_LISTEN_OPTIONS: ListenOptions = {
  lang: 'zh-CN',
  enablePartial: true,
  vad: {},
  fullDuplex: {
    allowListeningDuringSpeaking: true,
    bargeInMode: 'none'
  }
}

const BASE_SPEAK_OPTIONS: SpeakOptions = {
  voiceId: 'default'
}

async function flush(): Promise<void> {
  await Promise.resolve()
}

describe('FullDuplexArbiter (v3)', () => {
  test('while speaking, listening stays true in full-duplex mode', async () => {
    const io = new MockSpeechIOv3()
    const arbiter = new FullDuplexArbiter()
    arbiter.attach(io)

    await arbiter.startListening(io, BASE_LISTEN_OPTIONS)
    await arbiter.speak(io, 'hello', BASE_SPEAK_OPTIONS, BASE_LISTEN_OPTIONS)

    expect(arbiter.state.listening).toBe(true)
    expect(io.stopListeningCalls).toBe(0)
  })

  test('ducking triggers on speech_start and recovers on speech_end', async () => {
    const io = new MockSpeechIOv3()
    const output = new MockAudioOutput()
    const arbiter = new FullDuplexArbiter(output)
    const listenOpts: ListenOptions = {
      ...BASE_LISTEN_OPTIONS,
      fullDuplex: {
        allowListeningDuringSpeaking: true,
        bargeInMode: 'duck_tts',
        duckVolume: 0.35
      }
    }
    arbiter.attach(io)

    await arbiter.startListening(io, listenOpts)
    await arbiter.speak(io, 'hello', BASE_SPEAK_OPTIONS, listenOpts)

    io.emitAsr({ type: 'vad', state: 'speech_start' })
    await flush()
    expect(arbiter.state.currentDuck).toBe(0.35)
    expect(output.volumes.at(-1)).toBe(0.35)

    io.emitAsr({ type: 'vad', state: 'speech_end' })
    await flush()
    expect(arbiter.state.currentDuck).toBe(1)
    expect(output.volumes.at(-1)).toBe(1)
  })

  test('stop_tts_on_speech stops speaking upon speech_start', async () => {
    const io = new MockSpeechIOv3()
    const arbiter = new FullDuplexArbiter()
    const listenOpts: ListenOptions = {
      ...BASE_LISTEN_OPTIONS,
      fullDuplex: {
        allowListeningDuringSpeaking: true,
        bargeInMode: 'stop_tts_on_speech'
      }
    }
    arbiter.attach(io)

    await arbiter.startListening(io, listenOpts)
    await arbiter.speak(io, 'hello', BASE_SPEAK_OPTIONS, listenOpts)
    expect(arbiter.state.speaking).toBe(true)

    io.emitAsr({ type: 'vad', state: 'speech_start' })
    await flush()

    expect(io.stopSpeakCalls).toBe(1)
    expect(arbiter.state.speaking).toBe(false)
  })
})
