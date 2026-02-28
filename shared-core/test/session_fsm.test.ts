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

  test('ui start restarts session from EXIT state', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const exited = {
      ...initial,
      type: 'EXIT' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 2,
        reciteProgress: [{ idx: 0, passed: true, score: 1 }]
      }
    }
    const restarted = sessionReducer(exited, { type: 'USER_UI_START' })

    expect(restarted.state.type).toBe('WAIT_POEM_NAME')
    expect(restarted.state.ctx.selectedPoem).toBeUndefined()
    expect(restarted.state.ctx.currentLineIdx).toBe(0)
    expect(restarted.state.ctx.reciteProgress).toEqual([])
    expect(restarted.actions).toEqual([
      { type: 'SPEAK', text: '你好，欢迎背诵诗词。请说出诗词题目。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('ui start with explicit now sets noPoemIntentSince deterministically', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const started = sessionReducer(initial, { type: 'USER_UI_START', now: now + 1234 })
    expect(started.state.type).toBe('WAIT_POEM_NAME')
    expect(started.state.ctx.timers.noPoemIntentSince).toBe(now + 1234)
  })

  test('ui start restart refreshes noPoemIntentSince baseline', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const exited = {
      ...initial,
      type: 'EXIT' as const,
      ctx: {
        ...initial.ctx,
        lastUserActiveAt: 1
      }
    }
    const restarted = sessionReducer(exited, { type: 'USER_UI_START' })
    expect(restarted.state.type).toBe('WAIT_POEM_NAME')
    expect(restarted.state.ctx.timers.noPoemIntentSince).not.toBe(1)
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

  test('natural recite utterance containing title should select poem directly', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const started = sessionReducer(initial, { type: 'USER_UI_START' })
    const afterAsr = sessionReducer(started.state, {
      type: 'USER_ASR',
      text: '我想背静夜思',
      isFinal: true
    })

    expect(afterAsr.state.type).toBe('WAIT_DYNASTY_AUTHOR')
    expect(afterAsr.state.ctx.selectedPoem?.title).toBe('静夜思')
  })

  test('traditional recite utterance containing title should select poem directly', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const started = sessionReducer(initial, { type: 'USER_UI_START' })
    const afterAsr = sessionReducer(started.state, {
      type: 'USER_ASR',
      text: '我想背誦靜夜思',
      isFinal: true
    })

    expect(afterAsr.state.type).toBe('WAIT_DYNASTY_AUTHOR')
    expect(afterAsr.state.ctx.selectedPoem?.title).toBe('静夜思')
  })

  test('generic recite command without title should not force fuzzy confirm', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const started = sessionReducer(initial, { type: 'USER_UI_START' })
    const afterAsr = sessionReducer(started.state, {
      type: 'USER_ASR',
      text: '我想背一首诗',
      isFinal: true
    })

    expect(afterAsr.state.type).toBe('WAIT_POEM_NAME')
    expect(afterAsr.actions).toEqual([
      { type: 'SPEAK', text: '没有找到这首诗，请再说一次题目。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('confirm candidate with non-affirmative utterance should clear selected poem', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const confirmState = {
      ...initial,
      type: 'CONFIRM_POEM_CANDIDATE' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(confirmState, {
      type: 'USER_ASR',
      text: '不知道',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '没有确认到题目，请再说一次题目。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('confirm candidate with reject intent should clear selected poem', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const confirmState = {
      ...initial,
      type: 'CONFIRM_POEM_CANDIDATE' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(confirmState, {
      type: 'USER_ASR',
      text: '不是',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '好的，请再说一次题目。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('confirm candidate with affirmative utterance should proceed to dynasty-author stage', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const confirmState = {
      ...initial,
      type: 'CONFIRM_POEM_CANDIDATE' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(confirmState, {
      type: 'USER_ASR',
      text: '是的',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_DYNASTY_AUTHOR')
    expect(output.state.ctx.selectedPoem?.title).toBe('静夜思')
    expect(output.actions[0]).toEqual({
      type: 'SPEAK',
      text: '已选择《静夜思》。请说出朝代和作者。'
    })
    expect(output.actions.some((action) => action.type === 'FETCH_VARIANTS')).toBe(true)
    expect(output.actions).toContainEqual({ type: 'START_LISTENING' })
  })

  test('confirm candidate with question containing shi should not auto-confirm', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const confirmState = {
      ...initial,
      type: 'CONFIRM_POEM_CANDIDATE' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(confirmState, {
      type: 'USER_ASR',
      text: '是吗',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '没有确认到题目，请再说一次题目。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('confirm candidate with question phrase shi zhe shou ma should not auto-confirm', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const confirmState = {
      ...initial,
      type: 'CONFIRM_POEM_CANDIDATE' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(confirmState, {
      type: 'USER_ASR',
      text: '是这首吗',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '没有确认到题目，请再说一次题目。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('confirm candidate with question tone ne should not auto-confirm', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const confirmState = {
      ...initial,
      type: 'CONFIRM_POEM_CANDIDATE' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(confirmState, {
      type: 'USER_ASR',
      text: '是这首呢',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '没有确认到题目，请再说一次题目。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('confirm candidate with question punctuation should not auto-confirm', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const confirmState = {
      ...initial,
      type: 'CONFIRM_POEM_CANDIDATE' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(confirmState, {
      type: 'USER_ASR',
      text: '是这首？',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '没有确认到题目，请再说一次题目。' },
      { type: 'START_LISTENING' }
    ])
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

  test('wait dynasty-author accepts only selected poem dynasty and author', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const waiting = {
      ...initial,
      type: 'WAIT_DYNASTY_AUTHOR' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(waiting, {
      type: 'USER_ASR',
      text: '唐 李白',
      isFinal: true
    })

    expect(output.state.type).toBe('RECITE_READY')
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '好的，开始背诵。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('wait dynasty-author rejects mismatched author even when both entities are recognized', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const waiting = {
      ...initial,
      type: 'WAIT_DYNASTY_AUTHOR' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(waiting, {
      type: 'USER_ASR',
      text: '唐 孟浩然',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_DYNASTY_AUTHOR')
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '请再说一次朝代和作者。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('asr error keeps state and asks user to retry', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const started = sessionReducer(initial, { type: 'USER_UI_START' })
    const afterError = sessionReducer(started.state, {
      type: 'USER_ASR_ERROR',
      code: 7,
      message: 'no match'
    })
    expect(afterError.state.type).toBe('WAIT_POEM_NAME')
    expect(afterError.actions).toEqual([
      { type: 'SPEAK', text: '语音识别异常：no match。请再说一次。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('voice exit command exits active session and stops listening', () => {
    const ctx = makeCtx()
    const initial = createInitialSession({ ...ctx, lastUserActiveAt: now })
    const reciting = {
      ...initial,
      type: 'RECITING' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 0,
        lastUserActiveAt: now
      }
    }

    const output = sessionReducer(reciting, {
      type: 'USER_ASR',
      text: '退出',
      isFinal: true
    })

    expect(output.state.type).toBe('EXIT')
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '好的，已结束会话。' },
      { type: 'STOP_LISTENING' }
    ])
  })

  test('traditional exit command exits active session and stops listening', () => {
    const ctx = makeCtx()
    const initial = createInitialSession({ ...ctx, lastUserActiveAt: now })
    const reciting = {
      ...initial,
      type: 'RECITING' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 0,
        lastUserActiveAt: now
      }
    }

    const output = sessionReducer(reciting, {
      type: 'USER_ASR',
      text: '結束',
      isFinal: true
    })

    expect(output.state.type).toBe('EXIT')
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '好的，已结束会话。' },
      { type: 'STOP_LISTENING' }
    ])
  })

  test('repeat prompt command replays current prompt in WAIT_POEM_NAME', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const started = sessionReducer(initial, { type: 'USER_UI_START' })
    const repeated = sessionReducer(started.state, {
      type: 'USER_ASR',
      text: '再说一遍',
      isFinal: true
    })

    expect(repeated.state.type).toBe('WAIT_POEM_NAME')
    expect(repeated.actions).toEqual([
      { type: 'SPEAK', text: '你好，欢迎背诵诗词。请说出诗词题目。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('repeat phrase zailaiyibian in FINISHED should replay prompt instead of switching poem', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const finished = {
      ...initial,
      type: 'FINISHED' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(finished, {
      type: 'USER_ASR',
      text: '再来一遍',
      isFinal: true
    })

    expect(output.state.type).toBe('FINISHED')
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '还要再来一首吗？' },
      { type: 'START_LISTENING' }
    ])
  })

  test('ui stop exits from active reciting state', () => {
    const ctx = makeCtx()
    const initial = createInitialSession({ ...ctx, lastUserActiveAt: now })
    const reciting = {
      ...initial,
      type: 'RECITING' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 0,
        lastUserActiveAt: now
      }
    }

    const output = sessionReducer(reciting, { type: 'USER_UI_STOP' })

    expect(output.state.type).toBe('EXIT')
    expect(output.actions).toEqual([{ type: 'STOP_LISTENING' }])
  })

  test('hint offer times out and resumes reciting', () => {
    const ctx = makeCtx()
    const initial = createInitialSession({ ...ctx, lastUserActiveAt: now })
    const reciting = {
      ...initial,
      type: 'RECITING' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 0,
        lastUserActiveAt: now
      }
    }

    const hintOffer = sessionReducer(reciting, {
      type: 'TICK',
      now: now + DEFAULTS.timeouts.reciteSilenceAskHintSec * 1000
    })
    expect(hintOffer.state.type).toBe('HINT_OFFER')

    const resumed = sessionReducer(hintOffer.state, {
      type: 'TICK',
      now:
        now +
        DEFAULTS.timeouts.reciteSilenceAskHintSec * 1000 +
        DEFAULTS.timeouts.hintOfferWaitSec * 1000 +
        1
    })

    expect(resumed.state.type).toBe('RECITING')
    expect(resumed.actions).toEqual([
      { type: 'SPEAK', text: '好的，继续背诵。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('RECITE_READY should not swallow first recite ASR', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const reciteReady = {
      ...initial,
      type: 'RECITE_READY' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 0
      }
    }

    const output = sessionReducer(reciteReady, {
      type: 'USER_ASR',
      text: samplePoems[0].lines[0].text,
      isFinal: true
    })

    expect(output.state.type).toBe('RECITING')
    expect(output.state.ctx.currentLineIdx).toBe(1)
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '很好，下一句。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('USER_ASR now overrides lastUserActiveAt in reciting flow', () => {
    const ctx = makeCtx()
    const initial = createInitialSession({ ...ctx, lastUserActiveAt: now })
    const reciting = {
      ...initial,
      type: 'RECITING' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 0,
        lastUserActiveAt: now
      }
    }
    const ts = now + 5000
    const output = sessionReducer(reciting, {
      type: 'USER_ASR',
      text: samplePoems[0].lines[0].text,
      isFinal: true,
      now: ts
    })

    expect(output.state.type).toBe('RECITING')
    expect(output.state.ctx.lastUserActiveAt).toBe(ts)
  })

  test('HINT_GIVEN should evaluate immediate ASR instead of forcing repeat', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const hintGiven = {
      ...initial,
      type: 'HINT_GIVEN' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 0
      }
    }

    const output = sessionReducer(hintGiven, {
      type: 'USER_ASR',
      text: samplePoems[0].lines[0].text,
      isFinal: true
    })

    expect(output.state.type).toBe('RECITING')
    expect(output.state.ctx.currentLineIdx).toBe(1)
    expect(output.actions).toEqual([
      { type: 'SPEAK', text: '很好，下一句。' },
      { type: 'START_LISTENING' }
    ])
  })

  test('finished next poem command should reset poem-scoped session context', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const finished = {
      ...initial,
      type: 'FINISHED' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        dynastyResolved: {
          matchedBy: 'canonical' as const,
          canonical: {
            id: 'dynasty:tang',
            name: '唐',
            source: 'auto' as const,
            createdAt: now,
            updatedAt: now,
            freq: 1
          },
        },
        authorResolved: {
          matchedBy: 'primary' as const,
          author: {
            id: 'author:李白',
            name: '李白',
            aliases: [],
            dynastyId: 'dynasty:tang',
            source: 'auto' as const,
            createdAt: now,
            updatedAt: now,
            freq: 1
          },
        },
        variantsCacheEntry: {
          poemId: samplePoems[0].id,
          variants: {
            poemId: samplePoems[0].id,
            lines: [],
            sourceTags: []
          },
          cachedAt: now,
          expiresAt: now + 60000
        },
        currentLineIdx: 2,
        reciteProgress: [{ idx: 0, passed: true, score: 1 }]
      }
    }

    const output = sessionReducer(finished, {
      type: 'USER_ASR',
      text: '下一首',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.state.ctx.dynastyResolved).toBeUndefined()
    expect(output.state.ctx.authorResolved).toBeUndefined()
    expect(output.state.ctx.variantsCacheEntry).toBeNull()
    expect(output.state.ctx.currentLineIdx).toBe(0)
    expect(output.state.ctx.reciteProgress).toEqual([])
  })

  test('finished traditional next-poem command should reset poem-scoped session context', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const finished = {
      ...initial,
      type: 'FINISHED' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 2,
        reciteProgress: [{ idx: 0, passed: true, score: 1 }]
      }
    }

    const output = sessionReducer(finished, {
      type: 'USER_ASR',
      text: '換一首',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.state.ctx.currentLineIdx).toBe(0)
    expect(output.state.ctx.reciteProgress).toEqual([])
  })

  test('finished zailaiyishou command should reset poem-scoped session context', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const finished = {
      ...initial,
      type: 'FINISHED' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 2,
        reciteProgress: [{ idx: 0, passed: true, score: 1 }]
      }
    }

    const output = sessionReducer(finished, {
      type: 'USER_ASR',
      text: '再来一首',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.state.ctx.currentLineIdx).toBe(0)
    expect(output.state.ctx.reciteProgress).toEqual([])
  })

  test('finished traditional zailaiyishou command should reset poem-scoped session context', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const finished = {
      ...initial,
      type: 'FINISHED' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0],
        currentLineIdx: 2,
        reciteProgress: [{ idx: 0, passed: true, score: 1 }]
      }
    }

    const output = sessionReducer(finished, {
      type: 'USER_ASR',
      text: '再來一首',
      isFinal: true
    })

    expect(output.state.type).toBe('WAIT_POEM_NAME')
    expect(output.state.ctx.selectedPoem).toBeUndefined()
    expect(output.state.ctx.currentLineIdx).toBe(0)
    expect(output.state.ctx.reciteProgress).toEqual([])
  })

  test('finished bare zailai utterance should exit session', () => {
    const ctx = makeCtx()
    const initial = createInitialSession(ctx)
    const finished = {
      ...initial,
      type: 'FINISHED' as const,
      ctx: {
        ...initial.ctx,
        selectedPoem: samplePoems[0]
      }
    }

    const output = sessionReducer(finished, {
      type: 'USER_ASR',
      text: '再来',
      isFinal: true
    })

    expect(output.state.type).toBe('EXIT')
    expect(output.actions).toEqual([{ type: 'SPEAK', text: '好的，已结束会话。' }])
  })
})
