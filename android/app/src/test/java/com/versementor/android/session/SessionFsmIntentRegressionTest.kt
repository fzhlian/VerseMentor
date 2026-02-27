package com.versementor.android.session

import com.versementor.android.storage.PoemLineVariant
import com.versementor.android.storage.PoemVariants
import com.versementor.android.storage.PoemVariantsCacheEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFsmIntentRegressionTest {
    private val reducer = SessionReducer()

    @Test
    fun userUiStop_fromActiveState_exitsAndStopsListening() {
        val state = stateOf(SessionStateType.RECITING) {
            selectedPoem = SamplePoems.poems.first()
            currentLineIdx = 1
        }

        val output = reducer.reduce(state, SessionEvent.UserUiStop)

        assertEquals(SessionStateType.EXIT, output.state.type)
        assertTrue(output.actions.any { it is SessionAction.StopListening })
    }

    @Test
    fun userUiStart_fromExit_resetsSessionContext() {
        val state = stateOf(SessionStateType.EXIT) {
            selectedPoem = SamplePoems.poems.first()
            variantsCacheEntry = PoemVariantsCacheEntry(
                poemId = "p1",
                variants = PoemVariants(
                    poemId = "p1",
                    lines = listOf(PoemLineVariant(lineIndex = 0, variants = listOf("床前看月光"))),
                    sourceTags = listOf("online")
                ),
                cachedAt = 100L,
                expiresAt = 200L
            )
            currentLineIdx = 2
            noPoemIntentSince = 99L
            hintOfferSince = 88L
        }

        val output = reducer.reduce(state, SessionEvent.UserUiStart(now = 1234L))

        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertNull(output.state.ctx.selectedPoem)
        assertNull(output.state.ctx.variantsCacheEntry)
        assertEquals(0, output.state.ctx.currentLineIdx)
        assertNull(output.state.ctx.hintOfferSince)
        assertEquals(1234L, output.state.ctx.noPoemIntentSince)
        assertTrue(output.actions.any { it is SessionAction.StartListening })
        val speech = output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()
        assertNotNull(speech)
        assertEquals("你好，欢迎背诵诗词。请说出诗词题目。", speech!!.text)
    }

    @Test
    fun userAsrExitIntent_inActiveState_exitsAndStopsListening() {
        val state = stateOf(SessionStateType.WAIT_POEM_NAME)

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "我想退出",
                isFinal = true,
                confidence = 0.9f,
                now = 2000L
            )
        )

        assertEquals(SessionStateType.EXIT, output.state.type)
        assertTrue(output.actions.any { it is SessionAction.StopListening })
        val speech = output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()
        assertNotNull(speech)
        assertEquals("好的，已结束会话。", speech!!.text)
    }

    @Test
    fun userAsrRepeatIntent_inWaitDynastyAuthor_replaysPromptAndKeepsState() {
        val poem = SamplePoems.poems.first()
        val state = stateOf(SessionStateType.WAIT_DYNASTY_AUTHOR) {
            selectedPoem = poem
        }

        val output = reducer.reduce(
            state,
            SessionEvent.UserAsr(
                text = "再说一遍",
                isFinal = true,
                confidence = 0.95f,
                now = 2100L
            )
        )

        assertEquals(SessionStateType.WAIT_DYNASTY_AUTHOR, output.state.type)
        assertTrue(output.actions.any { it is SessionAction.StartListening })
        val speech = output.actions.filterIsInstance<SessionAction.Speak>().firstOrNull()
        assertNotNull(speech)
        assertEquals("已选择《${poem.title}》。请说出朝代和作者。", speech!!.text)
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
