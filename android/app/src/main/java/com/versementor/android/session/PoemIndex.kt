package com.versementor.android.session

import kotlin.math.max

class PoemIndex(private val poems: List<Poem>) {
    private val fillers: List<String> = listOf(
        "\u55ef", "\u554a", "\u5443", "\u989d", "\u90a3\u4e2a", "\u8fd9\u4e2a",
        "\u5c31\u662f", "\u7136\u540e", "\u561b", "\u5440", "\u8bf6", "\u5450"
    )
    private val tradToSimp: Map<Char, Char> = mapOf(
        '\u4f86' to '\u6765',
        '\u975c' to '\u9759',
        '\u89ba' to '\u89c9',
        '\u66c9' to '\u6653',
        '\u9ce5' to '\u9e1f',
        '\u9e1b' to '\u9e73',
        '\u9db4' to '\u9e64',
        '\u98a8' to '\u98ce',
        '\u8655' to '\u5904',
        '\u9593' to '\u95f4',
        '\u6a13' to '\u697c',
        '\u5ee3' to '\u5e7f',
        '\u96f2' to '\u4e91',
        '\u9019' to '\u8fd9',
        '\u8208' to '\u5174',
        '\u570b' to '\u56fd',
        '\u8a69' to '\u8bd7',
        '\u8a5e' to '\u8bcd',
        '\u8d08' to '\u8d60',
        '\u502b' to '\u4f26',
        '\u9ec3' to '\u9ec4',
        '\u5eec' to '\u5e90',
        '\u6973' to '\u67ab',
        '\u7d55' to '\u7edd',
        '\u904a' to '\u6e38',
        '\u8a60' to '\u548f',
        '\u81fa' to '\u53f0',
        '\u984c' to '\u9898',
        '\u5bae' to '\u5bab',
        '\u767c' to '\u53d1'
    )
    private val titleEntries: List<TitleEntry> = poems.map { poem ->
        TitleEntry(poem = poem, normalizedTitle = normalize(poem.title))
    }

    fun findByTitleExact(title: String): List<Poem> {
        val norm = cleanSpeechText(title)
        if (norm.isEmpty()) return emptyList()
        return titleEntries.filter { it.normalizedTitle == norm }.map { it.poem }
    }

    fun findByTitleContained(spoken: String, limit: Int = 3): List<ScoredPoem> {
        val norm = cleanSpeechText(spoken)
        if (norm.isEmpty()) return emptyList()
        return titleEntries
            .asSequence()
            .filter { it.normalizedTitle.isNotEmpty() && norm.contains(it.normalizedTitle) }
            .map { entry ->
                val matchRatio = entry.normalizedTitle.length.toDouble() / max(norm.length, 1).toDouble()
                val score = 0.94 + minOf(0.05, matchRatio * 0.05)
                ScoredContainedPoem(ScoredPoem(entry.poem, score), entry.normalizedTitle.length)
            }
            .sortedWith(compareByDescending<ScoredContainedPoem> { it.scored.score }.thenByDescending { it.titleLength })
            .take(limit)
            .map { it.scored }
            .toList()
    }

    fun findByTitleFuzzy(spoken: String, limit: Int = 3): List<ScoredPoem> {
        val norm = cleanSpeechText(spoken)
        if (norm.isEmpty()) return emptyList()
        return titleEntries.map { entry ->
            ScoredPoem(entry.poem, similarity(norm, entry.normalizedTitle))
        }.sortedByDescending { it.score }.take(limit)
    }

    private fun cleanSpeechText(text: String): String {
        var out = normalize(text)
        for (filler in fillers) {
            out = out.replace(filler, "")
        }
        // Collapse long repeated single characters and repeated two-character chunks.
        out = out.replace(Regex("(.)\\1{2,}"), "$1")
        out = out.replace(Regex("(.{2})\\1{1,}"), "$1")
        // Collapse repeated trailing segments: "我想背静夜思静夜思" -> "我想背静夜思"
        out = collapseRepeatedTailSegment(out)
        return out.trim()
    }

    private fun collapseRepeatedTailSegment(input: String): String {
        var out = input
        val maxChunkLen = minOf(12, out.length / 2)
        for (len in maxChunkLen downTo 3) {
            var changed = true
            while (changed && out.length >= len * 2) {
                changed = false
                val tail = out.substring(out.length - len)
                val prev = out.substring(out.length - len * 2, out.length - len)
                if (tail == prev) {
                    out = out.substring(0, out.length - len)
                    changed = true
                }
            }
        }
        return out
    }

    private fun normalize(text: String): String {
        val unified = unifyTradSimp(text.trim().lowercase())
        return unified.replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    }

    private fun unifyTradSimp(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(tradToSimp[ch] ?: ch)
        }
        return sb.toString()
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

    private data class TitleEntry(
        val poem: Poem,
        val normalizedTitle: String
    )

    private data class ScoredContainedPoem(
        val scored: ScoredPoem,
        val titleLength: Int
    )
}

data class ScoredPoem(val poem: Poem, val score: Double)
