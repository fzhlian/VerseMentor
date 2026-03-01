export type VadState = 'speech_start' | 'speech_end'

export type BargeInMode = 'none' | 'duck_tts' | 'stop_tts_on_speech'

export type AsrEvent =
  | { type: 'ready' }
  | { type: 'partial'; text: string; confidence?: number }
  | { type: 'final'; text: string; confidence?: number }
  | { type: 'vad'; state: VadState }
  | { type: 'volume'; level: number }
  | { type: 'error'; code: string; message: string }

export type SpeakEvent =
  | {
      type: 'start'
      url?: string
      pcmStream?: AsyncIterable<ArrayBuffer>
      sampleRate?: 16000
      channels?: 1
    }
  | { type: 'end' }
  | { type: 'error'; code: string; message: string }

export type ListenOptions = {
  lang: 'zh-CN'
  enablePartial: boolean
  vad: {
    speechStartMs?: number
    speechEndMs?: number
  }
  fullDuplex: {
    allowListeningDuringSpeaking: boolean
    bargeInMode: BargeInMode
    duckVolume?: number
  }
  aec?: boolean
  ns?: boolean
}

export type SpeakOptions = {
  voiceId: string
  rate?: number
  pitch?: number
  volume?: number
}

export type VoiceInfo = {
  id: string
  name: string
}

export type SpeechStatus = {
  asrReady: boolean
  ttsReady: boolean
  activeProvider: string
  lastError?: string
}

export interface ISpeechIOv3 {
  startListening(opts: ListenOptions): Promise<void>
  stopListening(): Promise<void>
  onAsrEvent(cb: (event: AsrEvent) => void): () => void
  speak(text: string, opts: SpeakOptions): Promise<void>
  stopSpeak(): Promise<void>
  onSpeakEvent(cb: (event: SpeakEvent) => void): () => void
  listVoices(): Promise<VoiceInfo[]>
  getStatus(): Promise<SpeechStatus>
}
