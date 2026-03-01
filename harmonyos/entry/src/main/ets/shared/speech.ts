export interface SpeechAsrResult {
  text: string
  confidence?: number
  isFinal?: boolean
}

export interface SpeechAsrError {
  code: number
  message: string
}

export type SpeechProviderId = 'iflytek' | 'volc'

export type BargeInMode = 'none' | 'duck_tts' | 'stop_tts_on_speech'

export type MicPermissionStatus = 'granted' | 'denied' | 'unknown'

export interface SpeechAudioProcessingOptions {
  echoCancellation?: boolean
  noiseSuppression?: boolean
}

export interface SpeechDuplexOptions {
  allowListeningDuringSpeaking?: boolean
  bargeInMode?: BargeInMode
  duckVolume?: number
  audioProcessing?: SpeechAudioProcessingOptions
}

export interface SpeechProviderDescriptor {
  id: SpeechProviderId
  displayName: string
  supportsStreamingAsr: boolean
}

export interface MockAsrScriptAsrStep {
  kind?: 'asr'
  text: string
  isFinal?: boolean
  confidence?: number
  delayMs?: number
}

export interface MockAsrScriptErrorStep {
  kind: 'error'
  code?: number
  message?: string
  delayMs?: number
}

export type MockAsrScriptStep = MockAsrScriptAsrStep | MockAsrScriptErrorStep

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
  providerId?: SpeechProviderId
  duplex?: SpeechDuplexOptions
}

export interface SpeakOptions {
  voiceId?: string
  rate?: number
  pitch?: number
  volume?: number
  providerId?: SpeechProviderId
}

export interface SpeechDiagnostics {
  activeProvider: SpeechProviderId
  providerName: string
  asrReady: boolean
  ttsReady: boolean
  lastError: string
  micPermissionStatus: MicPermissionStatus
}

export interface SpeechDebugState {
  listening: boolean
  speaking: boolean
  listenOptions?: SpeechListenOptions
  lastSpokenText: string
  lastVoiceId?: string
  activeProvider: SpeechProviderId
  duplexOptions: SpeechDuplexOptions
  mockScriptRunning: boolean
  pendingScriptTimers: number
  diagnostics: SpeechDiagnostics
}

export interface ISpeechIO {
  startListening(options: SpeechListenOptions): void
  stopListening(): void
  onAsrResult(handler: (result: SpeechAsrResult) => void): void
  onAsrError(handler: (error: SpeechAsrError) => void): void
  onSpeakingStateChange(handler: (speaking: boolean) => void): void
  onDiagnosticsChange(handler: (diagnostics: SpeechDiagnostics) => void): void
  speak(text: string, options?: SpeakOptions): void
  stopSpeak(): void
  listVoices(): Promise<SpeechVoice[]>
  setActiveProvider(providerId: SpeechProviderId): void
  listProviders(): Promise<SpeechProviderDescriptor[]>
  configureDuplex(options: SpeechDuplexOptions): void
  getDiagnostics(): SpeechDiagnostics
}

export interface IMockSpeechIO extends ISpeechIO {
  mockRecognize(text: string, isFinal?: boolean, confidence?: number): void
  mockError(code?: number, message?: string): void
  playMockScript(steps: MockAsrScriptStep[], options?: MockAsrScriptOptions): void
  stopMockScript(): void
  onMockScriptStateChange(handler: (running: boolean, pendingTimers: number) => void): void
  getDebugState(): SpeechDebugState
}
