import type { AsrEvent, SpeakEvent, SpeakOptions, VadState } from '../../ports/speech_v3'
import type { KVStore } from '../../ports/storage'
import type { ISpeechProvider } from '../../speech/provider'

export interface VolcCredentials {
  appId: string
  accessKey: string
  secretKey: string
}

export interface VolcAsrParams {
  lang: 'zh-CN'
  partial: boolean
  aec?: boolean
  ns?: boolean
}

export interface VolcAsrCallbacks {
  onReady?: () => void
  onPartial?: (text: string, confidence?: number) => void
  onFinal?: (text: string, confidence?: number) => void
  onVad?: (state: VadState) => void
  onVolume?: (level: number) => void
  onError?: (code: string, message: string) => void
}

export interface VolcAsrSession {
  pushAudio(pcm: ArrayBuffer): Promise<void>
  stop(): Promise<void>
}

export type VolcTtsResult =
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

export interface VolcClient {
  createAsrSession(params: VolcAsrParams, callbacks: VolcAsrCallbacks): Promise<VolcAsrSession>
  requestTts(text: string, opts: SpeakOptions): Promise<VolcTtsResult>
  dispose?(): Promise<void>
}

export interface VolcProviderConfig {
  kv?: Pick<KVStore, 'get'>
  env?: Record<string, string | undefined>
  direct?: Partial<VolcCredentials>
  kvPrefix?: string
  envKeys?: {
    appId?: string
    accessKey?: string
    secretKey?: string
  }
  voices?: Array<{ id: string; name: string }>
}

export interface VolcProviderOptions {
  config?: VolcProviderConfig
  clientFactory: (credentials: VolcCredentials) => Promise<VolcClient> | VolcClient
}

const DEFAULT_ENV_KEYS = {
  appId: 'VOLC_APP_ID',
  accessKey: 'VOLC_ACCESS_KEY',
  secretKey: 'VOLC_SECRET_KEY'
}

const DEFAULT_VOICES: Array<{ id: string; name: string }> = [
  { id: 'zh_female_default', name: 'Volc Female CN' },
  { id: 'zh_male_default', name: 'Volc Male CN' }
]

async function resolveCredentialValue(
  key: keyof VolcCredentials,
  config?: VolcProviderConfig
): Promise<string | undefined> {
  const fromDirect = config?.direct?.[key]
  if (typeof fromDirect === 'string' && fromDirect.trim().length > 0) {
    return fromDirect.trim()
  }

  const kvPrefix = config?.kvPrefix ?? 'volc'
  const fromKv = await config?.kv?.get(`${kvPrefix}.${key}`)
  if (typeof fromKv === 'string' && fromKv.trim().length > 0) {
    return fromKv.trim()
  }

  const envKey =
    key === 'appId'
      ? config?.envKeys?.appId ?? DEFAULT_ENV_KEYS.appId
      : key === 'accessKey'
        ? config?.envKeys?.accessKey ?? DEFAULT_ENV_KEYS.accessKey
        : config?.envKeys?.secretKey ?? DEFAULT_ENV_KEYS.secretKey
  const fromEnv = config?.env?.[envKey]
  if (typeof fromEnv === 'string' && fromEnv.trim().length > 0) {
    return fromEnv.trim()
  }

  return undefined
}

export class VolcProvider implements ISpeechProvider {
  readonly name = 'volc'

  private client: VolcClient | null = null
  private asrSession: VolcAsrSession | null = null
  private asrListeners = new Set<(e: AsrEvent) => void>()
  private ttsListeners = new Set<(e: SpeakEvent) => void>()
  private ttsRequestId = 0
  private activeTts: { id: number; stop?: () => Promise<void>; ended: boolean } | null = null

  constructor(private readonly options: VolcProviderOptions) {}

  async init(): Promise<void> {
    if (this.client) return

    const [appId, accessKey, secretKey] = await Promise.all([
      resolveCredentialValue('appId', this.options.config),
      resolveCredentialValue('accessKey', this.options.config),
      resolveCredentialValue('secretKey', this.options.config)
    ])

    const missing: string[] = []
    if (!appId) missing.push('appId')
    if (!accessKey) missing.push('accessKey')
    if (!secretKey) missing.push('secretKey')
    if (missing.length > 0) {
      throw new Error(`volc-config-missing:${missing.join(',')}`)
    }

    const credentials: VolcCredentials = {
      appId: appId!,
      accessKey: accessKey!,
      secretKey: secretKey!
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

  async asrStart(params: VolcAsrParams): Promise<void> {
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
      throw new Error('volc-asr-not-started')
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

  private async requireClient(): Promise<VolcClient> {
    if (!this.client) {
      await this.init()
    }
    if (!this.client) {
      throw new Error('volc-client-not-initialized')
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
