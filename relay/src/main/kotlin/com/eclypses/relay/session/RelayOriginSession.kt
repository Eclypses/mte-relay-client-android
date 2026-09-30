package com.eclypses.relay.session

import com.eclypses.mte.wire.Discovery
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.Token
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class RelaySessionSnapshot(
    val origin: String,
    val state: RelaySessionState,
    val revision: Long,
    val hasClientId: Boolean,
    val pairCount: Int,
)

/**
 * Per-origin session state holder used by the V5 path.
 */
class RelayOriginSession internal constructor(
    val origin: String,
) {
    private val stateRef = AtomicReference(RelaySessionState.INITIALIZING)
    private val revisionRef = AtomicLong(0)
    private val runtimePairCursor = AtomicLong(0)
    /**
     * The session's identity and its routing value in one.
     *
     * The previous generation carried two things here: a client id and a separate,
     * opaque routing token the relay minted for replica stickiness. Section 11 collapses
     * them -- `X-MTE-Relay-Route` is the client id hex, derived from this token, stable
     * for the client's life -- so there is one thing to hold and one thing to clear.
     */
    @Volatile
    private var token: Token? = null

    /**
     * What the relay told us about itself at the last auth.
     *
     * Null only before the first auth. Frame v2 makes discovery mandatory: the sequence
     * and time windows are pairing inputs, so there is no "relay without discovery" case
     * to degrade into.
     */
    @Volatile
    private var discovery: Discovery? = null

    /**
     * Subjects already warned about, so a warning that describes a standing condition — a
     * header the relay will not forward, a keepalive interval that conflicts with the
     * server's timeout — is logged once instead of on every request.
     *
     * Deliberately not cleared on repair. A repair re-reads discovery, and re-warning on
     * every reconnect would turn a one-line notice into a stream of them for a condition the
     * caller has already been told about and may have chosen to live with.
     */
    private val warnedKeys = linkedSetOf<String>()
    private val pairMaterialsById = linkedMapOf<PairId, RelayPairMaterial>()
    private val runtimePairsById = linkedMapOf<PairId, RelayRuntimePair>()
    private val leasedRuntimePairIds = linkedSetOf<PairId>()
    private val orphanedLeasedRuntimePairIds = linkedSetOf<PairId>()

    val state: RelaySessionState
        get() = stateRef.get()

    val revision: Long
        get() = revisionRef.get()

    fun hasClientId(): Boolean {
        return token != null
    }

    @Synchronized
    fun getToken(): Token? {
        return token
    }

    @Synchronized
    fun setToken(value: Token) {
        token = value
    }

    @Synchronized
    fun getDiscovery(): Discovery? {
        return discovery
    }

    @Synchronized
    fun setDiscovery(value: Discovery) {
        discovery = value
    }

    /**
     * The two decoder windows to build pairs with, both from discovery and neither with a
     * fallback here.
     *
     * Section 11: "A client MUST take both from discovery and MUST NOT carry a default of
     * its own past the point where discovery has answered." The fallbacks that do exist
     * live in [Discovery] itself, where the absent and zero cases differ per member and
     * are pinned by the vectors. Reaching this before auth is a programming error, not a
     * relay that said nothing.
     */
    @Synchronized
    fun sequenceWindow(): Int = requireDiscovery().sequenceWindow

    @Synchronized
    fun timeWindow(): Long = requireDiscovery().timeWindow.toLong()

    @Synchronized
    fun requireDiscovery(): Discovery = discovery
        ?: throw IllegalStateException(
            "no discovery for $origin; pairing inputs are only known after auth",
        )

    /**
     * True the first time [key] is seen, false afterwards. Callers gate a warning on this so
     * the message is emitted once per subject for the life of the session.
     */
    @Synchronized
    fun shouldWarn(key: String): Boolean {
        return warnedKeys.add(key)
    }

    @Synchronized
    fun setPairMaterials(values: Collection<RelayPairMaterial>) {
        pairMaterialsById.clear()
        values.forEach { pairMaterialsById[it.pairId] = it }
    }

    @Synchronized
    fun addPairMaterials(values: Collection<RelayPairMaterial>) {
        values.forEach {
            pairMaterialsById[it.pairId] = it
            orphanedLeasedRuntimePairIds.remove(it.pairId)
        }
    }

    @Synchronized
    fun setRuntimePairs(values: Collection<RelayRuntimePair>) {
        runtimePairsById.clear()
        values.forEach { runtimePairsById[it.pairId] = it }
        leasedRuntimePairIds.clear()
        orphanedLeasedRuntimePairIds.clear()
        runtimePairCursor.set(0)
    }

    @Synchronized
    fun addRuntimePairs(values: Collection<RelayRuntimePair>) {
        values.forEach {
            runtimePairsById[it.pairId] = it
            orphanedLeasedRuntimePairIds.remove(it.pairId)
        }
    }

    @Synchronized
    fun clearControlPlaneState() {
        token = null
        pairMaterialsById.clear()
        runtimePairsById.clear()
        leasedRuntimePairIds.clear()
        orphanedLeasedRuntimePairIds.clear()
        runtimePairCursor.set(0)
    }

    @Synchronized
    fun clearIdleControlPlaneStatePreservingLeasedPairs() {
        token = null
        if (leasedRuntimePairIds.isEmpty()) {
            pairMaterialsById.clear()
            runtimePairsById.clear()
            runtimePairCursor.set(0)
            return
        }

        val leased: Set<PairId> = leasedRuntimePairIds.toSet()
        val materialIdsToRemove = pairMaterialsById.keys.filterNot { it in leased }
        materialIdsToRemove.forEach { pairMaterialsById.remove(it) }
        orphanedLeasedRuntimePairIds.clear()
        orphanedLeasedRuntimePairIds.addAll(leased)
        val runtimeIdsToRemove = runtimePairsById.keys.filterNot { it in leased }
        runtimeIdsToRemove.forEach { runtimePairsById.remove(it) }
        runtimePairCursor.set(0)
    }

    @Synchronized
    fun pairCount(): Int {
        return pairMaterialsById.size
    }

    @Synchronized
    fun getPairMaterial(pairId: PairId): RelayPairMaterial? {
        return pairMaterialsById[pairId]
    }

    @Synchronized
    fun getRuntimePair(pairId: PairId): RelayRuntimePair? {
        return runtimePairsById[pairId]
    }

    @Synchronized
    fun runtimePairCount(): Int {
        return runtimePairsById.size
    }

    @Synchronized
    fun currentRuntimePairIds(): List<PairId> {
        return runtimePairsById.keys.toList()
    }

    @Synchronized
    fun removePair(pairId: PairId): Boolean {
        val removedMaterial = pairMaterialsById.remove(pairId)
        val removedRuntime = runtimePairsById.remove(pairId)
        leasedRuntimePairIds.remove(pairId)
        orphanedLeasedRuntimePairIds.remove(pairId)
        if (runtimePairsById.isEmpty()) {
            runtimePairCursor.set(0)
        }
        val removed = removedMaterial != null || removedRuntime != null
        if (removed) {
            // Every discard path funnels through here, so this is the one place the debt
            // has to be recorded. A pair the session never held is not owed: naming it
            // spends room in a drop list the relay bounds by maxPairsPerClient.
            pendingDrops += pairId
        }
        return removed
    }

    /**
     * Pairs this client has finished with, which the next keep-alive asks the relay to delete.
     *
     * Section 11 made the client authoritative: one keep-alive refreshes *every* pair the
     * relay holds for this client and deletes exactly the ones named in `drop`. The relay no
     * longer prunes a live client's pairs behind its back -- which is the guarantee the
     * mechanism exists for, and the reason the client now owes it an accounting. A client that
     * never names a discarded pair grows its footprint until `maxPairsPerClient`, and then
     * pairing fails outright with 490 `pair_limit`.
     */
    private val pendingDrops = linkedSetOf<PairId>()

    /**
     * Takes on ids this process never held, so they can be handed back.
     *
     * For pairs a previous launch left on the relay: there is no session entry to remove and
     * no MTE state to discard, only a debt to settle.
     */
    @Synchronized
    fun oweDrops(ids: Collection<PairId>) {
        pendingDrops += ids
    }

    /** The ids to offer the relay on the next keep-alive, capped at what it will accept. */
    @Synchronized
    fun pendingDropIds(limit: Int): List<PairId> {
        if (limit <= 0) return emptyList()
        return pendingDrops.take(limit)
    }

    /**
     * Forgets the ids the relay confirmed it deleted.
     *
     * Only the acknowledged ones, never the whole list: a discard made while the keep-alive
     * was in flight is still owed, and clearing wholesale would lose it silently.
     */
    @Synchronized
    fun acknowledgeDrops(ids: Collection<PairId>) {
        pendingDrops -= ids.toSet()
    }

    /**
     * Discards every pair and owes the relay a drop for each.
     *
     * For the paths that abandon a whole pool. Those must drop *before* the client id is
     * cleared or replaced -- the drop list is addressed by client id, so once it is gone this
     * client cannot name its own pairs and they sit on the relay until they age out.
     */
    @Synchronized
    fun discardAllPairs(): List<PairId> {
        val removed = (pairMaterialsById.keys + runtimePairsById.keys).toList()
        pairMaterialsById.clear()
        runtimePairsById.clear()
        leasedRuntimePairIds.clear()
        orphanedLeasedRuntimePairIds.clear()
        runtimePairCursor.set(0)
        pendingDrops += removed
        return removed
    }

    @Synchronized
    fun leaseRuntimePairRoundRobin(): RelayRuntimePair? {
        if (runtimePairsById.isEmpty()) {
            return null
        }
        val availablePairs = runtimePairsById.values.filterNot { leasedRuntimePairIds.contains(it.pairId) }
        if (availablePairs.isEmpty()) {
            return null
        }
        val index = (runtimePairCursor.getAndIncrement() % availablePairs.size).toInt()
        val pair = availablePairs[index]
        leasedRuntimePairIds += pair.pairId
        return pair
    }

    @Synchronized
    fun releaseLeasedRuntimePair(pairId: PairId): Boolean {
        val released = leasedRuntimePairIds.remove(pairId)
        if (released && orphanedLeasedRuntimePairIds.remove(pairId)) {
            pairMaterialsById.remove(pairId)
            runtimePairsById.remove(pairId)
            if (runtimePairsById.isEmpty()) {
                runtimePairCursor.set(0)
            }
        }
        return released
    }

    @Synchronized
    fun isRuntimePairLeased(pairId: PairId): Boolean {
        return leasedRuntimePairIds.contains(pairId)
    }

    @Synchronized
    fun selectRuntimePairRoundRobin(): RelayRuntimePair? {
        if (runtimePairsById.isEmpty()) {
            return null
        }
        val pairs = runtimePairsById.values.toList()
        val index = (runtimePairCursor.getAndIncrement() % pairs.size).toInt()
        return pairs[index]
    }

    fun transition(target: RelaySessionState): Boolean {
        while (true) {
            val current = stateRef.get()
            if (current == target) {
                return true
            }
            if (!isValidTransition(current, target)) {
                return false
            }
            if (stateRef.compareAndSet(current, target)) {
                revisionRef.incrementAndGet()
                return true
            }
        }
    }

    fun snapshot(): RelaySessionSnapshot {
        return RelaySessionSnapshot(
            origin = origin,
            state = stateRef.get(),
            revision = revisionRef.get(),
            hasClientId = hasClientId(),
            pairCount = pairCount(),
        )
    }

    private fun isValidTransition(
        current: RelaySessionState,
        target: RelaySessionState,
    ): Boolean {
        return when (current) {
            RelaySessionState.INITIALIZING -> target == RelaySessionState.READY || target == RelaySessionState.FAILED
            RelaySessionState.READY -> target == RelaySessionState.REPAIRING || target == RelaySessionState.FAILED
            RelaySessionState.REPAIRING -> target == RelaySessionState.READY || target == RelaySessionState.FAILED
            RelaySessionState.FAILED -> target == RelaySessionState.INITIALIZING
        }
    }
}
