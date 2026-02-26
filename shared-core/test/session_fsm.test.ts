import { describe, expect, test } from 'vitest'
import { DEFAULTS } from '../src/config/defaults'
import { PoemIndex } from '../src/data/poem_index'
import { samplePoems } from '../src/data/sample_poems'
import { buildOrUpdateDynastyKB } from '../src/kb/dynasty_kb'
import { buildOrUpdateAuthorKB } from '../src/kb/author_kb'
import { createInitialSession, sessionReducer } from '../src/fsm/session_fsm'

const now = 1700000000000

function makeCtx() {
  const poemIndex = new PoemIndex(samplePoems)
  const dynastyKB = buildOrUpdateDynastyKB({ observedDynasties: ['唐', '宋'], now })
  const authorKB = buildOrUpdateAuthorKB({
    poems: samplePoems.map((p) => ({
      title: p.title,
      dynastyRaw: p.dynasty,
      authorPrimary: p.author
    })),
    dynastyKB,
    now
  })
  return {
    config: DEFAULTS,
    poemIndex,
    poems: samplePoems,
    dynastyKB,
    authorKB
  }
}

describe('session_fsm', () => {
  test('timeout exit', () => {
    const ctx = makeCtx()
    const initial = createInitialSession({ ...ctx, lastUserActiveAt: now })
    const started = sessionReducer(initial, { type: 'USER_UI_START' })
    const exit = sessionReducer(started.state, {
      type: 'TICK',
      now: now + DEFAULTS.timeouts.noPoemIntentExitSec * 1000 + 10
    })
    expect(exit.state.type).toBe('EXIT')
  })

  test('poem confirm path', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const started = sessionReducer(initial, { type: 'USER_UI_START' })
    const afterAsr = sessionReducer(started.state, {
      type: 'USER_ASR',
      text: '静夜思',
      isFinal: true
    })
    expect(afterAsr.state.type).toBe('WAIT_DYNASTY_AUTHOR')
  })

  test('variants hot update does not block', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const started = sessionReducer(initial, { type: 'USER_UI_START' })
    const afterAsr = sessionReducer(started.state, {
      type: 'USER_ASR',
      text: '静夜思',
      isFinal: true
    })
    const updated = sessionReducer(afterAsr.state, {
      type: 'EV_VARIANTS_FETCH_DONE',
      entry: null
    })
    expect(updated.state.type).toBe('WAIT_DYNASTY_AUTHOR')
  })
})
