import { describe, expect, test } from 'vitest'
import type { SpeakOptions } from '../src/ports/speech_v3'
import {
  IflytekProvider,
  type IflytekAsrCallbacks,
  type IflytekAsrSession,
  type IflytekClient
} from '../src/providers/iflytek/IflytekProvider'

class MockAsrSession implements IflytekAsrSession {
  pushed: ArrayBuffer[] = []
  stopped = false

  async pushAudio(pcm: ArrayBuffer): Promise<void> {
    this.pushed.push(pcm)
  }

  async stop(): Promise<void> {
    this.stopped = true
  }
}

class MockIflytekClient implements IflytekClient {
  asrSession = new MockAsrSession()
  callbacks?: IflytekAsrCallbacks
  ttsMode: 'url' | 'pcm' = 'url'

  async createAsrSession(
    _params: { lang: 'zh-CN'; partial: boolean; aec?: boolean; ns?: boolean },
    callbacks: IflytekAsrCallbacks
  ): Promise<IflytekAsrSession> {
    this.callbacks = callbacks
    return this.asrSession
  }

  async requestTts(_text: string, _opts: SpeakOptions) {
    if (this.ttsMode === 'url') {
      return {
        type: 'url' as const,
        url: 'https://tts.mock/audio.mp3'
      }
    }
    return {
      type: 'pcm' as const,
      stream: (async function* () {
        yield new ArrayBuffer(4)
      })()
    }
  }
}

describe('IflytekProvider', () => {
  test('reads credentials from injected env and maps ASR callbacks', async () => {
    const client = new MockIflytekClient()
    const events: Array<{ type: string; text?: string; state?: string; level?: number }> = []
    const provider = new IflytekProvider({
      config: {
        env: {
          IFLYTEK_APP_ID: 'app-id',
          IFLYTEK_API_KEY: 'api-key',
          IFLYTEK_API_SECRET: 'api-secret'
        }
      },
      clientFactory: () => client
    })

    provider.onAsr((event) => {
      events.push(event)
    })

    await provider.init()
    await provider.asrStart({ lang: 'zh-CN', partial: true, aec: true, ns: true })
    client.callbacks?.onReady?.()
    client.callbacks?.onPartial?.('静夜', 0.81)
    client.callbacks?.onFinal?.('静夜思', 0.95)
    client.callbacks?.onVad?.('speech_start')
    client.callbacks?.onVolume?.(0.4)
    await provider.asrPushPcm(new ArrayBuffer(8))
    await provider.asrStop()

    expect(events.map((e) => e.type)).toEqual([
      'ready',
      'partial',
      'final',
      'vad',
      'volume'
    ])
    expect(events[1]).toMatchObject({ type: 'partial', text: '静夜' })
    expect(events[2]).toMatchObject({ type: 'final', text: '静夜思' })
    expect(client.asrSession.pushed.length).toBe(1)
    expect(client.asrSession.stopped).toBe(true)
  })

  test('emits tts start with url and pcm stream modes', async () => {
    const client = new MockIflytekClient()
    const speakEvents: Array<{ type: string; url?: string; hasPcm?: boolean }> = []
    const provider = new IflytekProvider({
      config: {
        env: {
          IFLYTEK_APP_ID: 'app-id',
          IFLYTEK_API_KEY: 'api-key',
          IFLYTEK_API_SECRET: 'api-secret'
        }
      },
      clientFactory: () => client
    })
    provider.onTts((event) => {
      speakEvents.push({
        type: event.type,
        url: event.type === 'start' ? event.url : undefined,
        hasPcm: event.type === 'start' ? !!event.pcmStream : undefined
      })
    })

    await provider.ttsStart('你好', { voiceId: 'xiaoyan' })
    client.ttsMode = 'pcm'
    await provider.ttsStart('你好', { voiceId: 'xiaoyan' })

    expect(speakEvents[0]).toMatchObject({
      type: 'start',
      url: 'https://tts.mock/audio.mp3'
    })
    expect(speakEvents.some((e) => e.type === 'start' && e.hasPcm)).toBe(true)
  })

  test('throws if required config is missing', async () => {
    const provider = new IflytekProvider({
      config: {
        env: {}
      },
      clientFactory: () => new MockIflytekClient()
    })

    await expect(provider.init()).rejects.toThrow('iflytek-config-missing')
  })
})
