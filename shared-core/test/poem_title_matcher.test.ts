import { describe, expect, test } from 'vitest'
import { samplePoems } from '../src/data/sample_poems'
import { PoemIndex } from '../src/data/poem_index'
import { matchPoemTitle } from '../src/nlu/poem_title_matcher'

describe('poem_title_matcher', () => {
  const index = new PoemIndex(samplePoems)

  test('exact title should match with score 1', () => {
    const result = matchPoemTitle(index, '静夜思')
    expect(result.candidates.length).toBeGreaterThan(0)
    expect(result.candidates[0].poem.title).toBe('静夜思')
    expect(result.candidates[0].score).toBe(1)
  })

  test('natural utterance containing title should match directly', () => {
    const result = matchPoemTitle(index, '我想背静夜思')
    expect(result.candidates.length).toBeGreaterThan(0)
    expect(result.candidates[0].poem.title).toBe('静夜思')
    expect(result.candidates[0].score).toBeGreaterThanOrEqual(0.9)
  })

  test('utterance with repeated title tail should still match directly', () => {
    const result = matchPoemTitle(index, '嗯嗯那个我想背静夜思静夜思')
    expect(result.candidates.length).toBeGreaterThan(0)
    expect(result.candidates[0].poem.title).toBe('静夜思')
    expect(result.candidates[0].score).toBeGreaterThanOrEqual(0.9)
  })

  test('generic recite command without title should not produce candidates', () => {
    const result = matchPoemTitle(index, '我想背一首诗')
    expect(result.candidates).toEqual([])
  })

  test('short fuzzy title still returns candidate above threshold', () => {
    const result = matchPoemTitle(index, '静思')
    expect(result.candidates.length).toBeGreaterThan(0)
    expect(result.candidates[0].poem.title).toBe('静夜思')
  })

  test('traditional title should normalize to simplified and match', () => {
    const result = matchPoemTitle(index, '靜夜思')
    expect(result.candidates.length).toBeGreaterThan(0)
    expect(result.candidates[0].poem.title).toBe('静夜思')
    expect(result.candidates[0].score).toBe(1)
  })

  test('traditional qiao character in title should normalize for exact match', () => {
    const result = matchPoemTitle(index, '楓橋夜泊')
    expect(result.candidates.length).toBeGreaterThan(0)
    expect(result.candidates[0].poem.title).toBe('枫桥夜泊')
    expect(result.candidates[0].score).toBe(1)
  })
})
