interface BridgeHost {
  [key: string]: unknown
}

type DriverEvent =
  | { type: 'USER_ASR'; text?: string; isFinal?: boolean }
  | { type: 'TICK'; now?: number }
  | { type: 'EV_VARIANTS_FETCH_DONE'; entry?: unknown | null }
  | { type: 'USER_UI_START' }
  | { type: 'USER_UI_STOP' }

type DriverAction =
  | { type: 'SPEAK'; text: string }
  | { type: 'START_LISTENING' }
  | { type: 'STOP_LISTENING' }
  | { type: 'FETCH_VARIANTS'; poem: { title: string; author?: string; dynasty?: string } }

interface DriverPoem {
  title: string
  author: string
  dynasty: string
}

interface DriverState {
  type: string
  ctx: {
    currentLineIdx: number
    selectedPoem: DriverPoem | null
  }
}

interface DriverOutput {
  state: DriverState
  actions: DriverAction[]
}

const DRIVER_POEM: DriverPoem = {
  title: 'Jing Ye Si',
  author: 'Li Bai',
  dynasty: 'Tang'
}

const DRIVER_LINES: string[] = [
  'chuang qian ming yue guang',
  'yi shi di shang shuang',
  'ju tou wang ming yue',
  'di tou si gu xiang'
]

let installed = false
type HookMode = 'mock' | 'delegate'
let hookMode: HookMode = 'mock'

const CREATE_HOOK_NAME = '__vmCreateSessionDriverStateJson'
const REDUCE_HOOK_NAME = '__vmReduceSessionDriverJson'
const DELEGATE_CREATE_HOOK_NAME = '__vmSharedCoreDelegateCreateSessionDriverStateJson'
const DELEGATE_REDUCE_HOOK_NAME = '__vmSharedCoreDelegateReduceSessionDriverJson'
const MODE_HOOK_NAME = '__vmGetSessionDriverHookMode'

export interface SharedCoreDelegateHooks {
  createSessionDriverStateJson: () => string
  reduceSessionDriverJson: (stateJson: string, eventJson: string) => string
}

function getHost(): BridgeHost {
  return globalThis as unknown as BridgeHost
}

function normalize(input: string): string {
  return input.toLowerCase().replace(/[^a-z0-9]/g, '')
}

function createInitialState(): DriverState {
  return {
    type: 'IDLE',
    ctx: {
      currentLineIdx: 0,
      selectedPoem: null
    }
  }
}

function toRecord(value: unknown): Record<string, unknown> | null {
  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>
  }
  return null
}

function parseStateJson(raw: string): DriverState {
  try {
    const parsed = JSON.parse(raw) as unknown
    const envelope = toRecord(parsed)
    if (envelope && typeof envelope['type'] === 'string') {
      const ctx = toRecord(envelope['ctx'])
      return {
        type: envelope['type'],
        ctx: {
          currentLineIdx: typeof ctx?.['currentLineIdx'] === 'number' ? Math.max(0, Math.floor(ctx['currentLineIdx'])) : 0,
          selectedPoem: toRecord(ctx?.['selectedPoem']) && typeof toRecord(ctx?.['selectedPoem'])?.['title'] === 'string'
            ? {
                title: String(toRecord(ctx?.['selectedPoem'])?.['title']),
                author: typeof toRecord(ctx?.['selectedPoem'])?.['author'] === 'string'
                  ? String(toRecord(ctx?.['selectedPoem'])?.['author'])
                  : DRIVER_POEM.author,
                dynasty: typeof toRecord(ctx?.['selectedPoem'])?.['dynasty'] === 'string'
                  ? String(toRecord(ctx?.['selectedPoem'])?.['dynasty'])
                  : DRIVER_POEM.dynasty
              }
            : null
        }
      }
    }
  } catch (_err) {
    // fall back below
  }
  return createInitialState()
}

