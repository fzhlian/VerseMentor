import {
  AudioCaptureHarmony,
  type AudioCaptureHarmonyConfig
} from './audio_capture_harmony'
import { AudioOutputHarmony } from './audio_output_harmony'
import type {
  MockAsrScriptErrorStep,
  SpeechAsrError,
  IMockSpeechIO,
  MockAsrScriptOptions,
  MockAsrScriptStep,
  SpeechAsrResult,
  SpeechDebugState,
  SpeechDiagnostics,
  SpeechDuplexOptions,
  SpeechListenOptions,
  SpeakOptions,
  SpeechProviderDescriptor,
  SpeechProviderId,
  SpeechVoice,
  MicPermissionStatus,
  BargeInMode
} from './speech'

declare function setTimeout(handler: () => void, delay?: number): number
declare function clearTimeout(timeoutId: number): void

const BUILTIN_VOICES_BY_PROVIDER: Record<SpeechProviderId, SpeechVoice[]> = {
  iflytek: [
    { id: 'iflytek_zh_female_default', name: 'iFlytek Female Default', locale: 'zh-CN', style: 'friendly' },
    { id: 'iflytek_zh_male_default', name: 'iFlytek Male Default', locale: 'zh-CN', style: 'calm' }
  ],
  volc: [
    { id: 'volc_zh_female_default', name: 'Volc Female Default', locale: 'zh-CN', style: 'clear' },
    { id: 'volc_zh_male_default', name: 'Volc Male Default', locale: 'zh-CN', style: 'warm' }
  ]
}

const BUILTIN_PROVIDERS: SpeechProviderDescriptor[] = [
  { id: 'iflytek', displayName: 'iFlytek', supportsStreamingAsr: true },
  { id: 'volc', displayName: 'Volcengine', supportsStreamingAsr: true }
]

const DEFAULT_DUPLEX_OPTIONS: SpeechDuplexOptions = {
  allowListeningDuringSpeaking: true,
  bargeInMode: 'stop_tts_on_speech',
  duckVolume: 0.4,
  audioProcessing: {
    echoCancellation: true,
    noiseSuppression: true
  }
}

const DEFAULT_CAPTURE_CONFIG: AudioCaptureHarmonyConfig = {
  sampleRate: 16000,
  channels: 1,
  frameMs: 20,
  aec: true,
  ns: true
}

const DEFAULT_VOLUME_TRIGGER_THRESHOLD = 0.2
const DUCK_RECOVERY_FRAMES = 6

type MockScriptStateHandler = (running: boolean, pendingTimers: number) => void
type SpeakingStateHandler = (speaking: boolean) => void
type DiagnosticsHandler = (diagnostics: SpeechDiagnostics) => void

function clamp01(value: number): number {
  if (value < 0) return 0
  if (value > 1) return 1
  return value
}

function normalizeProviderId(raw: unknown): SpeechProviderId {
  if (raw === 'volc' || raw === 'volcengine') {
    return 'volc'
  }
  return 'iflytek'
}

function resolveProviderName(providerId: SpeechProviderId): string {
  return providerId === 'iflytek' ? 'iFlytek' : 'Volcengine'
}

function normalizeBargeInMode(raw: unknown): BargeInMode {
  switch (raw) {
    case 'none':
    case 'duck_tts':
    case 'stop_tts_on_speech':
      return raw
    default:
      return 'stop_tts_on_speech'
  }
}

function normalizeDuplexOptions(next?: SpeechDuplexOptions): SpeechDuplexOptions {
  if (!next) {
    return {
      allowListeningDuringSpeaking: DEFAULT_DUPLEX_OPTIONS.allowListeningDuringSpeaking,
      bargeInMode: DEFAULT_DUPLEX_OPTIONS.bargeInMode,
      duckVolume: DEFAULT_DUPLEX_OPTIONS.duckVolume,
      audioProcessing: {
        echoCancellation: DEFAULT_DUPLEX_OPTIONS.audioProcessing?.echoCancellation,
        noiseSuppression: DEFAULT_DUPLEX_OPTIONS.audioProcessing?.noiseSuppression
      }
    }
  }
  return {
    allowListeningDuringSpeaking:
      next.allowListeningDuringSpeaking ?? DEFAULT_DUPLEX_OPTIONS.allowListeningDuringSpeaking,
    bargeInMode: normalizeBargeInMode(next.bargeInMode),
    duckVolume: clamp01(next.duckVolume ?? DEFAULT_DUPLEX_OPTIONS.duckVolume ?? 0.4),
    audioProcessing: {
      echoCancellation:
        next.audioProcessing?.echoCancellation ??
        DEFAULT_DUPLEX_OPTIONS.audioProcessing?.echoCancellation,
      noiseSuppression:
        next.audioProcessing?.noiseSuppression ??
        DEFAULT_DUPLEX_OPTIONS.audioProcessing?.noiseSuppression
    }
  }
}

