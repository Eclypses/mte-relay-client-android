package com.eclypses.relay.session

import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.RelayAction
import com.eclypses.mte.wire.Token
import com.eclypses.relay.LogHelper
import com.eclypses.relay.RelayClientSettings
import com.eclypses.relay.RelayWarnings
import com.eclypses.relay.persistence.RelayStateStore
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Every method takes the session's current routing token so the call can echo it in
 * `X-MTE-Relay-Route`, which is what pins a client to one relay replica. Null means the
 * server never minted one and the header is omitted.
 */
interface RelayControlPlaneClient {
    /**
     * @param existingToken the token to refresh, or null for a first authentication.
     *   The relay answers a token it refuses -- invalid or past `tokenMaxAgeSeconds` --
     *   with a *new* client id rather than an error, so a caller that presented one must
     *   compare the client id that comes back before assuming its pairs are still live.
     */
    fun authenticate(origin: String, existingToken: com.eclypses.mte.wire.Token?): RelayAuthResponse

    /**
     * @param sequenceWindow decoder sequence window, from discovery.
     * @param timeWindow decoder time window, from discovery. Both are pairing inputs
     *   rather than hints: each end builds its decoder from them, so a disagreement is
     *   no pair at all rather than a degraded one, and nothing in the failure says why.
     */
    fun pair(
        origin: String,
        token: com.eclypses.mte.wire.Token,
        pairPoolSize: Int,
        sequenceWindow: Int,
        timeWindow: Long,
    ): RelayPairingResult

    /**
     * Refreshes every pair the client holds and deletes those in [drop]. One call, not
     * one per pair: the relay does the whole client in a single store operation.
     */
    fun keepAlive(origin: String, token: com.eclypses.mte.wire.Token, drop: List<com.eclypses.mte.wire.PairId>)
}

/**
 * Phase 2 lifecycle coordinator for auth/pair readiness and deterministic repair flow.
 */
