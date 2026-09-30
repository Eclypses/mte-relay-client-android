package com.eclypses.relay.session

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class RelaySessionManagerTest {

    @Test
    fun `getOrCreate returns same session per origin`() {
        val manager = RelaySessionManager()

        val first = manager.getOrCreate("https://relay-a.example")
        val second = manager.getOrCreate("https://relay-a.example")

        assertSame(first, second)
        assertEquals(RelaySessionState.INITIALIZING, first.state)
    }

    @Test
    fun `state machine allows expected transitions`() {
        val manager = RelaySessionManager()
        val origin = "https://relay-a.example"

        assertTrue(manager.transition(origin, RelaySessionState.READY))
        assertTrue(manager.transition(origin, RelaySessionState.REPAIRING))
        assertTrue(manager.transition(origin, RelaySessionState.READY))
        assertTrue(manager.transition(origin, RelaySessionState.FAILED))
        assertTrue(manager.transition(origin, RelaySessionState.INITIALIZING))

        val snapshot = assertNotNull(manager.snapshot(origin))
        assertEquals(RelaySessionState.INITIALIZING, snapshot.state)
        assertEquals(5, snapshot.revision)
    }

    @Test
    fun `state machine rejects invalid transitions`() {
        val manager = RelaySessionManager()
        val origin = "https://relay-a.example"

        assertFalse(manager.transition(origin, RelaySessionState.REPAIRING))
        assertTrue(manager.transition(origin, RelaySessionState.FAILED))
        assertFalse(manager.transition(origin, RelaySessionState.READY))

        val snapshot = assertNotNull(manager.snapshot(origin))
        assertEquals(RelaySessionState.FAILED, snapshot.state)
        assertEquals(1, snapshot.revision)
    }

    @Test
    fun `session state is isolated per origin`() {
        val manager = RelaySessionManager()

        manager.transition("https://relay-a.example", RelaySessionState.READY)
        manager.transition("https://relay-b.example", RelaySessionState.READY)
        manager.transition("https://relay-b.example", RelaySessionState.REPAIRING)

        val snapshots = manager.allSnapshots()
        assertEquals(2, snapshots.size)
        assertEquals("https://relay-a.example", snapshots[0].origin)
        assertEquals(RelaySessionState.READY, snapshots[0].state)
        assertEquals("https://relay-b.example", snapshots[1].origin)
        assertEquals(RelaySessionState.REPAIRING, snapshots[1].state)
    }

    @Test
    fun `leased runtime pairs are excluded until released`() {
        val manager = RelaySessionManager()
        val session = manager.getOrCreate("https://relay-a.example")
        session.setRuntimePairs(
            listOf(
                fakeRuntimePair("pair-1"),
                fakeRuntimePair("pair-2"),
            ),
        )

        val firstLease = assertNotNull(session.leaseRuntimePairRoundRobin())
        val secondLease = assertNotNull(session.leaseRuntimePairRoundRobin())
        val thirdLease = session.leaseRuntimePairRoundRobin()

        assertFalse(firstLease.pairId == secondLease.pairId)
        assertTrue(session.isRuntimePairLeased(firstLease.pairId))
        assertTrue(session.isRuntimePairLeased(secondLease.pairId))
        assertNull(thirdLease)

        assertTrue(session.releaseLeasedRuntimePair(firstLease.pairId))
        val leasedAgain = assertNotNull(session.leaseRuntimePairRoundRobin())
        assertEquals(firstLease.pairId, leasedAgain.pairId)
    }

    private fun fakeRuntimePair(pairId: String): RelayRuntimePair {
        val id = testPairId(pairId)
        return object : RelayRuntimePair {
            override val pairId: com.eclypses.mte.wire.PairId = id
            override fun encode(payload: ByteArray): ByteArray = payload
            override fun decode(payload: ByteArray): ByteArray = payload
        }
    }
}
