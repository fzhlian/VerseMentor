import { makeDynastyId } from '../utils/id'
import { normalizeZh, stripPunct } from '../utils/zh_normalize'
import { sim } from '../utils/similarity'

export type SourceTag = 'auto' | 'user' | 'import'

export interface DynastyCanonical {
  id: string
  name: string
  source: SourceTag
  createdAt: number
  updatedAt: number
  freq: number
}

export interface DynastyAlias {
  id: string
  alias: string
  canonicalId: string
  source: SourceTag
  createdAt: number
  updatedAt: number
  freq: number
}

export interface DynastyGroup {
  id: string
  name: string
  canonicalIds: string[]
  source: SourceTag
  createdAt: number
  updatedAt: number
  freq: number
}

export interface DynastyKB {
  canonicals: DynastyCanonical[]
  aliases: DynastyAlias[]
  groups: DynastyGroup[]
}

export interface DynastyResolveResult {
  matchedBy: 'canonical' | 'alias' | 'group' | 'fuzzy' | 'none'
  canonical?: DynastyCanonical
  candidates?: DynastyCanonical[]
  score?: number
}

export function normalizeDynasty(raw: string): string {
  return stripPunct(normalizeZh(raw)).trim()
}

function dynastyCore(raw: string): string {
  let out = normalizeDynasty(raw)
  out = out.replace(/^大/u, '')
  out = out.replace(/(王朝|朝代)$/u, '')
  out = out.replace(/(朝|代)$/u, '')
  return out.trim()
}

function clusterBySimilarity(items: string[], threshold: number): string[][] {
  const clusters: string[][] = []
  const used = new Set<string>()
  for (const item of items) {
    if (used.has(item)) continue
    const cluster = [item]
    used.add(item)
    for (const other of items) {
      if (used.has(other)) continue
      if (sim(item, other) >= threshold) {
        cluster.push(other)
        used.add(other)
      }
    }
    clusters.push(cluster)
  }
  return clusters
}

function chooseCanonical(cluster: string[], freq: Map<string, number>): string {
  return cluster
    .slice()
    .sort((a, b) => {
      const fa = freq.get(a) ?? 0
      const fb = freq.get(b) ?? 0
      if (fb !== fa) return fb - fa
      if (a.length !== b.length) return a.length - b.length
      return a.localeCompare(b)
    })[0]
}

function mapById<T extends { id: string }>(items: T[]): Map<string, T> {
  const map = new Map<string, T>()
  for (const item of items) map.set(item.id, item)
  return map
}

function mapByName<T extends { name?: string; alias?: string }>(items: T[]): Map<string, T> {
  const map = new Map<string, T>()
  for (const item of items) {
    const key = (item.name ?? item.alias ?? '').trim()
    if (key) map.set(key, item)
  }
  return map
}

export function buildOrUpdateDynastyKB(input: {
  observedDynasties: string[]
  existingKB?: DynastyKB
  now: number
}): DynastyKB {
  const aliasFreq = new Map<string, number>()
  const aliasToCore = new Map<string, string>()
  const coreFreq = new Map<string, number>()

  for (const raw of input.observedDynasties) {
    const alias = normalizeDynasty(raw)
    if (!alias) continue
    const core = dynastyCore(alias) || alias
    aliasFreq.set(alias, (aliasFreq.get(alias) ?? 0) + 1)
    aliasToCore.set(alias, core)
    coreFreq.set(core, (coreFreq.get(core) ?? 0) + 1)
  }

  const uniqueCores = Array.from(coreFreq.keys())
  const clusters = clusterBySimilarity(uniqueCores, 0.86)

  const autoCanonicals: DynastyCanonical[] = []
  const autoAliasByAlias = new Map<string, DynastyAlias>()
  const coreToCanonicalName = new Map<string, string>()

  for (const cluster of clusters) {
    const canonicalName = chooseCanonical(cluster, coreFreq)
    for (const member of cluster) {
      coreToCanonicalName.set(member, canonicalName)
    }

    const canonicalId = makeDynastyId(canonicalName)
    autoCanonicals.push({
      id: canonicalId,
      name: canonicalName,
      source: 'auto',
      createdAt: input.now,
      updatedAt: input.now,
      freq: coreFreq.get(canonicalName) ?? 0
    })
  }

  for (const [alias, freq] of aliasFreq) {
    const core = aliasToCore.get(alias) ?? alias
    const canonicalName = coreToCanonicalName.get(core) ?? core
    if (alias === canonicalName) continue
    autoAliasByAlias.set(alias, {
      id: `alias_${makeDynastyId(alias)}`,
      alias,
      canonicalId: makeDynastyId(canonicalName),
      source: 'auto',
      createdAt: input.now,
      updatedAt: input.now,
      freq
    })
  }
  const autoAliases = Array.from(autoAliasByAlias.values())

  const existing = input.existingKB
  if (!existing) {
    return {
      canonicals: autoCanonicals,
      aliases: autoAliases,
      groups: []
    }
  }

  const preservedCanonicals = existing.canonicals.filter((c) => c.source !== 'auto')
  const preservedAliases = existing.aliases.filter((a) => a.source !== 'auto')
  const preservedGroups = existing.groups

  const preservedCanonById = mapById(preservedCanonicals)
  const preservedCanonByName = mapByName(preservedCanonicals)

  const mergedCanonicals = [...preservedCanonicals]
  for (const auto of autoCanonicals) {
    if (preservedCanonById.has(auto.id) || preservedCanonByName.has(auto.name)) {
      continue
    }
    mergedCanonicals.push(auto)
  }

  const preservedAliasByAlias = mapByName(preservedAliases)
  const mergedAliases = [...preservedAliases]
  for (const auto of autoAliases) {
    if (preservedAliasByAlias.has(auto.alias)) {
      continue
    }
    mergedAliases.push(auto)
  }

  return {
    canonicals: mergedCanonicals,
    aliases: mergedAliases,
    groups: preservedGroups
  }
}

