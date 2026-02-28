export const DEFAULT_TITLE_SELECT_SCORE = 0.9
export const DEFAULT_MIN_FUZZY_TITLE_SCORE = 0.22

export function normalizeAscii(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9\u3400-\u9fff]/g, '').trim()
}

function editDistance(a: string, b: string): number {
  const rows = a.length + 1
  const cols = b.length + 1
  const dp: number[][] = []
  for (let i = 0; i < rows; i++) {
    const row: number[] = []
    for (let j = 0; j < cols; j++) {
      row.push(0)
    }
    dp.push(row)
  }
  for (let i = 0; i < rows; i++) dp[i][0] = i
  for (let j = 0; j < cols; j++) dp[0][j] = j
  for (let i = 1; i < rows; i++) {
    for (let j = 1; j < cols; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      const deletion = dp[i - 1][j] + 1
      const insertion = dp[i][j - 1] + 1
      const substitution = dp[i - 1][j - 1] + cost
      dp[i][j] = Math.min(deletion, insertion, substitution)
    }
  }
  return dp[a.length][b.length]
}

function scoreTitleCandidate(query: string, candidate: string, minFuzzyScore: number): number {
  if (query.length === 0 || candidate.length === 0) return 0
  if (query === candidate) return 1
  if (query.indexOf(candidate) >= 0) {
    const ratio = candidate.length / Math.max(query.length, 1)
    return 0.94 + Math.min(0.05, ratio * 0.05)
  }
  const distance = editDistance(query, candidate)
  const maxLen = Math.max(query.length, candidate.length)
  if (maxLen <= 0) return 0
  const fuzzy = 1 - distance / maxLen
  return fuzzy >= minFuzzyScore ? fuzzy : 0
}

export function resolveTitleScore(
  query: string,
  candidates: string[],
  minFuzzyScore: number = DEFAULT_MIN_FUZZY_TITLE_SCORE
): number {
  const normalizedQuery = normalizeAscii(query)
  let best = 0
  for (const candidate of candidates) {
    const score = scoreTitleCandidate(
      normalizedQuery,
      normalizeAscii(candidate),
      minFuzzyScore
    )
    if (score > best) {
      best = score
    }
  }
  return best
}
