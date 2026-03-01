declare function requireNapi(moduleName: string): any

export interface AudioOutputHarmonyStreamConfig {
  sampleRate: 16000
  channels: 1
}

type AudioOutputModule = {
  createAudioRenderer?: (options: Record<string, unknown>) => Promise<any>
  AudioSamplingRate?: Record<string, number>
  AudioChannel?: Record<string, number>
  AudioSampleFormat?: Record<string, number>
  AudioEncodingType?: Record<string, number>
  ContentType?: Record<string, number>
  StreamUsage?: Record<string, number>
}

type MediaModule = {
  createAVPlayer?: () => Promise<any>
}

function tryRequireNapi(moduleName: string): any | null {
  try {
    return requireNapi(moduleName)
  } catch (_err) {
    return null
  }
}

function clamp01(value: number): number {
  if (value < 0) return 0
  if (value > 1) return 1
  return value
}

export class AudioOutputHarmony {
  private audioModule: AudioOutputModule | null = null
  private mediaModule: MediaModule | null = null
  private renderer: any = null
  private player: any = null
  private volume = 1
  private playing = false

  constructor() {
    this.audioModule = tryRequireNapi('@ohos.multimedia.audio') as AudioOutputModule | null
    this.mediaModule = tryRequireNapi('@ohos.multimedia.media') as MediaModule | null
  }

  isPlaying(): boolean {
    return this.playing
  }

  async playUrl(url: string): Promise<void> {
    await this.stop()

    if (!this.mediaModule || typeof this.mediaModule.createAVPlayer !== 'function') {
      throw new Error('media player module unavailable')
    }

    this.player = await this.mediaModule.createAVPlayer()
    if (!this.player) {
      throw new Error('createAVPlayer returned null')
    }

    this.playing = true

    try {
      if (typeof this.player.on === 'function') {
        this.player.on('stateChange', (state: string) => {
          if (state === 'completed' || state === 'stopped' || state === 'released') {
            this.playing = false
          }
        })
      }

      this.player.url = url
      if (typeof this.player.prepare === 'function') {
        await this.player.prepare()
      }
      await this.setVolume(this.volume)
      if (typeof this.player.play === 'function') {
        await this.player.play()
      }
    } catch (err) {
      this.playing = false
      throw err
    }
  }

  async playPcmStream(
    stream: AsyncIterable<ArrayBuffer>,
    config: AudioOutputHarmonyStreamConfig
  ): Promise<void> {
    await this.stop()

    if (!this.audioModule || typeof this.audioModule.createAudioRenderer !== 'function') {
      throw new Error('audio renderer module unavailable')
    }

    const audio = this.audioModule
    const rendererOptions = {
      streamInfo: {
        samplingRate: audio.AudioSamplingRate?.SAMPLE_RATE_16000 ?? config.sampleRate,
        channels: audio.AudioChannel?.CHANNEL_1 ?? config.channels,
        format: audio.AudioSampleFormat?.SAMPLE_FORMAT_S16LE ?? 1,
        encodingType: audio.AudioEncodingType?.ENCODING_TYPE_RAW ?? 0
      },
      rendererInfo: {
        content: audio.ContentType?.CONTENT_TYPE_SPEECH ?? 1,
        usage: audio.StreamUsage?.STREAM_USAGE_MEDIA ?? 1,
        rendererFlags: 0
      }
    }

    this.renderer = await audio.createAudioRenderer(rendererOptions)
    if (!this.renderer) {
      throw new Error('createAudioRenderer returned null')
    }

    this.playing = true

    try {
      if (typeof this.renderer.start === 'function') {
        await this.renderer.start()
      }
      await this.setVolume(this.volume)

      for await (const chunk of stream) {
        if (!this.playing) {
          break
        }
        if (chunk.byteLength <= 0) {
          continue
        }
        if (typeof this.renderer.write === 'function') {
          await this.renderer.write(chunk)
        }
      }
    } finally {
      await this.stopRendererOnly()
    }
  }

  async setVolume(volume: number): Promise<void> {
    this.volume = clamp01(volume)

    if (this.player) {
      if (typeof this.player.setVolume === 'function') {
        await this.player.setVolume(this.volume)
      }
    }

    if (this.renderer) {
      if (typeof this.renderer.setVolume === 'function') {
        await this.renderer.setVolume(this.volume)
      }
    }
  }

  async stop(): Promise<void> {
    this.playing = false
    await this.stopRendererOnly()
    await this.stopPlayerOnly()
  }

  private async stopRendererOnly(): Promise<void> {
    const active = this.renderer
    this.renderer = null
    if (!active) {
      return
    }

    try {
      if (typeof active.stop === 'function') {
        await active.stop()
      }
    } catch (_err) {
      // no-op
    }

    try {
      if (typeof active.release === 'function') {
        await active.release()
      }
    } catch (_err) {
      // no-op
    }
  }

  private async stopPlayerOnly(): Promise<void> {
    const active = this.player
    this.player = null
    if (!active) {
      return
    }

    try {
      if (typeof active.stop === 'function') {
        await active.stop()
      }
    } catch (_err) {
      // no-op
    }

    try {
      if (typeof active.release === 'function') {
        await active.release()
      }
    } catch (_err) {
      // no-op
    }
  }
}
