import { describe, expect, test } from 'vitest'
import { IntentType, parseIntent } from '../src/nlu/intent'

describe('intent parser', () => {
  test('recognizes explicit poem confirmation utterances', () => {
    expect(parseIntent('\u662f\u7684').type).toBe(IntentType.SET_POEM)
    expect(parseIntent('\u597d').type).toBe(IntentType.SET_POEM)
    expect(parseIntent('\u6ca1\u9519').type).toBe(IntentType.SET_POEM)
    expect(parseIntent('\u662f\u7684\u3002').type).toBe(IntentType.SET_POEM)
    expect(parseIntent('\u597d\uff01').type).toBe(IntentType.SET_POEM)
  })

  test('does not treat question-style confirmation as SET_POEM', () => {
    expect(parseIntent('\u662f\u5417').type).toBe(IntentType.UNKNOWN)
    expect(parseIntent('\u662f\u8fd9\u9996\u5417').type).toBe(IntentType.UNKNOWN)
    expect(parseIntent('\u662f\u8fd9\u9996\uff1f').type).toBe(IntentType.UNKNOWN)
    expect(parseIntent('\u662f\u8fd9\u9996?').type).toBe(IntentType.UNKNOWN)
  })

  test('still recognizes reject intent with higher priority', () => {
    expect(parseIntent('\u4e0d\u662f').type).toBe(IntentType.REJECT_POEM)
    expect(parseIntent('\u4e0d\u5bf9').type).toBe(IntentType.REJECT_POEM)
  })

  test('recognizes common traditional command variants', () => {
    expect(parseIntent('\u7d50\u675f\u3002').type).toBe(IntentType.EXIT_SESSION)
    expect(parseIntent('\u63db\u4e00\u9996').type).toBe(IntentType.NEXT_POEM)
    expect(parseIntent('\u518d\u4f86\u4e00\u9996').type).toBe(IntentType.NEXT_POEM)
    expect(parseIntent('\u958b\u59cb\u80cc\u8aa6').type).toBe(IntentType.START_RECITE)
    expect(parseIntent('\u80cc\u8a69 \u975c\u591c\u601d').type).toBe(IntentType.RECITE_POEM)
    expect(parseIntent('\u518d\u4f86\u4e00\u904d').type).toBe(IntentType.REPEAT_PROMPT)
  })
})
