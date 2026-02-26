import { makeAuthorId } from '../utils/id'
import { normalizeZh, stripPunct } from '../utils/zh_normalize'
import { sim } from '../utils/similarity'
import { DynastyKB, normalizeDynasty, resolveDynastySpoken } from './dynasty_kb'

export interface AuthorEntry {
  id: string
  name: string
  aliases: string[]
  dynastyId?: string
  source: 'auto' | 'user' | 'import'
  createdAt: number
  updatedAt: number
  freq: number
}

export interface AuthorKB {
  authors: AuthorEntry[]
}

export interface AuthorResolveResult {
  matchedBy: 'primary' | 'alias' | 'fuzzy' | 'none'
  author?: AuthorEntry
  candidates?: AuthorEntry[]
  score?: number
  hint?: string
}

export function normalizeAuthor(raw: string): string {
  return stripPunct(normalizeZh(raw)).trim()
}

function mergeAliases(existing: string[], incoming: string[]): string[] {
  const set = new Set(existing)
  for (const alias of incoming) {
    const norm = normalizeAuthor(alias)
    if (norm) set.add(norm)
  }
  return Array.from(set.values())
}

function resolveDynastyId(dynastyRaw: string | undefined, dynastyId: string | undefined, dynastyKB: DynastyKB): string | undefined {
  if (dynastyId) return dynastyId
  if (!dynastyRaw) return undefined
  const normalized = normalizeDynasty(dynastyRaw)
  if (!normalized) return undefined
  const resolved = resolveDynastySpoken(dynastyKB, normalized)
  return resolved.canonical?.id
}

export function buildOrUpdateAuthorKB(input: {
  poems: Array<{ title: string; dynastyRaw?: string; dynastyId?: string; authorPrimary: string; authorAliases?: string[] }>
  dynastyKB: DynastyKB
  existingKB?: AuthorKB
  now: number
}): AuthorKB {
  const freq = new Map<string, number>()
  const entries = new Map<string, AuthorEntry>()

  for (const poem of input.poems) {
    const primary = normalizeAuthor(poem.authorPrimary)
    if (!primary) continue
    const id = makeAuthorId(primary)
    const dynId = resolveDynastyId(poem.dynastyRaw, poem.dynastyId, input.dynastyKB)
    const aliases = (poem.authorAliases ?? []).map(normalizeAuthor).filter((a) => !!a)

    freq.set(primary, (freq.get(primary) ?? 0) + 1)

    const existing = entries.get(id)
    if (existing) {
      existing.aliases = mergeAliases(existing.aliases, aliases)
      existing.freq += 1
      if (!existing.dynastyId && dynId) existing.dynastyId = dynId
      existing.updatedAt = input.now
    } else {
      entries.set(id, {
        id,
        name: primary,
        aliases,
        dynastyId: dynId,
        source: 'auto',
        createdAt: input.now,
        updatedAt: input.now,
        freq: 1
      })
    }
  }

  const autoAuthors = Array.from(entries.values())
  const existing = input.existingKB
  if (!existing) return { authors: autoAuthors }

  const preserved = existing.authors.filter((a) => a.source !== 'auto')
  const preservedById = new Map(preserved.map((a) => [a.id, a]))
  const merged = [...preserved]

  for (const auto of autoAuthors) {
    if (preservedById.has(auto.id)) continue
    merged.push(auto)
  }

  return { authors: merged }
}

export function resolveAuthorSpoken(
  kb: AuthorKB,
  spokenRaw: string,
  dynastyIdsContext?: string[]
): AuthorResolveResult {
  const normalized = normalizeAuthor(spokenRaw)
  if (!normalized) return { matchedBy: 'none' }

  const byName = kb.authors.filter((a) => a.name === normalized)
  if (byName.length === 1) return { matchedBy: 'primary', author: byName[0] }

  const byAlias = kb.authors.filter((a) => a.aliases.includes(normalized))
  if (byAlias.length === 1) return { matchedBy: 'alias', author: byAlias[0] }

  let candidates = [...byName, ...byAlias]
  if (candidates.length === 0) {
    let best: AuthorEntry | undefined
    let bestScore = 0
    for (const author of kb.authors) {
      const score = sim(normalized, author.name)
      if (score > bestScore) {
        bestScore = score
        best = author
      }
    }
    if (best && bestScore >= 0.86) {
      candidates = [best]
      return { matchedBy: 'fuzzy', author: best, score: bestScore }
    }
    return { matchedBy: 'none' }
  }

  if (dynastyIdsContext && dynastyIdsContext.length > 0) {
    const filtered = candidates.filter((c) => c.dynastyId && dynastyIdsContext.includes(c.dynastyId))
    if (filtered.length === 1) {
      return { matchedBy: byName.length > 0 ? 'primary' : 'alias', author: filtered[0] }
    }
    if (filtered.length > 1) {
      return { matchedBy: byName.length > 0 ? 'primary' : 'alias', candidates: filtered, hint: '多个作者匹配' }
    }
  }

  if (candidates.length === 1) {
    return { matchedBy: byName.length > 0 ? 'primary' : 'alias', author: candidates[0] }
  }

  return { matchedBy: byName.length > 0 ? 'primary' : 'alias', candidates, hint: '多个作者匹配' }
}

export function upsertAuthorUser(
  kb: AuthorKB,
  payload: { name: string; aliases?: string[]; dynastyId?: string },
  now: number
): AuthorKB {
  const name = normalizeAuthor(payload.name)
  if (!name) return kb
  const id = makeAuthorId(name)
  const aliases = (payload.aliases ?? []).map(normalizeAuthor).filter((a) => !!a)

  const existing = kb.authors.find((a) => a.id === id)
  if (existing) {
    const mergedAliases = mergeAliases(existing.aliases, aliases)
    const updated: AuthorEntry[] = kb.authors.map((a): AuthorEntry =>
      a.id === id
        ? {
            ...a,
            name,
            aliases: mergedAliases,
            dynastyId: payload.dynastyId ?? a.dynastyId,
            source: 'user',
            updatedAt: now
          }
        : a
    )
    return { authors: updated }
  }

  return {
    authors: [
      ...kb.authors,
      {
        id,
        name,
        aliases,
        dynastyId: payload.dynastyId,
        source: 'user',
        createdAt: now,
        updatedAt: now,
        freq: 0
      }
    ]
  }
}
