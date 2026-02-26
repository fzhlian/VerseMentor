import { describe, expect, test } from 'vitest'
import { PoemCatalog, PoemMatcher } from '../src/poems'
import { RecitationFsm } from '../src/fsm'

describe('fsm', () => {
  test('START_RECITATION transitions IDLE -> LISTENING_FOR_TITLE', () => {
    const catalog = PoemCatalog.fromDataset()
    const matcher = new PoemMatcher(catalog)
    const fsm = new RecitationFsm(matcher)

    const output = fsm.transition({ type: 'IDLE' }, { type: 'START_RECITATION' })
    expect(output.state.type).toBe('LISTENING_FOR_TITLE')
  })
})
