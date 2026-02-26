import { describe, expect, test } from 'vitest'
import {
  decodeSessionDriverEvent,
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
})
