package com.versementor.android.session

import com.versementor.android.storage.PoemVariantsCacheEntry
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
    var variantsCacheEntry: PoemVariantsCacheEntry? = null,
    var currentLineIdx: Int = 0,
    var lastUserActiveAt: Long? = null,
    var noPoemIntentSince: Long? = null,
    var hintOfferSince: Long? = null
)

data class SessionState(val type: SessionStateType, val ctx: SessionContext)

sealed class SessionEvent {
    data class UserAsr(val text: String, val isFinal: Boolean, val confidence: Float?, val now: Long? = null) : SessionEvent()
    data class UserAsrError(val code: Int, val message: String) : SessionEvent()
    data class Tick(val now: Long) : SessionEvent()
    data class UserUiStart(val now: Long? = null) : SessionEvent()
    data object UserUiStop : SessionEvent()
    data class VariantsFetched(val entry: PoemVariantsCacheEntry?) : SessionEvent()
}

sealed class SessionAction {
    data class Speak(val text: String) : SessionAction()
    data object StartListening : SessionAction()
    data object StopListening : SessionAction()
    data class UpdateScreenHint(val key: String) : SessionAction()
    data class FetchVariants(val poem: Poem) : SessionAction()
}

class SessionReducer {
    private companion object {
        const val MIN_FUZZY_TITLE_SCORE = 0.22
        val TRAD_TO_SIMP: Map<Char, Char> = mapOf(
            '來' to '来',
            '開' to '开',
            '結' to '结',
            '換' to '换',
            '詩' to '诗',
            '誦' to '诵',
            '對' to '对',
            '給' to '给',
            '幫' to '帮',
            '嗎' to '吗',
            '這' to '这',
            '沒' to '没',
            '錯' to '错',
            '複' to '复',
            '確' to '确',
            '認' to '认',
            '麼' to '么',
            '講' to '讲',
            '聽' to '听',
            '說' to '说',
            '續' to '续'
        )
    }

