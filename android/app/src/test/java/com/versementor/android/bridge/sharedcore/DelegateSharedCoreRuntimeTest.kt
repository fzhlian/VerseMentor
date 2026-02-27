package com.versementor.android.bridge.sharedcore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DelegateSharedCoreRuntimeTest {
    @Before
    fun setUp() {
        resetHooks()
    }

    @After
    fun tearDown() {
        resetHooks()
    }

    @Test
    fun reduce_withHookResult_usesHookAndSkipsFallback() {
        val fallback = CountingRuntime("fallback-result")
        val runtime = DelegateSharedCoreRuntime(fallback = fallback)
        SharedCoreRuntimeHooks.registerReduceHook { _, _ -> "hook-result" }

        val output = runtime.reduce("{}", "{}")

        assertEquals("hook-result", output)
        assertEquals(0, fallback.calls)
        assertEquals("delegate-hook", runtime.mode())
    }

    @Test
    fun reduce_withHookNull_fallsBackToFallback() {
        val fallback = CountingRuntime("fallback-result")
        val runtime = DelegateSharedCoreRuntime(fallback = fallback)
        SharedCoreRuntimeHooks.registerReduceHook { _, _ -> null }

        val output = runtime.reduce("{}", "{}")

        assertEquals("fallback-result", output)
        assertEquals(1, fallback.calls)
        assertEquals("delegate-hook", runtime.mode())
    }

    @Test
    fun reduce_withoutHook_usesFallbackAndReportsMissingMode() {
        val fallback = CountingRuntime("fallback-result")
        val runtime = DelegateSharedCoreRuntime(fallback = fallback)

        val output = runtime.reduce("{}", "{}")

        assertEquals("fallback-result", output)
        assertEquals(1, fallback.calls)
        assertEquals("delegate-missing", runtime.mode())
    }

    @Test
    fun hookRegistry_clearWithStaleToken_returnsFalse() {
        val tokenA = SharedCoreRuntimeHooks.registerReduceHook { _, _ -> "a" }
        val tokenB = SharedCoreRuntimeHooks.registerReduceHook { _, _ -> "b" }

        assertFalse(SharedCoreRuntimeHooks.clearReduceHook(tokenA))
        assertTrue(SharedCoreRuntimeHooks.clearReduceHook(tokenB))
        assertFalse(SharedCoreRuntimeHooks.hasReduceHook())
    }

    private fun resetHooks() {
        val token = SharedCoreRuntimeHooks.registerReduceHook { _, _ -> null }
        SharedCoreRuntimeHooks.clearReduceHook(token)
    }

    private class CountingRuntime(private val response: String?) : SharedCoreRuntime {
        var calls: Int = 0
            private set

        override fun reduce(stateJson: String, eventJson: String): String? {
            calls += 1
            return response
        }
    }
}
