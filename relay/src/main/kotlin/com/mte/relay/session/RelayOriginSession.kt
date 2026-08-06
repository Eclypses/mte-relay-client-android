package com.mte.relay.session

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
    @Volatile
    private var clientId: String? = null
    private val pairMaterialsById = linkedMapOf<String, RelayPairMaterial>()
    private val runtimePairsById = linkedMapOf<String, RelayRuntimePair>()
    private val leasedRuntimePairIds = linkedSetOf<String>()
    private val orphanedLeasedRuntimePairIds = linkedSetOf<String>()

    val state: RelaySessionState
        get() = stateRef.get()

    val revision: Long
        get() = revisionRef.get()

    fun hasClientId(): Boolean {
        return !clientId.isNullOrBlank()
    }

    @Synchronized
    fun getClientId(): String? {
        return clientId
    }

    @Synchronized
    fun setClientId(value: String) {
        clientId = value
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
        clientId = null
        pairMaterialsById.clear()
        runtimePairsById.clear()
        leasedRuntimePairIds.clear()
        orphanedLeasedRuntimePairIds.clear()
        runtimePairCursor.set(0)
    }

    @Synchronized
    fun clearIdleControlPlaneStatePreservingLeasedPairs() {
        clientId = null
        if (leasedRuntimePairIds.isEmpty()) {
            pairMaterialsById.clear()
            runtimePairsById.clear()
            runtimePairCursor.set(0)
            return
        }

        val leased = leasedRuntimePairIds.toSet()
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
    fun getPairMaterial(pairId: String): RelayPairMaterial? {
        return pairMaterialsById[pairId]
    }

    @Synchronized
    fun getRuntimePair(pairId: String): RelayRuntimePair? {
        return runtimePairsById[pairId]
    }

    @Synchronized
    fun runtimePairCount(): Int {
        return runtimePairsById.size
    }

    @Synchronized
    fun currentRuntimePairIds(): List<String> {
        return runtimePairsById.keys.toList()
    }

    @Synchronized
    fun removePair(pairId: String): Boolean {
        val removedMaterial = pairMaterialsById.remove(pairId)
        val removedRuntime = runtimePairsById.remove(pairId)
        leasedRuntimePairIds.remove(pairId)
        orphanedLeasedRuntimePairIds.remove(pairId)
        if (runtimePairsById.isEmpty()) {
            runtimePairCursor.set(0)
        }
        return removedMaterial != null || removedRuntime != null
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
    fun releaseLeasedRuntimePair(pairId: String): Boolean {
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
    fun isRuntimePairLeased(pairId: String): Boolean {
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
