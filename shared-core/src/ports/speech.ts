export interface SpeechAsrResult {
  text: string
  confidence?: number
  isFinal?: boolean
}

export type SpeechProviderId = 'iflytek' | 'volcengine'

export type BargeInMode = 'none' | 'duck_tts' | 'stop_tts_on_speech'

export interface SpeechVoice {
  id: string
  name: string
  locale: string
  style?: string
}

export interface SpeechAudioProcessingOptions {
  echoCancellation?: boolean
  noiseSuppression?: boolean
}

export interface SpeechDuplexOptions {
  allowListeningDuringSpeaking?: boolean
  bargeInMode?: BargeInMode
  audioProcessing?: SpeechAudioProcessingOptions
}

export interface SpeechProviderDescriptor {
  id: SpeechProviderId
  displayName: string
  supportsStreamingAsr: boolean
}

export type SpeechListenOptions = {
  locale: string
  partialResults?: boolean
  providerId?: SpeechProviderId
  duplex?: SpeechDuplexOptions
}

export type SpeakOptions = {
  voiceId?: string
  rate?: number
  pitch?: number
  volume?: number
  providerId?: SpeechProviderId
}

export interface ISpeechIO {
  startListening(options: SpeechListenOptions): void
  stopListening(): void
  onAsrResult(handler: (result: SpeechAsrResult) => void): void
  speak(text: string, options?: SpeakOptions): void
  stopSpeak(): void
  listVoices(): Promise<SpeechVoice[]>
  setActiveProvider?(providerId: SpeechProviderId): void
  listProviders?(): Promise<SpeechProviderDescriptor[]>
  configureDuplex?(options: SpeechDuplexOptions): void
}