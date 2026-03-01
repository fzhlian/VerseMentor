import type { AsrEvent, SpeakEvent, SpeakOptions } from '../ports/speech_v3'

export interface ISpeechProvider {
  name: string
  init(): Promise<void>
  dispose(): Promise<void>
  asrStart(params: {
    lang: 'zh-CN'
    partial: boolean
    aec?: boolean
    ns?: boolean
  }): Promise<void>
  asrPushPcm(pcm: ArrayBuffer): Promise<void>
  asrStop(): Promise<void>
  onAsr(cb: (e: AsrEvent) => void): () => void
  ttsStart(text: string, opts: SpeakOptions): Promise<void>
  ttsStop(): Promise<void>
  onTts(cb: (e: SpeakEvent) => void): () => void
  listVoices(): Promise<Array<{ id: string; name: string }>>
}
