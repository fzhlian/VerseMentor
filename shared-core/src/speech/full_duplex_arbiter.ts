import type {
  AsrEvent,
  ISpeechIOv3,
  ListenOptions,
  SpeakEvent,
  SpeakOptions
} from '../ports/speech_v3'
import type { IAudioOutput } from './audio_ports'
import type {
  BargeInMode as LegacyBargeInMode,
  SpeechAudioProcessingOptions
} from '../ports/speech'

export interface FullDuplexRuntimeState {
  listening: boolean
  speaking: boolean
  currentDuck: number
}

export interface FullDuplexArbiterOptions {
  volumeThreshold?: number
}

type FullDuplexConfig = ListenOptions['fullDuplex']

const DEFAULT_DUCK_VOLUME = 0.25
const DEFAULT_VOLUME_THRESHOLD = 0.2

function clamp01(value: number): number {
  if (value < 0) return 0
  if (value > 1) return 1
  return value
}

function normalizeFullDuplexConfig(config: FullDuplexConfig): FullDuplexConfig {
  return {
    allowListeningDuringSpeaking: config.allowListeningDuringSpeaking,
    bargeInMode: config.bargeInMode,
    duckVolume: config.duckVolume === undefined ? undefined : clamp01(config.duckVolume)
  }
}

export class FullDuplexArbiter {
  readonly state: FullDuplexRuntimeState = {
    listening: false,
    speaking: false,
    currentDuck: 1
  }

  private config: FullDuplexConfig = {
    allowListeningDuringSpeaking: true,
    bargeInMode: 'duck_tts',
    duckVolume: DEFAULT_DUCK_VOLUME
  }

  private io?: ISpeechIOv3
  private asrUnsub?: () => void
  private speakUnsub?: () => void
  private restartAfterSpeak?: ListenOptions
  private seenVadSinceSpeakStart = false
  private activeSpeechFromVolume = false
  private readonly volumeThreshold: number

  constructor(
    private readonly output?: Pick<IAudioOutput, 'setVolume'>,
    options?: FullDuplexArbiterOptions
  ) {
    this.volumeThreshold = clamp01(options?.volumeThreshold ?? DEFAULT_VOLUME_THRESHOLD)
  }

  configure(opts: ListenOptions['fullDuplex']): void {
    this.config = normalizeFullDuplexConfig(opts)
  }

  attach(io: ISpeechIOv3): void {
    this.detach()
    this.io = io
    this.asrUnsub = io.onAsrEvent((event) => {
      void this.handleAsrEvent(event)
    })
    this.speakUnsub = io.onSpeakEvent((event) => {
      void this.handleSpeakEvent(event)
    })
  }

  async startListening(io: ISpeechIOv3, opts: ListenOptions): Promise<void> {
    this.configure(opts.fullDuplex)
    if (this.io !== io) {
      this.attach(io)
    }
    await io.startListening(opts)
    this.state.listening = true
  }

  async stopListening(io: ISpeechIOv3): Promise<void> {
    await io.stopListening()
    this.state.listening = false
  }

  async speak(
    io: ISpeechIOv3,
    text: string,
    speakOpts: SpeakOptions,
    listenOptsForThisTurn: ListenOptions
  ): Promise<void> {
    this.configure(listenOptsForThisTurn.fullDuplex)
    if (this.io !== io) {
      this.attach(io)
    }

    const wasListening = this.state.listening

    if (!this.config.allowListeningDuringSpeaking && wasListening) {
      await io.stopListening()
      this.state.listening = false
      this.restartAfterSpeak = listenOptsForThisTurn
    } else {
      this.restartAfterSpeak = undefined
    }

    await io.speak(text, speakOpts)
  }

  private async handleAsrEvent(event: AsrEvent): Promise<void> {
    if (!this.state.speaking) {
      return
    }

    if (event.type === 'vad') {
      this.seenVadSinceSpeakStart = true
      this.activeSpeechFromVolume = false
      if (event.state === 'speech_start') {
        await this.onSpeechStart()
      } else {
        await this.onSpeechEnd()
      }
      return
    }

    if (event.type !== 'volume') {
      return
    }

    if (this.seenVadSinceSpeakStart) {
      return
    }

    if (event.level >= this.volumeThreshold) {
      this.activeSpeechFromVolume = true
      await this.onSpeechStart()
      return
    }

    if (this.activeSpeechFromVolume) {
      this.activeSpeechFromVolume = false
      await this.onSpeechEnd()
    }
  }

  private async handleSpeakEvent(event: SpeakEvent): Promise<void> {
    if (event.type === 'start') {
      this.state.speaking = true
      this.seenVadSinceSpeakStart = false
      this.activeSpeechFromVolume = false
      return
    }

    if (event.type === 'end' || event.type === 'error') {
      this.state.speaking = false
      this.seenVadSinceSpeakStart = false
      this.activeSpeechFromVolume = false
      await this.restoreDuck()

      if (this.restartAfterSpeak && this.io) {
        const opts = this.restartAfterSpeak
        this.restartAfterSpeak = undefined
        await this.io.startListening(opts)
        this.state.listening = true
      }
    }
  }

