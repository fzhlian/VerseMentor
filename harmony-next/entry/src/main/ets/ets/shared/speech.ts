export interface SpeechAsrResult {
  text: string
  confidence?: number
  isFinal?: boolean
}

export interface MockAsrScriptStep {
  text: string
  isFinal?: boolean
  confidence?: number
  delayMs?: number
}

export interface MockAsrScriptOptions {
  startDelayMs?: number
  autoStartListening?: boolean
  locale?: string
  partialResults?: boolean
}

export interface SpeechVoice {
  id: string
  name: string
  locale: string
  style?: string
}

export interface SpeechListenOptions {
  locale: string
  partialResults?: boolean
}

export interface SpeakOptions {
  voiceId?: string
  rate?: number
  pitch?: number
  volume?: number
}

export interface SpeechDebugState {
  listening: boolean
  speaking: boolean
  listenOptions?: SpeechListenOptions
  lastSpokenText: string
  lastVoiceId?: string
  mockScriptRunning: boolean
  pendingScriptTimers: number
}

export interface ISpeechIO {
  startListening(options: SpeechListenOptions): void
  stopListening(): void
  onAsrResult(handler: (result: SpeechAsrResult) => void): void
  speak(text: string, options?: SpeakOptions): void
  stopSpeak(): void
  listVoices(): Promise<SpeechVoice[]>
}

export interface IMockSpeechIO extends ISpeechIO {
  mockRecognize(text: string, isFinal?: boolean, confidence?: number): void
  playMockScript(steps: MockAsrScriptStep[], options?: MockAsrScriptOptions): void
  stopMockScript(): void
  onMockScriptStateChange(handler: (running: boolean, pendingTimers: number) => void): void
  getDebugState(): SpeechDebugState
}
