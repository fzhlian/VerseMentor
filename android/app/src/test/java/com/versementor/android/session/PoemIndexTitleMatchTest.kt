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

    @Test
    fun findByTitleExact_normalizesTraditionalQiaoCharacter() {
        val customIndex = PoemIndex(
            listOf(
                Poem(
                    id = "custom-1",
                    title = "\u67ab\u6865\u591c\u6cca",
                    dynasty = "\u5510",
                    author = "\u5f20\u7ee7",
                    lines = listOf(PoemLine("\u6708\u843d\u4e4c\u557c\u971c\u6ee1\u5929"))
                )
            )
        )

        val result = customIndex.findByTitleExact("\u6953\u6a4b\u591c\u6cca")

        assertEquals(1, result.size)
        assertEquals("\u67ab\u6865\u591c\u6cca", result.first().title)
    }

    @Test
    fun findByTitleExact_normalizesTraditionalChangCharacter() {
        val customIndex = PoemIndex(
            listOf(
                Poem(
                    id = "custom-2",
                    title = "\u957f\u76f8\u601d",
                    dynasty = "\u5510",
                    author = "\u674e\u767d",
                    lines = listOf(PoemLine("\u957f\u76f8\u601d\u5728\u957f\u5b89"))
                )
            )
        )

        val result = customIndex.findByTitleExact("\u9577\u76f8\u601d")

        assertEquals(1, result.size)
        assertEquals("\u957f\u76f8\u601d", result.first().title)
    }
}