  private async onSpeechStart(): Promise<void> {
    if (!this.state.speaking) return

    if (this.config.bargeInMode === 'stop_tts_on_speech') {
      await this.io?.stopSpeak()
      return
    }

    if (this.config.bargeInMode === 'duck_tts') {
      const target = clamp01(this.config.duckVolume ?? DEFAULT_DUCK_VOLUME)
      await this.applyDuck(target)
    }
  }

  private async onSpeechEnd(): Promise<void> {
    if (this.config.bargeInMode !== 'duck_tts') {
      return
    }
    await this.restoreDuck()
  }

  private async applyDuck(duck: number): Promise<void> {
    const next = clamp01(duck)
    if (this.state.currentDuck === next) {
      return
    }
    this.state.currentDuck = next
    await this.output?.setVolume(next)
  }

  private async restoreDuck(): Promise<void> {
    if (this.state.currentDuck === 1) {
      return
    }
    this.state.currentDuck = 1
    await this.output?.setVolume(1)
  }

  private detach(): void {
    if (this.asrUnsub) {
      this.asrUnsub()
      this.asrUnsub = undefined
    }
    if (this.speakUnsub) {
      this.speakUnsub()
      this.speakUnsub = undefined
    }
  }
}

export interface FullDuplexArbiterConfig {
  allowListeningDuringSpeaking: boolean
  bargeInMode: LegacyBargeInMode
  audioProcessing: SpeechAudioProcessingOptions
}

export interface ListeningDecision {
  allow: boolean
  reason: string
}

export interface SpeakingTransitionDecision {
  stopListening: boolean
  reason: string
}

export interface BargeInDecision {
  duckTts: boolean
  stopTts: boolean
  reason: string
}

export interface FullDuplexArbiterState {
  listening: boolean
  speaking: boolean
  config: FullDuplexArbiterConfig
}

const DEFAULT_CONFIG: FullDuplexArbiterConfig = {
  allowListeningDuringSpeaking: true,
  bargeInMode: 'duck_tts',
  audioProcessing: {
    echoCancellation: true,
    noiseSuppression: true
  }
}

function sanitizeConfig(next?: Partial<FullDuplexArbiterConfig>): FullDuplexArbiterConfig {
  if (!next) {
    return {
      ...DEFAULT_CONFIG,
      audioProcessing: { ...DEFAULT_CONFIG.audioProcessing }
    }
  }
  return {
    allowListeningDuringSpeaking:
      next.allowListeningDuringSpeaking ?? DEFAULT_CONFIG.allowListeningDuringSpeaking,
    bargeInMode: next.bargeInMode ?? DEFAULT_CONFIG.bargeInMode,
    audioProcessing: {
      echoCancellation:
        next.audioProcessing?.echoCancellation ?? DEFAULT_CONFIG.audioProcessing.echoCancellation,
      noiseSuppression:
        next.audioProcessing?.noiseSuppression ?? DEFAULT_CONFIG.audioProcessing.noiseSuppression
    }
  }
}

export class FullDuplexAudioArbiter {
  private listening = false
  private speaking = false
  private config: FullDuplexArbiterConfig

  constructor(config?: Partial<FullDuplexArbiterConfig>) {
    this.config = sanitizeConfig(config)
  }

  updateConfig(config?: Partial<FullDuplexArbiterConfig>): FullDuplexArbiterConfig {
    this.config = sanitizeConfig(config)
    return this.getConfig()
  }

  getConfig(): FullDuplexArbiterConfig {
    return {
      allowListeningDuringSpeaking: this.config.allowListeningDuringSpeaking,
      bargeInMode: this.config.bargeInMode,
      audioProcessing: { ...this.config.audioProcessing }
    }
  }

  requestStartListening(): ListeningDecision {
    if (this.speaking && !this.config.allowListeningDuringSpeaking) {
      return {
        allow: false,
        reason: 'blocked-listening-during-speaking'
      }
    }
    this.listening = true
    return {
      allow: true,
      reason: this.speaking ? 'listening-allowed-during-speaking' : 'listening-started'
    }
  }

  stopListening(): void {
    this.listening = false
  }

  onSpeakingStarted(): SpeakingTransitionDecision {
    this.speaking = true
    if (this.listening && !this.config.allowListeningDuringSpeaking) {
      this.listening = false
      return {
        stopListening: true,
        reason: 'speaking-started-stop-listening'
      }
    }
    return {
      stopListening: false,
      reason: 'speaking-started'
    }
  }

  onSpeakingStopped(): void {
    this.speaking = false
  }

  onSpeechDetected(): BargeInDecision {
    if (!this.speaking) {
      return {
        duckTts: false,
        stopTts: false,
        reason: 'ignored-not-speaking'
      }
    }

    if (this.config.bargeInMode === 'duck_tts') {
      return {
        duckTts: true,
        stopTts: false,
        reason: 'barge-in-duck'
      }
    }

    if (this.config.bargeInMode === 'stop_tts_on_speech') {
      return {
        duckTts: false,
        stopTts: true,
        reason: 'barge-in-stop-tts'
      }
    }

    return {
      duckTts: false,
      stopTts: false,
      reason: 'barge-in-disabled'
    }
  }

  getState(): FullDuplexArbiterState {
    return {
      listening: this.listening,
      speaking: this.speaking,
      config: this.getConfig()
    }
  }
}
