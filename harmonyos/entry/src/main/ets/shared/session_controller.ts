import { GlobalFunctionSharedCoreRuntime, SharedCoreRuntime } from './shared_core_runtime'

export type SessionShellStage =
  | 'IDLE'
  | 'WAIT_POEM_NAME'
  | 'WAIT_DYNASTY_AUTHOR'
  | 'RECITING'
  | 'FINISHED'
  | 'EXIT'

export type SessionShellEvent =
  | { type: 'USER_ASR'; text: string; isFinal: boolean; confidence?: number; now?: number }
  | { type: 'USER_ASR_ERROR'; code: number; message: string }
  | { type: 'TICK'; now: number }
  | { type: 'EV_VARIANTS_FETCH_DONE'; entry: unknown | null }
  | { type: 'USER_UI_START'; now?: number }
  | { type: 'USER_UI_STOP' }

export type SessionShellAction =
  | { type: 'SPEAK'; text: string }
  | { type: 'START_LISTENING' }
  | { type: 'STOP_LISTENING' }
  | { type: 'UPDATE_SCREEN_HINT'; key: string }
  | { type: 'FETCH_VARIANTS'; poem: { title: string; author?: string; dynasty?: string } }

export interface SessionShellSnapshot {
  stage: SessionShellStage
  runtimeEnabled: boolean
  reducerPath: 'local' | 'runtime'
  runtimeMode: string
  runtimeFallbackReason: string
  lastEventType: SessionShellEvent['type'] | '-'
  lastActionTypes: string
  dispatchCount: number
  runtimeDispatchCount: number
  localDispatchCount: number
  traceSummary: string
  selectedPoemTitle?: string
  currentLineIdx: number
  totalLines: number
  expectedLine?: string
  lastAsr: string
  lastReply: string
}

interface SessionPoemSeed {
  title: string
  dynasty: string
  author: string
  lines: string[]
}

type UnknownRecord = Record<string, unknown>

const MOCK_POEM: SessionPoemSeed = {
  title: 'Jing Ye Si',
  dynasty: 'Tang',
  author: 'Li Bai',
  lines: [
    'chuang qian ming yue guang',
    'yi shi di shang shuang',
    'ju tou wang ming yue',
    'di tou si gu xiang'
  ]
}

function normalizeZh(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]/g, '').trim()
}

export class SessionShellController {
  private stage: SessionShellStage = 'IDLE'
  private runtimeEnabled = true
  private selectedPoemTitle?: string
  private currentLineIdx = 0
  private lastAsr = '-'
  private lastReply = '-'
  private reducerPath: 'local' | 'runtime' = 'local'
  private runtimeMode = 'local'
  private runtimeFallbackReason = '-'
  private lastEventType: SessionShellEvent['type'] | '-' = '-'
  private lastActionTypes = '-'
  private dispatchCount = 0
  private runtimeDispatchCount = 0
  private localDispatchCount = 0
  private traceLines: string[] = []
  private listener?: (snapshot: SessionShellSnapshot) => void
  private runtime: SharedCoreRuntime
  private runtimeStateJson: string | null = null

  constructor(runtime: SharedCoreRuntime = new GlobalFunctionSharedCoreRuntime()) {
    this.runtime = runtime
  }

  onStateChange(listener: (snapshot: SessionShellSnapshot) => void): void {
    this.listener = listener
    this.emit()
  }

  resetRuntimeState(): void {
    this.runtimeStateJson = null
  }

  setRuntimeEnabled(enabled: boolean): void {
    this.runtimeEnabled = enabled
    if (!enabled) {
      this.runtimeMode = 'local'
      this.reducerPath = 'local'
      this.runtimeFallbackReason = 'runtime-disabled'
    } else {
      this.runtimeFallbackReason = '-'
      this.resetRuntimeState()
    }
    this.emit()
  }

  resetDebugStats(): void {
    this.lastEventType = '-'
    this.lastActionTypes = '-'
    this.runtimeFallbackReason = '-'
    this.dispatchCount = 0
    this.runtimeDispatchCount = 0
    this.localDispatchCount = 0
    this.traceLines = []
    this.emit()
  }

  getSnapshot(): SessionShellSnapshot {
    const expectedLine =
      this.stage === 'RECITING' ? MOCK_POEM.lines[this.currentLineIdx] : undefined
    return {
      stage: this.stage,
      runtimeEnabled: this.runtimeEnabled,
      reducerPath: this.reducerPath,
      runtimeMode: this.runtimeMode,
      runtimeFallbackReason: this.runtimeFallbackReason,
      lastEventType: this.lastEventType,
      lastActionTypes: this.lastActionTypes,
      dispatchCount: this.dispatchCount,
      runtimeDispatchCount: this.runtimeDispatchCount,
      localDispatchCount: this.localDispatchCount,
      traceSummary: this.traceLines.length === 0 ? '-' : this.traceLines.join('\n'),
      selectedPoemTitle: this.selectedPoemTitle,
      currentLineIdx: this.currentLineIdx,
      totalLines: MOCK_POEM.lines.length,
      expectedLine,
      lastAsr: this.lastAsr,
      lastReply: this.lastReply
    }
  }

