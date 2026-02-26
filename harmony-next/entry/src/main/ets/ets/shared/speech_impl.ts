import type { ISpeechIO, SpeechAsrResult, SpeechListenOptions, SpeakOptions, SpeechVoice } from './speech'

export class HarmonySpeechIO implements ISpeechIO {
  private handler?: (result: SpeechAsrResult) => void

  startListening(_options: SpeechListenOptions): void {
    // TODO: Use HarmonyOS speech recognizer service (zh-CN)
  }

  stopListening(): void {
    // TODO: Stop recognizer
  }

  onAsrResult(handler: (result: SpeechAsrResult) => void): void {
    this.handler = handler
  }

  speak(_text: string, _options?: SpeakOptions): void {
    // TODO: Use TTS service with Chinese voices
  }

  stopSpeak(): void {
    // TODO: Stop TTS
  }

  async listVoices(): Promise<SpeechVoice[]> {
    // TODO: Query TTS voices
    return []
  }
}
