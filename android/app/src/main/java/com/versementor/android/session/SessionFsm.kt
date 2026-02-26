package com.versementor.android.session

import kotlin.math.max

enum class SessionStateType {
    IDLE,
    SESSION_START,
    WAIT_POEM_NAME,
    CONFIRM_POEM_CANDIDATE,
    WAIT_DYNASTY_AUTHOR,
    RECITE_READY,
    RECITING,
    HINT_OFFER,
    HINT_GIVEN,
    FINISHED,
    EXIT
}

data class SessionContext(
    val config: AppConfig,
    val poemIndex: PoemIndex,
    val poems: List<Poem>,
    var selectedPoem: Poem? = null,
    var currentLineIdx: Int = 0,
    var lastUserActiveAt: Long? = null,
    var noPoemIntentSince: Long? = null
)

data class SessionState(val type: SessionStateType, val ctx: SessionContext)

sealed class SessionEvent {
    data class UserAsr(val text: String, val isFinal: Boolean, val confidence: Float?) : SessionEvent()
    data class Tick(val now: Long) : SessionEvent()
    data object UserUiStart : SessionEvent()
    data object UserUiStop : SessionEvent()
    data class VariantsFetched(val entry: Any?) : SessionEvent()
}

sealed class SessionAction {
    data class Speak(val text: String) : SessionAction()
    data object StartListening : SessionAction()
    data object StopListening : SessionAction()
    data class UpdateScreenHint(val key: String) : SessionAction()
    data class FetchVariants(val poem: Poem) : SessionAction()
}

class SessionReducer {
    fun reduce(state: SessionState, event: SessionEvent): SessionOutput {
        val ctx = state.ctx
        val actions = mutableListOf<SessionAction>()

        fun next(type: SessionStateType, update: SessionContext = ctx) = SessionOutput(SessionState(type, update), actions)

        when (state.type) {
            SessionStateType.IDLE -> {
                if (event is SessionEvent.UserUiStart) {
                    actions += SessionAction.Speak("你好，欢迎背诵诗词。请说出诗词题目。")
                    actions += SessionAction.StartListening
                    val now = System.currentTimeMillis()
                    ctx.noPoemIntentSince = now
                    return next(SessionStateType.WAIT_POEM_NAME, ctx)
                }
                return next(SessionStateType.IDLE)
            }

            SessionStateType.WAIT_POEM_NAME -> {
                when (event) {
                    is SessionEvent.Tick -> {
                        val start = ctx.noPoemIntentSince
                        if (start != null && event.now - start >= ctx.config.timeouts.noPoemIntentExitSec * 1000L) {
                            actions += SessionAction.Speak("暂时没有识别到诗词题目，先结束会话。")
                            return next(SessionStateType.EXIT)
                        }
                        return next(SessionStateType.WAIT_POEM_NAME)
                    }
                    is SessionEvent.UserAsr -> {
                        if (!event.isFinal) return next(SessionStateType.WAIT_POEM_NAME)
                        val spoken = event.text
                        val exact = ctx.poemIndex.findByTitleExact(spoken)
                        val candidates = if (exact.isNotEmpty()) exact.map { ScoredPoem(it, 1.0) } else ctx.poemIndex.findByTitleFuzzy(spoken)
                        if (candidates.isEmpty()) {
                            actions += SessionAction.Speak("没有找到这首诗，请再说一次题目。")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.WAIT_POEM_NAME)
                        }
                        val top = candidates.first()
                        ctx.selectedPoem = top.poem
                        if (top.score >= 0.9) {
                            actions += SessionAction.Speak("已选择《${top.poem.title}》。请说出朝代和作者。")
                            actions += SessionAction.FetchVariants(top.poem)
                            actions += SessionAction.StartListening
                            return next(SessionStateType.WAIT_DYNASTY_AUTHOR)
                        }
                        actions += SessionAction.Speak("你说的是《${top.poem.title}》吗？")
                        actions += SessionAction.StartListening
                        return next(SessionStateType.CONFIRM_POEM_CANDIDATE)
                    }
                    else -> return next(SessionStateType.WAIT_POEM_NAME)
                }
            }

            SessionStateType.CONFIRM_POEM_CANDIDATE -> {
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    if (event.text.contains("不是") || event.text.contains("不对")) {
                        actions += SessionAction.Speak("好的，请再说一次题目。")
                        actions += SessionAction.StartListening
                        return next(SessionStateType.WAIT_POEM_NAME)
                    }
                    val poem = ctx.selectedPoem
                    if (poem != null) {
                        actions += SessionAction.Speak("已选择《${poem.title}》。请说出朝代和作者。")
                        actions += SessionAction.FetchVariants(poem)
                        actions += SessionAction.StartListening
                        return next(SessionStateType.WAIT_DYNASTY_AUTHOR)
                    }
                    return next(SessionStateType.WAIT_POEM_NAME)
                }
                return next(SessionStateType.CONFIRM_POEM_CANDIDATE)
            }

