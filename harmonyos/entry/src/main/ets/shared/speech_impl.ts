import type {
  MockAsrScriptErrorStep,
  SpeechAsrError,
  IMockSpeechIO,
  MockAsrScriptOptions,
  MockAsrScriptStep,
  SpeechAsrResult,
  SpeechDebugState,
  SpeechListenOptions,
  SpeakOptions,
  SpeechVoice
} from './speech'

declare function setTimeout(handler: () => void, delay?: number): number
declare function clearTimeout(timeoutId: number): void

const BUILTIN_VOICES: SpeechVoice[] = [
  { id: 'zh_female_default', name: 'Chinese Female Default', locale: 'zh-CN', style: 'friendly' },
  { id: 'zh_male_default', name: 'Chinese Male Default', locale: 'zh-CN', style: 'calm' }
]

type MockScriptStateHandler = (running: boolean, pendingTimers: number) => void
type SpeakingStateHandler = (speaking: boolean) => void

export class HarmonySpeechIO implements IMockSpeechIO {
  private handler?: (result: SpeechAsrResult) => void
  private asrErrorHandler?: (error: SpeechAsrError) => void
  private listening = false
  private speaking = false
  private listenOptions?: SpeechListenOptions
  private lastSpokenText = ''
  private lastVoiceId?: string
  private mockScriptRunning = false
  private mockScriptTimerIds: number[] = []
  private mockScriptRunId = 0
  private scriptStateHandler?: MockScriptStateHandler
  private speakingStateHandler?: SpeakingStateHandler
  private speakTimerId: number | null = null
  private speakRunId = 0

  startListening(options: SpeechListenOptions): void {
    this.listenOptions = options
    this.listening = true
  }

  stopListening(): void {
    this.listening = false
    this.stopMockScript()
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

  onMockScriptStateChange(handler: MockScriptStateHandler): void {
    this.scriptStateHandler = handler
    this.notifyMockScriptState()
  }

  speak(text: string, options?: SpeakOptions): void {
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

    const runId = this.speakRunId + 1
    this.speakRunId = runId
    const durationMs = this.estimateSpeakDurationMs(trimmed, options?.rate)
    this.speakTimerId = setTimeout(() => {
      if (runId !== this.speakRunId) return
      this.speakTimerId = null
      this.speaking = false
      this.notifySpeakingState()
    }, durationMs)
  }

  stopSpeak(): void {
    this.speakRunId += 1
    this.clearSpeakTimer()
    if (!this.speaking) return
    this.speaking = false
    this.notifySpeakingState()
  }

  async listVoices(): Promise<SpeechVoice[]> {
    return BUILTIN_VOICES
  }

  mockRecognize(text: string, isFinal: boolean = true, confidence: number = 0.9): void {
    this.emitMockAsrResult({ text, isFinal, confidence })
  }

  mockError(code: number = -1, message: string = 'mock asr error'): void {
    this.emitMockAsrError({ code, message })
  }

  playMockScript(steps: MockAsrScriptStep[], options: MockAsrScriptOptions = {}): void {
    this.stopMockScript()

    if (steps.length === 0) return

    const runId = this.mockScriptRunId

    if (options.autoStartListening === true && !this.listening) {
      this.startListening({
        locale: options.locale ?? 'zh-CN',
        partialResults: options.partialResults ?? true
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
          this.emitMockAsrError({
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
    if (!this.listening || !this.handler) return
    this.handler(result)
  }

  emitMockAsrError(error: SpeechAsrError): void {
    this.listening = false
    this.stopMockScript()
    if (this.asrErrorHandler) {
      this.asrErrorHandler(error)
    }
  }

  getDebugState(): SpeechDebugState {
    return {
      listening: this.listening,
      speaking: this.speaking,
      listenOptions: this.listenOptions,
      lastSpokenText: this.lastSpokenText,
      lastVoiceId: this.lastVoiceId,
      mockScriptRunning: this.mockScriptRunning,
      pendingScriptTimers: this.mockScriptTimerIds.length
    }
  }

  private notifyMockScriptState(): void {
    if (!this.scriptStateHandler) return
    this.scriptStateHandler(this.mockScriptRunning, this.mockScriptTimerIds.length)
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
