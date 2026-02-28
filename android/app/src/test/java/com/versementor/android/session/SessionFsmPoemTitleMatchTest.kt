package com.versementor.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFsmPoemTitleMatchTest {
    private val reducer = SessionReducer()

    @Test
    fun waitPoemName_whenUtteranceContainsTitle_selectsPoemDirectly() {
        val state = stateOf(SessionStateType.WAIT_POEM_NAME)

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u6211\u60f3\u80cc\u9759\u591c\u601d",
                isFinal = true,
                confidence = 0.95f,
                now = 3000L
            )
        )

        assertEquals(SessionStateType.WAIT_DYNASTY_AUTHOR, output.state.type)
        assertEquals("\u9759\u591c\u601d", output.state.ctx.selectedPoem?.title)
        assertTrue(output.actions.any { it is SessionAction.FetchVariants })
    }

    @Test
    fun waitPoemName_whenUtteranceHasFillersAndRepeats_stillSelectsPoem() {
        val state = stateOf(SessionStateType.WAIT_POEM_NAME)

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u55ef\u55ef\u90a3\u4e2a\u6211\u60f3\u80cc\u9759\u591c\u601d\u9759\u591c\u601d",
                isFinal = true,
                confidence = 0.95f,
                now = 3002L
            )
        )

        assertEquals(SessionStateType.WAIT_DYNASTY_AUTHOR, output.state.type)
        assertEquals("\u9759\u591c\u601d", output.state.ctx.selectedPoem?.title)
    }

    @Test
    fun waitPoemName_whenUtteranceHasNoTitle_staysWaitingWithoutGuessing() {
        val state = stateOf(SessionStateType.WAIT_POEM_NAME)

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u6211\u60f3\u80cc\u4e00\u9996\u8bd7",
                isFinal = true,
                confidence = 0.95f,
                now = 3001L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertTrue(output.actions.any { it is SessionAction.StartListening })
        assertEquals(
            "\u6ca1\u6709\u627e\u5230\u8fd9\u9996\u8bd7\uff0c\u8bf7\u518d\u8bf4\u4e00\u6b21\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceIsNotAffirmative_returnsToWaitPoemName() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u4e0d\u77e5\u9053",
                isFinal = true,
                confidence = 0.95f,
                now = 3010L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u6ca1\u6709\u786e\u8ba4\u5230\u9898\u76ee\uff0c\u8bf7\u518d\u8bf4\u4e00\u6b21\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceIsTraditionalRejectCuoLe_returnsToWaitPoemName() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u932f\u4e86",
                isFinal = true,
                confidence = 0.95f,
                now = 30102L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u597d\u7684\uff0c\u8bf7\u518d\u8bf4\u4e00\u6b21\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceIsAffirmativeShiDe_confirmsPoem() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u662f\u7684",
                isFinal = true,
                confidence = 0.95f,
                now = 30105L
            )
        )

        assertEquals(SessionStateType.WAIT_DYNASTY_AUTHOR, output.state.type)
        assertEquals("\u9759\u591c\u601d", output.state.ctx.selectedPoem?.title)
        assertEquals(
            "\u5df2\u9009\u62e9\u300a\u9759\u591c\u601d\u300b\u3002\u8bf7\u8bf4\u51fa\u671d\u4ee3\u548c\u4f5c\u8005\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
        assertTrue(output.actions.any { it is SessionAction.FetchVariants })
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceIsTraditionalAffirmativeMeiCuo_confirmsPoem() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u6c92\u932f",
                isFinal = true,
                confidence = 0.95f,
                now = 301055L
            )
        )

        assertEquals(SessionStateType.WAIT_DYNASTY_AUTHOR, output.state.type)
        assertEquals("\u9759\u591c\u601d", output.state.ctx.selectedPoem?.title)
        assertEquals(
            "\u5df2\u9009\u62e9\u300a\u9759\u591c\u601d\u300b\u3002\u8bf7\u8bf4\u51fa\u671d\u4ee3\u548c\u4f5c\u8005\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
        assertTrue(output.actions.any { it is SessionAction.FetchVariants })
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceIsTraditionalAffirmativeQueRen_confirmsPoem() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u78ba\u8a8d",
                isFinal = true,
                confidence = 0.95f,
                now = 301056L
            )
        )

        assertEquals(SessionStateType.WAIT_DYNASTY_AUTHOR, output.state.type)
        assertEquals("\u9759\u591c\u601d", output.state.ctx.selectedPoem?.title)
        assertEquals(
            "\u5df2\u9009\u62e9\u300a\u9759\u591c\u601d\u300b\u3002\u8bf7\u8bf4\u51fa\u671d\u4ee3\u548c\u4f5c\u8005\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
        assertTrue(output.actions.any { it is SessionAction.FetchVariants })
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceIsQuestionShiMa_shouldNotAutoConfirm() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u662f\u5417",
                isFinal = true,
                confidence = 0.95f,
                now = 30106L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u6ca1\u6709\u786e\u8ba4\u5230\u9898\u76ee\uff0c\u8bf7\u518d\u8bf4\u4e00\u6b21\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceIsQuestionShiZheShouMa_shouldNotAutoConfirm() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u662f\u8fd9\u9996\u5417",
                isFinal = true,
                confidence = 0.95f,
                now = 30107L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u6ca1\u6709\u786e\u8ba4\u5230\u9898\u76ee\uff0c\u8bf7\u518d\u8bf4\u4e00\u6b21\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceHasQuestionToneNe_shouldNotAutoConfirm() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u662f\u8fd9\u9996\u5462",
                isFinal = true,
                confidence = 0.95f,
                now = 30109L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u6ca1\u6709\u786e\u8ba4\u5230\u9898\u76ee\uff0c\u8bf7\u518d\u8bf4\u4e00\u6b21\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceHasTraditionalQuestionToneMe_shouldNotAutoConfirm() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u662f\u9019\u9996\u9ebc",
                isFinal = true,
                confidence = 0.95f,
                now = 30110L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u6ca1\u6709\u786e\u8ba4\u5230\u9898\u76ee\uff0c\u8bf7\u518d\u8bf4\u4e00\u6b21\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun confirmPoemCandidate_whenUtteranceHasQuestionPunctuation_shouldNotAutoConfirm() {
        val state = stateOf(SessionStateType.CONFIRM_POEM_CANDIDATE) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u662f\u8fd9\u9996\uff1f",
                isFinal = true,
                confidence = 0.95f,
                now = 30108L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u6ca1\u6709\u786e\u8ba4\u5230\u9898\u76ee\uff0c\u8bf7\u518d\u8bf4\u4e00\u6b21\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun finished_whenUtteranceIsNegative_shouldExitInsteadOfRestarting() {
        val state = stateOf(SessionStateType.FINISHED) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u4e0d\u662f",
                isFinal = true,
                confidence = 0.95f,
                now = 3011L
            )
        )

        assertEquals(SessionStateType.EXIT, output.state.type)
        assertEquals(
            "\u597d\u7684\uff0c\u5df2\u7ed3\u675f\u4f1a\u8bdd\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun finished_whenTraditionalNextPoemIntent_shouldRestart() {
        val state = stateOf(SessionStateType.FINISHED) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u63db\u4e00\u9996",
                isFinal = true,
                confidence = 0.95f,
                now = 30115L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u597d\u7684\uff0c\u8bf7\u8bf4\u51fa\u4e0b\u4e00\u9996\u8bd7\u7684\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun finished_whenZaiLaiYiShouIntent_shouldRestart() {
        val state = stateOf(SessionStateType.FINISHED) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u518d\u6765\u4e00\u9996",
                isFinal = true,
                confidence = 0.95f,
                now = 30117L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u597d\u7684\uff0c\u8bf7\u8bf4\u51fa\u4e0b\u4e00\u9996\u8bd7\u7684\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun finished_whenTraditionalZaiLaiYiShouIntent_shouldRestart() {
        val state = stateOf(SessionStateType.FINISHED) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u518d\u4f86\u4e00\u9996",
                isFinal = true,
                confidence = 0.95f,
                now = 30119L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u597d\u7684\uff0c\u8bf7\u8bf4\u51fa\u4e0b\u4e00\u9996\u8bd7\u7684\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun finished_whenBareZaiLaiUtterance_shouldExitSession() {
        val state = stateOf(SessionStateType.FINISHED) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u518d\u6765",
                isFinal = true,
                confidence = 0.95f,
                now = 30118L
            )
        )

        assertEquals(SessionStateType.EXIT, output.state.type)
        assertEquals(
            "\u597d\u7684\uff0c\u5df2\u7ed3\u675f\u4f1a\u8bdd\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun reciting_whenTraditionalExitIntent_shouldExitSession() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.RECITING) {
            selectedPoem = poem
            currentLineIdx = 0
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u7d50\u675f",
                isFinal = true,
                confidence = 0.95f,
                now = 30116L
            )
        )

        assertEquals(SessionStateType.EXIT, output.state.type)
        assertEquals(
            "\u597d\u7684\uff0c\u5df2\u7ed3\u675f\u4f1a\u8bdd\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun waitPoemName_whenTraditionalRepeatIntent_shouldReplayPrompt() {
        val state = stateOf(SessionStateType.WAIT_POEM_NAME)

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u518d\u8aaa\u4e00\u904d",
                isFinal = true,
                confidence = 0.95f,
                now = 30117L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertEquals(
            "\u4f60\u597d\uff0c\u6b22\u8fce\u80cc\u8bf5\u8bd7\u8bcd\u3002\u8bf7\u8bf4\u51fa\u8bd7\u8bcd\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun waitPoemName_whenTraditionalRepeatIntentWithLai_shouldReplayPrompt() {
        val state = stateOf(SessionStateType.WAIT_POEM_NAME)

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u518d\u4f86\u4e00\u6b21",
                isFinal = true,
                confidence = 0.95f,
                now = 30118L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertEquals(
            "\u4f60\u597d\uff0c\u6b22\u8fce\u80cc\u8bf5\u8bd7\u8bcd\u3002\u8bf7\u8bf4\u51fa\u8bd7\u8bcd\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun waitDynastyAuthor_whenBothDynastyAndAuthorProvided_entersReciteReady() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.WAIT_DYNASTY_AUTHOR) {
            selectedPoem = poem
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u5510 \u674e\u767d",
                isFinal = true,
                confidence = 0.95f,
                now = 3012L
            )
        )

        assertEquals(SessionStateType.RECITE_READY, output.state.type)
        assertEquals(0, output.state.ctx.currentLineIdx)
        assertEquals(
            "\u597d\u7684\uff0c\u5f00\u59cb\u80cc\u8bf5\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun waitDynastyAuthor_whenOnlyAuthorProvided_staysWaiting() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.WAIT_DYNASTY_AUTHOR) {
            selectedPoem = poem
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u674e\u767d",
                isFinal = true,
                confidence = 0.95f,
                now = 3013L
            )
        )

        assertEquals(SessionStateType.WAIT_DYNASTY_AUTHOR, output.state.type)
        assertEquals(
            "\u8bf7\u518d\u8bf4\u4e00\u6b21\u671d\u4ee3\u548c\u4f5c\u8005\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun waitPoemName_tickWithoutBaseline_setsNoPoemIntentSince() {
        val state = stateOf(SessionStateType.WAIT_POEM_NAME) {
            noPoemIntentSince = null
        }

        val output = reducer.reduce(state, SessionEvent.Tick(now = 4000L))

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertEquals(4000L, output.state.ctx.noPoemIntentSince)
    }

    @Test
    fun waitPoemName_tickAfterTimeout_exitsSession() {
        val state = stateOf(SessionStateType.WAIT_POEM_NAME) {
            noPoemIntentSince = 1000L
        }

        val timeoutMs = AppConfig().timeouts.noPoemIntentExitSec * 1000L
        val output = reducer.reduce(state, SessionEvent.Tick(now = 1000L + timeoutMs + 1))

        assertEquals(SessionStateType.EXIT, output.state.type)
        val speech = output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()
        assertNotNull(speech)
        assertEquals(
            "\u6682\u65f6\u6ca1\u6709\u8bc6\u522b\u5230\u8bd7\u8bcd\u9898\u76ee\uff0c\u5148\u7ed3\u675f\u4f1a\u8bdd\u3002",
            speech!!.text
        )
    }

    @Test
    fun reciteReady_whenImmediateAsr_matchesFirstLineWithoutSwallowing() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.RECITE_READY) {
            selectedPoem = poem
            currentLineIdx = 0
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = poem.lines[0].text,
                isFinal = true,
                confidence = 0.95f,
                now = 4005L
            )
        )

        assertEquals(SessionStateType.RECITING, output.state.type)
        assertEquals(1, output.state.ctx.currentLineIdx)
        assertEquals(
            "\u5f88\u597d\uff0c\u4e0b\u4e00\u53e5\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun reciteReady_whenNoAsr_promptsLineOneAndEntersReciting() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.RECITE_READY) {
            selectedPoem = poem
            currentLineIdx = 0
        }

        val output = reducer.reduce(
            state,
            SessionEvent.Tick(now = 4006L)
        )

        assertEquals(SessionStateType.RECITING, output.state.type)
        assertEquals(
            "\u8bf7\u80cc\u8bf5\u7b2c\u4e00\u53e5\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
        assertTrue(output.actions.any { it is SessionAction.StartListening })
    }

    @Test
    fun reciting_whenUserAsksHint_entersHintGiven() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.RECITING) {
            selectedPoem = poem
            currentLineIdx = 0
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u63d0\u793a\u4e00\u4e0b",
                isFinal = true,
                confidence = 0.95f,
                now = 4010L
            )
        )

        assertEquals(SessionStateType.HINT_GIVEN, output.state.type)
        assertEquals(
            "\u63d0\u793a\uff1a\u5e8a\u524d\u2026",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun reciting_whenTraditionalHintIntent_entersHintGiven() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.RECITING) {
            selectedPoem = poem
            currentLineIdx = 0
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u7d66\u63d0\u793a",
                isFinal = true,
                confidence = 0.95f,
                now = 40101L
            )
        )

        assertEquals(SessionStateType.HINT_GIVEN, output.state.type)
        assertEquals(
            "\u63d0\u793a\uff1a\u5e8a\u524d\u2026",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun hintOffer_whenUserDoesNotAskHint_resumesRecitingWithoutHint() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.HINT_OFFER) {
            selectedPoem = poem
            currentLineIdx = 0
            hintOfferSince = 4000L
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u7ee7\u7eed",
                isFinal = true,
                confidence = 0.95f,
                now = 4011L
            )
        )

        assertEquals(SessionStateType.RECITING, output.state.type)
        assertEquals(
            "\u597d\u7684\uff0c\u7ee7\u7eed\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun hintOffer_whenUserAsksHint_entersHintGiven() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.HINT_OFFER) {
            selectedPoem = poem
            currentLineIdx = 0
            hintOfferSince = 4000L
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u7ed9\u63d0\u793a",
                isFinal = true,
                confidence = 0.95f,
                now = 4012L
            )
        )

        assertEquals(SessionStateType.HINT_GIVEN, output.state.type)
        assertEquals(
            "\u63d0\u793a\uff1a\u5e8a\u524d\u2026",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun finished_whenTraditionalNextPoemIntentWithLai_shouldRestart() {
        val state = stateOf(SessionStateType.FINISHED) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u518d\u4f86\u4e00\u9996",
                isFinal = true,
                confidence = 0.95f,
                now = 40120L
            )
        )

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertEquals(
            "\u597d\u7684\uff0c\u8bf7\u8bf4\u51fa\u4e0b\u4e00\u9996\u8bd7\u7684\u9898\u76ee\u3002",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
    }

    @Test
    fun finished_whenRepeatPhraseZaiLaiYiBian_shouldReplayFinishedPrompt() {
        val state = stateOf(SessionStateType.FINISHED) {
            selectedPoem = SamplePoems.poems.first()
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "\u518d\u4f86\u4e00\u904d",
                isFinal = true,
                confidence = 0.95f,
                now = 40121L
            )
        )

        assertEquals(SessionStateType.FINISHED, output.state.type)
        assertEquals(
            "\u8fd8\u8981\u518d\u6765\u4e00\u9996\u5417\uff1f",
            output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()?.text
        )
        assertTrue(output.actions.any { it is SessionAction.StartListening })
    }

    private fun stateOf(type: SessionStateType, mutate: SessionContext.() -> Unit = {}): SessionState {
        val base = buildInitialSession(
            config = AppConfig(),
            poems = SamplePoems.poems
        )
        base.ctx.mutate()
        return SessionState(type, base.ctx)
    }
}
