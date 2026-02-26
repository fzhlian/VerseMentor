import { Poem } from './models'

export class PoemCatalog {
  constructor(public poems: Poem[]) {}

  static fromDataset(): PoemCatalog {
    return new PoemCatalog(poemDataset)
  }
}

export class PoemMatcher {
  constructor(private catalog: PoemCatalog) {}

  matchByTitle(title: string): Poem | null {
    const normalized = title.trim()
    return (
      this.catalog.poems.find((poem) => poem.title === normalized) ||
      this.catalog.poems.find(
        (poem) => poem.title.includes(normalized) || normalized.includes(poem.title)
      ) ||
      null
    )
  }
}

export class DynastyMap {
  private mapping = new Map<string, string>()

  observe(dynasty: string): void {
    const key = dynasty.trim().toLowerCase()
    if (key) {
      if (!this.mapping.has(key)) {
        this.mapping.set(key, dynasty)
      }
    }
  }

  resolve(input: string): string | null {
    const key = input.trim().toLowerCase()
    return this.mapping.get(key) ?? null
  }

  override(alias: string, canonical: string): void {
    this.mapping.set(alias.trim().toLowerCase(), canonical.trim())
  }

  snapshot(): Record<string, string> {
    const result: Record<string, string> = {}
    for (const [key, value] of this.mapping.entries()) {
      result[key] = value
    }
    return result
  }
}

export class AuthorLibrary {
  private authors = new Set<string>()

  observe(author: string): void {
    const trimmed = author.trim()
    if (trimmed) {
      this.authors.add(trimmed)
    }
  }

  addManual(author: string): void {
    this.observe(author)
  }

  remove(author: string): void {
    this.authors.delete(author.trim())
  }

  list(): string[] {
    return Array.from(this.authors.values()).sort()
  }
}

export class MetadataIndex {
  dynastyMap = new DynastyMap()
  authorLibrary = new AuthorLibrary()

  constructor(catalog: PoemCatalog) {
    catalog.poems.forEach((poem) => {
      this.dynastyMap.observe(poem.dynasty)
      this.authorLibrary.observe(poem.author)
    })
  }
}

export const poemDataset: Poem[] = [
  {
    id: 'p1',
    title: '静夜思',
    author: '李白',
    dynasty: '唐',
    linesSimplified: ['床前明月光', '疑是地上霜', '举头望明月', '低头思故乡'],
    linesTraditional: ['床前明月光', '疑是地上霜', '舉頭望明月', '低頭思故鄉']
  },
  {
    id: 'p2',
    title: '春晓',
    author: '孟浩然',
    dynasty: '唐',
    linesSimplified: ['春眠不觉晓', '处处闻啼鸟', '夜来风雨声', '花落知多少'],
    linesTraditional: ['春眠不覺曉', '處處聞啼鳥', '夜來風雨聲', '花落知多少']
  }
]
