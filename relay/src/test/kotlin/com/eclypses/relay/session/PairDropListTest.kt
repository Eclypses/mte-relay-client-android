package com.eclypses.relay.session

import com.eclypses.mte.wire.PairId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The accounting that keeps a client under `maxPairsPerClient`.
 *
 * Section 11 made the client authoritative: one keep-alive refreshes *every* pair the relay
 * holds for this client and deletes exactly the ones named in `drop`. Nothing is pruned behind
 * a live client's back, which is the guarantee the mechanism exists for -- and the reason the
 * client now owes the relay an accounting for what it discards.
 *
 * A client that never names a discarded pair grows its footprint until the relay refuses to
 * pair at all with 490 `pair_limit`. Measured on iOS before this existed: roughly forty
 * launches of the demo app, five pairs each, and the client id was unusable until the pairs
 * aged out a day later.
 */
class PairDropListTest {

    private val first = PairId.parse("AAECAwQFBgcICQoLDA0ODw")
    private val second = PairId.parse("EBESExQVFhcYGRobHB0eHw")

    private fun session(): RelayOriginSession =
        RelaySessionManager().getOrCreate("https://relay.example")

    // Discards become debts

    /** Pairs a previous launch left on the relay: no session entry, no MTE state, only a debt. */
    @Test
    fun `inherited ids are owed without ever being held`() {
        val session = session()

        session.oweDrops(listOf(first, second))

        assertEquals(setOf(first, second), session.pendingDropIds(10).toSet())
    }

    @Test
    fun `an inherited id is not owed twice`() {
        val session = session()

        session.oweDrops(listOf(first))
        session.oweDrops(listOf(first))

        assertEquals(listOf(first), session.pendingDropIds(10))
    }

    /**
     * A pair the session never held is not a debt. Naming it spends room in a list the relay
     * bounds by `maxPairsPerClient`, and buys nothing.
     */
    @Test
    fun `removing a pair the client never held owes nothing`() {
        val session = session()

        assertFalse(session.removePair(first))

        assertTrue(session.pendingDropIds(10).isEmpty())
    }

    // Settling them

    @Test
    fun `only acknowledged ids are forgotten`() {
        val session = session()
        session.oweDrops(listOf(first, second))

        session.acknowledgeDrops(listOf(first))

        assertEquals(
            listOf(second),
            session.pendingDropIds(10),
            "a discard made while the keep-alive was in flight is still owed",
        )
    }

    @Test
    fun `a keep-alive that was not accepted leaves the debt standing`() {
        val session = session()
        session.oweDrops(listOf(first))

        // Nothing acknowledged, because the relay never answered 200.
        session.acknowledgeDrops(emptyList())

        assertEquals(listOf(first), session.pendingDropIds(10))
    }

    /**
     * The relay refuses a drop list longer than `maxPairsPerClient`, so the tick caps it and
     * the remainder goes on the next one rather than being discarded.
     */
    @Test
    fun `the list is capped and the remainder is kept`() {
        val session = session()
        session.oweDrops(listOf(first, second))

        assertEquals(1, session.pendingDropIds(1).size)
        assertEquals(2, session.pendingDropIds(99).size)
        assertTrue(session.pendingDropIds(0).isEmpty())
    }

    @Test
    fun `discarding the whole pool owes every pair`() {
        val session = session()
        session.oweDrops(listOf(first))

        session.discardAllPairs()

        assertEquals(listOf(first), session.pendingDropIds(10))
    }
}
