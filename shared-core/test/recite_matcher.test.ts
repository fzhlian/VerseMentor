import { describe, expect, test } from 'vitest'
import { DEFAULTS } from '../src/config/defaults'
import { judgeLine } from '../src/recite/matcher'

const baseCfg = DEFAULTS

describe('recite matcher', () => {
  test('exact pass', () => {
    const pack = { idx: 0, baseText: '床前明月光' }
    const result = judgeLine('床前明月光', pack, baseCfg)
    expect(result.passed).toBe(true)
  })

  test('variant pass', () => {
    const pack = { idx: 0, baseText: '床前明月光', localVariants: ['床前皓月光'] }
    const result = judgeLine('床前皓月光', pack, baseCfg)
    expect(result.passed).toBe(true)
  })

  test('accent tolerance pass (pinyin)', () => {
    const pack = { idx: 0, baseText: '山' }
    const result = judgeLine('伤', pack, baseCfg)
    expect(result.passed).toBe(true)
  })
})