  dispatch(event: SessionShellEvent): SessionShellAction[] {
    this.dispatchCount += 1
    this.lastEventType = event.type

    if (!this.runtimeEnabled) {
      this.localDispatchCount += 1
      this.reducerPath = 'local'
      this.runtimeMode = 'local'
      this.runtimeFallbackReason = 'runtime-disabled'
      const localWhenDisabled = this.dispatchLocal(event)
      this.lastActionTypes = this.summarizeActions(localWhenDisabled)
      this.recordTrace('local')
      this.emit()
      return localWhenDisabled
    }

    const runtimeResult = this.tryDispatchByRuntime(event)
    if (runtimeResult !== null) {
      this.runtimeDispatchCount += 1
      this.reducerPath = 'runtime'
      this.runtimeMode = this.runtime.getDebugMode() ?? 'runtime'
      this.lastActionTypes = this.summarizeActions(runtimeResult)
      this.recordTrace('runtime')
      this.emit()
      return runtimeResult
    }
    this.localDispatchCount += 1
    this.reducerPath = 'local'
    this.runtimeMode = 'local'
    const localActions = this.dispatchLocal(event)
    this.lastActionTypes = this.summarizeActions(localActions)
    this.recordTrace('local')
    this.emit()
    return localActions
  }

  private dispatchLocal(event: SessionShellEvent): SessionShellAction[] {
    switch (event.type) {
      case 'USER_UI_START':
        return this.handleStart()
      case 'USER_UI_STOP':
        return this.handleStop()
      case 'USER_ASR':
        if (!event.isFinal) {
          this.lastAsr = `partial: ${event.text}`
          return []
        }
        return this.handleFinalAsr(event.text)
      case 'USER_ASR_ERROR':
        return this.handleAsrError(event.code, event.message)
      case 'TICK':
      case 'EV_VARIANTS_FETCH_DONE':
      default:
        return []
    }
  }

  private handleStart(): SessionShellAction[] {
    this.runtimeStateJson = null
    this.stage = 'WAIT_POEM_NAME'
    this.selectedPoemTitle = undefined
    this.currentLineIdx = 0
    return this.speakAndListen('Welcome. Please say a poem title.')
  }

  private handleStop(): SessionShellAction[] {
    this.runtimeStateJson = null
    this.stage = 'EXIT'
    return this.speakAndStop('Session ended.')
  }

  private handleFinalAsr(text: string): SessionShellAction[] {
    this.lastAsr = `final: ${text}`
    const normalized = normalizeZh(text)

    if (normalized.includes('stop') || normalized.includes('exit')) {
      this.stage = 'EXIT'
      return this.speakAndStop('Session ended.')
    }

    switch (this.stage) {
      case 'IDLE':
        return this.handleStart()

      case 'WAIT_POEM_NAME':
        if (normalized.includes(normalizeZh(MOCK_POEM.title)) || normalized.includes('jingye')) {
          this.selectedPoemTitle = MOCK_POEM.title
          this.stage = 'WAIT_DYNASTY_AUTHOR'
          return this.speakAndListen(`Selected ${MOCK_POEM.title}. Say dynasty and author.`, [
            {
              type: 'FETCH_VARIANTS',
              poem: {
                title: MOCK_POEM.title,
                author: MOCK_POEM.author,
                dynasty: MOCK_POEM.dynasty
              }
            }
          ])
        }
        return this.speakAndListen('Poem not found. Please repeat the title.')

      case 'WAIT_DYNASTY_AUTHOR': {
        const hasDynasty = normalized.includes(normalizeZh(MOCK_POEM.dynasty))
        const hasAuthor = normalized.includes(normalizeZh(MOCK_POEM.author))
        if (hasDynasty && hasAuthor) {
          this.stage = 'RECITING'
          this.currentLineIdx = 0
          return this.speakAndListen('Great. Start reciting line one.')
        }
        return this.speakAndListen('Please repeat dynasty and author.')
      }

      case 'RECITING': {
        const expected = MOCK_POEM.lines[this.currentLineIdx]
        const expectedNorm = normalizeZh(expected)

        if (normalized.includes('hint')) {
          const hint = expected.slice(0, 5)
          return this.speakAndListen(`Hint: ${hint}...`)
        }

        if (normalized === expectedNorm) {
          this.currentLineIdx += 1
          if (this.currentLineIdx >= MOCK_POEM.lines.length) {
            this.stage = 'FINISHED'
            return this.speakAndStop('Great work. Poem finished. Want another one?')
          }
          return this.speakAndListen('Good. Next line.')
        }

        const expectedPrefix = expectedNorm.slice(0, 2)
        if (
          normalized.length >= 2 &&
          (expectedNorm.includes(normalized) || normalized.includes(expectedPrefix))
        ) {
          return this.speakAndListen('Close. Try again.')
        }

        return this.speakAndListen('Try again.')
      }

      case 'FINISHED':
        if (normalized.includes('again') || normalized.includes('next')) {
          this.stage = 'WAIT_POEM_NAME'
          this.selectedPoemTitle = undefined
          this.currentLineIdx = 0
          return this.speakAndListen('OK. Say the next poem title.')
        }
        this.stage = 'EXIT'
        return this.speakAndStop('Session ended.')

      case 'EXIT':
        if (normalized.includes('start')) {
          return this.handleStart()
        }
        return []

      default:
        return []
    }
  }

