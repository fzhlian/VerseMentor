import { Clock } from './interfaces'
import { Poem, PoemLineVariant, PoemVariants } from './models'

export interface VariantsProvider {
  fetch(poem: Poem): Promise<PoemVariants>
}

export class VariantsRepository {
  private cache = new Map<string, CachedEntry>()

  constructor(private provider: VariantsProvider, private clock: Clock, private ttlMillis: number) {}

  get(poem: Poem): PoemVariants | null {
    const entry = this.cache.get(poem.id)
    if (!entry) return null
    if (this.clock.nowMillis() - entry.timestamp > this.ttlMillis) {
      this.cache.delete(poem.id)
      return null
    }
    return entry.variants
  }

  async refresh(poem: Poem): Promise<PoemVariants> {
    const variants = await this.provider.fetch(poem)
    this.cache.set(poem.id, { timestamp: this.clock.nowMillis(), variants })
    return variants
  }

  merge(existing: PoemVariants | null, incoming: PoemVariants): PoemVariants {
    if (!existing) return incoming
    const mergedLines = existing.lines.map((existingLine) => {
      const incomingLine = incoming.lines.find((line) => line.lineIndex === existingLine.lineIndex)
      if (!incomingLine) return existingLine
      const mergedVariants = Array.from(new Set([...existingLine.variants, ...incomingLine.variants]))
      return { ...existingLine, variants: mergedVariants }
    })
    const newLines = incoming.lines.filter(
      (incomingLine) => !mergedLines.some((line) => line.lineIndex === incomingLine.lineIndex)
    )
    return {
      poemId: existing.poemId,
      lines: [...mergedLines, ...newLines],
      sourceTags: Array.from(new Set([...existing.sourceTags, ...incoming.sourceTags]))
    }
  }

  static fromLines(poemId: string, lines: string[], sourceTag: string): PoemVariants {
    const mapped: PoemLineVariant[] = lines.map((line, index) => ({
      lineIndex: index,
      variants: [line]
    }))
    return { poemId, lines: mapped, sourceTags: [sourceTag] }
  }
}

interface CachedEntry {
  timestamp: number
  variants: PoemVariants
}
