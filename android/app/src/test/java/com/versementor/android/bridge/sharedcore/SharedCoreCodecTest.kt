package com.versementor.android.bridge.sharedcore

import com.versementor.android.session.AppConfig
import com.versementor.android.session.SamplePoems
import com.versementor.android.session.SessionAction
import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionOutput
import com.versementor.android.session.SessionStateType
import com.versementor.android.session.buildInitialSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCoreCodecTest {
    private val codec = SharedCoreCodec()

    @Test
    fun encodeDecodeEvent_userUiStart_preservesNow() {
        val raw = codec.encodeEvent(SessionEvent.UserUiStart(now = 1234L))

        val decoded = codec.decodeEvent(raw)

        assertTrue(decoded is SessionEvent.UserUiStart)
        assertEquals(1234L, (decoded as SessionEvent.UserUiStart).now)
    }

    @Test
    fun encodeDecodeEvent_userAsr_preservesConfidenceAndNow() {
        val raw = codec.encodeEvent(
            SessionEvent.UserAsr(
                text = "jing ye si",
                isFinal = true,
                confidence = 0.87f,
                now = 5678L
            )
        )

        val decoded = codec.decodeEvent(raw)

        assertTrue(decoded is SessionEvent.UserAsr)
        val asr = decoded as SessionEvent.UserAsr
        assertEquals("jing ye si", asr.text)
        assertTrue(asr.isFinal)
        assertEquals(0.87f, asr.confidence)
        assertEquals(5678L, asr.now)
    }

    @Test
    fun decodeEvent_userAsrWithoutIsFinal_returnsNull() {
        val decoded = codec.decodeEvent("""{"type":"USER_ASR","text":"jing ye si"}""")

        assertNull(decoded)
    }

    @Test
    fun decodeEvent_missingType_returnsNull() {
        val decoded = codec.decodeEvent("""{}""")

        assertNull(decoded)
    }

    @Test
    fun decodeState_missingType_returnsNull() {
        val decoded = codec.decodeState("""{"ctx":{}}""")

        assertNull(decoded)
    }

    @Test
    fun decodeState_missingCtx_returnsNull() {
        val decoded = codec.decodeState("""{"type":"WAIT_POEM_NAME"}""")

        assertNull(decoded)
    }

    @Test
    fun decodeOutput_unknownAction_returnsNull() {
        val stateJson = codec.encodeState(
            buildInitialSession(
                config = AppConfig(),
                poems = SamplePoems.poems
            )
        )
        val raw = """{"state":$stateJson,"actions":[{"type":"UNKNOWN_ACTION"}]}"""

        val decoded = codec.decodeOutput(raw)

        assertNull(decoded)
    }

    @Test
    fun encodeDecodeOutput_roundTripPreservesStateAndActions() {
        val initial = buildInitialSession(
            config = AppConfig(),
            poems = SamplePoems.poems
        )
        val output = SessionOutput(
            state = initial.copy(type = SessionStateType.WAIT_POEM_NAME),
            actions = listOf(
                SessionAction.Speak("hello"),
                SessionAction.StartListening
            )
        )

        val raw = codec.encodeOutput(output)
        val decoded = codec.decodeOutput(raw)

        assertNotNull(decoded)
        assertEquals(SessionStateType.WAIT_POEM_NAME, decoded!!.state.type)
        assertEquals(2, decoded.actions.size)
        assertTrue(decoded.actions[0] is SessionAction.Speak)
        assertTrue(decoded.actions[1] is SessionAction.StartListening)
    }

    @Test
    fun decodeOutputWithReason_invalidJson_reportsReason() {
        val result = codec.decodeOutputWithReason("{bad json")

        assertNull(result.output)
        assertEquals("invalid-json", result.reason)
    }

    @Test
    fun decodeOutputWithReason_invalidState_reportsReason() {
        val raw = """{"state":{"type":"BAD_STATE","ctx":{"config":{},"poems":[]}},"actions":[]}"""

        val result = codec.decodeOutputWithReason(raw)

        assertNull(result.output)
        assertEquals("state-invalid", result.reason)
    }

    @Test
    fun decodeOutputWithReason_invalidActions_reportsReason() {
        val stateJson = codec.encodeState(
            buildInitialSession(
                config = AppConfig(),
                poems = SamplePoems.poems
            )
        )
        val raw = """{"state":$stateJson,"actions":[{"type":"BAD_ACTION"}]}"""

        val result = codec.decodeOutputWithReason(raw)

        assertNull(result.output)
        assertEquals("actions-invalid", result.reason)
    }

    @Test
    fun decodeOutputWithReason_missingStateCtx_reportsStateInvalid() {
        val result = codec.decodeOutputWithReason("""{"state":{"type":"WAIT_POEM_NAME"},"actions":[]}""")

        assertNull(result.output)
        assertEquals("state-invalid", result.reason)
    }

    @Test
    fun decodeOutputWithReason_missingState_reportsStateInvalid() {
        val result = codec.decodeOutputWithReason("""{"actions":[]}""")

        assertNull(result.output)
        assertEquals("state-invalid", result.reason)
    }

    @Test
    fun decodeOutputWithReason_actionMissingType_reportsActionsInvalid() {
        val stateJson = codec.encodeState(
            buildInitialSession(
                config = AppConfig(),
                poems = SamplePoems.poems
            )
        )
        val raw = """{"state":$stateJson,"actions":[{}]}"""

        val result = codec.decodeOutputWithReason(raw)

        assertNull(result.output)
        assertEquals("actions-invalid", result.reason)
    }

    @Test
    fun decodeOutputWithReason_missingActions_reportsActionsInvalid() {
        val stateJson = codec.encodeState(
            buildInitialSession(
                config = AppConfig(),
                poems = SamplePoems.poems
            )
        )
        val raw = """{"state":$stateJson}"""

        val result = codec.decodeOutputWithReason(raw)

        assertNull(result.output)
        assertEquals("actions-invalid", result.reason)
    }
}