function resolveCanonical(kb: DynastyKB, idOrName: string): DynastyCanonical | undefined {
  const byId = kb.canonicals.find((c) => c.id === idOrName)
  if (byId) return byId
  const byName = kb.canonicals.find((c) => c.name === idOrName)
  return byName
}

export function upsertDynastyAliasUser(
  kb: DynastyKB,
  aliasRaw: string,
  canonicalNameOrId: string,
  now: number
): DynastyKB {
  const alias = normalizeDynasty(aliasRaw)
  if (!alias) return kb
  let canonical = resolveCanonical(kb, canonicalNameOrId)
  if (!canonical) {
    const name = normalizeDynasty(canonicalNameOrId)
    if (!name) return kb
    canonical = {
      id: makeDynastyId(name),
      name,
      source: 'user',
      createdAt: now,
      updatedAt: now,
      freq: 0
    }
  }

  const nextCanonicals = kb.canonicals.some((c) => c.id === canonical!.id)
    ? kb.canonicals.map((c) => (c.id === canonical!.id ? { ...c, updatedAt: now } : c))
    : [...kb.canonicals, canonical!]

  const existing = kb.aliases.find((a) => a.alias === alias)
  const nextAliases: DynastyAlias[] = existing
    ? kb.aliases.map((a): DynastyAlias =>
        a.alias === alias
          ? { ...a, canonicalId: canonical!.id, source: 'user', updatedAt: now }
          : a
      )
    : [
        ...kb.aliases,
        {
          id: `alias_${makeDynastyId(alias)}`,
          alias,
          canonicalId: canonical!.id,
          source: 'user',
          createdAt: now,
          updatedAt: now,
          freq: 0
        }
      ]

  return { ...kb, canonicals: nextCanonicals, aliases: nextAliases }
}

export function upsertDynastyGroupUser(
  kb: DynastyKB,
  groupAliasRaw: string,
  canonicalIdsOrNames: string[],
  now: number
): DynastyKB {
  const name = normalizeDynasty(groupAliasRaw)
  if (!name) return kb

  const resolvedIds = canonicalIdsOrNames
    .map((item) => {
      const resolved = resolveCanonical(kb, item)
      if (resolved) return resolved.id
      const normalized = dynastyCore(item) || normalizeDynasty(item)
      if (!normalized) return undefined
      return makeDynastyId(normalized)
    })
    .filter((id): id is string => !!id)

  const id = `grp_${makeDynastyId(name)}`
  const existing = kb.groups.find((g) => g.id === id)
  const nextGroups: DynastyGroup[] = existing
    ? kb.groups.map((g): DynastyGroup =>
        g.id === id
          ? { ...g, name, canonicalIds: resolvedIds, source: 'user', updatedAt: now }
          : g
      )
    : [
        ...kb.groups,
        {
          id,
          name,
          canonicalIds: resolvedIds,
          source: 'user',
          createdAt: now,
          updatedAt: now,
          freq: 0
        }
      ]

  return { ...kb, groups: nextGroups }
}

