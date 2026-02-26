import { describe, expect, test } from 'vitest'
import { DEFAULTS } from '../src/config/defaults'
import {
  createSessionDriverState,
  dispatchSessionDriverEvent,
  projectSessionDriverUi,
  SerializableSessionState,
  SessionDriverAction,
  SessionDriverEvent
} from '../src/bridge/session_driver'

const FIXED_NOW = 1700000000000

const TEXT = {
  TITLE: '\u9759\u591c\u601d',
  FUZZY_TITLE: '\u9759\u601d',
  GREETING: '\u4f60\u597d\uff0c\u6b22\u8fce\u80cc\u8bf5\u8bd7\u8bcd\u3002\u8bf7\u8bf4\u51fa\u8bd7\u8bcd\u9898\u76ee\u3002',
  TITLE_SELECTED:
    '\u5df2\u9009\u62e9\u300a\u9759\u591c\u601d\u300b\u3002\u8bf7\u8bf4\u51fa\u671d\u4ee3\u548c\u4f5c\u8005\u3002',
  TITLE_CONFIRM: '\u4f60\u8bf4\u7684\u662f\u300a\u9759\u591c\u601d\u300b\u5417\uff1f',
  TITLE_TIMEOUT:
    '\u6682\u65f6\u6ca1\u6709\u8bc6\u522b\u5230\u8bd7\u8bcd\u9898\u76ee\uff0c\u5148\u7ed3\u675f\u4f1a\u8bdd\u3002',
  HINT_OFFER: '\u9700\u8981\u63d0\u793a\u5417\uff1f'
}

type TraceEntry = {
  event: SessionDriverEvent['type']
  state: string
  actionTypes: Array<SessionDriverAction['type']>
  speak: string[]
  selectedPoemTitle: string | null
  currentLineIdx: number
}

function toTraceEntry(event: SessionDriverEvent, state: SerializableSessionState, actions: SessionDriverAction[]): TraceEntry {
  return {
    event: event.type,
    state: state.type,
    actionTypes: actions.map((a) => a.type),
    speak: actions
      .filter((a): a is Extract<SessionDriverAction, { type: 'SPEAK' }> => a.type === 'SPEAK')
      .map((a) => a.text),
    selectedPoemTitle: state.ctx.selectedPoem?.title ?? null,
    currentLineIdx: state.ctx.currentLineIdx
  }
}

function replay(initial: SerializableSessionState, events: SessionDriverEvent[]): TraceEntry[] {
  const trace: TraceEntry[] = []
  let state = initial
  for (const event of events) {
    const output = dispatchSessionDriverEvent(state, event)
    state = output.state
    trace.push(toTraceEntry(event, output.state, output.actions))
  }
  return trace
}

describe('session_driver fixture', () => {
  test('happy path fixture: start then exact title', () => {
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })

    const trace = replay(initial, [
      { type: 'USER_UI_START' },
      { type: 'USER_ASR', text: TEXT.TITLE, isFinal: true }
    ])

    expect(trace).toEqual([
      {
        event: 'USER_UI_START',
        state: 'WAIT_POEM_NAME',
        actionTypes: ['SPEAK', 'START_LISTENING'],
        speak: [TEXT.GREETING],
        selectedPoemTitle: null,
        currentLineIdx: 0
      },
      {
        event: 'USER_ASR',
        state: 'WAIT_DYNASTY_AUTHOR',
        actionTypes: ['SPEAK', 'FETCH_VARIANTS', 'START_LISTENING'],
        speak: [TEXT.TITLE_SELECTED],
        selectedPoemTitle: TEXT.TITLE,
        currentLineIdx: 0
      }
    ])
  })

  test('confirm path fixture: fuzzy title requires confirm', () => {
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })

    const trace = replay(initial, [
      { type: 'USER_UI_START' },
      { type: 'USER_ASR', text: TEXT.FUZZY_TITLE, isFinal: true }
    ])

    expect(trace[1]).toEqual({
      event: 'USER_ASR',
      state: 'CONFIRM_POEM_CANDIDATE',
      actionTypes: ['SPEAK', 'START_LISTENING'],
      speak: [TEXT.TITLE_CONFIRM],
      selectedPoemTitle: TEXT.TITLE,
      currentLineIdx: 0
    })
  })

  test('timeout path fixture: no title exits session', () => {
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })

    const trace = replay(initial, [
      { type: 'USER_UI_START' },
      {
        type: 'TICK',
        now: FIXED_NOW + DEFAULTS.timeouts.noPoemIntentExitSec * 1000 + 1
      }
    ])

    expect(trace[1]).toEqual({
      event: 'TICK',
      state: 'EXIT',
      actionTypes: ['SPEAK'],
      speak: [TEXT.TITLE_TIMEOUT],
      selectedPoemTitle: null,
      currentLineIdx: 0
    })
  })

  test('hint path fixture: silence during reciting triggers hint offer', () => {
    const base = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })

    const recitingState: SerializableSessionState = {
      ...base,
      type: 'RECITING',
      ctx: {
        ...base.ctx,
        selectedPoem: base.ctx.poems[0],
        currentLineIdx: 0,
        lastUserActiveAt: FIXED_NOW
      }
    }

    const output = dispatchSessionDriverEvent(recitingState, {
      type: 'TICK',
      now: FIXED_NOW + DEFAULTS.timeouts.reciteSilenceAskHintSec * 1000
    })

    expect(toTraceEntry({ type: 'TICK', now: 0 }, output.state, output.actions)).toEqual({
      event: 'TICK',
      state: 'HINT_OFFER',
      actionTypes: ['SPEAK', 'START_LISTENING'],
      speak: [TEXT.HINT_OFFER],
      selectedPoemTitle: TEXT.TITLE,
      currentLineIdx: 0
    })
  })

  test('state is JSON-serializable and supports round-trip dispatch', () => {
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const raw = JSON.stringify(initial)
    const parsed = JSON.parse(raw) as SerializableSessionState

    const output = dispatchSessionDriverEvent(parsed, { type: 'USER_UI_START' })
    const projection = projectSessionDriverUi(output.state)

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(projection).toEqual({
      stateType: 'WAIT_POEM_NAME',
      currentLineIdx: 0,
      selectedPoemTitle: undefined,
      reciteProgress: []
    })
  })
})
