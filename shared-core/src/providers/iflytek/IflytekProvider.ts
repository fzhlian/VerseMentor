import type { AsrEvent, SpeakEvent, SpeakOptions, VadState } from '../../ports/speech_v3'
import type { KVStore } from '../../ports/storage'
import type { ISpeechProvider } from '../../speech/provider'

export interface IflytekCredentials {
  appId: string
  apiKey: string
  apiSecret: string
}

export interface IflytekAsrParams {
  lang: 'zh-CN'
  partial: boolean
  aec?: boolean
  ns?: boolean
}

export interface IflytekAsrCallbacks {
  onReady?: () => void
  onPartial?: (text: string, confidence?: number) => void
  onFinal?: (text: string, confidence?: number) => void
  onVad?: (state: VadState) => void
  onVolume?: (level: number) => void
  onError?: (code: string, message: string) => void
}

export interface IflytekAsrSession {
  pushAudio(pcm: ArrayBuffer): Promise<void>
  stop(): Promise<void>
}

export type IflytekTtsResult =
  | {
      type: 'url'
      url: string
      stop?: () => Promise<void>
    }
  | {
      type: 'pcm'
      stream: AsyncIterable<ArrayBuffer>
      sampleRate?: 16000
      channels?: 1
      stop?: () => Promise<void>
    }

export interface IflytekClient {
  createAsrSession(params: IflytekAsrParams, callbacks: IflytekAsrCallbacks): Promise<IflytekAsrSession>
  requestTts(text: string, opts: SpeakOptions): Promise<IflytekTtsResult>
  dispose?(): Promise<void>
}

export interface IflytekProviderConfig {
  kv?: Pick<KVStore, 'get'>
  env?: Record<string, string | undefined>
  direct?: Partial<IflytekCredentials>
  kvPrefix?: string
  envKeys?: {
    appId?: string
    apiKey?: string
    apiSecret?: string
  }
  voices?: Array<{ id: string; name: string }>
}

export interface IflytekProviderOptions {
  config?: IflytekProviderConfig
  clientFactory: (credentials: IflytekCredentials) => Promise<IflytekClient> | IflytekClient
}

const DEFAULT_ENV_KEYS = {
  appId: 'IFLYTEK_APP_ID',
  apiKey: 'IFLYTEK_API_KEY',
  apiSecret: 'IFLYTEK_API_SECRET'
}

const DEFAULT_VOICES: Array<{ id: string; name: string }> = [
  { id: 'xiaoyan', name: '小燕' },
  { id: 'xiaofeng', name: '小峰' },
  { id: 'xiaoyou', name: '小优' }
]

async function resolveCredentialValue(
  key: keyof IflytekCredentials,
  config?: IflytekProviderConfig
): Promise<string | undefined> {
  const fromDirect = config?.direct?.[key]
  if (typeof fromDirect === 'string' && fromDirect.trim().length > 0) {
    return fromDirect.trim()
  }

  const kvPrefix = config?.kvPrefix ?? 'iflytek'
  const fromKv = await config?.kv?.get(`${kvPrefix}.${key}`)
  if (typeof fromKv === 'string' && fromKv.trim().length > 0) {
    return fromKv.trim()
  }

  const envKey =
    key === 'appId'
      ? config?.envKeys?.appId ?? DEFAULT_ENV_KEYS.appId
      : key === 'apiKey'
        ? config?.envKeys?.apiKey ?? DEFAULT_ENV_KEYS.apiKey
        : config?.envKeys?.apiSecret ?? DEFAULT_ENV_KEYS.apiSecret
  const fromEnv = config?.env?.[envKey]
  if (typeof fromEnv === 'string' && fromEnv.trim().length > 0) {
    return fromEnv.trim()
  }

  return undefined
}

export class IflytekProvider implements ISpeechProvider {
  readonly name = 'iflytek'

  private client: IflytekClient | null = null
  private asrSession: IflytekAsrSession | null = null
  private asrListeners = new Set<(e: AsrEvent) => void>()
  private ttsListeners = new Set<(e: SpeakEvent) => void>()
  private ttsRequestId = 0
  private activeTts: { id: number; stop?: () => Promise<void>; ended: boolean } | null = null

  constructor(private readonly options: IflytekProviderOptions) {}

