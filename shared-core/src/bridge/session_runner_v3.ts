import type {
  ListenOptions,
  SpeakOptions,
  ISpeechIOv3,
  AsrEvent,
  SpeakEvent
} from '../ports/speech_v3'
import type { SessionStateType } from '../fsm/session_fsm'
import { FullDuplexArbiter } from '../speech/full_duplex_arbiter'
import {
  createSessionDriverState,
  dispatchSessionDriverEvent,
  type CreateSessionDriverInput,
  type SerializableSessionState,
  type SessionDriverAction,
  type SessionDriverEvent
} from './session_driver'

type SessionRunnerListener<T> = (payload: T) => void

export interface SessionRunnerV3Options {
  createInput?: CreateSessionDriverInput
  listenOptions: ListenOptions
  speakOptions: SpeakOptions
}

export interface SessionRunnerSnapshot {
  state: SerializableSessionState
  partialText: string
  lastDiag?: string
}

function isActiveState(type: SessionStateType): boolean {
  return type !== 'IDLE' && type !== 'EXIT'
}

const DEFAULT_DUCK_VOLUME = 0.25
const DEFAULT_BARGE_IN_MODE: ListenOptions['fullDuplex']['bargeInMode'] = 'duck_tts'
const DEFAULT_SILENCE_TIMEOUT_MS = 5000
const MIN_SILENCE_TIMEOUT_MS = 300
const MAX_SILENCE_TIMEOUT_MS = 15000
const ACTIVITY_VOLUME_THRESHOLD = 0.2
const RECOVERY_INITIAL_DELAY_MS = 150
const RECOVERY_MAX_DELAY_MS = 2000

function clampSilenceTimeout(value: number): number {
  if (!Number.isFinite(value)) {
    return DEFAULT_SILENCE_TIMEOUT_MS
  }
  if (value < MIN_SILENCE_TIMEOUT_MS) {
    return MIN_SILENCE_TIMEOUT_MS
  }
  if (value > MAX_SILENCE_TIMEOUT_MS) {
    return MAX_SILENCE_TIMEOUT_MS
  }
  return Math.floor(value)
}

function normalizeProviderName(provider: string | undefined): string {
  return provider?.trim().toLowerCase() ?? ''
}

function providerSupportsAec(provider: string): boolean {
  return provider === 'iflytek' || provider === 'ifly'
}

function normalizeListenOptions(input: ListenOptions): ListenOptions {
  return {
    lang: input.lang,
    enablePartial: input.enablePartial,
    vad: {
      speechStartMs: input.vad.speechStartMs,
      speechEndMs: input.vad.speechEndMs
    },
    fullDuplex: {
      allowListeningDuringSpeaking: input.fullDuplex.allowListeningDuringSpeaking ?? true,
      bargeInMode: input.fullDuplex.bargeInMode ?? DEFAULT_BARGE_IN_MODE,
      duckVolume: input.fullDuplex.duckVolume ?? DEFAULT_DUCK_VOLUME
    },
    aec: input.aec,
    ns: input.ns
  }
}

function resolveSilenceTimeoutMs(listenOptions: ListenOptions): number {
  if (typeof listenOptions.vad.speechEndMs === 'number' && listenOptions.vad.speechEndMs > 0) {
    return clampSilenceTimeout(listenOptions.vad.speechEndMs)
  }
  return DEFAULT_SILENCE_TIMEOUT_MS
}

export class SessionRunnerV3 {
  private state: SerializableSessionState
  private partialText = ''
  private lastDiag?: string

  private readonly arbiter: FullDuplexArbiter
  private readonly listenOptions: ListenOptions
  private activeListenOptions: ListenOptions
  private readonly speakOptions: SpeakOptions
  private readonly silenceTimeoutMs: number

  private queue: Promise<void> = Promise.resolve()
  private readonly ioAsrUnsub: () => void
  private readonly ioSpeakUnsub: () => void
  private silenceWatchdogTimer?: ReturnType<typeof setTimeout>
  private speaking = false
  private lastAsrActivityAt = 0
  private recoveryAttempt = 0
  private recoveryInFlight = false
  private recoveryGeneration = 0
  private disposed = false

  private readonly stateListeners = new Set<SessionRunnerListener<SessionRunnerSnapshot>>()
  private readonly partialListeners = new Set<SessionRunnerListener<{ text: string; confidence?: number }>>()
  private readonly diagListeners = new Set<SessionRunnerListener<string>>()

  constructor(private readonly io: ISpeechIOv3, options: SessionRunnerV3Options) {
    this.state = createSessionDriverState(options.createInput)
    this.listenOptions = normalizeListenOptions(options.listenOptions)
    this.activeListenOptions = this.listenOptions
    this.speakOptions = options.speakOptions
    this.silenceTimeoutMs = resolveSilenceTimeoutMs(this.listenOptions)
    this.arbiter = new FullDuplexArbiter()
    this.arbiter.attach(io)
    this.lastAsrActivityAt = Date.now()

    this.ioAsrUnsub = this.io.onAsrEvent((event) => {
      void this.enqueue(() => this.handleAsrEvent(event))
    })
    this.ioSpeakUnsub = this.io.onSpeakEvent((event) => {
      this.handleSpeakEvent(event)
    })
  }

