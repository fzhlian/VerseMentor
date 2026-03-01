export interface IAudioCapture {
  start(cfg: {
    sampleRate: 16000
    channels: 1
    frameMs: 20 | 40
    aec?: boolean
    ns?: boolean
  }): AsyncIterable<ArrayBuffer>
  stop(): Promise<void>
}

export interface IAudioOutput {
  playPcmStream(
    stream: AsyncIterable<ArrayBuffer>,
    cfg: {
      sampleRate: 16000
      channels: 1
    }
  ): Promise<void>
  playUrl(url: string): Promise<void>
  setVolume(vol: number): Promise<void>
  stop(): Promise<void>
}
