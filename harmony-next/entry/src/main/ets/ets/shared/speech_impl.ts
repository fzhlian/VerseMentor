import type { ISpeechIO, SpeechAsrResult, SpeechListenOptions, SpeakOptions, SpeechVoice } from './speech'

const BUILTIN_VOICES: SpeechVoice[] = [
  { id: 'zh_female_default', name: 'Chinese Female Default', locale: 'zh-CN', style: 'friendly' },
  { id: 'zh_male_default', name: 'Chinese Male Default', locale: 'zh-CN', style: 'calm' }
]

export class HarmonySpeechIO implements ISpeechIO {
  private handler?: (result: SpeechAsrResult) => void
  private listening = false
  private speaking = false
  private listenOptions?: SpeechListenOptions
  private lastSpokenText = ''
  private lastVoiceId?: string

  startListening(options: SpeechListenOptions): void {
    this.listenOptions = options
    this.listening = true
  }

  stopListening(): void {
    this.listening = false
  }

  onAsrResult(handler: (result: SpeechAsrResult) => void): void {
    this.handler = handler
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

  emitMockAsrResult(result: SpeechAsrResult): void {
    if (!this.listening || !this.handler) return
    this.handler(result)
  }

  getDebugState(): {
    listening: boolean
    speaking: boolean
    listenOptions?: SpeechListenOptions
    lastSpokenText: string
    lastVoiceId?: string
  } {
    return {
      listening: this.listening,
      speaking: this.speaking,
      listenOptions: this.listenOptions,
      lastSpokenText: this.lastSpokenText,
      lastVoiceId: this.lastVoiceId
    }
  }
}