  private handleAsrError(code: number, message: string): SessionShellAction[] {
    this.lastAsr = `error:${code}:${message}`
    if (this.stage === 'IDLE' || this.stage === 'EXIT') {
      return []
    }
    return this.speakAndListen(`ASR error: ${message}. Please repeat.`)
  }

  private tryDispatchByRuntime(event: SessionShellEvent): SessionShellAction[] | null {
    const stateJson = this.ensureRuntimeStateJson()
    if (stateJson === null) {
      this.runtimeFallbackReason = 'runtime-state-null'
      return null
    }

    let raw: string | null
    try {
      raw = this.runtime.reduce(stateJson, JSON.stringify(event))
    } catch (_err) {
      this.runtimeFallbackReason = 'runtime-reduce-throw'
      return null
    }

    if (raw === null || raw.trim().length === 0) {
      this.runtimeFallbackReason = 'runtime-empty-result'
      return null
    }

    let parsed: unknown
    try {
      parsed = JSON.parse(raw)
    } catch (_err) {
      this.runtimeFallbackReason = 'runtime-invalid-json'
      return null
    }

    const root = this.asRecord(parsed)
    if (root === null) {
      this.runtimeFallbackReason = 'runtime-root-not-object'
      return null
    }

    const actions = this.decodeActionsFromRuntime(root)
    if (actions === null) {
      this.runtimeFallbackReason = 'runtime-actions-invalid'
      return null
    }

    const applied = this.applyRuntimeState(root)
    if (!applied) {
      this.runtimeFallbackReason = 'runtime-state-invalid'
      return null
    }

    const rawState = root['state']
    if (rawState !== undefined) {
      this.runtimeStateJson = JSON.stringify(rawState)
    }

    const spoken = actions.find((action) => action.type === 'SPEAK')
    if (spoken && spoken.type === 'SPEAK') {
      this.lastReply = spoken.text
    }
    this.runtimeFallbackReason = '-'
    return actions
  }

  private ensureRuntimeStateJson(): string | null {
    if (this.runtimeStateJson !== null) {
      return this.runtimeStateJson
    }

    const initial = this.runtime.createInitialStateJson()
    if (initial !== null && initial.trim().length > 0) {
      this.runtimeStateJson = initial
      return this.runtimeStateJson
    }

    this.runtimeStateJson = this.encodeLocalStateForRuntime()
    return this.runtimeStateJson
  }

  private encodeLocalStateForRuntime(): string {
    return JSON.stringify({
      stage: this.stage,
      selectedPoemTitle: this.selectedPoemTitle,
      currentLineIdx: this.currentLineIdx,
      lastAsr: this.lastAsr,
      lastReply: this.lastReply
    })
  }

  private decodeActionsFromRuntime(payload: unknown): SessionShellAction[] | null {
    const root = this.asRecord(payload)
    if (!root) return null

    const rawActions = root['actions']
    if (!Array.isArray(rawActions)) return null

    const actions: SessionShellAction[] = []
    for (const item of rawActions) {
      const action = this.toShellAction(item)
      if (!action) return null
      actions.push(action)
    }
    return actions
  }

