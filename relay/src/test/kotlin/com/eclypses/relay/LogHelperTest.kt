package com.eclypses.relay

import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for the LogHelper facade. Focus is the level gating and the lazy
 * `Supplier` overloads — the behaviour that keeps disabled levels free. LogHelper
 * holds global static state, so each test restores it.
 */
class LogHelperTest {

    @AfterEach
    fun restore() {
        LogHelper.setEnabled(true)
    }

    @Test
    fun `setEnabled toggles isEnabled`() {
        LogHelper.setEnabled(false)
        assertFalse(LogHelper.isEnabled())
        LogHelper.setEnabled(true)
        assertTrue(LogHelper.isEnabled())
    }

    @Test
    fun `disabled logging never invokes the message supplier`() {
        LogHelper.setEnabled(false)
        var invocations = 0
        val supplier = Supplier { invocations++; "expensive message" }

        LogHelper.trace("Test", supplier)
        LogHelper.debug("Test", supplier)
        LogHelper.info("Test", supplier)
        LogHelper.warn("Test", supplier)
        LogHelper.error("Test", supplier)

        assertEquals(0, invocations, "the message must not be built when logging is disabled")
    }

    @Test
    fun `enabled error logging invokes the message supplier once`() {
        LogHelper.setEnabled(true)
        var invocations = 0
        // error is enabled at any reasonable root level, so the message is built.
        LogHelper.error("Test", Supplier { invocations++; "boom" })
        assertEquals(1, invocations)
    }

    @Test
    fun `level gates report false when logging is disabled`() {
        LogHelper.setEnabled(false)
        assertFalse(LogHelper.isTraceEnabled("Test"))
        assertFalse(LogHelper.isDebugEnabled("Test"))
    }

    @Test
    fun `eager overloads run without throwing when enabled`() {
        LogHelper.setEnabled(true)
        LogHelper.trace("Test", "t")
        LogHelper.debug("Test", "d")
        LogHelper.info("Test", "i")
        LogHelper.warn("Test", "w")
        LogHelper.error("Test", "e")
        LogHelper.error("Test", "e", RuntimeException("boom"))
        assertTrue(true)
    }
}
