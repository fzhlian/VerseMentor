import { describe, expect, test } from 'vitest'
import {
  decodeSessionDriverActions,
  decodeSessionDriverEvent,
  decodeSessionDriverState,
  decodeSessionDriverReduceResponse,
  encodeSessionDriverEvent,
  encodeSessionDriverState,
  reduceSessionDriverJson
} from '../src/bridge/session_contract'
import { createSessionDriverState, dispatchSessionDriverEvent } from '../src/bridge/session_driver'

const FIXED_NOW = 1700000000000

describe('session_contract', () => {
  test('event encode/decode round-trip', () => {
    const event = { type: 'USER_ASR', text: '\u9759\u591c\u601d', isFinal: true, confidence: 0.9 } as const
    const raw = encodeSessionDriverEvent(event)
    const decoded = decodeSessionDriverEvent(raw)
    expect(decoded).toEqual(event)
  })

  test('asr event encode/decode with now round-trip', () => {
    const event = { type: 'USER_ASR', text: '\u9759\u591c\u601d', isFinal: true, now: FIXED_NOW } as const
    const raw = encodeSessionDriverEvent(event)
    const decoded = decodeSessionDriverEvent(raw)
    expect(decoded).toEqual(event)
  })

  test('asr event encode/decode with confidence and now round-trip', () => {
    const event = { type: 'USER_ASR', text: '\u9759\u591c\u601d', isFinal: true, confidence: 0.92, now: FIXED_NOW } as const
    const raw = encodeSessionDriverEvent(event)
    const decoded = decodeSessionDriverEvent(raw)
    expect(decoded).toEqual(event)
  })

  test('ui start event encode/decode with now round-trip', () => {
    const event = { type: 'USER_UI_START', now: FIXED_NOW } as const
    const raw = encodeSessionDriverEvent(event)
    const decoded = decodeSessionDriverEvent(raw)
    expect(decoded).toEqual(event)
  })

  test('asr error event encode/decode round-trip', () => {
    const event = { type: 'USER_ASR_ERROR', code: 7, message: 'no match' } as const
    const raw = encodeSessionDriverEvent(event)
    const decoded = decodeSessionDriverEvent(raw)
    expect(decoded).toEqual(event)
  })

  test('reduceSessionDriverJson equals direct dispatch', () => {
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const event = { type: 'USER_UI_START' } as const

    const direct = dispatchSessionDriverEvent(initial, event)
    const jsonRaw = reduceSessionDriverJson(encodeSessionDriverState(initial), encodeSessionDriverEvent(event))
    const fromJson = decodeSessionDriverReduceResponse(jsonRaw)

    expect(fromJson).toEqual(direct)
  })

  test('reduceSessionDriverJson preserves USER_UI_START.now as timer baseline', () => {
    const now = FIXED_NOW + 1234
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const event = { type: 'USER_UI_START', now } as const

    const jsonRaw = reduceSessionDriverJson(encodeSessionDriverState(initial), encodeSessionDriverEvent(event))
    const fromJson = decodeSessionDriverReduceResponse(jsonRaw)

    expect(fromJson.state.type).toBe('WAIT_POEM_NAME')
    expect(fromJson.state.ctx.timers.noPoemIntentSince).toBe(now)
  })

  test('reduceSessionDriverJson handles USER_UI_STOP on active state', () => {
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const started = dispatchSessionDriverEvent(initial, { type: 'USER_UI_START' }).state
    const stopEvent = { type: 'USER_UI_STOP' } as const

    const direct = dispatchSessionDriverEvent(started, stopEvent)
    const jsonRaw = reduceSessionDriverJson(
      encodeSessionDriverState(started),
      encodeSessionDriverEvent(stopEvent)
    )
    const fromJson = decodeSessionDriverReduceResponse(jsonRaw)

    expect(fromJson).toEqual(direct)
    expect(fromJson.state.type).toBe('EXIT')
    expect(fromJson.actions).toEqual([{ type: 'STOP_LISTENING' }])
  })

  test('reduceSessionDriverJson handles REPEAT_PROMPT on active state', () => {
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const started = dispatchSessionDriverEvent(initial, { type: 'USER_UI_START' }).state
    const repeatEvent = { type: 'USER_ASR', text: '\u518d\u8bf4\u4e00\u904d', isFinal: true } as const

    const direct = dispatchSessionDriverEvent(started, repeatEvent)
    const jsonRaw = reduceSessionDriverJson(
      encodeSessionDriverState(started),
      encodeSessionDriverEvent(repeatEvent)
    )
    const fromJson = decodeSessionDriverReduceResponse(jsonRaw)

    expect(fromJson).toEqual(direct)
    expect(fromJson.state.type).toBe('WAIT_POEM_NAME')
    expect(fromJson.actions).toEqual([
      { type: 'SPEAK', text: '\u4f60\u597d\uff0c\u6b22\u8fce\u80cc\u8bf5\u8bd7\u8bcd\u3002\u8bf7\u8bf4\u51fa\u8bd7\u8bcd\u9898\u76ee\u3002' },
      { type: 'START_LISTENING' }
    ])
  })

  test('reduceSessionDriverJson handles restart after USER_UI_STOP', () => {
    const initial = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const started = dispatchSessionDriverEvent(initial, { type: 'USER_UI_START' }).state
    const stopped = dispatchSessionDriverEvent(started, { type: 'USER_UI_STOP' }).state
    const restartEvent = { type: 'USER_UI_START' } as const

    const direct = dispatchSessionDriverEvent(stopped, restartEvent)
    const jsonRaw = reduceSessionDriverJson(
      encodeSessionDriverState(stopped),
      encodeSessionDriverEvent(restartEvent)
    )
    const fromJson = decodeSessionDriverReduceResponse(jsonRaw)

    expect(fromJson.actions).toEqual(direct.actions)
    expect(fromJson.state.type).toBe(direct.state.type)
    expect(fromJson.state.type).toBe('WAIT_POEM_NAME')
    expect(fromJson.actions).toEqual([
      { type: 'SPEAK', text: '\u4f60\u597d\uff0c\u6b22\u8fce\u80cc\u8bf5\u8bd7\u8bcd\u3002\u8bf7\u8bf4\u51fa\u8bd7\u8bcd\u9898\u76ee\u3002' },
      { type: 'START_LISTENING' }
    ])
    expect(fromJson.state.ctx.currentLineIdx).toBe(0)
    expect(fromJson.state.ctx.reciteProgress).toEqual([])
    expect(fromJson.state.ctx.selectedPoem).toBeUndefined()
    expect(typeof fromJson.state.ctx.timers.noPoemIntentSince).toBe('number')
  })

  test('decode rejects invalid event payload', () => {
    expect(() => decodeSessionDriverEvent(JSON.stringify({ type: 'UNKNOWN' }))).toThrow(
      'invalid-session-driver-event'
    )
  })

  test('decode rejects invalid USER_UI_START now payload', () => {
    expect(() => decodeSessionDriverEvent(JSON.stringify({ type: 'USER_UI_START', now: 'bad' }))).toThrow(
      'invalid-session-driver-event'
    )
  })

  test('decode rejects invalid USER_ASR now payload', () => {
    const bad = {
      type: 'USER_ASR',
      text: '\u9759\u591c\u601d',
      isFinal: true,
      now: 'bad'
    }
    expect(() => decodeSessionDriverEvent(JSON.stringify(bad))).toThrow('invalid-session-driver-event')
  })

  test('decode rejects invalid USER_ASR confidence payload', () => {
    const bad = {
      type: 'USER_ASR',
      text: '\u9759\u591c\u601d',
      isFinal: true,
      confidence: 'bad'
    }
    expect(() => decodeSessionDriverEvent(JSON.stringify(bad))).toThrow('invalid-session-driver-event')
  })

  test('decode rejects non-finite event numeric payload', () => {
    const raw = '{"type":"TICK","now":1e309}'
    expect(() => decodeSessionDriverEvent(raw)).toThrow('invalid-session-driver-event')
  })

  test('decode rejects invalid EV_VARIANTS_FETCH_DONE entry payload', () => {
    const badEvent = {
      type: 'EV_VARIANTS_FETCH_DONE',
      entry: {
        poemId: 'k',
        variants: {
          poemId: 'k',
          lines: [{ lineIndex: '0', variants: ['a'] }],
          sourceTags: ['p']
        },
        cachedAt: 1,
        expiresAt: 2
      }
    }
    expect(() => decodeSessionDriverEvent(JSON.stringify(badEvent))).toThrow(
      'invalid-session-driver-event'
    )
  })

  test('decode rejects invalid state payload', () => {
    expect(() => decodeSessionDriverState(JSON.stringify({ type: 'IDLE', ctx: {} }))).toThrow(
      'invalid-session-driver-state'
    )
  })

  test('decode rejects invalid state type payload', () => {
    const state = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const bad = {
      ...state,
      type: 'WAITING_POEM'
    }
    expect(() => decodeSessionDriverState(JSON.stringify(bad))).toThrow('invalid-session-driver-state')
  })

  test('decode rejects invalid state timers.hintOfferSince payload', () => {
    const state = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const bad = {
      ...state,
      ctx: {
        ...state.ctx,
        timers: {
          ...state.ctx.timers,
          hintOfferSince: 'oops'
        }
      }
    }
    expect(() => decodeSessionDriverState(JSON.stringify(bad))).toThrow('invalid-session-driver-state')
  })

  test('decode rejects invalid state config payload', () => {
    const state = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const bad = {
      ...state,
      ctx: {
        ...state.ctx,
        config: {
          ...state.ctx.config,
          timeouts: {
            ...state.ctx.config.timeouts,
            noPoemIntentExitSec: 'bad'
          }
        }
      }
    }
    expect(() => decodeSessionDriverState(JSON.stringify(bad))).toThrow('invalid-session-driver-state')
  })

  test('decode rejects invalid state variantsCacheEntry payload', () => {
    const state = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const bad = {
      ...state,
      ctx: {
        ...state.ctx,
        variantsCacheEntry: {
          poemId: 'k',
          variants: {
            poemId: 'k',
            lines: [{ lineIndex: 0, variants: [1] }],
            sourceTags: ['p']
          },
          cachedAt: 1,
          expiresAt: 2
        }
      }
    }
    expect(() => decodeSessionDriverState(JSON.stringify(bad))).toThrow('invalid-session-driver-state')
  })

  test('decode rejects invalid state dynastyResolved payload', () => {
    const state = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const bad = {
      ...state,
      ctx: {
        ...state.ctx,
        dynastyResolved: {
          matchedBy: 'canonical',
          canonical: {
            id: 1,
            name: '唐',
            source: 'auto',
            createdAt: 1,
            updatedAt: 1,
            freq: 1
          }
        }
      }
    }
    expect(() => decodeSessionDriverState(JSON.stringify(bad))).toThrow('invalid-session-driver-state')
  })

  test('decode rejects invalid state authorResolved payload', () => {
    const state = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const bad = {
      ...state,
      ctx: {
        ...state.ctx,
        authorResolved: {
          matchedBy: 'primary',
          author: {
            id: 'a',
            name: '李白',
            aliases: [1],
            source: 'auto',
            createdAt: 1,
            updatedAt: 1,
            freq: 1
          }
        }
      }
    }
    expect(() => decodeSessionDriverState(JSON.stringify(bad))).toThrow('invalid-session-driver-state')
  })

  test('decode rejects invalid state dynastyKB payload', () => {
    const state = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const bad = {
      ...state,
      ctx: {
        ...state.ctx,
        dynastyKB: {
          ...state.ctx.dynastyKB,
          canonicals: [
            {
              id: 1,
              name: '唐',
              source: 'auto',
              createdAt: 1,
              updatedAt: 1,
              freq: 1
            }
          ]
        }
      }
    }
    expect(() => decodeSessionDriverState(JSON.stringify(bad))).toThrow('invalid-session-driver-state')
  })

  test('decode rejects invalid state authorKB payload', () => {
    const state = createSessionDriverState({
      now: FIXED_NOW,
      lastUserActiveAt: FIXED_NOW
    })
    const bad = {
      ...state,
      ctx: {
        ...state.ctx,
        authorKB: {
          authors: [
            {
              id: 'a',
              name: '李白',
              aliases: [1],
              source: 'auto',
              createdAt: 1,
              updatedAt: 1,
              freq: 1
            }
          ]
        }
      }
    }
    expect(() => decodeSessionDriverState(JSON.stringify(bad))).toThrow('invalid-session-driver-state')
  })

  test('decode rejects invalid actions payload', () => {
    expect(() => decodeSessionDriverActions(JSON.stringify([{ type: 'SPEAK' }]))).toThrow(
      'invalid-session-driver-actions'
    )
  })

  test('decode rejects invalid reduce response payload', () => {
    const bad = {
      state: { type: 'IDLE', ctx: {} },
      actions: [{ type: 'START_LISTENING' }]
    }
    expect(() => decodeSessionDriverReduceResponse(JSON.stringify(bad))).toThrow(
      'invalid-session-driver-state'
    )
  })
})
