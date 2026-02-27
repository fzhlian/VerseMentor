package com.versementor.android.bridge

import com.versementor.android.bridge.sharedcore.LocalBridgeSharedCoreRuntime
import com.versementor.android.bridge.sharedcore.SharedCoreCodec
import com.versementor.android.bridge.sharedcore.SharedCoreRuntime
import com.versementor.android.session.AppConfig
import com.versementor.android.session.SamplePoems
import com.versementor.android.session.SessionAction
import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionOutput
import com.versementor.android.session.SessionStateType
import com.versementor.android.session.buildInitialSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCoreBridgeTest {
    private val codec = SharedCoreCodec()

    @Test
    fun reduce_runtimeSuccess_recordsRuntimeTrace() {
        val bridge = SharedCoreBridge(
            runtime = LocalBridgeSharedCoreRuntime(),
            fallback = LocalKotlinBridge()
        )

        val output = bridge.reduce(initialState(), SessionEvent.UserUiStart(now = 1000L))

        assertStartOutput(output)
        val trace = bridge.getLastTrace()
        assertEquals("runtime", trace.path)
        assertEquals("local-bridge", trace.reason)
    }

    @Test
    fun reduce_runtimeNull_fallsBackToLocalWithReason() {
        val bridge = SharedCoreBridge(
            runtime = FakeRuntime(mode = "fake-null") { _, _ -> null },
            fallback = LocalKotlinBridge()
        )

        val output = bridge.reduce(initialState(), SessionEvent.UserUiStart(now = 1000L))

        assertStartOutput(output)
        val trace = bridge.getLastTrace()
        assertEquals("local", trace.path)
        assertEquals("runtime-null:fake-null", trace.reason)
    }

    @Test
    fun reduce_runtimeInvalidState_fallsBackToLocalWithStateReason() {
        val validOutput = LocalBridgeSharedCoreRuntime().reduce(
            stateJson = codec.encodeState(initialState()),
            eventJson = codec.encodeEvent(SessionEvent.UserUiStart(now = 1000L))
        ) ?: throw IllegalStateException("expected valid runtime output")
        val invalidStateOutput = validOutput.replace(
            "\"WAIT_POEM_NAME\"",
            "\"BAD_STATE_TYPE\""
        )
        val bridge = SharedCoreBridge(
            runtime = FakeRuntime(mode = "fake-bad-state") { _, _ -> invalidStateOutput },
            fallback = LocalKotlinBridge()
        )

        val output = bridge.reduce(initialState(), SessionEvent.UserUiStart(now = 1000L))

        assertStartOutput(output)
        val trace = bridge.getLastTrace()
        assertEquals("local", trace.path)
        assertEquals("runtime-state-invalid:fake-bad-state", trace.reason)
    }

    @Test
    fun reduce_runtimeInvalidActions_fallsBackToLocalWithActionsReason() {
        val validOutput = LocalBridgeSharedCoreRuntime().reduce(
            stateJson = codec.encodeState(initialState()),
            eventJson = codec.encodeEvent(SessionEvent.UserUiStart(now = 1000L))
        ) ?: throw IllegalStateException("expected valid runtime output")
        val invalidActionsOutput = validOutput.replace(
            "\"START_LISTENING\"",
            "\"BAD_ACTION\""
        )
        val bridge = SharedCoreBridge(
            runtime = FakeRuntime(mode = "fake-bad-actions") { _, _ -> invalidActionsOutput },
            fallback = LocalKotlinBridge()
        )

        val output = bridge.reduce(initialState(), SessionEvent.UserUiStart(now = 1000L))

        assertStartOutput(output)
        val trace = bridge.getLastTrace()
        assertEquals("local", trace.path)
        assertEquals("runtime-actions-invalid:fake-bad-actions", trace.reason)
    }

    @Test
    fun reduce_runtimeMissingActions_fallsBackToLocalWithActionsReason() {
        val validOutput = LocalBridgeSharedCoreRuntime().reduce(
            stateJson = codec.encodeState(initialState()),
            eventJson = codec.encodeEvent(SessionEvent.UserUiStart(now = 1000L))
        ) ?: throw IllegalStateException("expected valid runtime output")
        val missingActionsOutput = validOutput.replace(
            "\"actions\"",
            "\"__actions\""
        )
        val bridge = SharedCoreBridge(
            runtime = FakeRuntime(mode = "fake-missing-actions") { _, _ -> missingActionsOutput },
            fallback = LocalKotlinBridge()
        )

        val output = bridge.reduce(initialState(), SessionEvent.UserUiStart(now = 1000L))

        assertStartOutput(output)
        val trace = bridge.getLastTrace()
        assertEquals("local", trace.path)
        assertEquals("runtime-actions-invalid:fake-missing-actions", trace.reason)
    }

    @Test
    fun reduce_runtimeMissingState_fallsBackToLocalWithStateReason() {
        val validOutput = LocalBridgeSharedCoreRuntime().reduce(
            stateJson = codec.encodeState(initialState()),
            eventJson = codec.encodeEvent(SessionEvent.UserUiStart(now = 1000L))
        ) ?: throw IllegalStateException("expected valid runtime output")
        val missingStateOutput = validOutput.replace(
            "\"state\"",
            "\"__state\""
        )
        val bridge = SharedCoreBridge(
            runtime = FakeRuntime(mode = "fake-missing-state") { _, _ -> missingStateOutput },
            fallback = LocalKotlinBridge()
        )

        val output = bridge.reduce(initialState(), SessionEvent.UserUiStart(now = 1000L))

        assertStartOutput(output)
        val trace = bridge.getLastTrace()
        assertEquals("local", trace.path)
        assertEquals("runtime-state-invalid:fake-missing-state", trace.reason)
    }

    @Test
    fun reduce_runtimeInvalidJson_fallsBackToLocalWithInvalidJsonReason() {
        val bridge = SharedCoreBridge(
            runtime = FakeRuntime(mode = "fake-invalid-json") { _, _ -> "{bad json" },
            fallback = LocalKotlinBridge()
        )

        val output = bridge.reduce(initialState(), SessionEvent.UserUiStart(now = 1000L))

        assertStartOutput(output)
        val trace = bridge.getLastTrace()
        assertEquals("local", trace.path)
        assertEquals("runtime-invalid-json:fake-invalid-json", trace.reason)
    }

    @Test
    fun reduce_runtimeThrows_fallsBackToLocalWithThrowReason() {
        val bridge = SharedCoreBridge(
            runtime = FakeRuntime(mode = "fake-throw") { _, _ ->
                throw IllegalStateException("boom")
            },
            fallback = LocalKotlinBridge()
        )

        val output = bridge.reduce(initialState(), SessionEvent.UserUiStart(now = 1000L))

        assertStartOutput(output)
        val trace = bridge.getLastTrace()
        assertEquals("local", trace.path)
        assertEquals("runtime-throw:fake-throw", trace.reason)
    }

    private fun initialState() = buildInitialSession(
        config = AppConfig(),
        poems = SamplePoems.poems
    )

    private fun assertStartOutput(output: SessionOutput) {
        assertEquals(SessionStateType.WAIT_POEM_NAME, output.state.type)
        assertTrue(output.actions.any { it is SessionAction.Speak })
        assertTrue(output.actions.any { it is SessionAction.StartListening })
    }

    private class FakeRuntime(
        private val mode: String,
        private val reducer: (stateJson: String, eventJson: String) -> String?
    ) : SharedCoreRuntime {
        override fun reduce(stateJson: String, eventJson: String): String? {
            return reducer(stateJson, eventJson)
        }

        override fun mode(): String = mode
    }
}