export function resolveDynastySpoken(kb: DynastyKB, spokenRaw: string): DynastyResolveResult {
  const normalized = normalizeDynasty(spokenRaw)
  if (!normalized) return { matchedBy: 'none' }

  const canonical = kb.canonicals.find((c) => c.name === normalized)
  if (canonical) return { matchedBy: 'canonical', canonical }

  const aliases = kb.aliases.filter((a) => a.alias === normalized)
  const aliasUser = aliases.find((a) => a.source === 'user') ?? aliases[0]
  if (aliasUser) {
    const aliasCanonical = kb.canonicals.find((c) => c.id === aliasUser.canonicalId)
    if (aliasCanonical) return { matchedBy: 'alias', canonical: aliasCanonical }
  }

  const groups = kb.groups.filter((g) => g.name === normalized)
  const groupUser = groups.find((g) => g.source === 'user') ?? groups[0]
  if (groupUser) {
    const candidates = groupUser.canonicalIds
      .map((id) => kb.canonicals.find((c) => c.id === id))
      .filter((c): c is DynastyCanonical => !!c)
    return { matchedBy: 'group', canonical: candidates[0], candidates }
  }

  let best: DynastyCanonical | undefined
  let bestScore = 0
  const normalizedCore = dynastyCore(normalized) || normalized
  for (const c of kb.canonicals) {
    const canonical = normalizeDynasty(c.name)
    const canonicalCore = dynastyCore(canonical) || canonical
    const rawScore = normalized === canonical ? 1 : sim(normalized, canonical)
    const coreScore = normalizedCore === canonicalCore ? 1 : sim(normalizedCore, canonicalCore)
    const score = Math.max(rawScore, coreScore)
    if (score > bestScore) {
      bestScore = score
      best = c
    }
  }
  if (best && bestScore >= 0.86) {
    return { matchedBy: 'fuzzy', canonical: best, score: bestScore }
  }

  return { matchedBy: 'none' }
}

export function serializeDynastyKB(kb: DynastyKB): string {
  return JSON.stringify(kb)
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isSourceTag(value: unknown): value is SourceTag {
  return value === 'auto' || value === 'user' || value === 'import'
}

function sanitizeCanonicals(value: unknown): DynastyCanonical[] {
  if (!Array.isArray(value)) return []
  const out: DynastyCanonical[] = []
  for (const item of value) {
    if (!isObjectRecord(item)) continue
    const id = item.id
    const name = item.name
    const source = item.source
    const createdAt = item.createdAt
    const updatedAt = item.updatedAt
    const freq = item.freq
    if (
      typeof id === 'string' &&
      typeof name === 'string' &&
      isSourceTag(source) &&
      typeof createdAt === 'number' &&
      typeof updatedAt === 'number' &&
      typeof freq === 'number'
    ) {
      out.push({ id, name, source, createdAt, updatedAt, freq })
    }
  }
  return out
}

function sanitizeAliases(value: unknown): DynastyAlias[] {
  if (!Array.isArray(value)) return []
  const out: DynastyAlias[] = []
  for (const item of value) {
    if (!isObjectRecord(item)) continue
    const id = item.id
    const alias = item.alias
    const canonicalId = item.canonicalId
    const source = item.source
    const createdAt = item.createdAt
    const updatedAt = item.updatedAt
    const freq = item.freq
    if (
      typeof id === 'string' &&
      typeof alias === 'string' &&
      typeof canonicalId === 'string' &&
      isSourceTag(source) &&
      typeof createdAt === 'number' &&
      typeof updatedAt === 'number' &&
      typeof freq === 'number'
    ) {
      out.push({ id, alias, canonicalId, source, createdAt, updatedAt, freq })
    }
  }
  return out
}

function sanitizeGroups(value: unknown): DynastyGroup[] {
  if (!Array.isArray(value)) return []
  const out: DynastyGroup[] = []
  for (const item of value) {
    if (!isObjectRecord(item)) continue
    const id = item.id
    const name = item.name
    const canonicalIds = item.canonicalIds
    const source = item.source
    const createdAt = item.createdAt
    const updatedAt = item.updatedAt
    const freq = item.freq
    if (
      typeof id === 'string' &&
      typeof name === 'string' &&
      Array.isArray(canonicalIds) &&
      canonicalIds.every((canonicalId) => typeof canonicalId === 'string') &&
      isSourceTag(source) &&
      typeof createdAt === 'number' &&
      typeof updatedAt === 'number' &&
      typeof freq === 'number'
    ) {
      out.push({ id, name, canonicalIds, source, createdAt, updatedAt, freq })
    }
  }
  return out
}

export function deserializeDynastyKB(raw: string): DynastyKB {
  const parsed = JSON.parse(raw) as unknown
  if (!isObjectRecord(parsed)) {
    return { canonicals: [], aliases: [], groups: [] }
  }
  return {
    canonicals: sanitizeCanonicals(parsed.canonicals),
    aliases: sanitizeAliases(parsed.aliases),
    groups: sanitizeGroups(parsed.groups)
  }
}
