package com.versementor.android.bridge

import com.versementor.android.bridge.sharedcore.SharedCoreCodec
import com.versementor.android.bridge.sharedcore.DelegateSharedCoreRuntime
import com.versementor.android.bridge.sharedcore.SharedCoreRuntime
import com.versementor.android.session.SessionEvent
import com.versementor.android.session.SessionOutput
import com.versementor.android.session.SessionState

data class SharedCoreBridgeTrace(
    val path: String,
    val reason: String
)

/**
 * Bridge entry for shared-core runtime integration.
 * Default runtime delegates to process hooks and gracefully falls back to local Kotlin reducer.
 */
class SharedCoreBridge(
    private val runtime: SharedCoreRuntime = DelegateSharedCoreRuntime(),
    private val codec: SharedCoreCodec = SharedCoreCodec(),
    private val fallback: SessionBridge = LocalKotlinBridge()
) : SessionBridge {
    private var lastTrace: SharedCoreBridgeTrace = SharedCoreBridgeTrace(path = "local", reason = "init")

    fun getLastTrace(): SharedCoreBridgeTrace = lastTrace

    override fun reduce(state: SessionState, event: SessionEvent): SessionOutput {
        var fallbackReason: String? = null
        val output = try {
            val stateJson = codec.encodeState(state)
            val eventJson = codec.encodeEvent(event)
            val responseJson = runtime.reduce(stateJson, eventJson)
            if (responseJson == null) {
                fallbackReason = "runtime-null:${runtime.mode()}"
                null
            } else {
                val decodedResult = codec.decodeOutputWithReason(responseJson)
                val decoded = decodedResult.output
                if (decoded == null) {
                    val reason = decodedResult.reason ?: "decode-null"
                    fallbackReason = "runtime-$reason:${runtime.mode()}"
                } else {
                    lastTrace = SharedCoreBridgeTrace(path = "runtime", reason = runtime.mode())
                }
                decoded
            }
        } catch (_: Throwable) {
            fallbackReason = "runtime-throw:${runtime.mode()}"
            null
        }
        if (output != null) {
            return output
        }
        lastTrace = SharedCoreBridgeTrace(path = "local", reason = fallbackReason ?: "runtime-fallback")
        return fallback.reduce(state, event)
    }
}
