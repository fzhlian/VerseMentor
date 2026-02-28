import { normalizeZh, stripPunct } from '../utils/zh_normalize'

export enum IntentType {
  RECITE_POEM = 'RECITE_POEM',
  SET_POEM = 'SET_POEM',
  REJECT_POEM = 'REJECT_POEM',
  DYNASTY_AUTHOR = 'DYNASTY_AUTHOR',
  START_RECITE = 'START_RECITE',
  ASK_HINT = 'ASK_HINT',
  EXIT_SESSION = 'EXIT_SESSION',
  NEXT_POEM = 'NEXT_POEM',
  REPEAT_PROMPT = 'REPEAT_PROMPT',
  UNKNOWN = 'UNKNOWN'
}

const KEYWORDS = {
  start: ['开始', '开始背诵', '开始朗诵', '开始背诗', '开始背诵诗词'],
  recite: ['背诵', '朗诵', '背诗', '念诗'],
  reject: ['不是', '不对', '不要', '错了', '换一个'],
  confirm: ['是', '是的', '对', '对的', '好的', '好', '确认', '就是它', '没错', '就是这首', '是这首', '就这首'],
  hint: ['提示', '给提示', '不会了', '提示一下', '帮我'],
  exit: ['退出', '结束', '停止', '不背了'],
  next: ['下一首', '换一首', '换诗'],
  repeat: ['再说一遍', '重复', '再来一次', '再来一遍']
}

function normalizeForKeywordMatch(raw: string): string {
  return stripPunct(normalizeZh(raw)).replace(/\s+/g, '')
}

function normalizeKeywordList(list: string[]): string[] {
  return list.map((item) => normalizeForKeywordMatch(item))
}

const KEYWORDS_NORM = {
  start: normalizeKeywordList(KEYWORDS.start),
  recite: normalizeKeywordList(KEYWORDS.recite),
  reject: normalizeKeywordList(KEYWORDS.reject),
  confirm: normalizeKeywordList(KEYWORDS.confirm),
  hint: normalizeKeywordList(KEYWORDS.hint),
  exit: normalizeKeywordList(KEYWORDS.exit),
  next: normalizeKeywordList(KEYWORDS.next),
  repeat: normalizeKeywordList(KEYWORDS.repeat)
}

function includesAny(normalizedRaw: string, normalizedKeywords: string[]): boolean {
  return normalizedKeywords.some((keyword) => normalizedRaw.includes(keyword))
}

export function parseIntent(text: string): { type: IntentType; slots: Record<string, string> } {
  const raw = text.trim()
  const normalized = normalizeForKeywordMatch(raw)
  const hasQuestionTone =
    raw.includes('?') ||
    raw.includes('？') ||
    raw.includes('吗') ||
    raw.includes('嘛') ||
    raw.includes('么') ||
    raw.includes('嗎')
  const slots: Record<string, string> = {}

  if (!raw) return { type: IntentType.UNKNOWN, slots }

  if (includesAny(normalized, KEYWORDS_NORM.exit)) return { type: IntentType.EXIT_SESSION, slots }
  if (includesAny(normalized, KEYWORDS_NORM.hint)) return { type: IntentType.ASK_HINT, slots }
  if (includesAny(normalized, KEYWORDS_NORM.next)) return { type: IntentType.NEXT_POEM, slots }
  if (includesAny(normalized, KEYWORDS_NORM.repeat)) return { type: IntentType.REPEAT_PROMPT, slots }
  if (includesAny(normalized, KEYWORDS_NORM.start)) return { type: IntentType.START_RECITE, slots }
  if (includesAny(normalized, KEYWORDS_NORM.reject)) return { type: IntentType.REJECT_POEM, slots }

  if (includesAny(normalized, KEYWORDS_NORM.recite)) {
    slots.title = raw.replace(/(背诵|背誦|朗诵|朗誦|背诗|背詩|念诗|念詩)/g, '').trim()
    return { type: IntentType.RECITE_POEM, slots }
  }

  if (!hasQuestionTone && KEYWORDS_NORM.confirm.some((keyword) => normalized === keyword)) {
    return { type: IntentType.SET_POEM, slots }
  }

  if (normalized.includes('朝') || normalized.includes('代') || normalized.includes('作者')) {
    slots.query = raw
    return { type: IntentType.DYNASTY_AUTHOR, slots }
  }

  return { type: IntentType.UNKNOWN, slots }
}
