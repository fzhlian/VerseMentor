package com.versementor.android.bridge.sharedcore

/**
 * Runtime abstraction for invoking shared-core reducer logic using JSON envelopes.
 * Concrete engine can be JS runtime, IPC process, or native bridge.
 */
interface SharedCoreRuntime {
    fun reduce(stateJson: String, eventJson: String): String?
}

class StubSharedCoreRuntime : SharedCoreRuntime {
    override fun reduce(stateJson: String, eventJson: String): String? = null
}
