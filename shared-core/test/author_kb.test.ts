import { describe, expect, test } from 'vitest'
import { buildOrUpdateDynastyKB } from '../src/kb/dynasty_kb'
import { buildOrUpdateAuthorKB, resolveAuthorSpoken, upsertAuthorUser } from '../src/kb/author_kb'

const now = 1700000000000

describe('author_kb', () => {
  test('李白 aliases 太白 match', () => {
    const dynastyKB = buildOrUpdateDynastyKB({ observedDynasties: ['唐'], now })
    const kb = buildOrUpdateAuthorKB({
      poems: [
        {
          title: '静夜思',
          dynastyRaw: '唐',
          authorPrimary: '李白',
          authorAliases: ['太白']
        }
      ],
      dynastyKB,
      now
    })

    const result = resolveAuthorSpoken(kb, '太白')
    expect(result.author?.name).toBe('李白')
    expect(result.matchedBy).toBe('alias')
  })

  test('dynasty context reduces candidates', () => {
    const dynastyKB = buildOrUpdateDynastyKB({ observedDynasties: ['唐', '宋'], now })
    const tangId = dynastyKB.canonicals.find((c) => c.name === '唐')?.id
    const songId = dynastyKB.canonicals.find((c) => c.name === '宋')?.id

    const kb = buildOrUpdateAuthorKB({
      poems: [
        {
          title: 'A',
          dynastyRaw: '唐',
          authorPrimary: '张三'
        },
        {
          title: 'B',
          dynastyRaw: '宋',
          authorPrimary: '张三'
        }
      ],
      dynastyKB,
      now
    })

    const result = resolveAuthorSpoken(kb, '张三', tangId ? [tangId] : undefined)
    expect(result.author?.dynastyId).toBe(tangId)
  })

  test('user add works', () => {
    const dynastyKB = buildOrUpdateDynastyKB({ observedDynasties: ['唐'], now })
    const kb = buildOrUpdateAuthorKB({ poems: [], dynastyKB, now })

    const updated = upsertAuthorUser(kb, { name: '白居易', aliases: ['乐天'] }, now + 1)
    const result = resolveAuthorSpoken(updated, '乐天')
    expect(result.author?.name).toBe('白居易')
  })
})
