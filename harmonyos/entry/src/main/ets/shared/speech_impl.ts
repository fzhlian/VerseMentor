import type {
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

export class HarmonySpeechIO implements IMockSpeechIO {
  private handler?: (result: SpeechAsrResult) => void
  private listening = false
  private speaking = false
  private listenOptions?: SpeechListenOptions
  private lastSpokenText = ''
  private lastVoiceId?: string
  private mockScriptRunning = false
  private mockScriptTimerIds: number[] = []
  private scriptStateHandler?: MockScriptStateHandler

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

  onMockScriptStateChange(handler: MockScriptStateHandler): void {
    this.scriptStateHandler = handler
    this.notifyMockScriptState()
  }

  speak(text: string, options?: SpeakOptions): void {
    this.lastSpokenText = text
    this.lastVoiceId = options?.voiceId
    this.speaking = text.trim().length > 0
  }

  stopSpeak(): void {
    this.speaking = false
  }

  async listVoices(): Promise<SpeechVoice[]> {
    return BUILTIN_VOICES
  }

  mockRecognize(text: string, isFinal: boolean = true, confidence: number = 0.9): void {
    this.emitMockAsrResult({ text, isFinal, confidence })
  }

  playMockScript(steps: MockAsrScriptStep[], options: MockAsrScriptOptions = {}): void {
    this.stopMockScript()

    if (steps.length === 0) return

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
      const timerId = setTimeout(() => {
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

    const finishTimerId = setTimeout(() => {
      this.mockScriptRunning = false
      this.mockScriptTimerIds = []
      this.notifyMockScriptState()
    }, elapsed + 50)
    this.mockScriptTimerIds.push(finishTimerId)
    this.notifyMockScriptState()
  }

  stopMockScript(): void {
    this.mockScriptTimerIds.forEach((id) => clearTimeout(id))
    this.mockScriptTimerIds = []
    this.mockScriptRunning = false
    this.notifyMockScriptState()
  }

  emitMockAsrResult(result: SpeechAsrResult): void {
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
      mockScriptRunning: this.mockScriptRunning,
      pendingScriptTimers: this.mockScriptTimerIds.length
    }
  }

  private notifyMockScriptState(): void {
    if (!this.scriptStateHandler) return
    this.scriptStateHandler(this.mockScriptRunning, this.mockScriptTimerIds.length)
  }
}