function cloneDiagnostics(input: SpeechDiagnostics): SpeechDiagnostics {
  return {
    activeProvider: input.activeProvider,
    providerName: input.providerName,
    asrReady: input.asrReady,
    ttsReady: input.ttsReady,
    lastError: input.lastError,
    micPermissionStatus: input.micPermissionStatus
  }
}

export class HarmonySpeechIO implements IMockSpeechIO {
  private handler?: (result: SpeechAsrResult) => void
  private asrErrorHandler?: (error: SpeechAsrError) => void
  private listening = false
  private speaking = false
  private listenOptions?: SpeechListenOptions
  private lastSpokenText = ''
  private lastVoiceId?: string
  private activeProvider: SpeechProviderId = 'iflytek'
  private duplexOptions: SpeechDuplexOptions = normalizeDuplexOptions()
  private mockScriptRunning = false
  private mockScriptTimerIds: number[] = []
  private mockScriptRunId = 0
  private scriptStateHandler?: MockScriptStateHandler
  private speakingStateHandler?: SpeakingStateHandler
  private diagnosticsHandler?: DiagnosticsHandler
  private speakTimerId: number | null = null
  private speakRunId = 0
  private capture = new AudioCaptureHarmony()
  private output = new AudioOutputHarmony()
  private diagnostics: SpeechDiagnostics = {
    activeProvider: 'iflytek',
    providerName: 'iFlytek',
    asrReady: false,
    ttsReady: true,
    lastError: '',
    micPermissionStatus: 'unknown'
  }
  private duckedTts = false
  private duckRecoveryFrames = 0

  startListening(options: SpeechListenOptions): void {
    const provider = normalizeProviderId(options.providerId)
    this.setActiveProvider(provider)
    if (options.duplex) {
      this.duplexOptions = normalizeDuplexOptions(options.duplex)
    }

    if (this.speaking && this.duplexOptions.allowListeningDuringSpeaking === false) {
      this.emitAsrError({
        code: -8,
        message: 'listening blocked while speaking'
      })
      return
    }

    this.listenOptions = {
      locale: options.locale,
      partialResults: options.partialResults,
      providerId: this.activeProvider,
      duplex: normalizeDuplexOptions(this.duplexOptions)
    }
    this.listening = true

    void this.startAudioCapture()
  }

  stopListening(): void {
    this.listening = false
    this.stopMockScript()
    void this.capture.stop()
  }

  onAsrResult(handler: (result: SpeechAsrResult) => void): void {
    this.handler = handler
  }

  onAsrError(handler: (error: SpeechAsrError) => void): void {
    this.asrErrorHandler = handler
  }

  onSpeakingStateChange(handler: (speaking: boolean) => void): void {
    this.speakingStateHandler = handler
    this.notifySpeakingState()
  }

  onDiagnosticsChange(handler: (diagnostics: SpeechDiagnostics) => void): void {
    this.diagnosticsHandler = handler
    this.notifyDiagnostics()
  }

  onMockScriptStateChange(handler: MockScriptStateHandler): void {
    this.scriptStateHandler = handler
    this.notifyMockScriptState()
  }

  setActiveProvider(providerId: SpeechProviderId): void {
    const normalized = normalizeProviderId(providerId)
    this.activeProvider = normalized
    this.updateDiagnostics({
      activeProvider: normalized,
      providerName: resolveProviderName(normalized)
    })
  }

  async listProviders(): Promise<SpeechProviderDescriptor[]> {
    return BUILTIN_PROVIDERS
  }

  configureDuplex(options: SpeechDuplexOptions): void {
    this.duplexOptions = normalizeDuplexOptions(options)
  }

