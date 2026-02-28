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
  repeat: ['再说一遍', '重复', '再来一次']
}

export function parseIntent(text: string): { type: IntentType; slots: Record<string, string> } {
  const raw = text.trim()
  const slots: Record<string, string> = {}

  if (!raw) return { type: IntentType.UNKNOWN, slots }

  if (KEYWORDS.exit.some((k) => raw.includes(k))) return { type: IntentType.EXIT_SESSION, slots }
  if (KEYWORDS.hint.some((k) => raw.includes(k))) return { type: IntentType.ASK_HINT, slots }
  if (KEYWORDS.next.some((k) => raw.includes(k))) return { type: IntentType.NEXT_POEM, slots }
  if (KEYWORDS.repeat.some((k) => raw.includes(k))) return { type: IntentType.REPEAT_PROMPT, slots }
  if (KEYWORDS.start.some((k) => raw.includes(k))) return { type: IntentType.START_RECITE, slots }
  if (KEYWORDS.reject.some((k) => raw.includes(k))) return { type: IntentType.REJECT_POEM, slots }

  if (KEYWORDS.recite.some((k) => raw.includes(k))) {
    slots.title = raw.replace(/(背诵|朗诵|背诗|念诗)/g, '').trim()
    return { type: IntentType.RECITE_POEM, slots }
  }

  if (KEYWORDS.confirm.some((k) => raw === k)) {
    return { type: IntentType.SET_POEM, slots }
  }

  if (raw.includes('朝') || raw.includes('代') || raw.includes('作者')) {
    slots.query = raw
    return { type: IntentType.DYNASTY_AUTHOR, slots }
  }

  return { type: IntentType.UNKNOWN, slots }
}