function parseEventJson(raw: string): DriverEvent {
  try {
    const parsed = JSON.parse(raw) as unknown
    const event = toRecord(parsed)
    if (event && typeof event['type'] === 'string') {
      const type = event['type']
      if (type === 'USER_ASR') {
        return {
          type: 'USER_ASR',
          text: typeof event['text'] === 'string' ? event['text'] : '',
          isFinal: event['isFinal'] === true
        }
      }
      if (type === 'TICK') {
        return { type: 'TICK', now: typeof event['now'] === 'number' ? event['now'] : Date.now() }
      }
      if (type === 'EV_VARIANTS_FETCH_DONE') {
        return { type: 'EV_VARIANTS_FETCH_DONE', entry: event['entry'] ?? null }
      }
      if (type === 'USER_UI_START') return { type: 'USER_UI_START' }
      if (type === 'USER_UI_STOP') return { type: 'USER_UI_STOP' }
    }
  } catch (_err) {
    // fall back below
  }
  return { type: 'TICK', now: Date.now() }
}

function speakAndListen(state: DriverState, text: string, extra: DriverAction[] = []): DriverOutput {
  return {
    state,
    actions: [{ type: 'SPEAK', text }, ...extra, { type: 'START_LISTENING' }]
  }
}

function speakAndStop(state: DriverState, text: string): DriverOutput {
  return {
    state,
    actions: [{ type: 'SPEAK', text }, { type: 'STOP_LISTENING' }]
  }
}

function reduceDriver(state: DriverState, event: DriverEvent): DriverOutput {
  if (event.type === 'USER_UI_START') {
    const next: DriverState = {
      type: 'WAIT_POEM_NAME',
      ctx: { currentLineIdx: 0, selectedPoem: null }
    }
    return speakAndListen(next, 'Welcome. Please say a poem title.')
  }

  if (event.type === 'USER_UI_STOP') {
    const next: DriverState = {
      ...state,
      type: 'EXIT'
    }
    return speakAndStop(next, 'Session ended.')
  }

  if (event.type !== 'USER_ASR' || event.isFinal !== true) {
    return { state, actions: [] }
  }

  const text = event.text ?? ''
  const normalized = normalize(text)

  if (normalized.indexOf('stop') >= 0 || normalized.indexOf('exit') >= 0) {
    return speakAndStop({ ...state, type: 'EXIT' }, 'Session ended.')
  }

  if (state.type === 'IDLE' || state.type === 'SESSION_START') {
    const next: DriverState = {
      type: 'WAIT_POEM_NAME',
      ctx: { currentLineIdx: 0, selectedPoem: null }
    }
    return speakAndListen(next, 'Welcome. Please say a poem title.')
  }

  if (state.type === 'WAIT_POEM_NAME') {
    if (normalized.indexOf('jingyesi') >= 0 || normalized.indexOf('jingye') >= 0) {
      const next: DriverState = {
        type: 'WAIT_DYNASTY_AUTHOR',
        ctx: { currentLineIdx: 0, selectedPoem: DRIVER_POEM }
      }
      return speakAndListen(next, 'Selected Jing Ye Si. Say dynasty and author.', [
        { type: 'FETCH_VARIANTS', poem: DRIVER_POEM }
      ])
    }
    return speakAndListen(state, 'Poem not found. Please repeat the title.')
  }

  if (state.type === 'WAIT_DYNASTY_AUTHOR') {
    if (normalized.indexOf('tang') >= 0 && normalized.indexOf('libai') >= 0) {
      const next: DriverState = {
        type: 'RECITING',
        ctx: { currentLineIdx: 0, selectedPoem: state.ctx.selectedPoem ?? DRIVER_POEM }
      }
      return speakAndListen(next, 'Great. Start reciting line one.')
    }
    return speakAndListen(state, 'Please repeat dynasty and author.')
  }

  if (state.type === 'RECITING') {
    const idx = state.ctx.currentLineIdx
    const expected = idx >= 0 && idx < DRIVER_LINES.length ? DRIVER_LINES[idx] : DRIVER_LINES[0]
    const expectedNorm = normalize(expected)

    if (normalized.indexOf('hint') >= 0) {
      return speakAndListen(state, `Hint: ${expected.slice(0, 5)}...`)
    }

    if (normalized === expectedNorm) {
      const nextIdx = idx + 1
      if (nextIdx >= DRIVER_LINES.length) {
        const next: DriverState = {
          type: 'FINISHED',
          ctx: { currentLineIdx: nextIdx, selectedPoem: state.ctx.selectedPoem ?? DRIVER_POEM }
        }
        return speakAndStop(next, 'Great work. Poem finished. Want another one?')
      }
      const next: DriverState = {
        type: 'RECITING',
        ctx: { currentLineIdx: nextIdx, selectedPoem: state.ctx.selectedPoem ?? DRIVER_POEM }
      }
      return speakAndListen(next, 'Good. Next line.')
    }

    return speakAndListen(state, 'Try again.')
  }

  if (state.type === 'FINISHED') {
    if (normalized.indexOf('again') >= 0 || normalized.indexOf('next') >= 0) {
      const next: DriverState = {
        type: 'WAIT_POEM_NAME',
        ctx: { currentLineIdx: 0, selectedPoem: null }
      }
      return speakAndListen(next, 'OK. Say the next poem title.')
    }
    const next: DriverState = { ...state, type: 'EXIT' }
    return speakAndStop(next, 'Session ended.')
  }

  if (state.type === 'EXIT') {
    if (normalized.indexOf('start') >= 0) {
      const next: DriverState = {
        type: 'WAIT_POEM_NAME',
        ctx: { currentLineIdx: 0, selectedPoem: null }
      }
      return speakAndListen(next, 'Welcome. Please say a poem title.')
    }
    return { state, actions: [] }
  }

  return { state, actions: [] }
}

