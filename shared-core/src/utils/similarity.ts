export function editDistance(a: string, b: string): number {
  const aLen = a.length
  const bLen = b.length
  const dp: number[] = new Array((aLen + 1) * (bLen + 1)).fill(0)

  const idx = (i: number, j: number) => i * (bLen + 1) + j

  for (let i = 0; i <= aLen; i++) dp[idx(i, 0)] = i
  for (let j = 0; j <= bLen; j++) dp[idx(0, j)] = j

  for (let i = 1; i <= aLen; i++) {
    for (let j = 1; j <= bLen; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      dp[idx(i, j)] = Math.min(
        dp[idx(i - 1, j)] + 1,
        dp[idx(i, j - 1)] + 1,
        dp[idx(i - 1, j - 1)] + cost
      )
    }
  }
  return dp[idx(aLen, bLen)]
}

function biGrams(s: string): string[] {
  const grams: string[] = []
  for (let i = 0; i < s.length - 1; i++) {
    grams.push(s.slice(i, i + 2))
  }
  return grams
}

export function jaccard2gram(a: string, b: string): number {
  if (!a || !b) return 0
  const aGrams = biGrams(a)
  const bGrams = biGrams(b)
  const aSet = new Set(aGrams)
  const bSet = new Set(bGrams)
  const union = new Set<string>([...aSet, ...bSet])
  let intersection = 0
  for (const g of aSet) {
    if (bSet.has(g)) intersection++
  }
  return union.size === 0 ? 0 : intersection / union.size
}

export function sim(a: string, b: string): number {
  if (!a && !b) return 1
  if (!a || !b) return 0
  const ed = editDistance(a, b)
  const maxLen = Math.max(a.length, b.length)
  const edSim = maxLen === 0 ? 1 : 1 - ed / maxLen
  const jac = jaccard2gram(a, b)
  return 0.6 * jac + 0.4 * edSim
}