  private toShellAction(value: unknown): SessionShellAction | null {
    const item = this.asRecord(value)
    if (!item || typeof item['type'] !== 'string') return null

    switch (item['type']) {
      case 'SPEAK':
        return typeof item['text'] === 'string' ? { type: 'SPEAK', text: item['text'] } : null
      case 'START_LISTENING':
        return { type: 'START_LISTENING' }
      case 'STOP_LISTENING':
        return { type: 'STOP_LISTENING' }
      case 'UPDATE_SCREEN_HINT':
        return typeof item['key'] === 'string' ? { type: 'UPDATE_SCREEN_HINT', key: item['key'] } : null
      case 'FETCH_VARIANTS': {
        const poem = this.asRecord(item['poem'])
        if (!poem || typeof poem['title'] !== 'string') return null
        const normalizedPoem: { title: string; author?: string; dynasty?: string } = { title: poem['title'] }
        if (typeof poem['author'] === 'string') normalizedPoem.author = poem['author']
        if (typeof poem['dynasty'] === 'string') normalizedPoem.dynasty = poem['dynasty']
        return { type: 'FETCH_VARIANTS', poem: normalizedPoem }
      }
      default:
        return null
    }
  }

  private applyRuntimeState(payload: unknown): boolean {
    const root = this.asRecord(payload)
    if (!root) return false

    const rawState = this.asRecord(root['state'])
    if (!rawState) return false

    if (typeof rawState['type'] === 'string') {
      this.stage = this.mapDriverTypeToStage(rawState['type'])
      const ctx = this.asRecord(rawState['ctx'])
      if (ctx) {
        this.currentLineIdx = this.toSafeLineIdx(ctx['currentLineIdx'])
        const selectedPoem = this.asRecord(ctx['selectedPoem'])
        this.selectedPoemTitle = selectedPoem && typeof selectedPoem['title'] === 'string'
          ? selectedPoem['title']
          : undefined
      }
      return true
    }

    if (typeof rawState['stage'] === 'string') {
      this.stage = this.toShellStage(rawState['stage'])
      this.currentLineIdx = this.toSafeLineIdx(rawState['currentLineIdx'])
      this.selectedPoemTitle = typeof rawState['selectedPoemTitle'] === 'string'
        ? rawState['selectedPoemTitle']
          : undefined
      if (typeof rawState['lastAsr'] === 'string') {
        this.lastAsr = rawState['lastAsr']
      }
      if (typeof rawState['lastReply'] === 'string') {
        this.lastReply = rawState['lastReply']
      }
      return true
    }

    return false
  }

  private mapDriverTypeToStage(type: string): SessionShellStage {
    switch (type) {
      case 'WAIT_POEM_NAME':
        return 'WAIT_POEM_NAME'
      case 'WAIT_DYNASTY_AUTHOR':
        return 'WAIT_DYNASTY_AUTHOR'
      case 'RECITE_READY':
      case 'RECITING':
      case 'HINT_OFFER':
      case 'HINT_GIVEN':
        return 'RECITING'
      case 'FINISHED':
        return 'FINISHED'
      case 'EXIT':
        return 'EXIT'
      case 'IDLE':
      case 'SESSION_START':
      case 'CONFIRM_POEM_CANDIDATE':
      default:
        return 'IDLE'
    }
  }

  private toShellStage(stage: string): SessionShellStage {
    switch (stage) {
      case 'WAIT_POEM_NAME':
      case 'WAIT_DYNASTY_AUTHOR':
      case 'RECITING':
      case 'FINISHED':
      case 'EXIT':
      case 'IDLE':
        return stage
      default:
        return 'IDLE'
    }
  }

  private toSafeLineIdx(value: unknown): number {
    if (typeof value !== 'number' || Number.isNaN(value)) return 0
    if (value < 0) return 0
    return Math.floor(value)
  }

  private asRecord(value: unknown): UnknownRecord | null {
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      return value as UnknownRecord
    }
    return null
  }

  private summarizeActions(actions: SessionShellAction[]): string {
    if (actions.length === 0) {
      return 'none'
    }
    return actions.map((action) => action.type).join(' -> ')
  }

  private recordTrace(path: 'local' | 'runtime'): void {
    const fallback =
      path === 'local' && this.runtimeFallbackReason !== '-'
        ? `; fallback=${this.runtimeFallbackReason}`
        : ''
    const line =
      `#${this.dispatchCount} ${path}/${this.runtimeMode} ${this.lastEventType}` +
      ` => ${this.lastActionTypes}${fallback}`
    this.traceLines.push(line)
    if (this.traceLines.length > 8) {
      this.traceLines.shift()
    }
  }

  private speakAndListen(text: string, trailingActions: SessionShellAction[] = []): SessionShellAction[] {
    this.lastReply = text
    return [
      { type: 'SPEAK', text },
      ...trailingActions,
      { type: 'START_LISTENING' }
    ]
  }

  private speakAndStop(text: string): SessionShellAction[] {
    this.lastReply = text
    return [
      { type: 'SPEAK', text },
      { type: 'STOP_LISTENING' }
    ]
  }

  private emit(): void {
    if (this.listener) {
      this.listener(this.getSnapshot())
    }
  }
}
