import { PoemCatalog, PoemMatcher } from '../src/poems'
import { RecitationFsm } from '../src/fsm'

const catalog = PoemCatalog.fromDataset()
const matcher = new PoemMatcher(catalog)
const fsm = new RecitationFsm(matcher)

const output = fsm.transition({ type: 'IDLE' }, { type: 'START_RECITATION' })
if (output.state.type !== 'LISTENING_FOR_TITLE') {
  throw new Error('FSM did not transition to LISTENING_FOR_TITLE')
}
