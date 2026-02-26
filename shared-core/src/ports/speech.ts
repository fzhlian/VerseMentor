export interface SpeechAsrResult {
  text: string
  confidence?: number
  isFinal?: boolean
}

export interface SpeechVoice {
  id: string
  name: string
  locale: string
  style?: string
}

export type SpeechListenOptions = {
  locale: string
  partialResults?: boolean
}

export type SpeakOptions = {
  voiceId?: string
  rate?: number
  pitch?: number
  volume?: number
}

export interface ISpeechIO {
  startListening(options: SpeechListenOptions): void
  stopListening(): void
  onAsrResult(handler: (result: SpeechAsrResult) => void): void
  speak(text: string, options?: SpeakOptions): void
  stopSpeak(): void
  listVoices(): Promise<SpeechVoice[]>
}
