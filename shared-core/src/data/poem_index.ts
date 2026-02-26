import { normalizeZh } from '../utils/zh_normalize'
import { sim } from '../utils/similarity'
import { Poem } from './models'

export class PoemIndex {
  private titleIndex: Map<string, Poem[]> = new Map()

  constructor(private poems: Poem[]) {
    for (const poem of poems) {
      const key = normalizeZh(poem.title)
      const list = this.titleIndex.get(key) ?? []
      list.push(poem)
      this.titleIndex.set(key, list)
    }
  }

  findByTitleExact(title: string): Poem[] {
    const key = normalizeZh(title)
    return this.titleIndex.get(key) ?? []
  }

  findByTitleFuzzy(spoken: string, limit = 3): Array<{ poem: Poem; score: number }> {
    const cleaned = normalizeZh(spoken)
    const scored = this.poems.map((poem) => ({
      poem,
      score: sim(cleaned, normalizeZh(poem.title))
    }))
    scored.sort((a, b) => b.score - a.score)
    return scored.slice(0, limit)
  }

  buildTitlePinyinIndex(): void {
    // Placeholder for future pinyin index building.
  }
}