    fun reduce(state: SessionState, event: SessionEvent): SessionOutput {
        val ctx = state.ctx
        val actions = mutableListOf<SessionAction>()

        fun next(type: SessionStateType, update: SessionContext = ctx) = SessionOutput(SessionState(type, update), actions)

        if (event is SessionEvent.UserAsrError) {
            if (state.type == SessionStateType.IDLE || state.type == SessionStateType.EXIT) {
                return next(state.type)
            }
            actions += SessionAction.Speak("语音识别异常：${event.message}。请再说一次。")
            actions += SessionAction.StartListening
            return next(state.type)
        }

        if (event is SessionEvent.UserUiStop && state.type != SessionStateType.IDLE && state.type != SessionStateType.EXIT) {
            actions += SessionAction.StopListening
            return next(SessionStateType.EXIT)
        }

        if (event is SessionEvent.UserUiStart && state.type != SessionStateType.IDLE) {
            actions += SessionAction.Speak("你好，欢迎背诵诗词。请说出诗词题目。")
            actions += SessionAction.StartListening
            ctx.selectedPoem = null
            ctx.variantsCacheEntry = null
            ctx.currentLineIdx = 0
            ctx.hintOfferSince = null
            ctx.noPoemIntentSince = event.now ?: System.currentTimeMillis()
            return next(SessionStateType.WAIT_POEM_NAME)
        }

        if (
            event is SessionEvent.UserAsr &&
            event.isFinal &&
            state.type != SessionStateType.IDLE &&
            state.type != SessionStateType.EXIT &&
            isExitIntent(event.text)
        ) {
            actions += SessionAction.Speak("好的，已结束会话。")
            actions += SessionAction.StopListening
            return next(SessionStateType.EXIT)
        }

        if (
            event is SessionEvent.UserAsr &&
            event.isFinal &&
            state.type != SessionStateType.IDLE &&
            state.type != SessionStateType.EXIT &&
            isRepeatIntent(event.text)
        ) {
            val repeatReply = buildRepeatReply(state.type, ctx)
            if (repeatReply != null) {
                actions += SessionAction.Speak(repeatReply)
                actions += SessionAction.StartListening
                return next(state.type)
            }
        }

        when (state.type) {
            SessionStateType.IDLE -> {
                if (event is SessionEvent.UserUiStart) {
                    actions += SessionAction.Speak("你好，欢迎背诵诗词。请说出诗词题目。")
                    actions += SessionAction.StartListening
                    val now = event.now ?: System.currentTimeMillis()
                    ctx.noPoemIntentSince = now
                    return next(SessionStateType.WAIT_POEM_NAME, ctx)
                }
                return next(SessionStateType.IDLE)
            }

            SessionStateType.WAIT_POEM_NAME -> {
                when (event) {
                    is SessionEvent.Tick -> {
                        val start = ctx.noPoemIntentSince
                        if (start == null) {
                            ctx.noPoemIntentSince = event.now
                            return next(SessionStateType.WAIT_POEM_NAME)
                        }
                        if (event.now - start >= ctx.config.timeouts.noPoemIntentExitSec * 1000L) {
                            actions += SessionAction.Speak("暂时没有识别到诗词题目，先结束会话。")
                            return next(SessionStateType.EXIT)
                        }
                        return next(SessionStateType.WAIT_POEM_NAME)
                    }
                    is SessionEvent.UserAsr -> {
                        if (!event.isFinal) return next(SessionStateType.WAIT_POEM_NAME)
                        val spoken = event.text
                        val candidates = resolvePoemCandidates(ctx.poemIndex, spoken)
                        if (candidates.isEmpty()) {
                            actions += SessionAction.Speak("没有找到这首诗，请再说一次题目。")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.WAIT_POEM_NAME)
                        }
                        val top = candidates.first()
                        ctx.selectedPoem = top.poem
                        ctx.variantsCacheEntry = null
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
                    if (isRejectPoemIntent(event.text)) {
                        ctx.selectedPoem = null
                        ctx.variantsCacheEntry = null
                        actions += SessionAction.Speak("好的，请再说一次题目。")
                        actions += SessionAction.StartListening
                        return next(SessionStateType.WAIT_POEM_NAME)
                    }
                    val poem = ctx.selectedPoem
                    if (poem != null && isConfirmPoemIntent(event.text)) {
                        actions += SessionAction.Speak("已选择《${poem.title}》。请说出朝代和作者。")
                        actions += SessionAction.FetchVariants(poem)
                        actions += SessionAction.StartListening
                        return next(SessionStateType.WAIT_DYNASTY_AUTHOR)
                    }
                    ctx.selectedPoem = null
                    ctx.variantsCacheEntry = null
                    actions += SessionAction.Speak("没有确认到题目，请再说一次题目。")
                    actions += SessionAction.StartListening
                    return next(SessionStateType.WAIT_POEM_NAME)
                }
                return next(SessionStateType.CONFIRM_POEM_CANDIDATE)
            }

            SessionStateType.WAIT_DYNASTY_AUTHOR -> {
                if (event is SessionEvent.VariantsFetched) {
                    ctx.variantsCacheEntry = event.entry
                    return next(SessionStateType.WAIT_DYNASTY_AUTHOR)
                }
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    val poem = ctx.selectedPoem
                    if (poem != null && isDynastyAuthorMatch(event.text, poem)) {
                        actions += SessionAction.Speak("好的，开始背诵。")
                        actions += SessionAction.StartListening
                        ctx.currentLineIdx = 0
                        ctx.lastUserActiveAt = event.now ?: System.currentTimeMillis()
                        return next(SessionStateType.RECITE_READY)
                    }
                    actions += SessionAction.Speak("请再说一次朝代和作者。")
                    actions += SessionAction.StartListening
                    return next(SessionStateType.WAIT_DYNASTY_AUTHOR)
                }
                return next(SessionStateType.WAIT_DYNASTY_AUTHOR)
            }

            SessionStateType.RECITE_READY -> {
                if (ctx.selectedPoem == null) return next(SessionStateType.EXIT)
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    if (ctx.currentLineIdx != 0) {
                        ctx.currentLineIdx = 0
                    }
                    ctx.lastUserActiveAt = event.now ?: System.currentTimeMillis()
                    return reduce(SessionState(SessionStateType.RECITING, ctx), event)
                }
                if (ctx.currentLineIdx != 0) {
                    ctx.currentLineIdx = 0
                }
                if (ctx.lastUserActiveAt == null) {
                    ctx.lastUserActiveAt = System.currentTimeMillis()
                }
                actions += SessionAction.Speak("请背诵第一句。")
                actions += SessionAction.StartListening
                return next(SessionStateType.RECITING)
            }

