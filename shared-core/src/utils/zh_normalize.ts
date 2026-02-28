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
  靜: '静',
  覺: '觉',
  曉: '晓',
  鳥: '鸟',
  鸛: '鹳',
  鶴: '鹤',
  風: '风',
  雨: '雨',
  處: '处',
  間: '间',
  樓: '楼',
  廣: '广',
  雲: '云',
  後: '后',
  這: '这',
  那: '那',
  開: '开',
  結: '结',
  換: '换',
  續: '续',
  誦: '诵',
  對: '对',
  給: '给',
  幫: '帮',
  嗎: '吗',
  興: '兴',
  國: '国',
  讀: '读',
  詩: '诗',
  詞: '词',
  贈: '赠',
  倫: '伦',
  車: '车',
  麗: '丽',
  見: '见',
  長: '长',
  轉: '转',
  寫: '写',
  愛: '爱',
  說: '说',
  遠: '远',
  黃: '黄',
  廬: '庐',
  楓: '枫',
  絕: '绝',
  遊: '游',
  詠: '咏',
  臺: '台',
  題: '题',
  宮: '宫',
  發: '发'
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

function collapseRepeatedTailSegment(input: string): string {
  let out = input
  const maxChunkLen = Math.min(12, Math.floor(out.length / 2))
  for (let len = maxChunkLen; len >= 3; len--) {
    let changed = true
    while (changed && out.length >= len * 2) {
      changed = false
      const tail = out.slice(out.length - len)
      const prev = out.slice(out.length - len * 2, out.length - len)
      if (tail === prev) {
        out = out.slice(0, out.length - len)
        changed = true
      }
    }
  }
  return out
}

function collapseRepeats(input: string): string {
  // Collapse immediate character repeats: "啊啊啊" -> "啊"
  let out = input.replace(/(.)\1{2,}/g, '$1')
  // Collapse immediate bi-gram repeats: "你好你好" -> "你好"
  out = out.replace(/(.{2})\1{1,}/g, '$1')
  // Collapse repeated trailing segments: "我想背静夜思静夜思" -> "我想背静夜思"
  out = collapseRepeatedTailSegment(out)
  return out
}

export function dedupeSpeechFiller(raw: string): string {
  const normalized = normalizeZh(raw)
  const noPunct = stripPunct(normalized)
  const noFillers = removeFillers(noPunct)
  return collapseRepeats(noFillers).trim()
}
