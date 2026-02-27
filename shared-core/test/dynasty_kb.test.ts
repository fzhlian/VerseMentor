import { describe, expect, test } from 'vitest'
import {
  buildOrUpdateDynastyKB,
  deserializeDynastyKB,
  resolveDynastySpoken,
  serializeDynastyKB,
  upsertDynastyAliasUser
} from '../src/kb/dynasty_kb'

const now = 1700000000000

describe('dynasty_kb', () => {
  test('cluster 唐/唐朝 -> canonical 唐', () => {
    const kb = buildOrUpdateDynastyKB({
      observedDynasties: ['唐', '唐朝', '唐朝', '唐'],
      now
    })

    const canonical = kb.canonicals.find((c) => c.name === '唐')
    expect(canonical).toBeTruthy()

    const alias = kb.aliases.find((a) => a.alias === '唐朝')
    expect(alias).toBeTruthy()
    expect(alias?.canonicalId).toBe(canonical?.id)
  })

  test('user override beats auto', () => {
    const base = buildOrUpdateDynastyKB({
      observedDynasties: ['唐', '唐朝'],
      now
    })

    const userKb = upsertDynastyAliasUser(base, '唐朝', '唐', now + 1)
    const alias = userKb.aliases.find((a) => a.alias === '唐朝')
    expect(alias?.source).toBe('user')
  })

  test('fuzzy resolve works', () => {
    const kb = buildOrUpdateDynastyKB({
      observedDynasties: ['唐', '宋'],
      now
    })

    const result = resolveDynastySpoken(kb, '大唐')
    expect(result.matchedBy).toBe('fuzzy')
    expect(result.canonical?.name).toBe('唐')
  })

  test('serialize/deserialize roundtrip keeps kb', () => {
    const kb = buildOrUpdateDynastyKB({
      observedDynasties: ['唐', '宋'],
      now
    })

    const raw = serializeDynastyKB(kb)
    const decoded = deserializeDynastyKB(raw)
    expect(decoded).toEqual(kb)
  })

  test('deserialize filters invalid records', () => {
    const raw = JSON.stringify({
      canonicals: [{ id: 'dyn_tang', name: '唐', source: 'auto', createdAt: now, updatedAt: now, freq: 1 }, { id: 1 }],
      aliases: [{ id: 'alias_tang', alias: '唐朝', canonicalId: 'dyn_tang', source: 'auto', createdAt: now, updatedAt: now, freq: 1 }, {}],
      groups: [{ id: 'grp_cn', name: '中古', canonicalIds: ['dyn_tang'], source: 'user', createdAt: now, updatedAt: now, freq: 0 }, { id: 'bad', canonicalIds: 'dyn_tang' }]
    })

    const decoded = deserializeDynastyKB(raw)
    expect(decoded.canonicals).toHaveLength(1)
    expect(decoded.aliases).toHaveLength(1)
    expect(decoded.groups).toHaveLength(1)
    expect(decoded.canonicals[0].name).toBe('唐')
  })
})
