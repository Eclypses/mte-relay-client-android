package com.eclypses.relay.session

import com.eclypses.relay.LogHelper
import com.eclypses.relay.RelayClientSettings
import com.eclypses.relay.persistence.RelayStateStore
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

interface RelayControlPlaneClient {
    fun authenticate(origin: String, existingClientId: String?): RelayAuthResponse
    fun pair(origin: String, clientId: String, pairPoolSize: Int): RelayPairingResult
    fun keepAlive(origin: String, clientId: String, pairIds: List<String>)
}

/**
 * Phase 2 lifecycle coordinator for auth/pair readiness and deterministic repair flow.
 */
class RelaySessionLifecycleManager(
    private val sessionManager: RelaySessionManager,
    private val controlPlaneClient: RelayControlPlaneClient,
    private val stateStore: RelayStateStore? = null,
    private val keepAliveScheduler: ScheduledExecutorService = defaultKeepAliveScheduler(),
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    private val keepAliveTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val keepAliveTelemetryByOrigin = ConcurrentHashMap<String, KeepAliveTelemetry>()
    private val settingsByOrigin = ConcurrentHashMap<String, RelayClientSettings>()

    fun getSettings(origin: String): RelayClientSettings {
        return settingsByOrigin.computeIfAbsent(origin) { RelayClientSettings.defaults() }
    }

    fun updateSettings(origin: String, newSettings: RelayClientSettings) {
        settingsByOrigin[origin] = newSettings
        sessionManager.get(origin)?.let { session ->
            synchronized(session) {
                ensureKeepAliveLoop(session)
            }
        }
    }

    fun describeKeepAlive(origin: String): String {
        val session = sessionManager.get(origin)
        val telemetry = keepAliveTelemetryByOrigin[origin]
        if (session == null && telemetry == null) {
            val settings = getSettings(origin)
            return buildString {
                append("Origin: ").append(origin)
                append("\nSession: not initialized")
                append("\nConfigured keep-alive interval: ")
                    .append(settings.keepAliveIntervalSeconds)
                    .append("s")
                append("\nLoop active: false")
                append("\nMaintained pair ids: []")
            }
        }

        val state: RelaySessionState?
        val hasClientId: Boolean
        val pairIds: List<String>
        val settings = getSettings(origin)
        synchronized(session ?: return buildString {
            append("Origin: ").append(origin)
            append("\nSession: not initialized")
            append("\nConfigured keep-alive interval: ")
                .append(settings.keepAliveIntervalSeconds)
                .append("s")
            append("\nLoop active: ").append(telemetry?.loopActive == true)
            append("\nLast event: ").append(telemetry?.lastEvent ?: "none")
            append("\nMaintained pair ids: []")
        }) {
            state = session.state
            hasClientId = session.hasClientId()
            pairIds = session.currentRuntimePairIds()
        }

        return buildString {
            append("Origin: ").append(origin)
            append("\nSession state: ").append(state)
            append("\nClient id present: ").append(hasClientId)
            append("\nConfigured keep-alive interval: ")
                .append(settings.keepAliveIntervalSeconds)
                .append("s")
            append("\nLoop active: ").append(telemetry?.loopActive == true)
            append("\nMaintained pair count: ").append(pairIds.size)
            append("\nMaintained pair ids: ").append(pairIds)
            append("\nLast event: ").append(telemetry?.lastEvent ?: "none")
            append("\nLast scheduled: ").append(formatTelemetryTime(telemetry?.scheduledAtEpochMillis))
            append("\nLast attempt: ").append(formatTelemetryTime(telemetry?.lastAttemptAtEpochMillis))
            append("\nLast success: ").append(formatTelemetryTime(telemetry?.lastSuccessAtEpochMillis))
            append("\nLast loop progress age: ").append(formatElapsedAge(telemetry?.lastProgressAtElapsedMillis))
            append("\nLast failure: ").append(telemetry?.lastFailureMessage ?: "none")
        }
    }


    fun ensureReady(origin: String): RelaySessionSnapshot {
        val session = sessionManager.getOrCreate(origin)
        synchronized(session) {
            if (shouldInvalidateForKeepAlivePause(origin, session)) {
                invalidateKeepAlivePool(origin, "keep-alive paused for more than 10 minutes")
            }
            if (session.state == RelaySessionState.READY && session.hasClientId() && session.pairCount() > 0) {
                return session.snapshot()
            }
            if (session.state == RelaySessionState.FAILED) {
                session.transition(RelaySessionState.INITIALIZING)
            }

            return initializeSession(session)
        }
    }

    fun repair(origin: String): RelaySessionSnapshot {
        val session = sessionManager.getOrCreate(origin)
        synchronized(session) {
            stopKeepAliveLoop(origin)
            if (session.state == RelaySessionState.READY) {
                session.transition(RelaySessionState.REPAIRING)
            }
            return initializeSession(session, clearExistingState = true, mergeNewPairs = false)
        }
    }

    fun manualRepair(origin: String): RelaySessionSnapshot {
        val session = sessionManager.getOrCreate(origin)
        synchronized(session) {
            stopKeepAliveLoop(origin)
            when (session.state) {
                RelaySessionState.READY -> session.transition(RelaySessionState.REPAIRING)
                RelaySessionState.FAILED -> session.transition(RelaySessionState.INITIALIZING)
                else -> {}
            }
            session.clearIdleControlPlaneStatePreservingLeasedPairs()
            removePersistedClientId(origin)
            return initializeSession(session, clearExistingState = false, mergeNewPairs = true)
        }
    }

    fun discardAndReplacePair(origin: String, pairId: String): RelaySessionSnapshot {
        val session = sessionManager.getOrCreate(origin)
        synchronized(session) {
            if (!session.removePair(pairId)) {
                return session.snapshot()
            }

            val clientId = session.getClientId()
                ?: throw IllegalStateException("missing clientId for $origin")
            try {
                val pairingResult = controlPlaneClient.pair(session.origin, clientId, 1)
                session.addPairMaterials(pairingResult.materials)
                session.addRuntimePairs(pairingResult.runtimePairs)
                ensureKeepAliveLoop(session)
                LogHelper.info(
                    "RelaySessionLifecycleManager",
                    "Replaced 1 pair for ${session.origin}; total pairs=${session.pairCount()}",
                )
                if (session.state == RelaySessionState.FAILED) {
                    session.transition(RelaySessionState.INITIALIZING)
                    return initializeSession(session)
                }
                return session.snapshot()
            } catch (failure: Throwable) {
                session.transition(RelaySessionState.REPAIRING)
                return initializeSession(session)
            }
        }
    }

    private fun initializeSession(
        session: RelayOriginSession,
        clearExistingState: Boolean,
        mergeNewPairs: Boolean,
    ): RelaySessionSnapshot {
        try {
            if (clearExistingState) {
                session.clearControlPlaneState()
            }
            val settings = getSettings(session.origin)
            loadPersistedClientId(session)
            val authResponse = controlPlaneClient.authenticate(session.origin, session.getClientId())
            val pairingResult = controlPlaneClient.pair(session.origin, authResponse.clientId, settings.basePairs)
            session.setClientId(authResponse.clientId)
            saveClientId(session.origin, authResponse.clientId)
            if (mergeNewPairs) {
                session.addPairMaterials(pairingResult.materials)
                session.addRuntimePairs(pairingResult.runtimePairs)
            } else {
                session.setPairMaterials(pairingResult.materials)
                session.setRuntimePairs(pairingResult.runtimePairs)
            }
            LogHelper.info(
                "RelaySessionLifecycleManager",
                "Created ${pairingResult.runtimePairs.size} pairs for ${session.origin}; total pairs=${session.pairCount()}",
            )
            ensureKeepAliveLoop(session)
            if (session.state == RelaySessionState.INITIALIZING) {
                session.transition(RelaySessionState.READY)
            } else if (session.state == RelaySessionState.REPAIRING) {
                session.transition(RelaySessionState.READY)
            }
            return session.snapshot()
        } catch (failure: Throwable) {
            session.transition(RelaySessionState.FAILED)
            throw failure
        }
    }

    private fun initializeSession(session: RelayOriginSession): RelaySessionSnapshot {
        return initializeSession(session, clearExistingState = false, mergeNewPairs = false)
    }

    private fun loadPersistedClientId(session: RelayOriginSession) {
        if (session.hasClientId()) {
            return
        }
        val persisted = runCatching { stateStore?.load(session.origin) }
            .getOrNull()
            ?.toString(StandardCharsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
            ?: return
        session.setClientId(persisted)
    }

    private fun saveClientId(origin: String, clientId: String) {
        runCatching {
            stateStore?.save(origin, clientId.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun removePersistedClientId(origin: String) {
        runCatching {
            stateStore?.remove(origin)
        }
    }

    private fun ensureKeepAliveLoop(session: RelayOriginSession) {
        val clientId = session.getClientId()
        if (clientId.isNullOrBlank() || session.pairCount() == 0) {
            stopKeepAliveLoop(session.origin)
            return
        }

        val settings = getSettings(session.origin)
        val intervalSeconds = settings.keepAliveIntervalSeconds.toLong()
        val pairIds = session.currentRuntimePairIds()
        keepAliveTasks.remove(session.origin)?.cancel(false)
        keepAliveTasks[session.origin] = keepAliveScheduler.scheduleAtFixedRate(
            { runKeepAliveTick(session.origin) },
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS,
        )
        val telemetry = keepAliveTelemetry(session.origin)
        telemetry.intervalSeconds = intervalSeconds
        telemetry.loopActive = true
        telemetry.scheduledAtEpochMillis = System.currentTimeMillis()
        telemetry.lastProgressAtElapsedMillis = elapsedRealtimeMillis()
        telemetry.lastEvent = "scheduled"
        LogHelper.info(
            "RelaySessionLifecycleManager",
            "Scheduled keep-alive for ${session.origin} every ${intervalSeconds}s with ${pairIds.size} maintained pairs: $pairIds",
        )
    }

    private fun stopKeepAliveLoop(origin: String) {
        val removed = keepAliveTasks.remove(origin)
        removed?.cancel(false)
        if (removed != null) {
            val telemetry = keepAliveTelemetry(origin)
            telemetry.loopActive = false
            telemetry.lastEvent = "stopped"
            LogHelper.info(
                "RelaySessionLifecycleManager",
                "Stopped keep-alive loop for $origin",
            )
        }
    }

    private fun runKeepAliveTick(origin: String) {
        val session = sessionManager.get(origin) ?: return
        val clientId: String
        val pairIds: List<String>
        synchronized(session) {
            if (session.state != RelaySessionState.READY) {
                return
            }
            clientId = session.getClientId().orEmpty()
            pairIds = session.currentRuntimePairIds()
        }
        if (clientId.isBlank() || pairIds.isEmpty()) {
            LogHelper.info(
                "RelaySessionLifecycleManager",
                "Skipping keep-alive for $origin because clientId or pairIds are unavailable",
            )
            return
        }

        try {
            val telemetry = keepAliveTelemetry(origin)
            telemetry.lastAttemptAtEpochMillis = System.currentTimeMillis()
            telemetry.lastProgressAtElapsedMillis = elapsedRealtimeMillis()
            telemetry.lastEvent = "attempting"
            LogHelper.info(
                "RelaySessionLifecycleManager",
                "Sending keep-alive for $origin with ${pairIds.size} maintained pairs: $pairIds",
            )
            controlPlaneClient.keepAlive(origin, clientId, pairIds)
            telemetry.lastSuccessAtEpochMillis = System.currentTimeMillis()
            telemetry.lastFailureMessage = null
            telemetry.lastEvent = "success"
        } catch (failure: Throwable) {
            val telemetry = keepAliveTelemetry(origin)
            telemetry.lastFailureMessage = failure.message ?: failure.javaClass.simpleName
            telemetry.lastEvent = "failed"
            if (failure.message?.contains("status 564") == true) {
                invalidateKeepAlivePool(origin, "invalidated after 564")
                return
            }
            LogHelper.warn(
                "RelaySessionLifecycleManager",
                "Keep-alive failed for $origin: ${failure.message}",
            )
        }
    }

    private fun invalidateKeepAlivePool(origin: String, reason: String) {
        stopKeepAliveLoop(origin)
        val session = sessionManager.get(origin) ?: return
        synchronized(session) {
            session.clearIdleControlPlaneStatePreservingLeasedPairs()
            removePersistedClientId(origin)
            if (session.state == RelaySessionState.READY) {
                session.transition(RelaySessionState.REPAIRING)
            }
            session.transition(RelaySessionState.FAILED)
        }
        val telemetry = keepAliveTelemetry(origin)
        telemetry.loopActive = false
        telemetry.lastEvent = reason
        LogHelper.info(
            "RelaySessionLifecycleManager",
            "Keep-alive invalidated pooled pairs for $origin ($reason); next request will re-pair",
        )
    }

    private fun keepAliveTelemetry(origin: String): KeepAliveTelemetry {
        return keepAliveTelemetryByOrigin.computeIfAbsent(origin) { KeepAliveTelemetry() }
    }

    private fun shouldInvalidateForKeepAlivePause(origin: String, session: RelayOriginSession): Boolean {
        if (session.state != RelaySessionState.READY || !session.hasClientId() || session.pairCount() == 0) {
            return false
        }
        val telemetry = keepAliveTelemetryByOrigin[origin] ?: return false
        if (!telemetry.loopActive) {
            return false
        }
        val lastProgressAt = telemetry.lastProgressAtElapsedMillis ?: return false
        val pauseMillis = elapsedRealtimeMillis() - lastProgressAt
        if (pauseMillis <= KEEP_ALIVE_PAUSE_INVALIDATION_MS) {
            return false
        }
        LogHelper.info(
            "RelaySessionLifecycleManager",
            "Invalidating keep-alive pool for $origin after ${pauseMillis}ms without loop progress",
        )
        return true
    }

    private fun formatTelemetryTime(value: Long?): String {
        return if (value == null) "never" else Date(value).toString()
    }

    private fun formatElapsedAge(value: Long?): String {
        if (value == null) {
            return "unknown"
        }
        val ageMillis = (elapsedRealtimeMillis() - value).coerceAtLeast(0)
        return "${ageMillis}ms"
    }

    private data class KeepAliveTelemetry(
        var intervalSeconds: Long? = null,
        var loopActive: Boolean = false,
        var scheduledAtEpochMillis: Long? = null,
        var lastAttemptAtEpochMillis: Long? = null,
        var lastSuccessAtEpochMillis: Long? = null,
        var lastProgressAtElapsedMillis: Long? = null,
        var lastFailureMessage: String? = null,
        var lastEvent: String = "none",
    )

    companion object {
        private const val KEEP_ALIVE_PAUSE_INVALIDATION_MS = 10 * 60 * 1000L
        private const val NANOS_PER_MILLI = 1_000_000L

        private fun defaultKeepAliveScheduler(): ScheduledExecutorService {
            return Executors.newSingleThreadScheduledExecutor(
                ThreadFactory { runnable ->
                    Thread(runnable, "relay-keepalive").apply { isDaemon = true }
                },
            )
        }
    }
}
