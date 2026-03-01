import { describe, expect, test } from 'vitest'
import type { AsrEvent, ListenOptions, SpeakEvent, SpeakOptions } from '../src/ports/speech_v3'
import type { IAudioCapture, IAudioOutput } from '../src/speech/audio_ports'
import type { ISpeechProvider } from '../src/speech/provider'
import { SpeechIOv3Impl } from '../src/speech/speech_io_v3_impl'

class MockProvider implements ISpeechProvider {
  name = 'mock-provider'

  initCalls = 0
  asrStartCalls: Array<{ lang: 'zh-CN'; partial: boolean; aec?: boolean; ns?: boolean }> = []
  asrPcmFrames: ArrayBuffer[] = []
  asrStopCalls = 0
  ttsStartCalls: Array<{ text: string; opts: SpeakOptions }> = []
  ttsStopCalls = 0

  ttsStartMode: 'none' | 'url' | 'pcm' = 'none'

  private asrHandlers = new Set<(e: AsrEvent) => void>()
  private ttsHandlers = new Set<(e: SpeakEvent) => void>()

  async init(): Promise<void> {
    this.initCalls += 1
  }

  async dispose(): Promise<void> {}

  async asrStart(params: { lang: 'zh-CN'; partial: boolean; aec?: boolean; ns?: boolean }): Promise<void> {
    this.asrStartCalls.push(params)
  }

  async asrPushPcm(pcm: ArrayBuffer): Promise<void> {
    this.asrPcmFrames.push(pcm)
  }

  async asrStop(): Promise<void> {
    this.asrStopCalls += 1
  }

  onAsr(cb: (e: AsrEvent) => void): () => void {
    this.asrHandlers.add(cb)
    return () => {
      this.asrHandlers.delete(cb)
    }
  }

  async ttsStart(text: string, opts: SpeakOptions): Promise<void> {
    this.ttsStartCalls.push({ text, opts })
    if (this.ttsStartMode === 'url') {
      this.emitTts({
        type: 'start',
        url: 'https://tts.example/audio.mp3'
      })
      return
    }
    if (this.ttsStartMode === 'pcm') {
      this.emitTts({
        type: 'start',
        pcmStream: (async function* () {
          yield new ArrayBuffer(8)
        })()
      })
      return
    }
    this.emitTts({ type: 'start' })
  }

  async ttsStop(): Promise<void> {
    this.ttsStopCalls += 1
  }

  onTts(cb: (e: SpeakEvent) => void): () => void {
    this.ttsHandlers.add(cb)
    return () => {
      this.ttsHandlers.delete(cb)
    }
  }

  async listVoices(): Promise<Array<{ id: string; name: string }>> {
    return [{ id: 'v1', name: 'Voice 1' }]
  }

  emitTts(event: SpeakEvent): void {
    for (const handler of this.ttsHandlers) {
      handler(event)
    }
  }
}

class MockCapture implements IAudioCapture {
  startCalls = 0
  stopCalls = 0
  private running = false

  constructor(private readonly frames: ArrayBuffer[]) {}

  start(_cfg: { sampleRate: 16000; channels: 1; frameMs: 20 | 40; aec?: boolean; ns?: boolean }): AsyncIterable<ArrayBuffer> {
    this.startCalls += 1
    this.running = true
    const self = this
    const frames = this.frames.slice()
    return (async function* () {
      for (const frame of frames) {
        if (!self.running) break
        yield frame
        await Promise.resolve()
      }
    })()
  }

  async stop(): Promise<void> {
    this.stopCalls += 1
    this.running = false
  }
}

class MockOutput implements IAudioOutput {
  playUrlCalls: string[] = []
  playPcmCalls = 0
  stopCalls = 0
  setVolumeCalls: number[] = []

  async playPcmStream(
    stream: AsyncIterable<ArrayBuffer>,
    _cfg: { sampleRate: 16000; channels: 1 }
  ): Promise<void> {
    this.playPcmCalls += 1
    for await (const _chunk of stream) {
      // consume
    }
  }

  async playUrl(url: string): Promise<void> {
    this.playUrlCalls.push(url)
  }

  async setVolume(vol: number): Promise<void> {
    this.setVolumeCalls.push(vol)
  }

  async stop(): Promise<void> {
    this.stopCalls += 1
  }
}

const BASE_LISTEN_OPTS: ListenOptions = {
  lang: 'zh-CN',
  enablePartial: true,
  vad: {},
  fullDuplex: {
    allowListeningDuringSpeaking: true,
    bargeInMode: 'none'
  },
  aec: true,
  ns: true
}

const BASE_SPEAK_OPTS: SpeakOptions = {
  voiceId: 'v1'
}

async function flushMicrotasks(times: number = 2): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await Promise.resolve()
  }
}

describe('SpeechIOv3Impl', () => {
  test('startListening starts ASR and pushes captured PCM frames', async () => {
    const frame = new ArrayBuffer(16)
    const provider = new MockProvider()
    const capture = new MockCapture([frame])
    const output = new MockOutput()
    const io = new SpeechIOv3Impl(provider, capture, output)

    await io.startListening(BASE_LISTEN_OPTS)
    await flushMicrotasks()

    expect(provider.initCalls).toBe(1)
    expect(provider.asrStartCalls).toEqual([
      {
        lang: 'zh-CN',
        partial: true,
        aec: true,
        ns: true
      }
    ])
    expect(provider.asrPcmFrames.length).toBeGreaterThanOrEqual(1)
  })

  test('stopListening stops capture and provider ASR', async () => {
    const provider = new MockProvider()
    const capture = new MockCapture([new ArrayBuffer(4)])
    const output = new MockOutput()
    const io = new SpeechIOv3Impl(provider, capture, output)

    await io.startListening(BASE_LISTEN_OPTS)
    await io.stopListening()

    expect(capture.stopCalls).toBe(1)
    expect(provider.asrStopCalls).toBe(1)
  })

  test('speak routes URL start payload to output.playUrl', async () => {
    const provider = new MockProvider()
    provider.ttsStartMode = 'url'
    const capture = new MockCapture([])
    const output = new MockOutput()
    const io = new SpeechIOv3Impl(provider, capture, output)

    await io.speak('hello', BASE_SPEAK_OPTS)
    await flushMicrotasks()

    expect(output.playUrlCalls).toEqual(['https://tts.example/audio.mp3'])
  })

  test('speak routes PCM start payload to output.playPcmStream', async () => {
    const provider = new MockProvider()
    provider.ttsStartMode = 'pcm'
    const capture = new MockCapture([])
    const output = new MockOutput()
    const io = new SpeechIOv3Impl(provider, capture, output)

    await io.speak('hello', BASE_SPEAK_OPTS)
    await flushMicrotasks()

    expect(output.playPcmCalls).toBe(1)
  })

  test('speaking does not cancel listening when allowListeningDuringSpeaking=true', async () => {
    const provider = new MockProvider()
    provider.ttsStartMode = 'url'
    const capture = new MockCapture([new ArrayBuffer(8)])
    const output = new MockOutput()
    const io = new SpeechIOv3Impl(provider, capture, output)

    await io.startListening({
      ...BASE_LISTEN_OPTS,
      fullDuplex: {
        allowListeningDuringSpeaking: true,
        bargeInMode: 'duck_tts',
        duckVolume: 0.3
      }
    })
    await io.speak('hello', BASE_SPEAK_OPTS)
    await flushMicrotasks()

    expect(capture.stopCalls).toBe(0)
    expect(provider.asrStopCalls).toBe(0)
  })
})
