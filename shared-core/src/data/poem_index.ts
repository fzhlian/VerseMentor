import { normalizeZh } from '../utils/zh_normalize'
import { sim } from '../utils/similarity'
import { Poem } from './models'

export class PoemIndex {
  private titleIndex: Map<string, Poem[]> = new Map()
  private titleEntries: Array<{ poem: Poem; normalizedTitle: string }> = []

  constructor(private poems: Poem[]) {
    for (const poem of poems) {
      const key = normalizeZh(poem.title)
      const list = this.titleIndex.get(key) ?? []
      list.push(poem)
      this.titleIndex.set(key, list)
      this.titleEntries.push({ poem, normalizedTitle: key })
    }
  }

  findByTitleExact(title: string): Poem[] {
    const key = normalizeZh(title)
    return this.titleIndex.get(key) ?? []
  }

  findByTitleFuzzy(spoken: string, limit = 3): Array<{ poem: Poem; score: number }> {
    const cleaned = normalizeZh(spoken)
    const scored = this.titleEntries.map(({ poem, normalizedTitle }) => ({
      poem,
      score: sim(cleaned, normalizedTitle)
    }))
    scored.sort((a, b) => b.score - a.score)
    return scored.slice(0, limit)
  }

  findByTitleContained(spoken: string, limit = 3): Array<{ poem: Poem; score: number }> {
    const cleaned = normalizeZh(spoken)
    if (!cleaned) return []

    const scored = this.titleEntries
      .filter(({ normalizedTitle }) => normalizedTitle.length > 0 && cleaned.includes(normalizedTitle))
      .map(({ poem, normalizedTitle }) => {
        const matchRatio = normalizedTitle.length / Math.max(cleaned.length, 1)
        const score = 0.94 + Math.min(0.05, matchRatio * 0.05)
        return { poem, score, titleLength: normalizedTitle.length }
      })

    scored.sort((a, b) => b.score - a.score || b.titleLength - a.titleLength)
    return scored.slice(0, limit).map(({ poem, score }) => ({ poem, score }))
  }

  buildTitlePinyinIndex(): void {
    // Placeholder for future pinyin index building.
  }
}
