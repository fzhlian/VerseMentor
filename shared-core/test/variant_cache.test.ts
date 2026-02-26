import { describe, expect, test } from 'vitest'
import { InMemoryVariantCacheStore } from '../src/ports/storage'
import { VariantFetchConfig } from '../src/config/defaults'
import { mergeVariantResults, VariantCacheManager } from '../src/variants/variant_cache'
import { IVariantProvider, ProviderResult } from '../src/variants/variant_provider'

const baseCfg: VariantFetchConfig = {
  enableOnline: true,
  ttlDays: 1,
  providerWeights: { default: 1, p1: 1, p2: 2 },
  maxVariantsPerLine: 2
}

describe('variant_cache', () => {
  test('merge de-duplicates identical variants', () => {
    const inputs: ProviderResult[] = [
      {
        provider: 'p1',
        lines: [
          { lineIndex: 0, variants: [{ text: '床前明月光' }, { text: '床前明月光' }] }
        ]
      }
    ]

    const merged = mergeVariantResults(inputs, baseCfg, 0)
    expect(merged.lines[0].variants.length).toBe(1)
  })

  test('multi-provider increases weight and keeps top N', () => {
    const inputs: ProviderResult[] = [
      {
        provider: 'p1',
        lines: [
          {
            lineIndex: 0,
            variants: [
              { text: 'A', confidence: 0.6 },
              { text: 'B', confidence: 0.9 }
            ]
          }
        ]
      },
      {
        provider: 'p2',
        lines: [
          {
            lineIndex: 0,
            variants: [
              { text: 'A', confidence: 0.4 },
              { text: 'C', confidence: 0.7 }
            ]
          }
        ]
      }
    ]

    const merged = mergeVariantResults(inputs, baseCfg, 0)
    expect(merged.lines[0].variants).toEqual(['A', 'C'])
  })

  test('TTL logic returns cached if not expired', async () => {
    let calls = 0
    const provider: IVariantProvider = {
      name: 'p1',
      async fetch() {
        calls += 1
        return { provider: 'p1', lines: [{ lineIndex: 0, variants: [{ text: 'X' }] }] }
      }
    }

    const store = new InMemoryVariantCacheStore()
    const manager = new VariantCacheManager(store, [provider], baseCfg)

    const poem = { title: '静夜思', author: '李白', dynasty: '唐' }
    const now = 1000
    await manager.getOrFetch(poem, now)
    await manager.getOrFetch(poem, now + 1000)

    expect(calls).toBe(1)
  })
})
