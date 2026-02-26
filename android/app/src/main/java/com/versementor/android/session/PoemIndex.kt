package com.versementor.android.session

import kotlin.math.max

class PoemIndex(private val poems: List<Poem>) {
    fun findByTitleExact(title: String): List<Poem> {
        val norm = normalize(title)
        return poems.filter { normalize(it.title) == norm }
    }

    fun findByTitleFuzzy(spoken: String, limit: Int = 3): List<ScoredPoem> {
        val norm = normalize(spoken)
        return poems.map { poem ->
            ScoredPoem(poem, similarity(norm, normalize(poem.title)))
        }.sortedByDescending { it.score }.take(limit)
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dist = editDistance(a, b)
        val maxLen = max(a.length, b.length)
        return if (maxLen == 0) 1.0 else 1.0 - dist.toDouble() / maxLen
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }
}

data class ScoredPoem(val poem: Poem, val score: Double)