  async init(): Promise<void> {
    if (this.client) return

    const [appId, apiKey, apiSecret] = await Promise.all([
      resolveCredentialValue('appId', this.options.config),
      resolveCredentialValue('apiKey', this.options.config),
      resolveCredentialValue('apiSecret', this.options.config)
    ])

    const missing: string[] = []
    if (!appId) missing.push('appId')
    if (!apiKey) missing.push('apiKey')
    if (!apiSecret) missing.push('apiSecret')
    if (missing.length > 0) {
      throw new Error(`iflytek-config-missing:${missing.join(',')}`)
    }

    const credentials: IflytekCredentials = {
      appId: appId!,
      apiKey: apiKey!,
      apiSecret: apiSecret!
    }
    this.client = await this.options.clientFactory(credentials)
  }

  async dispose(): Promise<void> {
    await this.ttsStop()
    await this.asrStop()
    const client = this.client
    this.client = null
    if (client?.dispose) {
      await client.dispose()
    }
    this.asrListeners.clear()
    this.ttsListeners.clear()
  }

  async asrStart(params: IflytekAsrParams): Promise<void> {
    const client = await this.requireClient()
    if (this.asrSession) {
      await this.asrStop()
    }
    this.asrSession = await client.createAsrSession(params, {
      onReady: () => {
        this.emitAsr({ type: 'ready' })
      },
      onPartial: (text, confidence) => {
        this.emitAsr({ type: 'partial', text, confidence })
      },
      onFinal: (text, confidence) => {
        this.emitAsr({ type: 'final', text, confidence })
      },
      onVad: (state) => {
        this.emitAsr({ type: 'vad', state })
      },
      onVolume: (level) => {
        const clamped = level < 0 ? 0 : level > 1 ? 1 : level
        this.emitAsr({ type: 'volume', level: clamped })
      },
      onError: (code, message) => {
        this.emitAsr({ type: 'error', code, message })
      }
    })
  }

  async asrPushPcm(pcm: ArrayBuffer): Promise<void> {
    const session = this.asrSession
    if (!session) {
      throw new Error('iflytek-asr-not-started')
    }
    await session.pushAudio(pcm)
  }

  async asrStop(): Promise<void> {
    const session = this.asrSession
    this.asrSession = null
    if (!session) return
    await session.stop()
  }

  onAsr(cb: (e: AsrEvent) => void): () => void {
    this.asrListeners.add(cb)
    return () => {
      this.asrListeners.delete(cb)
    }
  }

  async ttsStart(text: string, opts: SpeakOptions): Promise<void> {
    const client = await this.requireClient()
    const requestId = this.ttsRequestId + 1
    this.ttsRequestId = requestId

    if (this.activeTts && !this.activeTts.ended) {
      await this.ttsStop()
    }

    const result = await client.requestTts(text, opts)
    this.activeTts = {
      id: requestId,
      stop: result.stop,
      ended: false
    }

    if (result.type === 'url') {
      this.emitTts({ type: 'start', url: result.url })
      return
    }

    const wrappedStream = this.wrapTtsPcmStream(requestId, result.stream)
    this.emitTts({
      type: 'start',
      pcmStream: wrappedStream,
      sampleRate: result.sampleRate ?? 16000,
      channels: result.channels ?? 1
    })
  }

  async ttsStop(): Promise<void> {
    const active = this.activeTts
    this.activeTts = null
    if (!active) return

    if (active.stop) {
      await active.stop()
    }
    this.emitTts({ type: 'end' })
  }

  onTts(cb: (e: SpeakEvent) => void): () => void {
    this.ttsListeners.add(cb)
    return () => {
      this.ttsListeners.delete(cb)
    }
  }

  async listVoices(): Promise<Array<{ id: string; name: string }>> {
    return this.options.config?.voices ?? DEFAULT_VOICES
  }

  private async requireClient(): Promise<IflytekClient> {
    if (!this.client) {
      await this.init()
    }
    if (!this.client) {
      throw new Error('iflytek-client-not-initialized')
    }
    return this.client
  }

  private emitAsr(event: AsrEvent): void {
    for (const listener of this.asrListeners) {
      listener(event)
    }
  }

  private emitTts(event: SpeakEvent): void {
    for (const listener of this.ttsListeners) {
      listener(event)
    }
  }

  private wrapTtsPcmStream(
    requestId: number,
    stream: AsyncIterable<ArrayBuffer>
  ): AsyncIterable<ArrayBuffer> {
    const self = this
    return (async function* () {
      try {
        for await (const chunk of stream) {
          if (!self.activeTts || self.activeTts.id !== requestId) {
            break
          }
          yield chunk
        }
      } finally {
        if (self.activeTts && self.activeTts.id === requestId && !self.activeTts.ended) {
          self.activeTts.ended = true
          self.emitTts({ type: 'end' })
        }
      }
    })()
  }
}
