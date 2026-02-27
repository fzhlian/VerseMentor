import { clearSharedCoreDelegateHooks, registerSharedCoreDelegateHooks } from './shared_core_bridge_host'

interface DemoPoem {
  title: string
  author: string
  dynasty: string
}

interface DemoState {
  type: string
  ctx: {
    currentLineIdx: number
    selectedPoem: DemoPoem | null
  }
}

interface DemoEvent {
  type: string
  text?: string
  isFinal?: boolean
  confidence?: number
  now?: number
  code?: number
  message?: string
}

interface DemoAction {
  type: string
  text?: string
  poem?: DemoPoem
}

interface DemoOutput {
  state: DemoState
  actions: DemoAction[]
}

export type DemoSharedCoreDelegateMode =
  | 'off'
  | 'normal'
  | 'invalid-json'
  | 'invalid-actions'
  | 'invalid-state'

const POEM: DemoPoem = {
  title: 'Jing Ye Si',
  author: 'Li Bai',
  dynasty: 'Tang'
}

const LINES: string[] = [
  'chuang qian ming yue guang',
  'yi shi di shang shuang',
  'ju tou wang ming yue',
  'di tou si gu xiang'
]

let mode: DemoSharedCoreDelegateMode = 'off'
let delegateRegistrationToken: number | null = null

function normalize(input: string): string {
  return input.toLowerCase().replace(/[^a-z0-9]/g, '')
}

function createState(): DemoState {
  return {
    type: 'IDLE',
    ctx: {
      currentLineIdx: 0,
      selectedPoem: null
    }
  }
}

function parseStateJson(raw: string): DemoState {
  try {
    const parsed = JSON.parse(raw) as DemoState
    if (
      parsed &&
      typeof parsed.type === 'string' &&
      parsed.ctx &&
      typeof parsed.ctx.currentLineIdx === 'number'
    ) {
      return parsed
    }
  } catch (_err) {
    // fallback
  }
  return createState()
}

function parseEventJson(raw: string): DemoEvent {
  try {
    const parsed = JSON.parse(raw) as DemoEvent
    if (parsed && typeof parsed.type === 'string') {
      return parsed
    }
  } catch (_err) {
    // fallback
  }
  return { type: 'TICK' }
}

function speakAndListen(state: DemoState, text: string): DemoOutput {
  return {
    state,
    actions: [
      { type: 'SPEAK', text: `[delegate] ${text}` },
      { type: 'START_LISTENING' }
    ]
  }
}

function speakAndStop(state: DemoState, text: string): DemoOutput {
  return {
    state,
    actions: [
      { type: 'SPEAK', text: `[delegate] ${text}` },
      { type: 'STOP_LISTENING' }
    ]
  }
}

