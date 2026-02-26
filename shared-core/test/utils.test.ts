import { dedupeSpeechFiller, normalizeZh, stripPunct } from '../src/utils/zh_normalize'
import { editDistance, jaccard2gram, sim } from '../src/utils/similarity'
import { hash32, makeAuthorId, makeDynastyId, slugZh } from '../src/utils/id'

const normalized = normalizeZh('  這是　測試  ')
if (!normalized.includes('这')) {
  throw new Error('normalizeZh did not unify traditional to simplified')
}

const stripped = stripPunct('你好，世界！')
if (stripped !== '你好世界') {
  throw new Error('stripPunct failed')
}

const cleaned = dedupeSpeechFiller('嗯嗯那个这个 静夜思 静夜思')
if (!cleaned.includes('静夜思')) {
  throw new Error('dedupeSpeechFiller removed content')
}

if (editDistance('abc', 'abc') !== 0) {
  throw new Error('editDistance failed')
}

if (jaccard2gram('你好', '你好') <= 0.9) {
  throw new Error('jaccard2gram failed')
}

if (sim('静夜思', '静夜思') < 0.9) {
  throw new Error('sim failed')
}

if (!slugZh(' 静夜思 ')) {
  throw new Error('slugZh failed')
}

if (hash32('test').length !== 8) {
  throw new Error('hash32 length incorrect')
}

if (!makeDynastyId('唐').startsWith('dyn_') || !makeAuthorId('李白').startsWith('auth_')) {
  throw new Error('makeId failed')
}