  speak(text: string, options?: SpeakOptions): void {
    if (typeof options?.providerId === 'string') {
      this.setActiveProvider(options.providerId)
    }

    if (this.listening && this.duplexOptions.allowListeningDuringSpeaking === false) {
      this.listening = false
      void this.capture.stop()
    }

    this.lastSpokenText = text
    this.lastVoiceId = options?.voiceId
    this.clearSpeakTimer()

    const trimmed = text.trim()
    if (trimmed.length === 0) {
      this.speaking = false
      this.notifySpeakingState()
      return
    }

    this.speaking = true
    this.notifySpeakingState()
    this.updateDiagnostics({
      ttsReady: true,
      lastError: ''
    })

    this.restoreDuckVolume()

    const runId = this.speakRunId + 1
    this.speakRunId = runId

    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      void this.output.playUrl(trimmed).catch((err) => {
        const msg = err instanceof Error ? err.message : String(err)
        this.updateDiagnostics({ lastError: `playUrl failed: ${msg}` })
      })
    }

    const durationMs = this.estimateSpeakDurationMs(trimmed, options?.rate)
    this.speakTimerId = setTimeout(() => {
      if (runId !== this.speakRunId) return
      this.speakTimerId = null
      this.speaking = false
      this.restoreDuckVolume()
      this.notifySpeakingState()
    }, durationMs)
  }

  stopSpeak(): void {
    this.speakRunId += 1
    this.clearSpeakTimer()
    void this.output.stop()
    this.restoreDuckVolume()
    if (!this.speaking) return
    this.speaking = false
    this.notifySpeakingState()
  }

  async listVoices(): Promise<SpeechVoice[]> {
    return BUILTIN_VOICES_BY_PROVIDER[this.activeProvider]
  }

  getDiagnostics(): SpeechDiagnostics {
    return cloneDiagnostics(this.diagnostics)
  }

  mockRecognize(text: string, isFinal: boolean = true, confidence: number = 0.9): void {
    this.emitMockAsrResult({ text, isFinal, confidence })
  }

  mockError(code: number = -1, message: string = 'mock asr error'): void {
    this.emitAsrError({ code, message })
  }

  playMockScript(steps: MockAsrScriptStep[], options: MockAsrScriptOptions = {}): void {
    this.stopMockScript()

    if (steps.length === 0) return

    const runId = this.mockScriptRunId

    if (options.autoStartListening === true && !this.listening) {
      this.startListening({
        locale: options.locale ?? 'zh-CN',
        partialResults: options.partialResults ?? true,
        providerId: this.activeProvider,
        duplex: this.duplexOptions
      })
    }

    let elapsed = Math.max(0, options.startDelayMs ?? 0)
    this.mockScriptRunning = true
    this.notifyMockScriptState()

    steps.forEach((step) => {
      let timerId = 0
      timerId = setTimeout(() => {
        if (runId !== this.mockScriptRunId) return
        this.removeMockScriptTimer(timerId)
        if (this.isErrorStep(step)) {
          this.emitAsrError({
            code: step.code ?? -1,
            message: step.message ?? 'mock asr error'
          })
          return
        }
        this.emitMockAsrResult({
          text: step.text,
          isFinal: step.isFinal ?? true,
          confidence: step.confidence ?? 0.9
        })
      }, elapsed)
      this.mockScriptTimerIds.push(timerId)
      elapsed += Math.max(0, step.delayMs ?? 900)
      this.notifyMockScriptState()
    })

    let finishTimerId = 0
    finishTimerId = setTimeout(() => {
      if (runId !== this.mockScriptRunId) return
      this.removeMockScriptTimer(finishTimerId, false)
      this.mockScriptRunning = false
      this.notifyMockScriptState()
    }, elapsed + 50)
    this.mockScriptTimerIds.push(finishTimerId)
    this.notifyMockScriptState()
  }

  stopMockScript(): void {
    this.mockScriptRunId += 1
    this.mockScriptTimerIds.forEach((id) => clearTimeout(id))
    this.mockScriptTimerIds = []
    this.mockScriptRunning = false
    this.notifyMockScriptState()
  }

  emitMockAsrResult(result: SpeechAsrResult): void {
    this.onInputVolume(result.isFinal === true ? 0.9 : 0.5)
    if (this.speaking && result.isFinal) {
      if (this.duplexOptions.bargeInMode === 'stop_tts_on_speech') {
        this.stopSpeak()
      }
    }
    if (!this.listening || !this.handler) return
    this.handler(result)
  }

  getDebugState(): SpeechDebugState {
    return {
      listening: this.listening,
      speaking: this.speaking,
      listenOptions: this.listenOptions,
      lastSpokenText: this.lastSpokenText,
      lastVoiceId: this.lastVoiceId,
      activeProvider: this.activeProvider,
      duplexOptions: normalizeDuplexOptions(this.duplexOptions),
      mockScriptRunning: this.mockScriptRunning,
      pendingScriptTimers: this.mockScriptTimerIds.length,
      diagnostics: this.getDiagnostics()
    }
  }

  private async startAudioCapture(): Promise<void> {
    const captureConfig: AudioCaptureHarmonyConfig = {
      ...DEFAULT_CAPTURE_CONFIG,
      aec: this.duplexOptions.audioProcessing?.echoCancellation !== false,
      ns: this.duplexOptions.audioProcessing?.noiseSuppression !== false
    }

    const started = await this.capture.start(captureConfig, {
      onFrame: (_frame: ArrayBuffer, volume: number) => {
        this.onInputVolume(volume)
      },
      onError: (message: string) => {
        if (this.listening) {
          this.emitAsrError({ code: -4, message })
        }
      },
      onPermission: (status: MicPermissionStatus) => {
        this.updateDiagnostics({ micPermissionStatus: status })
      }
    })

    this.updateDiagnostics({ asrReady: started })
    if (!started) {
      this.listening = false
    }
  }

  private onInputVolume(level: number): void {
    if (!this.speaking) {
      return
    }

    const bargeMode = normalizeBargeInMode(this.duplexOptions.bargeInMode)
    if (bargeMode === 'none') {
      return
    }

    if (level >= DEFAULT_VOLUME_TRIGGER_THRESHOLD) {
      this.duckRecoveryFrames = 0
      if (bargeMode === 'stop_tts_on_speech') {
        this.stopSpeak()
        return
      }
      if (bargeMode === 'duck_tts') {
        this.applyDuckVolume()
      }
      return
    }

    if (bargeMode === 'duck_tts' && this.duckedTts) {
      this.duckRecoveryFrames += 1
      if (this.duckRecoveryFrames >= DUCK_RECOVERY_FRAMES) {
        this.restoreDuckVolume()
      }
    }
  }

  private async applyDuckVolume(): Promise<void> {
    if (this.duckedTts) {
      return
    }
    this.duckedTts = true
    this.duckRecoveryFrames = 0
    const duckVolume = clamp01(this.duplexOptions.duckVolume ?? 0.4)
    try {
      await this.output.setVolume(duckVolume)
    } catch (_err) {
      // no-op
    }
  }

  private restoreDuckVolume(): void {
    if (!this.duckedTts) {
      this.duckRecoveryFrames = 0
      return
    }
    this.duckedTts = false
    this.duckRecoveryFrames = 0
    void this.output.setVolume(1).catch(() => {
      // no-op
    })
  }

  private emitAsrError(error: SpeechAsrError): void {
    this.listening = false
    this.stopMockScript()
    this.updateDiagnostics({
      lastError: `${error.code}:${error.message}`,
      asrReady: false
    })
    if (this.asrErrorHandler) {
      this.asrErrorHandler(error)
    }
  }

  private notifyMockScriptState(): void {
    if (!this.scriptStateHandler) return
    this.scriptStateHandler(this.mockScriptRunning, this.mockScriptTimerIds.length)
  }

  private notifyDiagnostics(): void {
    if (!this.diagnosticsHandler) return
    this.diagnosticsHandler(this.getDiagnostics())
  }

  private updateDiagnostics(next: Partial<SpeechDiagnostics>): void {
    this.diagnostics = {
      ...this.diagnostics,
      ...next,
      activeProvider: this.activeProvider,
      providerName: resolveProviderName(this.activeProvider)
    }
    this.notifyDiagnostics()
  }

  private removeMockScriptTimer(timerId: number, notify: boolean = true): void {
    const index = this.mockScriptTimerIds.indexOf(timerId)
    if (index < 0) return
    this.mockScriptTimerIds.splice(index, 1)
    if (notify) {
      this.notifyMockScriptState()
    }
  }

  private isErrorStep(step: MockAsrScriptStep): step is MockAsrScriptErrorStep {
    return step.kind === 'error'
  }

  private clearSpeakTimer(): void {
    if (this.speakTimerId === null) return
    clearTimeout(this.speakTimerId)
    this.speakTimerId = null
  }

  private estimateSpeakDurationMs(text: string, rate?: number): number {
    const safeRate = typeof rate === 'number' && rate > 0 ? rate : 1
    const baseMs = text.length * 110
    const adjusted = Math.floor(baseMs / safeRate)
    if (adjusted < 500) return 500
    if (adjusted > 7000) return 7000
    return adjusted
  }

  private notifySpeakingState(): void {
    if (!this.speakingStateHandler) return
    this.speakingStateHandler(this.speaking)
  }
}