            SessionStateType.WAIT_DYNASTY_AUTHOR -> {
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    actions += SessionAction.Speak("好的，开始背诵。")
                    actions += SessionAction.StartListening
                    ctx.currentLineIdx = 0
                    return next(SessionStateType.RECITING)
                }
                return next(SessionStateType.WAIT_DYNASTY_AUTHOR)
            }

            SessionStateType.RECITING -> {
                when (event) {
                    is SessionEvent.Tick -> {
                        val last = ctx.lastUserActiveAt
                        if (last != null && event.now - last >= ctx.config.timeouts.reciteSilenceAskHintSec * 1000L) {
                            actions += SessionAction.Speak("需要提示吗？")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.HINT_OFFER)
                        }
                        return next(SessionStateType.RECITING)
                    }
                    is SessionEvent.UserAsr -> {
                        if (!event.isFinal) return next(SessionStateType.RECITING)
                        ctx.lastUserActiveAt = System.currentTimeMillis()
                        val poem = ctx.selectedPoem ?: return next(SessionStateType.EXIT)
                        val line = poem.lines.getOrNull(ctx.currentLineIdx)?.text ?: ""
                        val score = scoreLine(event.text, line)
                        if (score >= ctx.config.recite.passScore) {
                            val nextIdx = ctx.currentLineIdx + 1
                            if (nextIdx >= poem.lines.size) {
                                actions += SessionAction.Speak("很好！还要再来一首吗？")
                                return next(SessionStateType.FINISHED)
                            }
                            ctx.currentLineIdx = nextIdx
                            actions += SessionAction.Speak("很好，下一句。")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.RECITING)
                        }
                        if (score >= ctx.config.recite.partialScore) {
                            actions += SessionAction.Speak("接近了，再试一次。")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.RECITING)
                        }
                        actions += SessionAction.Speak("没关系，再试一次。")
                        actions += SessionAction.StartListening
                        return next(SessionStateType.RECITING)
                    }
                    else -> return next(SessionStateType.RECITING)
                }
            }

            SessionStateType.HINT_OFFER -> {
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    val poem = ctx.selectedPoem
                    if (poem != null) {
                        val line = poem.lines.getOrNull(ctx.currentLineIdx)
                        val hint = line?.text?.take(2) ?: "提示"
                        actions += SessionAction.Speak("提示：${hint}…")
                        actions += SessionAction.StartListening
                        return next(SessionStateType.HINT_GIVEN)
                    }
                }
                return next(SessionStateType.HINT_OFFER)
            }

            SessionStateType.HINT_GIVEN -> {
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    return next(SessionStateType.RECITING)
                }
                return next(SessionStateType.HINT_GIVEN)
            }

            SessionStateType.FINISHED -> {
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    if (event.text.contains("是") || event.text.contains("再来")) {
                        actions += SessionAction.Speak("好的，请说出下一首诗的题目。")
                        actions += SessionAction.StartListening
                        ctx.selectedPoem = null
                        ctx.currentLineIdx = 0
                        ctx.noPoemIntentSince = System.currentTimeMillis()
                        return next(SessionStateType.WAIT_POEM_NAME)
                    }
                    actions += SessionAction.Speak("好的，已结束会话。")
                    return next(SessionStateType.EXIT)
                }
                return next(SessionStateType.FINISHED)
            }

            SessionStateType.EXIT -> return next(SessionStateType.EXIT)
            else -> return next(SessionStateType.IDLE)
        }
    }

    private fun scoreLine(userText: String, target: String): Double {
        val u = normalize(userText)
        val t = normalize(target)
        if (u.isEmpty() || t.isEmpty()) return 0.0
        val dist = editDistance(u, t)
        val maxLen = max(u.length, t.length)
        return if (maxLen == 0) 1.0 else 1.0 - dist.toDouble() / maxLen
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
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

data class SessionOutput(val state: SessionState, val actions: List<SessionAction>)

data class SessionUiState(
    val statusText: String = "Idle",
    val lastSpoken: String = "",
    val lastHeard: String = "",
    val logs: List<String> = emptyList()
)

fun buildInitialSession(config: AppConfig, poems: List<Poem>): SessionState {
    val ctx = SessionContext(
        config = config,
        poemIndex = PoemIndex(poems),
        poems = poems
    )
    return SessionState(SessionStateType.IDLE, ctx)
}
