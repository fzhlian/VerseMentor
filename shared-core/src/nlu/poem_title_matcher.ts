import { Poem } from '../data/models'
import { PoemIndex } from '../data/poem_index'
import { cleanSpeechText } from './text_cleaner'

export function matchPoemTitle(
  index: PoemIndex,
  rawSpeech: string
): { candidates: Array<{ poem: Poem; score: number }>; extractedTitle?: string } {
  const cleaned = cleanSpeechText(rawSpeech)
  if (!cleaned) return { candidates: [] }

  const exact = index.findByTitleExact(cleaned)
  if (exact.length > 0) {
    return {
      candidates: exact.map((poem) => ({ poem, score: 1 })),
      extractedTitle: cleaned
    }
  }

  const fuzzy = index.findByTitleFuzzy(cleaned, 3)
  return {
    candidates: fuzzy,
    extractedTitle: cleaned
  }
}
