import type {
  AsrEvent,
  ISpeechIOv3,
  ListenOptions,
  SpeakEvent,
  SpeakOptions,
  SpeechStatus,
  VoiceInfo
} from '../ports/speech_v3'
import type { IAudioCapture, IAudioOutput } from './audio_ports'
import type { ISpeechProvider } from './provider'

const DEFAULT_CAPTURE_FRAME_MS: 20 | 40 = 20

export class SpeechIOv3Impl implements ISpeechIOv3 {
  private readonly asrListeners = new Set<(event: AsrEvent) => void>()
  private readonly speakListeners = new Set<(event: SpeakEvent) => void>()

  private readonly providerAsrUnsub: () => void
  private readonly providerTtsUnsub: () => void

  private initPromise: Promise<void> | null = null
  private initialized = false
  private listening = false
  private speaking = false
  private lastError?: string
  private activeListenOptions?: ListenOptions
  private restartListenAfterSpeak?: ListenOptions

  private captureLoopTask: Promise<void> | null = null
  private captureLoopGeneration = 0

  constructor(
    private readonly provider: ISpeechProvider,
    private readonly capture: IAudioCapture,
    private readonly output: IAudioOutput
  ) {
    this.providerAsrUnsub = this.provider.onAsr((event) => {
      this.forwardAsrEvent(event)
    })
    this.providerTtsUnsub = this.provider.onTts((event) => {
      void this.handleSpeakEvent(event)
    })
  }

  async startListening(opts: ListenOptions): Promise<void> {
    await this.ensureInitialized()
    if (this.listening) {
      await this.stopListening()
    }

    this.activeListenOptions = opts
    this.listening = true

    await this.provider.asrStart({
      lang: opts.lang,
      partial: opts.enablePartial,
      aec: opts.aec,
      ns: opts.ns
    })

    const generation = ++this.captureLoopGeneration
    const stream = this.capture.start({
      sampleRate: 16000,
      channels: 1,
      frameMs: DEFAULT_CAPTURE_FRAME_MS,
      aec: opts.aec,
      ns: opts.ns
    })
    this.captureLoopTask = this.runCaptureLoop(stream, generation)
  }

  async stopListening(): Promise<void> {
    const shouldStop = this.listening || this.captureLoopTask !== null
    this.listening = false
    this.activeListenOptions = undefined
    this.captureLoopGeneration += 1

    if (!shouldStop) {
      return
    }

    await this.capture.stop()
    await this.provider.asrStop()

    if (this.captureLoopTask) {
      await this.captureLoopTask
      this.captureLoopTask = null
    }
  }

  onAsrEvent(cb: (event: AsrEvent) => void): () => void {
    this.asrListeners.add(cb)
    return () => {
      this.asrListeners.delete(cb)
    }
  }

  async speak(text: string, opts: SpeakOptions): Promise<void> {
    await this.ensureInitialized()

    const currentListenOptions = this.activeListenOptions
    const allowDuringSpeak = currentListenOptions?.fullDuplex.allowListeningDuringSpeaking ?? true
    if (this.listening && !allowDuringSpeak && currentListenOptions) {
      this.restartListenAfterSpeak = currentListenOptions
      await this.stopListening()
    } else {
      this.restartListenAfterSpeak = undefined
    }

    await this.provider.ttsStart(text, opts)
  }

  async stopSpeak(): Promise<void> {
    this.speaking = false
    this.restartListenAfterSpeak = undefined
    await this.provider.ttsStop()
    await this.output.stop()
  }

  onSpeakEvent(cb: (event: SpeakEvent) => void): () => void {
    this.speakListeners.add(cb)
    return () => {
      this.speakListeners.delete(cb)
    }
  }

  async listVoices(): Promise<VoiceInfo[]> {
    await this.ensureInitialized()
    return this.provider.listVoices()
  }

  async getStatus(): Promise<SpeechStatus> {
    return {
      asrReady: this.initialized,
      ttsReady: this.initialized,
      activeProvider: this.provider.name,
      lastError: this.lastError
    }
  }

  private async ensureInitialized(): Promise<void> {
    if (this.initialized) {
      return
    }
    if (!this.initPromise) {
      this.initPromise = this.provider
        .init()
        .then(() => {
          this.initialized = true
        })
        .catch((err: unknown) => {
          this.lastError = this.errorToMessage(err)
          throw err
        })
    }
    await this.initPromise
  }

  private async runCaptureLoop(
    stream: AsyncIterable<ArrayBuffer>,
    generation: number
  ): Promise<void> {
    try {
      for await (const frame of stream) {
        if (!this.listening || generation !== this.captureLoopGeneration) {
          break
        }
        await this.provider.asrPushPcm(frame)
      }
    } catch (err: unknown) {
      this.lastError = this.errorToMessage(err)
      this.forwardAsrEvent({
        type: 'error',
        code: 'capture-loop-error',
        message: this.lastError
      })
    }
  }

  private async handleSpeakEvent(event: SpeakEvent): Promise<void> {
    if (event.type === 'start') {
      this.speaking = true
      void this.startOutputPlayback(event)
    } else if (event.type === 'end' || event.type === 'error') {
      this.speaking = false
      if (event.type === 'error') {
        this.lastError = `${event.code}:${event.message}`
      }
      await this.maybeRestartListeningAfterSpeak()
    }

    this.forwardSpeakEvent(event)
  }

  private async startOutputPlayback(event: Extract<SpeakEvent, { type: 'start' }>): Promise<void> {
    try {
      if (event.pcmStream) {
        await this.output.playPcmStream(event.pcmStream, {
          sampleRate: event.sampleRate ?? 16000,
          channels: event.channels ?? 1
        })
        return
      }
      if (event.url) {
        await this.output.playUrl(event.url)
      }
    } catch (err: unknown) {
      const message = this.errorToMessage(err)
      this.lastError = `output-playback-error:${message}`
      this.forwardSpeakEvent({
        type: 'error',
        code: 'output-playback-error',
        message
      })
    }
  }

  private async maybeRestartListeningAfterSpeak(): Promise<void> {
    if (!this.restartListenAfterSpeak) {
      return
    }
    const opts = this.restartListenAfterSpeak
    this.restartListenAfterSpeak = undefined
    await this.startListening(opts)
  }

  private forwardAsrEvent(event: AsrEvent): void {
    if (event.type === 'error') {
      this.lastError = `${event.code}:${event.message}`
    }
    for (const listener of this.asrListeners) {
      listener(event)
    }
  }

  private forwardSpeakEvent(event: SpeakEvent): void {
    if (event.type === 'error') {
      this.lastError = `${event.code}:${event.message}`
    }
    for (const listener of this.speakListeners) {
      listener(event)
    }
  }

  private errorToMessage(err: unknown): string {
    if (err instanceof Error) {
      return err.message
    }
    if (typeof err === 'string') {
      return err
    }
    return 'unknown-error'
  }

  // Optional explicit cleanup for embedding runtimes.
  async dispose(): Promise<void> {
    this.providerAsrUnsub()
    this.providerTtsUnsub()
    await this.stopSpeak()
    await this.stopListening()
    await this.provider.dispose()
  }
}