            SessionStateType.RECITING -> {
                when (event) {
                    is SessionEvent.Tick -> {
                        val last = ctx.lastUserActiveAt
                        if (last != null && event.now - last >= ctx.config.timeouts.reciteSilenceAskHintSec * 1000L) {
                            ctx.hintOfferSince = event.now
                            actions += SessionAction.Speak("需要提示吗？")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.HINT_OFFER)
                        }
                        return next(SessionStateType.RECITING)
                    }
                    is SessionEvent.UserAsr -> {
                        if (!event.isFinal) return next(SessionStateType.RECITING)
                        ctx.lastUserActiveAt = event.now ?: System.currentTimeMillis()
                        val poem = ctx.selectedPoem ?: return next(SessionStateType.EXIT)
                        val line = poem.lines.getOrNull(ctx.currentLineIdx)?.text ?: ""
                        val onlineVariants = ctx.variantsCacheEntry
                            ?.variants
                            ?.lines
                            ?.firstOrNull { it.lineIndex == ctx.currentLineIdx }
                            ?.variants
                            .orEmpty()
                        val score = scoreLineWithVariants(event.text, line, onlineVariants)
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
                        if (isAskHintIntent(event.text)) {
                            val hint = poem.lines.getOrNull(ctx.currentLineIdx)?.text?.take(2) ?: "提示"
                            actions += SessionAction.Speak("提示：${hint}…")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.HINT_GIVEN)
                        }
                        actions += SessionAction.Speak("没关系，再试一次。")
                        actions += SessionAction.StartListening
                        return next(SessionStateType.RECITING)
                    }
                    else -> return next(SessionStateType.RECITING)
                }
            }

            SessionStateType.HINT_OFFER -> {
                when (event) {
                    is SessionEvent.Tick -> {
                        val since = ctx.hintOfferSince ?: event.now.also { ctx.hintOfferSince = it }
                        if (event.now - since >= ctx.config.timeouts.hintOfferWaitSec * 1000L) {
                            ctx.hintOfferSince = null
                            actions += SessionAction.Speak("好的，继续背诵。")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.RECITING)
                        }
                        return next(SessionStateType.HINT_OFFER)
                    }
                    is SessionEvent.UserAsr -> {
                        if (!event.isFinal) return next(SessionStateType.HINT_OFFER)
                        ctx.hintOfferSince = null
                        val poem = ctx.selectedPoem
                        if (poem != null && isAskHintIntent(event.text)) {
                            val line = poem.lines.getOrNull(ctx.currentLineIdx)
                            val hint = line?.text?.take(2) ?: "提示"
                            actions += SessionAction.Speak("提示：${hint}…")
                            actions += SessionAction.StartListening
                            return next(SessionStateType.HINT_GIVEN)
                        }
                        actions += SessionAction.Speak("好的，继续。")
                        actions += SessionAction.StartListening
                        return next(SessionStateType.RECITING)
                    }
                    else -> return next(SessionStateType.HINT_OFFER)
                }
            }

            SessionStateType.HINT_GIVEN -> {
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    return reduce(SessionState(SessionStateType.RECITING, ctx), event)
                }
                return next(SessionStateType.HINT_GIVEN)
            }

            SessionStateType.FINISHED -> {
                if (event is SessionEvent.UserAsr && event.isFinal) {
                    if (isNextPoemIntent(event.text)) {
                        actions += SessionAction.Speak("好的，请说出下一首诗的题目。")
                        actions += SessionAction.StartListening
                        ctx.selectedPoem = null
                        ctx.variantsCacheEntry = null
                        ctx.currentLineIdx = 0
                        ctx.noPoemIntentSince = event.now ?: System.currentTimeMillis()
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

    private fun resolvePoemCandidates(poemIndex: PoemIndex, spoken: String): List<ScoredPoem> {
        val exact = poemIndex.findByTitleExact(spoken)
        if (exact.isNotEmpty()) {
            return exact.map { ScoredPoem(it, 1.0) }
        }

        val contained = poemIndex.findByTitleContained(spoken)
        if (contained.isNotEmpty()) {
            return contained
        }

        return poemIndex.findByTitleFuzzy(spoken).filter { it.score >= MIN_FUZZY_TITLE_SCORE }
    }

    private fun scoreLineWithVariants(userText: String, target: String, variants: List<String>): Double {
        var best = scoreLine(userText, target)
        for (variant in variants) {
            best = max(best, scoreLine(userText, variant))
        }
        return best
    }

    private fun isExitIntent(text: String): Boolean {
        val raw = normalizeForIntent(text)
        if (raw.isEmpty()) return false
        return raw.contains("退出") ||
            raw.contains("结束") ||
            raw.contains("停止") ||
            raw.contains("不背了") ||
            raw.contains("不背诵了") ||
            raw.contains("不背诗了") ||
            raw.contains("不用背了") ||
            raw.contains("不用背诵了") ||
            raw.contains("不用背诗了") ||
            raw.contains("不想背了") ||
            raw.contains("不想背诵了") ||
            raw.contains("不想背诗了")
    }

    private fun isRejectPoemIntent(text: String): Boolean {
        val raw = normalizeForIntent(text)
        if (raw.isEmpty()) return false
        return raw.contains("不是") ||
            raw.contains("不对") ||
            raw.contains("不要") ||
            raw.contains("错了") ||
            raw.contains("换一个")
    }

    private fun isDynastyAuthorMatch(text: String, poem: Poem): Boolean {
        val raw = normalizeForIntent(text)
        if (raw.isEmpty()) return false
        val dynasty = normalizeForIntent(poem.dynasty)
        val author = normalizeForIntent(poem.author)
        if (dynasty.isEmpty() || author.isEmpty()) return false
        return raw.contains(dynasty) && raw.contains(author)
    }

    private fun isConfirmPoemIntent(text: String): Boolean {
        val rawOriginal = text.trim()
        val raw = normalizeForIntent(text)
        if (raw.isEmpty()) return false
        if (isRejectPoemIntent(raw)) return false
        if (rawOriginal.contains("?") || rawOriginal.contains("？")) return false
        if (
            raw == "是" ||
            raw == "是的" ||
            raw == "对" ||
            raw == "对的" ||
            raw == "好的" ||
            raw == "好" ||
            raw == "确认" ||
            raw == "就是它" ||
            raw == "没错" ||
            raw == "就是这首" ||
            raw == "是这首" ||
            raw == "就这首"
        ) {
            return true
        }
        if (raw.contains("吗") || raw.contains("嘛") || raw.contains("么") || raw.contains("呢")) return false
        return raw.contains("就是这首") ||
            raw.contains("确认这首") ||
            raw.contains("是这首") ||
            raw.contains("就这首")
    }

    private fun isNextPoemIntent(text: String): Boolean {
        val raw = normalizeForIntent(text)
        if (raw.isEmpty()) return false
        return raw.contains("下一首") ||
            raw.contains("换一首") ||
            raw.contains("换首") ||
            raw.contains("换诗") ||
            raw.contains("再来一首") ||
            raw.contains("再来首") ||
            raw.contains("重来一首") ||
            raw.contains("重来首") ||
            raw.contains("重新来一首") ||
            raw.contains("重新来首") ||
            raw.contains("开始")
    }

    private fun isAskHintIntent(text: String): Boolean {
        val raw = normalizeForIntent(text)
        if (raw.isEmpty()) return false
        return raw.contains("提示") ||
            raw.contains("给提示") ||
            raw.contains("不会了") ||
            raw.contains("提示一下") ||
            raw.contains("帮我")
    }

    private fun normalizeForIntent(text: String): String {
        val lowered = text.trim().lowercase()
        if (lowered.isEmpty()) return ""
        val sb = StringBuilder(lowered.length)
        for (ch in lowered) {
            sb.append(TRAD_TO_SIMP[ch] ?: ch)
        }
        return sb.toString().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
    }

    private fun isRepeatIntent(text: String): Boolean {
        val raw = normalizeForIntent(text)
        if (raw.isEmpty()) return false
        return raw.contains("再说一遍") ||
            raw.contains("再说一次") ||
            raw.contains("再讲一遍") ||
            raw.contains("再讲一次") ||
            raw.contains("再听一遍") ||
            raw.contains("再听一次") ||
            raw.contains("重复") ||
            raw.contains("再来一次") ||
            raw.contains("再来一遍") ||
            raw.contains("重来一次") ||
            raw.contains("重来一遍")
    }

    private fun buildRepeatReply(type: SessionStateType, ctx: SessionContext): String? {
        return when (type) {
            SessionStateType.WAIT_POEM_NAME -> "你好，欢迎背诵诗词。请说出诗词题目。"
            SessionStateType.CONFIRM_POEM_CANDIDATE ->
                if (ctx.selectedPoem != null) "你说的是《${ctx.selectedPoem!!.title}》吗？" else "请再说一次题目。"
            SessionStateType.WAIT_DYNASTY_AUTHOR ->
                if (ctx.selectedPoem != null) "已选择《${ctx.selectedPoem!!.title}》。请说出朝代和作者。"
                else "请再说一次朝代和作者。"
            SessionStateType.RECITE_READY -> "请背诵第一句。"
            SessionStateType.RECITING, SessionStateType.HINT_GIVEN -> "请继续背诵当前句。"
            SessionStateType.HINT_OFFER -> "需要提示吗？"
            SessionStateType.FINISHED -> "还要再来一首吗？"
            else -> null
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
    val liveHeard: String = "",
    val recognizedLines: List<String> = emptyList(),
    val awaitingSpeech: Boolean = false,
    val sessionActive: Boolean = false,
    val sessionPaused: Boolean = false,
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
