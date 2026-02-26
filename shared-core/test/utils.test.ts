import { describe, expect, test } from 'vitest'
import { dedupeSpeechFiller, normalizeZh, stripPunct } from '../src/utils/zh_normalize'
import { editDistance, jaccard2gram, sim } from '../src/utils/similarity'
import { hash32, makeAuthorId, makeDynastyId, slugZh } from '../src/utils/id'

describe('utils', () => {
  test('normalize and punctuation cleanup', () => {
    expect(normalizeZh('  \u9019\u662f\u3000\u6e2c\u8a66  ')).toContain('\u8fd9')
    expect(stripPunct('\u4f60\u597d\uff0c\u4e16\u754c\uff01')).toBe('\u4f60\u597d\u4e16\u754c')
    expect(dedupeSpeechFiller('\u55ef\u55ef\u90a3\u4e2a\u8fd9\u4e2a \u9759\u591c\u601d \u9759\u591c\u601d')).toContain('\u9759\u591c\u601d')
  })

  test('similarity helpers', () => {
    expect(editDistance('abc', 'abc')).toBe(0)
    expect(jaccard2gram('\u4f60\u597d', '\u4f60\u597d')).toBeGreaterThan(0.9)
    expect(sim('\u9759\u591c\u601d', '\u9759\u591c\u601d')).toBeGreaterThan(0.9)
  })

  test('id helpers', () => {
    expect(slugZh(' \u9759\u591c\u601d ')).toBeTruthy()
    expect(hash32('test')).toHaveLength(8)
    expect(makeDynastyId('\u5510')).toMatch(/^dyn_/)
    expect(makeAuthorId('\u674e\u767d')).toMatch(/^auth_/)
  })
})
