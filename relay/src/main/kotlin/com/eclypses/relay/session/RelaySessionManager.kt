package com.eclypses.relay.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Central per-origin session registry for the V5 core.
 */
class RelaySessionManager {
    private val sessionsByOrigin = ConcurrentHashMap<String, RelayOriginSession>()

    fun getOrCreate(origin: String): RelayOriginSession {
        return sessionsByOrigin.computeIfAbsent(origin) { RelayOriginSession(it) }
    }

    fun get(origin: String): RelayOriginSession? {
        return sessionsByOrigin[origin]
    }

    fun transition(origin: String, target: RelaySessionState): Boolean {
        return getOrCreate(origin).transition(target)
    }

    fun snapshot(origin: String): RelaySessionSnapshot? {
        return sessionsByOrigin[origin]?.snapshot()
    }

    fun allSnapshots(): List<RelaySessionSnapshot> {
        return sessionsByOrigin.values
            .map { it.snapshot() }
            .sortedBy { it.origin }
    }
}