function callDelegateCreateStateJson(host: BridgeHost): string | null {
  const fn = host[DELEGATE_CREATE_HOOK_NAME]
  if (typeof fn !== 'function') return null
  try {
    const out = (fn as () => unknown)()
    return typeof out === 'string' ? out : null
  } catch (_err) {
    return null
  }
}

function callDelegateReduceJson(host: BridgeHost, stateJson: string, eventJson: string): string | null {
  const fn = host[DELEGATE_REDUCE_HOOK_NAME]
  if (typeof fn !== 'function') return null
  try {
    const out = (fn as (stateJson: string, eventJson: string) => unknown)(stateJson, eventJson)
    return typeof out === 'string' ? out : null
  } catch (_err) {
    return null
  }
}

function createSessionDriverStateJson(): string {
  const host = getHost()
  const delegate = callDelegateCreateStateJson(host)
  if (delegate !== null && delegate.trim().length > 0) {
    hookMode = 'delegate'
    return delegate
  }
  hookMode = 'mock'
  return JSON.stringify(createInitialState())
}

function reduceSessionDriverJson(stateJson: string, eventJson: string): string {
  const host = getHost()
  const delegate = callDelegateReduceJson(host, stateJson, eventJson)
  if (delegate !== null && delegate.trim().length > 0) {
    hookMode = 'delegate'
    return delegate
  }

  hookMode = 'mock'
  const current = parseStateJson(stateJson)
  const event = parseEventJson(eventJson)
  const output = reduceDriver(current, event)
  return JSON.stringify(output)
}

export function installSharedCoreBridgeHooks(): void {
  if (installed) return
  const host = getHost()
  if (typeof host[CREATE_HOOK_NAME] !== 'function') {
    host[CREATE_HOOK_NAME] = createSessionDriverStateJson
  }
  if (typeof host[REDUCE_HOOK_NAME] !== 'function') {
    host[REDUCE_HOOK_NAME] = reduceSessionDriverJson
  }
  if (typeof host[MODE_HOOK_NAME] !== 'function') {
    host[MODE_HOOK_NAME] = () => hookMode
  }
  installed = true
}

export function registerSharedCoreDelegateHooks(hooks: SharedCoreDelegateHooks): void {
  const host = getHost()
  host[DELEGATE_CREATE_HOOK_NAME] = hooks.createSessionDriverStateJson
  host[DELEGATE_REDUCE_HOOK_NAME] = hooks.reduceSessionDriverJson
  hookMode = 'delegate'
}

export function clearSharedCoreDelegateHooks(): void {
  const host = getHost()
  const mutable = host as Record<string, unknown>
  delete mutable[DELEGATE_CREATE_HOOK_NAME]
  delete mutable[DELEGATE_REDUCE_HOOK_NAME]
  hookMode = 'mock'
}

export function getSharedCoreHookMode(): HookMode {
  return hookMode
}