  getSnapshot(): SessionRunnerSnapshot {
    return {
      state: this.state,
      partialText: this.partialText,
      lastDiag: this.lastDiag
    }
  }

  onState(cb: SessionRunnerListener<SessionRunnerSnapshot>): () => void {
    this.stateListeners.add(cb)
    return () => {
      this.stateListeners.delete(cb)
    }
  }

  onPartial(cb: SessionRunnerListener<{ text: string; confidence?: number }>): () => void {
    this.partialListeners.add(cb)
    return () => {
      this.partialListeners.delete(cb)
    }
  }

  onDiag(cb: SessionRunnerListener<string>): () => void {
    this.diagListeners.add(cb)
    return () => {
      this.diagListeners.delete(cb)
    }
  }

  async startSession(now: number = Date.now()): Promise<void> {
    await this.enqueue(async () => {
      // Rule: Start listening immediately on user start click.
      await this.startListeningWithPolicy({
        ...this.listenOptions,
        fullDuplex: {
          ...this.listenOptions.fullDuplex,
          allowListeningDuringSpeaking: true,
          bargeInMode: this.listenOptions.fullDuplex.bargeInMode ?? DEFAULT_BARGE_IN_MODE,
          duckVolume: this.listenOptions.fullDuplex.duckVolume ?? DEFAULT_DUCK_VOLUME
        }
      })
      await this.dispatchEvent({ type: 'USER_UI_START', now })
    })
  }

  async stopSession(): Promise<void> {
    await this.enqueue(async () => {
      await this.dispatchEvent({ type: 'USER_UI_STOP' })
      this.recoveryGeneration += 1
      this.clearSilenceWatchdog()
      await this.arbiter.stopListening(this.io)
    })
  }

  async tick(now: number): Promise<void> {
    await this.enqueue(async () => {
      await this.dispatchEvent({ type: 'TICK', now })
    })
  }

  async dispatchManualEvent(event: SessionDriverEvent): Promise<void> {
    await this.enqueue(async () => {
      await this.dispatchEvent(event)
    })
  }

  async flush(): Promise<void> {
    await this.queue
  }

  async dispose(): Promise<void> {
    this.disposed = true
    this.recoveryGeneration += 1
    this.clearSilenceWatchdog()
    this.ioAsrUnsub()
    this.ioSpeakUnsub()
    await this.flush()
    await this.arbiter.stopListening(this.io)
    await this.io.stopSpeak()
  }

  private async handleAsrEvent(event: AsrEvent): Promise<void> {
    if (this.isAsrActivitySignal(event)) {
      this.registerAsrActivity()
    }

    switch (event.type) {
      case 'partial': {
        this.partialText = event.text
        this.emitPartial(event.text, event.confidence)
        await this.dispatchEvent({
          type: 'USER_ASR',
          text: event.text,
          isFinal: false,
          confidence: event.confidence,
          now: Date.now()
        })
        return
      }
      case 'final': {
        this.partialText = ''
        await this.dispatchEvent({
          type: 'USER_ASR',
          text: event.text,
          isFinal: true,
          confidence: event.confidence,
          now: Date.now()
        })
        return
      }
      case 'error': {
        this.lastDiag = `${event.code}:${event.message}`
        this.emitDiag(this.lastDiag)
        await this.dispatchEvent({
          type: 'USER_ASR_ERROR',
          code: -1,
          message: event.message
        })
        this.scheduleRecovery('asr-error')
        return
      }
      case 'ready':
      case 'vad':
      case 'volume':
      default:
        return
    }
  }

  private async dispatchEvent(event: SessionDriverEvent): Promise<void> {
    const output = dispatchSessionDriverEvent(this.state, event)
    this.state = output.state
    this.emitState()
    this.armSilenceWatchdog()
    await this.applyActions(output.actions)
  }

  private async applyActions(actions: SessionDriverAction[]): Promise<void> {
    for (const action of actions) {
      if (action.type === 'SPEAK') {
        const listenPolicy = await this.resolveListenPolicy(this.activeListenOptions)
        await this.arbiter.speak(this.io, action.text, this.speakOptions, listenPolicy)
      } else if (action.type === 'START_LISTENING') {
        await this.startListeningWithPolicy(this.listenOptions)
      } else if (action.type === 'STOP_LISTENING') {
        this.clearSilenceWatchdog()
        await this.arbiter.stopListening(this.io)
      }
    }
  }

  private handleSpeakEvent(event: SpeakEvent): void {
    if (event.type === 'start') {
      this.speaking = true
      this.clearSilenceWatchdog()
      return
    }
    if (event.type === 'end' || event.type === 'error') {
      this.speaking = false
      this.armSilenceWatchdog()
    }
  }

  private isAsrActivitySignal(event: AsrEvent): boolean {
    if (event.type === 'ready' || event.type === 'partial' || event.type === 'final') {
      return true
    }
    if (event.type === 'vad') {
      return true
    }
    if (event.type === 'volume') {
      return event.level >= ACTIVITY_VOLUME_THRESHOLD
    }
    return false
  }

