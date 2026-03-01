import type { MicPermissionStatus } from './speech'

declare function requireNapi(moduleName: string): any
declare function setTimeout(handler: () => void, delay?: number): number

type AudioCaptureModule = {
  createAudioCapturer?: (options: Record<string, unknown>) => Promise<any>
  AudioSamplingRate?: Record<string, number>
  AudioChannel?: Record<string, number>
  AudioSampleFormat?: Record<string, number>
  AudioEncodingType?: Record<string, number>
  SourceType?: Record<string, number>
}

export interface AudioCaptureHarmonyConfig {
  sampleRate: 16000
  channels: 1
  frameMs: 20 | 40
  aec?: boolean
  ns?: boolean
}

export interface AudioCaptureHarmonyCallbacks {
  onFrame: (frame: ArrayBuffer, volume: number) => void
  onError: (message: string) => void
  onPermission: (status: MicPermissionStatus) => void
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(() => resolve(), ms)
  })
}

function clamp01(value: number): number {
  if (value < 0) return 0
  if (value > 1) return 1
  return value
}

function tryRequireNapi(moduleName: string): any | null {
  try {
    return requireNapi(moduleName)
  } catch (_err) {
    return null
  }
}

function toInt16Array(buffer: ArrayBuffer): Int16Array {
  return new Int16Array(buffer)
}

function computeVolume(frame: ArrayBuffer): number {
  const int16 = toInt16Array(frame)
  if (int16.length === 0) return 0
  let sumSquares = 0
  for (let i = 0; i < int16.length; i += 1) {
    const v = int16[i]
    sumSquares += v * v
  }
  const rms = Math.sqrt(sumSquares / int16.length)
  return clamp01(rms / 32767)
}

function normalizeFrameMs(raw: number): 20 | 40 {
  return raw === 40 ? 40 : 20
}

export class AudioCaptureHarmony {
  private audioModule: AudioCaptureModule | null = null
  private capturer: any = null
  private running = false
  private pumpTask: Promise<void> | null = null
  private lastPermissionStatus: MicPermissionStatus = 'unknown'

  constructor() {
    const module = tryRequireNapi('@ohos.multimedia.audio')
    this.audioModule = module as AudioCaptureModule | null
  }

  getPermissionStatus(): MicPermissionStatus {
    return this.lastPermissionStatus
  }

  async start(
    config: AudioCaptureHarmonyConfig,
    callbacks: AudioCaptureHarmonyCallbacks
  ): Promise<boolean> {
    await this.stop()

    const frameMs = normalizeFrameMs(config.frameMs)
    const frameSamples = (16000 * frameMs) / 1000
    const frameBytes = frameSamples * 2

    if (!this.audioModule || typeof this.audioModule.createAudioCapturer !== 'function') {
      this.lastPermissionStatus = 'unknown'
      callbacks.onPermission(this.lastPermissionStatus)
      callbacks.onError('audio capturer module unavailable')
      return false
    }

    const audio = this.audioModule
    const capturerOptions = {
      streamInfo: {
        samplingRate: audio.AudioSamplingRate?.SAMPLE_RATE_16000 ?? 16000,
        channels: audio.AudioChannel?.CHANNEL_1 ?? 1,
        format: audio.AudioSampleFormat?.SAMPLE_FORMAT_S16LE ?? 1,
        encodingType: audio.AudioEncodingType?.ENCODING_TYPE_RAW ?? 0
      },
      capturerInfo: {
        source: audio.SourceType?.SOURCE_TYPE_MIC ?? 0,
        capturerFlags: 0
      },
      rendererInfo: {
        // Keep AEC/NS toggles in config contract for API compatibility,
        // even if specific platform API does not expose toggles directly.
        aec: config.aec === true,
        ns: config.ns === true
      }
    }

    try {
      this.capturer = await audio.createAudioCapturer?.(capturerOptions)
      if (!this.capturer) {
        throw new Error('createAudioCapturer returned null')
      }
      if (typeof this.capturer.start === 'function') {
        await this.capturer.start()
      }
      this.running = true
      this.lastPermissionStatus = 'granted'
      callbacks.onPermission(this.lastPermissionStatus)
      this.pumpTask = this.pumpFrames(frameBytes, callbacks)
      return true
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      if (msg.toLowerCase().includes('permission') || msg.toLowerCase().includes('denied')) {
        this.lastPermissionStatus = 'denied'
      } else {
        this.lastPermissionStatus = 'unknown'
      }
      callbacks.onPermission(this.lastPermissionStatus)
      callbacks.onError(`capturer start failed: ${msg}`)
      await this.stop()
      return false
    }
  }

  async stop(): Promise<void> {
    this.running = false
    if (this.pumpTask) {
      try {
        await this.pumpTask
      } catch (_err) {
        // no-op
      }
      this.pumpTask = null
    }

    const active = this.capturer
    this.capturer = null
    if (!active) return

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

  private async pumpFrames(
    frameBytes: number,
    callbacks: AudioCaptureHarmonyCallbacks
  ): Promise<void> {
    const active = this.capturer
    if (!active) return

    while (this.running) {
      let frame: ArrayBuffer | null = null
      try {
        if (typeof active.read === 'function') {
          const maybeFrame = await active.read(frameBytes, true)
          if (maybeFrame instanceof ArrayBuffer) {
            frame = maybeFrame
          } else if (ArrayBuffer.isView(maybeFrame)) {
            const view = maybeFrame as any
            frame = view.buffer.slice(view.byteOffset, view.byteOffset + view.byteLength)
          }
        }
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err)
        callbacks.onError(`capturer read failed: ${msg}`)
        break
      }

      if (!this.running) {
        break
      }

      if (frame && frame.byteLength > 0) {
        callbacks.onFrame(frame, computeVolume(frame))
      } else {
        await delay(8)
      }
    }
  }
}
