export function stripPunct(raw: string): string {
  return raw.replace(/[\p{P}\p{S}]/gu, '')
}

function toHalfWidth(input: string): string {
  let out = ''
  for (const ch of input) {
    const code = ch.charCodeAt(0)
    if (code === 0x3000) {
      out += ' '
    } else if (code >= 0xff01 && code <= 0xff5e) {
      out += String.fromCharCode(code - 0xfee0)
    } else {
      out += ch
    }
  }
  return out
}

const TRAD_TO_SIMP: Record<string, string> = {
  來: '来',
  覺: '觉',
  鳥: '鸟',
  風: '风',
  雨: '雨',
  處: '处',
  間: '间',
  樓: '楼',
  雲: '云',
  後: '后',
  這: '这',
  那: '那',
  興: '兴',
  國: '国',
  讀: '读',
  詩: '诗',
  詞: '词',
  車: '车',
  麗: '丽',
  見: '见',
  長: '长',
  轉: '转',
  寫: '写',
  愛: '爱',
  說: '说',
  遠: '远'
}

function simpTradUnify(input: string): string {
  let out = ''
  for (const ch of input) {
    out += TRAD_TO_SIMP[ch] ?? ch
  }
  return out
}

export function normalizeZh(raw: string): string {
  const half = toHalfWidth(raw)
  const unified = simpTradUnify(half)
  return unified.trim().toLowerCase()
}

const FILLERS = ['嗯', '啊', '呃', '额', '那个', '这个', '就是', '然后', '嘛', '呀', '诶', '呐']

function removeFillers(input: string): string {
  let out = input
  for (const filler of FILLERS) {
    const re = new RegExp(filler, 'g')
    out = out.replace(re, '')
  }
  return out
}

function collapseRepeats(input: string): string {
  // Collapse immediate character repeats: "啊啊啊" -> "啊"
  let out = input.replace(/(.)\1{2,}/g, '$1')
  // Collapse immediate bi-gram repeats: "你好你好" -> "你好"
  out = out.replace(/(.{2})\1{1,}/g, '$1')
  return out
}

export function dedupeSpeechFiller(raw: string): string {
  const normalized = normalizeZh(raw)
  const noPunct = stripPunct(normalized)
  const noFillers = removeFillers(noPunct)
  return collapseRepeats(noFillers).trim()
}