function reduce(state: DemoState, event: DemoEvent): DemoOutput {
  if (event.type === 'USER_UI_START') {
    return speakAndListen({ type: 'WAIT_POEM_NAME', ctx: { currentLineIdx: 0, selectedPoem: null } }, 'say poem title')
  }

  if (event.type === 'USER_UI_STOP') {
    return speakAndStop({ ...state, type: 'EXIT' }, 'session ended')
  }

  if (event.type === 'USER_ASR_ERROR') {
    if (state.type === 'IDLE' || state.type === 'EXIT') {
      return { state, actions: [] }
    }
    return speakAndListen(state, `asr error: ${event.message ?? 'unknown'}, please repeat`)
  }

  if (event.type !== 'USER_ASR' || event.isFinal !== true) {
    return { state, actions: [] }
  }

  const text = normalize(event.text ?? '')

  if (state.type === 'WAIT_POEM_NAME') {
    if (text.indexOf('jingyesi') >= 0 || text.indexOf('jingye') >= 0) {
      const next: DemoState = { type: 'WAIT_DYNASTY_AUTHOR', ctx: { currentLineIdx: 0, selectedPoem: POEM } }
      return {
        state: next,
        actions: [
          { type: 'SPEAK', text: '[delegate] say dynasty and author' },
          { type: 'FETCH_VARIANTS', poem: POEM },
          { type: 'START_LISTENING' }
        ]
      }
    }
    return speakAndListen(state, 'poem not found')
  }

  if (state.type === 'WAIT_DYNASTY_AUTHOR') {
    if (text.indexOf('tang') >= 0 && text.indexOf('libai') >= 0) {
      return speakAndListen({ type: 'RECITING', ctx: { currentLineIdx: 0, selectedPoem: state.ctx.selectedPoem ?? POEM } }, 'start line one')
    }
    return speakAndListen(state, 'repeat dynasty and author')
  }

  if (state.type === 'RECITING') {
    const idx = state.ctx.currentLineIdx
    const expected = idx >= 0 && idx < LINES.length ? normalize(LINES[idx]) : normalize(LINES[0])
    if (text === expected) {
      const nextIdx = idx + 1
      if (nextIdx >= LINES.length) {
        return speakAndStop({ type: 'FINISHED', ctx: { currentLineIdx: nextIdx, selectedPoem: state.ctx.selectedPoem ?? POEM } }, 'poem finished')
      }
      return speakAndListen({ type: 'RECITING', ctx: { currentLineIdx: nextIdx, selectedPoem: state.ctx.selectedPoem ?? POEM } }, 'next line')
    }
    return speakAndListen(state, 'try again')
  }

  if (state.type === 'FINISHED') {
    if (text.indexOf('again') >= 0 || text.indexOf('next') >= 0) {
      return speakAndListen({ type: 'WAIT_POEM_NAME', ctx: { currentLineIdx: 0, selectedPoem: null } }, 'say next title')
    }
    return speakAndStop({ ...state, type: 'EXIT' }, 'session ended')
  }

  if (state.type === 'EXIT') {
    if (text.indexOf('start') >= 0) {
      return speakAndListen({ type: 'WAIT_POEM_NAME', ctx: { currentLineIdx: 0, selectedPoem: null } }, 'say poem title')
    }
    return { state, actions: [] }
  }

  return { state, actions: [] }
}

function createSessionDriverStateJson(): string {
  return JSON.stringify(createState())
}

function reduceSessionDriverJson(stateJson: string, eventJson: string): string {
  if (mode === 'invalid-json') {
    return '{'
  }
  if (mode === 'invalid-actions') {
    return JSON.stringify({
      state: createState(),
      actions: [{}]
    })
  }
  if (mode === 'invalid-state') {
    return JSON.stringify({
      state: { broken: true },
      actions: []
    })
  }
  const state = parseStateJson(stateJson)
  const event = parseEventJson(eventJson)
  return JSON.stringify(reduce(state, event))
}

function enableDelegateWithMode(next: Exclude<DemoSharedCoreDelegateMode, 'off'>): void {
  mode = next
  delegateRegistrationToken = registerSharedCoreDelegateHooks({
    createSessionDriverStateJson,
    reduceSessionDriverJson
  })
}

export function enableDemoSharedCoreDelegate(): void {
  enableDelegateWithMode('normal')
}

export function enableDemoSharedCoreDelegateInvalidJson(): void {
  enableDelegateWithMode('invalid-json')
}

export function enableDemoSharedCoreDelegateInvalidActions(): void {
  enableDelegateWithMode('invalid-actions')
}

export function enableDemoSharedCoreDelegateInvalidState(): void {
  enableDelegateWithMode('invalid-state')
}

export function disableDemoSharedCoreDelegate(): void {
  if (delegateRegistrationToken !== null) {
    clearSharedCoreDelegateHooks(delegateRegistrationToken)
    delegateRegistrationToken = null
  }
  mode = 'off'
}

export function isDemoSharedCoreDelegateEnabled(): boolean {
  return mode !== 'off'
}

export function getDemoSharedCoreDelegateMode(): DemoSharedCoreDelegateMode {
  return mode
}
