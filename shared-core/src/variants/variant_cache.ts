import { VariantFetchConfig } from '../config/defaults'
import { PoemLineVariant, PoemVariants } from '../models'
import { VariantCacheStore } from '../ports/storage'
import { normalizeZh, stripPunct } from '../utils/zh_normalize'
import { IVariantProvider, ProviderResult, VariantCandidate } from './variant_provider'

export function buildPoemKey(poem: { title: string; author?: string; dynasty?: string }): string {
  const parts = [poem.title, poem.author ?? '', poem.dynasty ?? '']
  return parts.map((p) => normalizeVariantText(p)).join('|')
}

export function normalizeVariantText(raw: string): string {
  const normalized = normalizeZh(raw)
  const stripped = stripPunct(normalized)
  return stripped.replace(/\s+/g, '')
}

export interface MergedVariantResult {
  lines: PoemLineVariant[]
  sourceTags: string[]
}

function scoreCandidate(candidate: VariantCandidate, weight: number): number {
  const base = candidate.confidence ?? 0.6
  return base * weight
}

type AggregatedVariant = { text: string; score: number; bestSingle: number }

export function mergeVariantResults(
  inputs: ProviderResult[],
  cfg: VariantFetchConfig,
  _now: number
): MergedVariantResult {
  const lineMap = new Map<number, Map<string, AggregatedVariant>>()
  const sources = new Set<string>()

  for (const input of inputs) {
    const weight = cfg.providerWeights[input.provider] ?? cfg.providerWeights.default ?? 1
    sources.add(input.provider)
    for (const line of input.lines) {
      const perLine = lineMap.get(line.lineIndex) ?? new Map()
      for (const variant of line.variants) {
        const key = normalizeVariantText(variant.text)
        if (!key) continue
        const score = scoreCandidate(variant, weight)
        const existing = perLine.get(key)
        if (!existing) {
          perLine.set(key, { text: variant.text.trim(), score, bestSingle: score })
        } else {
          const bestSingle = Math.max(existing.bestSingle, score)
          const text = score >= existing.bestSingle ? variant.text.trim() : existing.text
          perLine.set(key, { text, score: existing.score + score, bestSingle })
        }
      }
      lineMap.set(line.lineIndex, perLine)
    }
  }

  const lines: PoemLineVariant[] = []
  for (const [lineIndex, variantsMap] of lineMap.entries()) {
    const list = Array.from(variantsMap.values())
      .sort((a, b) => {
        if (b.score !== a.score) return b.score - a.score
        return a.text.localeCompare(b.text)
      })
      .slice(0, cfg.maxVariantsPerLine)
      .map((item) => item.text)

    lines.push({ lineIndex, variants: list })
  }

  lines.sort((a, b) => a.lineIndex - b.lineIndex)

  return {
    lines,
    sourceTags: Array.from(sources.values())
  }
}

export class VariantCacheManager {
  constructor(
    private store: VariantCacheStore,
    private providers: IVariantProvider[],
    private cfg: VariantFetchConfig
  ) {}

  async getOrFetch(poem: { title: string; author?: string; dynasty?: string }, now: number) {
    const key = buildPoemKey(poem)
    const cached = await this.store.get(key)
    if (cached && now <= cached.expiresAt) {
      return cached
    }
    return this.fetchAndMerge(poem, now)
  }

  async fetchAndMerge(poem: { title: string; author?: string; dynasty?: string }, now: number) {
    const key = buildPoemKey(poem)
    const results = await Promise.all(
      this.providers.map(async (provider) => {
        try {
          return await provider.fetch(poem)
        } catch {
          return { provider: provider.name, lines: [] }
        }
      })
    )

    const merged = mergeVariantResults(results, this.cfg, now)
    const ttlMillis = Math.max(0, this.cfg.ttlDays) * 24 * 60 * 60 * 1000
    const entry = {
      poemId: key,
      variants: {
        poemId: key,
        lines: merged.lines,
        sourceTags: merged.sourceTags
      } as PoemVariants,
      cachedAt: now,
      expiresAt: now + ttlMillis
    }

    await this.store.set(key, entry)
    return entry
  }
}
