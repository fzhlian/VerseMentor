package com.versementor.android.bridge.sharedcore

import com.versementor.android.session.SessionReducer
import java.util.concurrent.atomic.AtomicLong

/**
 * Runtime abstraction for invoking shared-core reducer logic using JSON envelopes.
 * Concrete engine can be JS runtime, IPC process, or native bridge.
 */
interface SharedCoreRuntime {
    fun reduce(stateJson: String, eventJson: String): String?
    fun mode(): String = "runtime"
}

typealias SharedCoreReduceHook = (stateJson: String, eventJson: String) -> String?

/**
 * Process-wide hook registry so shell code can plug in a real shared-core reducer bridge
 * without adding direct compile-time dependency in this module.
 */
object SharedCoreRuntimeHooks {
    private data class ReduceHookRegistration(
        val token: Long,
        val hook: SharedCoreReduceHook
    )

    private val nextToken = AtomicLong(1L)

    @Volatile
    private var registration: ReduceHookRegistration? = null

    fun registerReduceHook(hook: SharedCoreReduceHook): Long {
        synchronized(this) {
            val next = ReduceHookRegistration(
                token = nextToken.getAndIncrement(),
                hook = hook
            )
            registration = next
            return next.token
        }
    }

    fun registerReduceHookIfAbsent(hook: SharedCoreReduceHook): Long? {
        synchronized(this) {
            if (registration != null) return null
            val next = ReduceHookRegistration(
                token = nextToken.getAndIncrement(),
                hook = hook
            )
            registration = next
            return next.token
        }
    }

    fun clearReduceHook(token: Long): Boolean {
        synchronized(this) {
            val current = registration ?: return false
            if (current.token != token) return false
            registration = null
            return true
        }
    }

    fun hasReduceHook(): Boolean = registration != null

    internal fun reduce(stateJson: String, eventJson: String): String? {
        return registration?.hook?.invoke(stateJson, eventJson)
    }
}

/**
 * Runtime that first tries registered hooks, then falls back to provided runtime.
 */
class DelegateSharedCoreRuntime(
    private val fallback: SharedCoreRuntime = StubSharedCoreRuntime()
) : SharedCoreRuntime {
    override fun reduce(stateJson: String, eventJson: String): String? {
        val hooked = SharedCoreRuntimeHooks.reduce(stateJson, eventJson)
        if (hooked != null) return hooked
        return fallback.reduce(stateJson, eventJson)
    }

    override fun mode(): String {
        return if (SharedCoreRuntimeHooks.hasReduceHook()) "delegate-hook" else "delegate-missing"
    }
}

/**
 * Local bridge runtime that consumes/produces shared-core JSON envelopes
 * but executes reducer logic with local Kotlin FSM.
 */
class LocalBridgeSharedCoreRuntime(
    private val codec: SharedCoreCodec = SharedCoreCodec(),
    private val reducer: SessionReducer = SessionReducer()
) : SharedCoreRuntime {
    override fun reduce(stateJson: String, eventJson: String): String? {
        val state = codec.decodeState(stateJson) ?: return null
        val event = codec.decodeEvent(eventJson) ?: return null
        val output = reducer.reduce(state, event)
        return codec.encodeOutput(output)
    }

    override fun mode(): String = "local-bridge"
}

class StubSharedCoreRuntime : SharedCoreRuntime {
    override fun reduce(stateJson: String, eventJson: String): String? = null

    override fun mode(): String = "stub"
}
