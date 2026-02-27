package com.versementor.android.bridge.sharedcore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SharedCoreRuntimeHooksTest {
    @Before
    fun setUp() {
        resetHooks()
    }

    @After
    fun tearDown() {
        resetHooks()
    }

    @Test
    fun registerIfAbsent_whenEmpty_registersAndClearsByToken() {
        assertFalse(SharedCoreRuntimeHooks.hasReduceHook())

        val token = SharedCoreRuntimeHooks.registerReduceHookIfAbsent { _, _ -> "hook-a" }

        assertNotNull(token)
        assertTrue(SharedCoreRuntimeHooks.hasReduceHook())
        assertEquals("hook-a", SharedCoreRuntimeHooks.reduce("{}", "{}"))
        assertTrue(SharedCoreRuntimeHooks.clearReduceHook(token!!))
        assertFalse(SharedCoreRuntimeHooks.hasReduceHook())
    }

    @Test
    fun registerIfAbsent_whenOccupied_returnsNullAndKeepsCurrentHook() {
        val token = SharedCoreRuntimeHooks.registerReduceHook { _, _ -> "hook-a" }

        val skipped = SharedCoreRuntimeHooks.registerReduceHookIfAbsent { _, _ -> "hook-b" }

        assertNull(skipped)
        assertEquals("hook-a", SharedCoreRuntimeHooks.reduce("{}", "{}"))
        assertTrue(SharedCoreRuntimeHooks.clearReduceHook(token))
        assertFalse(SharedCoreRuntimeHooks.hasReduceHook())
    }

    @Test
    fun clear_withStaleToken_keepsLatestHook() {
        val tokenA = SharedCoreRuntimeHooks.registerReduceHook { _, _ -> "hook-a" }
        val tokenB = SharedCoreRuntimeHooks.registerReduceHook { _, _ -> "hook-b" }

        assertFalse(SharedCoreRuntimeHooks.clearReduceHook(tokenA))
        assertTrue(SharedCoreRuntimeHooks.hasReduceHook())
        assertEquals("hook-b", SharedCoreRuntimeHooks.reduce("{}", "{}"))
        assertTrue(SharedCoreRuntimeHooks.clearReduceHook(tokenB))
        assertFalse(SharedCoreRuntimeHooks.hasReduceHook())
    }

    private fun resetHooks() {
        val token = SharedCoreRuntimeHooks.registerReduceHook { _, _ -> null }
        SharedCoreRuntimeHooks.clearReduceHook(token)
    }
}
