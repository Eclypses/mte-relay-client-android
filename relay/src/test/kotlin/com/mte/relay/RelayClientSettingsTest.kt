package com.mte.relay

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class RelayClientSettingsTest {

    @Test
    fun `defaults match approved phase one values`() {
        val settings = RelayClientSettings.defaults()

        assertEquals(5, settings.minPairs)
        assertEquals(8, settings.basePairs)
        assertEquals(15, settings.maxPairs)
        assertEquals(300, settings.keepAliveIntervalSeconds)
        assertEquals(1.0, settings.acquisitionWaitTime)
    }

    @Test
    fun `builder preserves defaults while allowing targeted overrides`() {
        val settings = RelayClientSettings.defaults()
            .buildUpon()
            .setKeepAliveIntervalSeconds(600)
            .build()

        assertEquals(5, settings.minPairs)
        assertEquals(8, settings.basePairs)
        assertEquals(15, settings.maxPairs)
        assertEquals(600, settings.keepAliveIntervalSeconds)
        assertEquals(1.0, settings.acquisitionWaitTime)
    }

    @Test
    fun `validation rejects invalid pair ranges`() {
        assertFailsWith<IllegalArgumentException> {
            RelayClientSettings(0, 8, 15, 300, 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            RelayClientSettings(5, 4, 15, 300, 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            RelayClientSettings(5, 8, 7, 300, 1.0)
        }
    }

    @Test
    fun `validation rejects invalid keep alive and acquisition wait values`() {
        assertFailsWith<IllegalArgumentException> {
            RelayClientSettings(5, 8, 15, 59, 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            RelayClientSettings(5, 8, 15, 601, 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            RelayClientSettings(5, 8, 15, 300, -1.0)
        }
    }
}