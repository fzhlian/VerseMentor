import { AppConfig, DEFAULTS } from '../config/defaults'
import { normalizeZh, stripPunct } from '../utils/zh_normalize'

export interface AcceptedLinePack {
  idx: number
  baseText: string
  localVariants?: string[]
  onlineVariants?: string[]
  meaning?: string
}

const PINYIN_MAP: Record<string, { syllable: string; tone: number }> = {
  山: { syllable: 'shan', tone: 1 },
  伤: { syllable: 'shang', tone: 1 },
  唐: { syllable: 'tang', tone: 2 },
  宋: { syllable: 'song', tone: 4 },
  春: { syllable: 'chun', tone: 1 },
  晓: { syllable: 'xiao', tone: 3 },
  静: { syllable: 'jing', tone: 4 },
  夜: { syllable: 'ye', tone: 4 },
  思: { syllable: 'si', tone: 1 },
  明: { syllable: 'ming', tone: 2 },
  月: { syllable: 'yue', tone: 4 },
  光: { syllable: 'guang', tone: 1 },
  望: { syllable: 'wang', tone: 4 },
  霜: { syllable: 'shuang', tone: 1 },
  乡: { syllable: 'xiang', tone: 1 }
}

function normalizeChars(input: string): string {
  const normalized = normalizeZh(input)
  const stripped = stripPunct(normalized)
  return stripped.replace(/\s+/g, '')
}

function normalizeFinal(syllable: string, cfg: AppConfig): string {
  let out = syllable
  if (cfg.accentTolerance.an_ang) out = out.replace(/ang$/u, 'an')
  if (cfg.accentTolerance.en_eng) out = out.replace(/eng$/u, 'en')
  if (cfg.accentTolerance.in_ing) out = out.replace(/ing$/u, 'in')
  if (cfg.accentTolerance.ian_iang) out = out.replace(/iang$/u, 'ian')
  return out
}

function sameSound(a: string, b: string, cfg: AppConfig): { same: boolean; toneDiff?: { char: string; expectedTone?: number } } {
  if (a === b) return { same: true }
  const pa = PINYIN_MAP[a]
  const pb = PINYIN_MAP[b]
  if (!pa || !pb) return { same: false }
  const na = normalizeFinal(pa.syllable, cfg)
  const nb = normalizeFinal(pb.syllable, cfg)
  if (na !== nb) return { same: false }
  if (cfg.tonePolicy === 'remind' && pa.tone !== pb.tone) {
    return { same: true, toneDiff: { char: b, expectedTone: pa.tone } }
  }
  return { same: true }
}

export function buildAcceptedTexts(pack: AcceptedLinePack): string[] {
  const all = [pack.baseText, ...(pack.localVariants ?? []), ...(pack.onlineVariants ?? [])]
  const seen = new Set<string>()
  const result: string[] = []
  for (const text of all) {
    const key = normalizeChars(text)
    if (!key) continue
    if (seen.has(key)) continue
    seen.add(key)
    result.push(text.trim())
  }
  return result
}

export function scoreMatch(
  userText: string,
  targetText: string,
  cfg: AppConfig = DEFAULTS
): { score: number; coverage: number; toneDiffs?: Array<{ char: string; expectedTone?: number }> } {
  const user = normalizeChars(userText)
  const target = normalizeChars(targetText)
  if (!user && !target) return { score: 1, coverage: 1 }
  if (!user || !target) return { score: 0, coverage: 0 }

  const u = user.split('')
  const t = target.split('')

  const rows = u.length + 1
  const cols = t.length + 1
  const dp: number[] = new Array(rows * cols).fill(0)
  const toneDiffs: Array<{ char: string; expectedTone?: number }> = []

  const idx = (i: number, j: number) => i * cols + j

  for (let i = 0; i <= u.length; i++) dp[idx(i, 0)] = i
  for (let j = 0; j <= t.length; j++) dp[idx(0, j)] = j

  for (let i = 1; i <= u.length; i++) {
    for (let j = 1; j <= t.length; j++) {
      const match = sameSound(u[i - 1], t[j - 1], cfg)
      const subCost = match.same ? 0 : 1
      dp[idx(i, j)] = Math.min(
        dp[idx(i - 1, j)] + 1,
        dp[idx(i, j - 1)] + 1,
        dp[idx(i - 1, j - 1)] + subCost
      )
      if (match.toneDiff) {
        toneDiffs.push(match.toneDiff)
      }
    }
  }

  const dist = dp[idx(u.length, t.length)]
  const maxLen = Math.max(u.length, t.length)
  const coverage = maxLen === 0 ? 1 : Math.max(0, 1 - dist / maxLen)
  const score = coverage

  return cfg.tonePolicy === 'remind'
    ? { score, coverage, toneDiffs: toneDiffs.length ? toneDiffs : undefined }
    : { score, coverage }
}

export function judgeLine(
  userText: string,
  pack: AcceptedLinePack,
  cfg: AppConfig = DEFAULTS
): { passed: boolean; partial: boolean; bestText: string; score: number; coverage: number; remindTone?: string } {
  const accepted = buildAcceptedTexts(pack)
  let bestText = pack.baseText
  let bestScore = 0
  let bestCoverage = 0
  let bestToneDiffs: Array<{ char: string; expectedTone?: number }> | undefined

  for (const text of accepted) {
    const result = scoreMatch(userText, text, cfg)
    if (result.score > bestScore) {
      bestScore = result.score
      bestCoverage = result.coverage
      bestText = text
      bestToneDiffs = result.toneDiffs
    }
  }

  const passed = bestScore >= cfg.recite.passScore && bestCoverage >= cfg.recite.minCoverage
  const partial = !passed && bestScore >= cfg.recite.partialScore

  let remindTone: string | undefined
  if (cfg.tonePolicy === 'remind' && bestToneDiffs && bestToneDiffs.length > 0) {
    const list = bestToneDiffs.slice(0, 3).map((d) => d.char).join('、')
    remindTone = `声调提醒：${list}`
  }

  return { passed, partial, bestText, score: bestScore, coverage: bestCoverage, remindTone }
}
