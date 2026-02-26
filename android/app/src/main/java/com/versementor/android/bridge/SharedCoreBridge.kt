package com.versementor.android.bridge

import com.versementor.android.bridge.sharedcore.SharedCoreCodec
import com.versementor.android.bridge.sharedcore.SharedCoreRuntime
import com.versementor.android.bridge.sharedcore.StubSharedCoreRuntime
import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionOutput
import com.versementor.android.session.SessionState

/**
 * Bridge entry for shared-core runtime integration.
 * Current default runtime is a stub and will gracefully fall back to local Kotlin reducer.
 */
class SharedCoreBridge(
    private val runtime: SharedCoreRuntime = StubSharedCoreRuntime(),
    private val codec: SharedCoreCodec = SharedCoreCodec(),
    private val fallback: SessionBridge = LocalKotlinBridge()
) : SessionBridge {
    override fun reduce(state: SessionState, event: SessionEvent): SessionOutput {
        val output = try {
            val stateJson = codec.encodeState(state)
            val eventJson = codec.encodeEvent(event)
            val responseJson = runtime.reduce(stateJson, eventJson)
            responseJson?.let { codec.decodeOutput(it) }
        } catch (_: Throwable) {
            null
        }
        return output ?: fallback.reduce(state, event)
    }
}