class RelaySessionLifecycleManager(
    private val sessionManager: RelaySessionManager,
    internal val controlPlaneClient: RelayControlPlaneClient,
    private val stateStore: RelayStateStore? = null,
    private val keepAliveScheduler: ScheduledExecutorService = defaultKeepAliveScheduler(),
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    /** The session's encode type, needed to check it against what the relay accepts. */
    private val useMke: Boolean = true,
    /** Whether cookies ride relay calls, so a relay that forwards them can say so is wasted. */
    private val cookiesEnabled: Boolean = true,
) {
    private val keepAliveTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val lastConfigVersionByOrigin = ConcurrentHashMap<String, Int>()
    private val keepAliveTelemetryByOrigin = ConcurrentHashMap<String, KeepAliveTelemetry>()
    private val settingsByOrigin = ConcurrentHashMap<String, RelayClientSettings>()

    /**
     * Origins whose settings the caller set deliberately, via [updateSettings].
     *
     * Discovery adopts a server-derived keepalive interval only where the caller expressed no
     * preference, so the two have to be told apart. [getSettings] cannot: it inserts defaults
     * on first read, after which every origin looks configured.
     */
    private val callerConfiguredOrigins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun getSettings(origin: String): RelayClientSettings {
        return settingsByOrigin.computeIfAbsent(origin) { RelayClientSettings.defaults() }
    }

    fun updateSettings(origin: String, newSettings: RelayClientSettings) {
        settingsByOrigin[origin] = newSettings
        callerConfiguredOrigins += origin
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
        val pairIds: List<PairId>
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
            removePersistedToken(origin)
            return initializeSession(session, clearExistingState = false, mergeNewPairs = true)
        }
    }

    fun discardAndReplacePair(origin: String, pairId: PairId): RelaySessionSnapshot {
        val session = sessionManager.getOrCreate(origin)
        synchronized(session) {
            if (!session.removePair(pairId)) {
                return session.snapshot()
            }

            val token = session.getToken()
                ?: throw IllegalStateException("missing token for $origin")
            try {
                val pairingResult = controlPlaneClient.pair(
                    session.origin,
                    token,
                    1,
                    session.sequenceWindow(),
                    session.timeWindow(),
                )
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
            loadPersistedToken(session)
            val presented = session.getToken()
            val authResponse = controlPlaneClient.authenticate(session.origin, presented)

            // A token the relay refuses -- malformed, or past tokenMaxAgeSeconds -- is not
            // an error: it answers with a NEW client id and says nothing. Every pair this
            // session holds belongs to the old id and is now orphaned, so keeping them
            // would mean a pool whose every member answers 470 pair_not_found. Compare and
            // drop rather than assume the refresh was in place.
            if (presented != null && presented.clientIdHex != authResponse.token.clientIdHex) {
                LogHelper.info(
                    "RelaySessionLifecycleManager",
                    "Relay ${session.origin} issued a new client id at auth; the previous " +
                        "token was invalid or aged, so its pairs are discarded.",
                )
                // Named under the id that owns them, and before it is replaced. The drop list
                // is addressed by client id: after this the new id cannot refer to them, and
                // they would sit on the relay until they age out.
                handBackEveryPair(session, presented)
                session.clearControlPlaneState()
            }

            // Checked before pairing so an incompatible relay reports why rather than
            // surfacing as a generic pairing failure on the first frame.
            applyDiscovery(session, authResponse.discovery)
            session.setToken(authResponse.token)
            val pairingResult = pairFlushingOnLimit(session, authResponse.token, settings.basePairs)
            if (mergeNewPairs) {
                session.addPairMaterials(pairingResult.materials)
                session.addRuntimePairs(pairingResult.runtimePairs)
            } else {
                session.setPairMaterials(pairingResult.materials)
                session.setRuntimePairs(pairingResult.runtimePairs)
            }
            // After the pairs exist, so the record names what this launch actually holds.
            saveToken(session.origin, authResponse.token, session.currentRuntimePairIds())
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

    /**
     * Adopts what the relay reported about itself, failing the session only for the three
     * conditions that would otherwise surface as an unexplained pairing error.
     */
    private fun applyDiscovery(session: RelayOriginSession, discovery: com.eclypses.mte.wire.Discovery) {
        session.setDiscovery(discovery)

        RelayDiscoveryChecks.assertCompatible(
            origin = session.origin,
            discovery = discovery,
            clientProfile = MteProfileSource.profile,
            clientKyberStrength = MtePairDraft.KYBER_STRENGTH,
        ).forEach { if (session.shouldWarn(it.key)) warn(it.message) }

        val wanted = if (useMke) com.eclypses.mte.wire.MteType.MKE else com.eclypses.mte.wire.MteType.MTE
        if (!RelayDiscoveryChecks.accepts(discovery, wanted)) {
            throw RelayIncompatibleException(
                "Relay ${session.origin} does not accept $wanted; it bounds metadata for " +
                    "${discovery.maxMetadataBytes.keys}.",
            )
        }

        LogHelper.debug(
            "RelaySessionLifecycleManager",
            "Relay ${session.origin} reports buildVersion=${discovery.buildVersion}, " +
                "frameVersions=${discovery.frameVersions}, features=${discovery.features}, " +
                "sequenceWindow=${discovery.sequenceWindow}, timeWindow=${discovery.timeWindow}",
        )

        applyTuning(session, discovery)

        // A relay that forwards cookies to the origin cannot do so for a client that sends
        // none, and the symptom is an origin that behaves as though the caller is never
        // logged in — far from the switch that caused it.
        val forwardsCookies = (discovery.raw["forwardBrowserCookies"] as? com.eclypses.mte.wire.JsonBool)?.value
        if (forwardsCookies == true &&
            !cookiesEnabled &&
            session.shouldWarn("cookies-disabled-but-forwarded")
        ) {
            warn(
                "Cookies are disabled on this client, but ${session.origin} forwards them to " +
                    "the origin. No cookie will ever reach it, so anything the origin " +
                    "authenticates with a cookie will behave as though nobody is signed in.",
            )
        }
    }

    /**
     * Adopts the pool and keepalive values the relay reports, where the caller expressed no
     * preference of their own. Nothing here fails a session — a client that ignored all of it
     * still works, just less well matched to the server it is talking to.
     */
    private fun applyTuning(session: RelayOriginSession, discovery: com.eclypses.mte.wire.Discovery) {
        val origin = session.origin
        val current = getSettings(origin)
        var next = current

        val timeoutSeconds = (discovery.raw["sessionTimeoutSeconds"] as? com.eclypses.mte.wire.JsonNumber)
            ?.raw?.toIntOrNull()?.takeIf { it > 0 }
        if (timeoutSeconds != null) {
            // A third of the server's idle timeout, so a pair is touched twice before it can
            // expire. Clamped to the range the settings type allows.
            val third = timeoutSeconds / 3
            val derived = third.coerceIn(MIN_KEEP_ALIVE_SECONDS, MAX_KEEP_ALIVE_SECONDS)
            if (!callerConfiguredOrigins.contains(origin)) {
                if (derived != current.keepAliveIntervalSeconds) {
                    next = next.buildUpon().setKeepAliveIntervalSeconds(derived).build()
                    LogHelper.debug(
                        "RelaySessionLifecycleManager",
                        "Keep-alive interval for $origin derived from the relay's " +
                            "${timeoutSeconds}s session timeout: ${derived}s",
                    )
                }
            } else if (current.keepAliveIntervalSeconds > third &&
                session.shouldWarn("keepalive-longer-than-timeout")
            ) {
                warn(
                    "Keep-alive interval for $origin is ${current.keepAliveIntervalSeconds}s but " +
                        "the relay's session timeout is ${timeoutSeconds}s, so pairs can expire " +
                        "between keep-alives. Use ${third}s or less.",
                )
            }
            if (third < MIN_KEEP_ALIVE_SECONDS && session.shouldWarn("relay-timeout-too-short")) {
                warn(
                    "The relay's session timeout for $origin is ${timeoutSeconds}s. A third of " +
                        "that is below the ${MIN_KEEP_ALIVE_SECONDS}s minimum keep-alive " +
                        "interval, so pairs may expire between keep-alives.",
                )
            }
        }

        val cap = discovery.maxPairsPerClient
        if (cap != null && (next.maxPairs > cap || next.basePairs > cap || next.minPairs > cap)) {
            next = next.buildUpon()
                .setMinPairs(minOf(next.minPairs, cap))
                .setBasePairs(minOf(next.basePairs, cap))
                .setMaxPairs(minOf(next.maxPairs, cap))
                .build()
            if (session.shouldWarn("max-pairs-clamped")) {
                warn("Relay $origin allows at most $cap pairs per client; pool sizes were clamped.")
            }
        }

        if (next != current) {
            settingsByOrigin[origin] = next
        }
    }

    private fun warn(message: String) {
        if (RelayWarnings.enabled) {
            LogHelper.warn("RelaySessionLifecycleManager", message)
        }
    }

    /**
     * Restores the token from the last run, so a restart refreshes an identity rather
     * than taking a new one and stranding whatever pairs the relay still holds.
     *
     * A stored value that no longer parses is dropped rather than raised: the token
     * format is part of the wire, so an upgrade across a format change finds one, and
     * the right answer is a fresh authentication, not a failed start.
     */
    private fun loadPersistedToken(session: RelayOriginSession) {
        if (session.hasClientId()) {
            return
        }
        val persisted = runCatching { stateStore?.load(session.origin) }
            .getOrNull()
            ?.toString(StandardCharsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val lines = persisted.lineSequence().toList()
        val orphaned = lines.getOrNull(1)
            ?.split(',')
            ?.mapNotNull { id -> runCatching { PairId.parse(id.trim()) }.getOrNull() }
            .orEmpty()
        runCatching { Token.parse(lines.first().trim()) }
            .onSuccess {
                session.setToken(it)
                if (orphaned.isNotEmpty()) {
                    // No session entry and no MTE state -- only a debt to settle.
                    LogHelper.info(
                        "RelaySessionLifecycleManager",
                        "Inheriting ${orphaned.size} pairs from a previous session of ${session.origin} to hand back",
                    )
                    session.oweDrops(orphaned)
                }
            }
            .onFailure {
                LogHelper.debug(
                    "RelaySessionLifecycleManager",
                    "Discarding an unreadable stored token for ${session.origin}: ${it.message}",
                )
                removePersistedToken(session.origin)
            }
    }

    /**
     * Stores the token and the pair ids it owns.
     *
     * Frame v2 keeps the client id across launches -- it is the route header for the client's
     * life -- and every launch pairs a fresh pool. Without the ids the previous launch's pairs
     * are unnameable and sit on the relay until they age out, counting against
     * `maxPairsPerClient` the whole time: roughly forty launches to 490 `pair_limit`, measured.
     *
     * Line one is the token, line two the ids. A record written before this has no second
     * line and still reads, which costs nothing and avoids stranding an identity over a
     * format change. The ids are not secret -- a pair id travels in the clear in the drop
     * list -- and no MTE state is kept: they exist only to be deleted.
     */
    /**
     * Discards every pair and tells the relay so, in one keep-alive, now.
     *
     * For the paths that abandon a whole pool. Those cannot wait for the next tick: they are
     * about to clear or replace the client id, and the drop list is addressed by it.
     */
    private fun handBackEveryPair(session: RelayOriginSession, token: Token?) {
        val owed = synchronized(session) {
            session.discardAllPairs()
            session.pendingDropIds(maxPairsPerClient(session))
        }
        val presented = token ?: session.getToken() ?: return
        if (owed.isEmpty()) return
        runCatching { controlPlaneClient.keepAlive(session.origin, presented, owed) }
            .onSuccess { synchronized(session) { session.acknowledgeDrops(owed) } }
            .onFailure {
                LogHelper.info(
                    "RelaySessionLifecycleManager",
                    "${session.origin} did not accept the drop list; ${owed.size} pairs will age out instead",
                )
            }
    }

    /**
     * Pairs, and settles the drop list once if the relay says the ceiling is in the way.
     *
     * The registry's action for 490 `pair_limit` is to flush dropped pairs through keep-alive
     * and retry once, then stop growing. Keyed on the reason and not the status: 490
     * `profile_mismatch` is a library build disagreement that no retry fixes.
     *
     * Once, then stop. A second refusal means the ceiling is real rather than this client's
     * unreturned pairs, and retrying would only add to it.
     */
    private fun pairFlushingOnLimit(
        session: RelayOriginSession,
        token: Token,
        count: Int,
    ): RelayPairingResult {
        return try {
            controlPlaneClient.pair(
                session.origin,
                token,
                count,
                session.sequenceWindow(),
                session.timeWindow(),
            )
        } catch (failure: RelayControlPlaneException) {
            if (failure.error.reason != "pair_limit") {
                throw failure
            }
            LogHelper.info(
                "RelaySessionLifecycleManager",
                "${session.origin} refused pairing with 490 pair_limit; flushing the drop list and retrying once",
            )
            flushDropList(session, token)
            controlPlaneClient.pair(
                session.origin,
                token,
                count,
                session.sequenceWindow(),
                session.timeWindow(),
            )
        }
    }

    /**
     * Sends whatever is owed now, rather than waiting for the next keep-alive tick.
     *
     * Best effort: a relay that refuses leaves the pairs to age out, which is no worse than
     * the caller was already facing.
     */
    private fun flushDropList(session: RelayOriginSession, token: Token) {
        val drop = synchronized(session) { session.pendingDropIds(maxPairsPerClient(session)) }
        if (drop.isEmpty()) {
            LogHelper.info(
                "RelaySessionLifecycleManager",
                "Nothing owed to flush for ${session.origin}; the ceiling is not this client's unreturned pairs",
            )
            return
        }
        runCatching { controlPlaneClient.keepAlive(session.origin, token, drop) }
            .onSuccess { synchronized(session) { session.acknowledgeDrops(drop) } }
            .onFailure {
                LogHelper.info(
                    "RelaySessionLifecycleManager",
                    "${session.origin} did not accept the drop list; ${drop.size} pairs will age out instead",
                )
            }
    }

    /**
     * What the relay accepts in one drop list, and the ceiling this client spends against.
     *
     * Falls back to the section 11 default when discovery has not been adopted yet, which is
     * only before the first auth.
     */
    private fun maxPairsPerClient(session: RelayOriginSession): Int {
        return session.getDiscovery()?.maxPairsPerClient ?: 200
    }

    private fun saveToken(origin: String, token: Token, pairIds: Collection<PairId> = emptyList()) {
        runCatching {
            val record = buildString {
                append(token.text)
                if (pairIds.isNotEmpty()) {
                    append('\n')
                    append(pairIds.joinToString(",") { it.text })
                }
            }
            stateStore?.save(origin, record.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun removePersistedToken(origin: String) {
        runCatching {
            stateStore?.remove(origin)
        }
    }

    private fun ensureKeepAliveLoop(session: RelayOriginSession) {
        if (session.getToken() == null || session.pairCount() == 0) {
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
        val token: Token?
        val pairIds: List<PairId>
        val drop: List<PairId>
        synchronized(session) {
            if (session.state != RelaySessionState.READY) {
                return
            }
            token = session.getToken()
            pairIds = session.currentRuntimePairIds()
            // Capped at what the relay accepts in one body; the remainder goes next tick.
            drop = session.pendingDropIds(maxPairsPerClient(session))
        }
        // A client that discarded its last pair still owes the relay the drop, so an empty
        // pool is not on its own a reason to skip.
        if (token == null || (pairIds.isEmpty() && drop.isEmpty())) {
            LogHelper.info(
                "RelaySessionLifecycleManager",
                "Skipping keep-alive for $origin because the token or the pairs are unavailable",
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
                "Sending keep-alive for $origin with ${pairIds.size} maintained pairs " +
                    "and ${drop.size} to drop: $pairIds",
            )
            // One call refreshes every pair this client holds and deletes the ones named.
            // The relay does not prune a live client's pairs on its own, so a discard this
            // client made is only real once it has been named here.
            controlPlaneClient.keepAlive(origin, token, drop)
            // Only what was sent. A discard made while this call was in flight is still owed,
            // and clearing the whole list would lose it with nothing left to name it.
            synchronized(session) { session.acknowledgeDrops(drop) }
            telemetry.lastSuccessAtEpochMillis = System.currentTimeMillis()
            telemetry.lastFailureMessage = null
            telemetry.lastEvent = "success"
        } catch (failure: Throwable) {
            val telemetry = keepAliveTelemetry(origin)
            telemetry.lastFailureMessage = failure.message ?: failure.javaClass.simpleName
            telemetry.lastEvent = "failed"
            // Keyed on the registry action, not on a status, and certainly not on a
            // substring of an exception message -- which is what this was. Five codes
            // carry reasons with opposite actions, so the status alone cannot decide.
            val action = (failure as? RelayControlPlaneException)?.error?.action
            if (action == RelayAction.FULL_REPAIR) {
                invalidateKeepAlivePool(origin, "invalidated after ${failure.error.code} ${failure.error.reason}")
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
            removePersistedToken(origin)
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

        /** The names the relay uses for the two encode types in its `encodeTypes` list. */
        /** The range RelayClientSettings accepts for a keepalive interval. */
        private const val MIN_KEEP_ALIVE_SECONDS = 60
        private const val MAX_KEEP_ALIVE_SECONDS = 600

        private const val ENCODE_TYPE_MKE = "MKE"
        private const val ENCODE_TYPE_MTE = "MTE"

        private fun defaultKeepAliveScheduler(): ScheduledExecutorService {
            return Executors.newSingleThreadScheduledExecutor(
                ThreadFactory { runnable ->
                    Thread(runnable, "relay-keepalive").apply { isDaemon = true }
                },
            )
        }
    }
}