  private registerAsrActivity(): void {
    this.lastAsrActivityAt = Date.now()
    this.recoveryAttempt = 0
    this.armSilenceWatchdog()
  }

  private armSilenceWatchdog(): void {
    this.clearSilenceWatchdog()
    if (!this.shouldRunSilenceWatchdog()) {
      return
    }
    this.silenceWatchdogTimer = setTimeout(() => {
      this.silenceWatchdogTimer = undefined
      const inactiveFor = Date.now() - this.lastAsrActivityAt
      if (inactiveFor < this.silenceTimeoutMs) {
        this.armSilenceWatchdog()
        return
      }
      this.lastDiag = `silence-timeout:${inactiveFor}ms`
      this.emitDiag(this.lastDiag)
      this.scheduleRecovery('silence-timeout')
    }, this.silenceTimeoutMs)
  }

  private clearSilenceWatchdog(): void {
    if (!this.silenceWatchdogTimer) {
      return
    }
    clearTimeout(this.silenceWatchdogTimer)
    this.silenceWatchdogTimer = undefined
  }

  private shouldRunSilenceWatchdog(): boolean {
    return (
      !this.disposed &&
      isActiveState(this.state.type) &&
      this.arbiter.state.listening &&
      !this.speaking
    )
  }

  private scheduleRecovery(trigger: 'asr-error' | 'silence-timeout'): void {
    if (this.disposed || !isActiveState(this.state.type)) {
      return
    }
    if (this.recoveryInFlight) {
      return
    }
    const delayMs = Math.min(
      RECOVERY_INITIAL_DELAY_MS * (2 ** this.recoveryAttempt),
      RECOVERY_MAX_DELAY_MS
    )
    this.recoveryAttempt += 1
    this.recoveryInFlight = true
    const generation = this.recoveryGeneration
    const scheduledAt = Date.now()
    this.lastDiag = `recovery:${trigger}:delay=${delayMs}ms`
    this.emitDiag(this.lastDiag)
    void this.runRecovery(delayMs, generation, scheduledAt)
  }

  private async runRecovery(
    delayMs: number,
    generation: number,
    scheduledAt: number
  ): Promise<void> {
    try {
      this.clearSilenceWatchdog()
      await this.arbiter.stopListening(this.io)
      await this.sleep(delayMs)
      if (this.disposed || generation !== this.recoveryGeneration) {
        return
      }
      if (!isActiveState(this.state.type)) {
        return
      }
      // If ASR activity resumed while waiting, the pending recovery is stale.
      if (this.lastAsrActivityAt > scheduledAt) {
        this.armSilenceWatchdog()
        return
      }
      await this.startListeningWithPolicy(this.activeListenOptions)
    } catch (err) {
      this.lastDiag = `recovery-failed:${this.errorToMessage(err)}`
      this.emitDiag(this.lastDiag)
    } finally {
      this.recoveryInFlight = false
    }
  }

  private async startListeningWithPolicy(opts: ListenOptions): Promise<void> {
    const effective = await this.resolveListenPolicy(opts)
    this.activeListenOptions = effective
    await this.arbiter.startListening(this.io, effective)
    this.lastAsrActivityAt = Date.now()
    this.armSilenceWatchdog()
  }

  private async resolveListenPolicy(opts: ListenOptions): Promise<ListenOptions> {
    const normalized = normalizeListenOptions(opts)
    if (!normalized.fullDuplex.allowListeningDuringSpeaking) {
      return normalized
    }

    let activeProvider = ''
    try {
      const status = await this.io.getStatus()
      activeProvider = normalizeProviderName(status.activeProvider)
    } catch (_err) {
      activeProvider = ''
    }

    if (providerSupportsAec(activeProvider)) {
      return {
        ...normalized,
        aec: true
      }
    }

    return {
      ...normalized,
      fullDuplex: {
        ...normalized.fullDuplex,
        bargeInMode: 'duck_tts',
        duckVolume: normalized.fullDuplex.duckVolume ?? DEFAULT_DUCK_VOLUME
      }
    }
  }

  private async sleep(delayMs: number): Promise<void> {
    await new Promise<void>((resolve) => {
      setTimeout(resolve, delayMs)
    })
  }

  private errorToMessage(err: unknown): string {
    if (err instanceof Error) {
      return err.message
    }
    if (typeof err === 'string') {
      return err
    }
    return 'unknown-error'
  }

  private enqueue(task: () => Promise<void>): Promise<void> {
    this.queue = this.queue.then(task, task)
    return this.queue
  }

  private emitState(): void {
    const snapshot = this.getSnapshot()
    for (const listener of this.stateListeners) {
      listener(snapshot)
    }
  }

  private emitPartial(text: string, confidence?: number): void {
    for (const listener of this.partialListeners) {
      listener({ text, confidence })
    }
  }

  private emitDiag(diag: string): void {
    for (const listener of this.diagListeners) {
      listener(diag)
    }
  }
}
