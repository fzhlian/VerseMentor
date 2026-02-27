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

  test('decode rejects invalid event payload', () => {
    expect(() => decodeSessionDriverEvent(JSON.stringify({ type: 'UNKNOWN' }))).toThrow(
      'invalid-session-driver-event'
    )
  })

  test('decode rejects invalid state payload', () => {
    expect(() => decodeSessionDriverState(JSON.stringify({ type: 'IDLE', ctx: {} }))).toThrow(
      'invalid-session-driver-state'
    )
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
