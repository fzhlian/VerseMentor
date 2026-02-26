package com.versementor.android.session

data class PoemLine(val text: String, val meaning: String? = null)

data class Poem(
    val id: String,
    val title: String,
    val dynasty: String,
    val author: String,
    val lines: List<PoemLine>
)

object SamplePoems {
    val poems = listOf(
        Poem(
            id = "p1",
            title = "静夜思",
            dynasty = "唐",
            author = "李白",
            lines = listOf(
                PoemLine("床前明月光"),
                PoemLine("疑是地上霜"),
                PoemLine("举头望明月"),
                PoemLine("低头思故乡")
            )
        ),
        Poem(
            id = "p2",
            title = "春晓",
            dynasty = "唐",
            author = "孟浩然",
            lines = listOf(
                PoemLine("春眠不觉晓"),
                PoemLine("处处闻啼鸟"),
                PoemLine("夜来风雨声"),
                PoemLine("花落知多少")
            )
        )
    )
}
