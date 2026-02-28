package com.versementor.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoemIndexTitleMatchTest {
    private val index = PoemIndex(SamplePoems.poems)

    @Test
    fun findByTitleExact_cleansFillers() {
        val result = index.findByTitleExact("\u55ef\u55ef\u90a3\u4e2a\u9759\u591c\u601d")

        assertEquals(1, result.size)
        assertEquals("\u9759\u591c\u601d", result.first().title)
    }

    @Test
    fun findByTitleExact_collapsesRepeatedTitleTail() {
        val result = index.findByTitleExact("\u55ef\u55ef\u90a3\u4e2a\u9759\u591c\u601d\u9759\u591c\u601d")

        assertEquals(1, result.size)
        assertEquals("\u9759\u591c\u601d", result.first().title)
    }

    @Test
    fun findByTitleExact_normalizesTraditionalTitle() {
        val result = index.findByTitleExact("\u975c\u591c\u601d")

        assertEquals(1, result.size)
        assertEquals("\u9759\u591c\u601d", result.first().title)
    }

    @Test
    fun findByTitleContained_matchesNaturalUtterance() {
        val result = index.findByTitleContained("\u6211\u60f3\u80cc\u9759\u591c\u601d")

        assertTrue(result.isNotEmpty())
        assertEquals("\u9759\u591c\u601d", result.first().poem.title)
        assertTrue(result.first().score >= 0.9)
    }
}
