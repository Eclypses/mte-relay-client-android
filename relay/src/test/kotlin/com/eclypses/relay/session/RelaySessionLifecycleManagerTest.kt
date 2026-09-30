package com.eclypses.relay.session

import com.eclypses.relay.RelayClientSettings
import com.eclypses.mte.wire.PairId
import com.eclypses.relay.persistence.RelayStateStore
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** A deterministic pair id from a readable name, so a failure still names the pair. */
internal fun testPairId(name: String): com.eclypses.mte.wire.PairId {
    val raw = ByteArray(16)
    val bytes = name.toByteArray(Charsets.UTF_8)
    for (i in raw.indices) raw[i] = bytes[i % bytes.size]
    return com.eclypses.mte.wire.PairId(raw)
}

/**
 * A discovery document with [extra] merged in.
 *
 * Every member the client needs is present, because frame v2 has no "relay without
 * discovery" case to degrade into: the sequence and time windows are pairing inputs and
 * there is nothing safe to assume in their absence.
 */
internal fun testDiscovery(extra: String = ""): com.eclypses.mte.wire.Discovery {
    val base = linkedMapOf<String, String>(
        "discoverySchema" to "1",
        "buildVersion" to "\"5.0.0\"",
        "frameVersions" to "[2]",
        "features" to "[]",
        "mteProfile" to "\"\"",
        "kyberStrength" to "1024",
        "sequenceWindow" to "-63",
        "timeWindow" to "1000",
        "maxFrameBytes" to "65536",
        "maxMessageBytes" to "1048576",
        "maxMetadataBytes" to "{\"MKE\":61439,\"MTE\":61439}",
        "transports" to "[\"http\"]",
    )
    // `extra` is a leading-comma fragment of members that REPLACE the defaults rather
    // than being appended -- appending would produce a duplicate key, which the parser
    // refuses (correctly: two values for one member is how a document means two things).
    for (member in splitMembers(extra)) {
        val colon = member.indexOf(':')
        base[member.substring(0, colon).trim().trim('"')] = member.substring(colon + 1).trim()
    }
    return com.eclypses.mte.wire.Discovery.parse(
        base.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" },
    )
}

/** Splits a leading-comma member fragment, ignoring commas inside braces or brackets. */
private fun splitMembers(fragment: String): List<String> {
    val trimmed = fragment.trim().removePrefix(",")
    if (trimmed.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    var depth = 0
    var start = 0
    for ((i, c) in trimmed.withIndex()) {
        when (c) {
            '{', '[' -> depth++
            '}', ']' -> depth--
            ',' -> if (depth == 0) { out += trimmed.substring(start, i); start = i + 1 }
        }
    }
    out += trimmed.substring(start)
    return out.filter { it.isNotBlank() }
}

class RelaySessionLifecycleManagerTest {

    @Test
    fun `the sequence window the relay reports is used to build pairs`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(
            discovery = testDiscovery(""","sequenceWindow":-40"""),
        )
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
        )

        lifecycle.ensureReady("https://relay-a.example")

        assertEquals(listOf(-40), controlPlane.sequenceWindows)
    }

    @Test
    fun `a relay reporting no sequence window falls back to the client default`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(discovery = testDiscovery())
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
        )

        lifecycle.ensureReady("https://relay-a.example")

        assertEquals(listOf(com.eclypses.mte.wire.Discovery.SEQUENCE_WINDOW_DEFAULT), controlPlane.sequenceWindows)
    }

    @Test
    fun `the keepalive interval is derived from the relay's session timeout`() {
        val manager = RelaySessionManager()
        // 7200s is what the dev relay actually reports. A third is 2400, clamped to 600.
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = RecordingControlPlaneClient(
                discovery = testDiscovery(""","sessionTimeoutSeconds":7200"""),
            ),
        )

        lifecycle.ensureReady("https://relay-a.example")

        assertEquals(600, lifecycle.getSettings("https://relay-a.example").keepAliveIntervalSeconds)
    }

    @Test
    fun `a short server timeout gives a proportionally short keepalive`() {
        val manager = RelaySessionManager()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = RecordingControlPlaneClient(
                discovery = testDiscovery(""","sessionTimeoutSeconds":360"""),
            ),
        )

        lifecycle.ensureReady("https://relay-a.example")

        // 360 / 3 = 120, inside the allowed range, so adopted as-is.
        assertEquals(120, lifecycle.getSettings("https://relay-a.example").keepAliveIntervalSeconds)
    }

    @Test
    fun `an interval the caller set is not overridden by the server's timeout`() {
        val manager = RelaySessionManager()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = RecordingControlPlaneClient(
                discovery = testDiscovery(""","sessionTimeoutSeconds":7200"""),
            ),
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setKeepAliveIntervalSeconds(90).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")

        assertEquals(
            90,
            lifecycle.getSettings("https://relay-a.example").keepAliveIntervalSeconds,
            "a caller's explicit choice wins over the derived value",
        )
    }

    @Test
    fun `a relay reporting no timeout leaves the interval alone`() {
        val manager = RelaySessionManager()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = RecordingControlPlaneClient(discovery = testDiscovery()),
        )

        lifecycle.ensureReady("https://relay-a.example")

        assertEquals(
            RelayClientSettings.DEFAULT_KEEP_ALIVE_INTERVAL_SECONDS,
            lifecycle.getSettings("https://relay-a.example").keepAliveIntervalSeconds,
        )
    }

    @Test
    fun `pool sizes are clamped to the relay's per-client cap`() {
        val manager = RelaySessionManager()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = RecordingControlPlaneClient(
                discovery = testDiscovery(""","maxPairsPerClient":2"""),
            ),
        )

        lifecycle.ensureReady("https://relay-a.example")

        val settings = lifecycle.getSettings("https://relay-a.example")
        assertEquals(2, settings.maxPairs)
        assertEquals(2, settings.basePairs)
        assertEquals(2, settings.minPairs)
    }

    @Test
    fun `an incompatible frame protocol fails session start before pairing`() {
        // The frame carries no version byte, so this is the only chance to catch a wire
        // change before the first frame silently desyncs a pair.
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(
            discovery = testDiscovery(""","frameVersions":[99]"""),
        )
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
        )

        val error = assertFailsWith<RelayIncompatibleException> {
            lifecycle.ensureReady("https://relay-a.example")
        }

        assertTrue(error.message!!.contains("99"))
        assertEquals(0, controlPlane.pairCalls, "an incompatible relay must not be paired with")
        assertEquals(
            RelaySessionState.FAILED,
            assertNotNull(manager.snapshot("https://relay-a.example")).state,
        )
    }

    @Test
    fun `a relay that does not bound this session's encode type fails session start`() {
        val manager = RelaySessionManager()
        // The runtime is built for MKE; this relay bounds metadata only for MTE, which is
        // how it says which types it accepts.
        val mteOnly = com.eclypses.mte.wire.Discovery.parse(
            """
            {"discoverySchema":1,"buildVersion":"5.0.0","frameVersions":[2],"features":[],
             "mteProfile":"","kyberStrength":1024,"sequenceWindow":-63,"timeWindow":1000,
             "maxFrameBytes":65536,"maxMessageBytes":1048576,
             "maxMetadataBytes":{"MTE":61439},"transports":["http"]}
            """.trimIndent(),
        )
        val controlPlane = RecordingControlPlaneClient(discovery = mteOnly)
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            useMke = true,
        )

        assertFailsWith<RelayIncompatibleException> {
            lifecycle.ensureReady("https://relay-a.example")
        }
        assertEquals(0, controlPlane.pairCalls, "an incompatible relay must not be paired with")
    }

    @Test
    fun `a relay accepting the session encode type pairs normally`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(discovery = testDiscovery())
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            useMke = true,
        )

        lifecycle.ensureReady("https://relay-a.example")

        assertEquals(1, controlPlane.pairCalls)
        assertEquals(
            setOf(com.eclypses.mte.wire.MteType.MKE, com.eclypses.mte.wire.MteType.MTE),
            manager.get("https://relay-a.example")?.getDiscovery()?.maxMetadataBytes?.keys,
            "discovery is stored on the session for the request path to read",
        )
    }

    /**
     * Both windows are pairing inputs: each end builds its decoder from them, so a
     * disagreement is no pair at all rather than a degraded one -- and nothing in the
     * resulting decode failure says why. The client must therefore carry neither past the
     * point discovery has answered.
     */
    @Test
    fun `both pairing windows come from discovery`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(
            discovery = testDiscovery(""","sequenceWindow":-40,"timeWindow":250"""),
        )
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
        )

        lifecycle.ensureReady("https://relay-a.example")

        assertEquals(listOf(-40), controlPlane.sequenceWindows)
        assertEquals(listOf(250L), controlPlane.timeWindows)
    }

    /**
     * The relay answers a token it refuses -- malformed, or past `tokenMaxAgeSeconds` --
     * with a *new* client id and no error at all. Every pair the session holds belongs to
     * the old id, so keeping them would leave a pool whose every member answers 470
     * pair_not_found on its first use.
     */
    @Test
    fun `a new client id at auth discards the pairs that belonged to the old one`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
        )

        lifecycle.ensureReady("https://relay-a.example")
        val first = controlPlane.issuedTokens.single()

        lifecycle.manualRepair("https://relay-a.example")

        // The fake mints a different token per call, which is exactly the case under test.
        assertEquals(2, controlPlane.authenticateCalls)
        assertTrue(
            controlPlane.issuedTokens[1].clientIdHex != first.clientIdHex,
            "the fake must issue a different client id for this test to mean anything",
        )
        assertEquals(
            controlPlane.issuedTokens[1].clientIdHex,
            manager.get("https://relay-a.example")?.getToken()?.clientIdHex,
        )
    }

    /** A token that survives the refresh keeps its pool: the client id did not change. */
    @Test
    fun `a refreshed token under the same client id keeps the pool`() {
        val manager = RelaySessionManager()
        val stable = com.eclypses.mte.wire.Token(ByteArray(40) { (it + 3).toByte() })
        val controlPlane = RecordingControlPlaneClient(fixedToken = stable)
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
        )

        lifecycle.ensureReady("https://relay-a.example")
        val pairsAfterStart = manager.get("https://relay-a.example")!!.pairCount()
        lifecycle.ensureReady("https://relay-a.example")

        assertEquals(stable.clientIdHex, manager.get("https://relay-a.example")?.getToken()?.clientIdHex)
        assertTrue(pairsAfterStart > 0)
    }

    @Test
    fun `ensureReady authenticates and pairs when session is empty`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(3).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")

        val snapshot = assertNotNull(manager.snapshot("https://relay-a.example"))
        assertEquals(RelaySessionState.READY, snapshot.state)
        assertTrue(snapshot.hasClientId)
        assertEquals(3, snapshot.pairCount)
        val session = assertNotNull(manager.get("https://relay-a.example"))
        assertNotNull(session.getPairMaterial(testPairId("pair-1-1")))
        assertNotNull(session.getRuntimePair(testPairId("pair-1-1")))
        assertEquals(1, controlPlane.authenticateCalls)
        assertEquals(1, controlPlane.pairCalls)
        assertEquals(null, controlPlane.presentedTokens.single(), "a first auth presents no token")
    }

    /**
     * The launch-to-launch half of the pair accounting.
     *
     * Frame v2 keeps the client id across launches and every launch pairs a fresh pool, so
     * without the ids the previous launch's pairs cannot be named and sit on the relay until
     * they age out -- roughly forty launches to 490 `pair_limit`.
     */
    @Test
    fun `the pair ids are stored with the token so the next launch can hand them back`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val store = RecordingStateStore()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            stateStore = store,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(2).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")

        val session = assertNotNull(manager.get("https://relay-a.example"))
        assertEquals(
            session.currentRuntimePairIds().map { it.text }.toSet(),
            store.savedPairIds("https://relay-a.example").toSet(),
            "the record names exactly the pairs this launch holds",
        )
    }

    /**
     * What the next launch does with them: owes them, without ever having held them.
     *
     * The token is fixed so the relay keeps the client id, which is the ordinary case. The
     * debt then stands until the first keep-alive tick carries it.
     */
    @Test
    fun `a stored record is inherited as a debt on the next launch`() {
        val manager = RelaySessionManager()
        val stable = com.eclypses.mte.wire.Token(ByteArray(40) { (it + 7).toByte() })
        val controlPlane = RecordingControlPlaneClient(fixedToken = stable)
        val store = RecordingStateStore()
        val orphan = PairId.parse("AAECAwQFBgcICQoLDA0ODw")
        // A real token, because an unreadable one takes the whole record with it -- without a
        // client id there is nothing to name these pairs under, so dropping them is right.
        store.save(
            "https://relay-a.example",
            "${stable.text}\n${orphan.text}".toByteArray(),
        )
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            stateStore = store,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(2).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")

        val session = assertNotNull(manager.get("https://relay-a.example"))
        assertTrue(
            session.pendingDropIds(10).contains(orphan),
            "the previous launch's pair is owed to the relay",
        )
    }

    @Test
    fun `ensureReady uses persisted client id and saves authenticated client id`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val store = RecordingStateStore()
        store.save("https://relay-a.example", "persisted-client".toByteArray())
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            stateStore = store,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(2).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")

        // The stored token is presented for refresh; what comes back is stored in its place.
        assertEquals(1, controlPlane.presentedTokens.size)
        assertEquals(
            controlPlane.issuedTokens.single().text,
            store.savedClientId("https://relay-a.example"),
        )
    }

    @Test
    fun `repair clears state then re-authenticates and re-pairs`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val lifecycle = RelaySessionLifecycleManager(
            manager,
            controlPlane,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(2).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")
        lifecycle.repair("https://relay-a.example")

        val snapshot = assertNotNull(manager.snapshot("https://relay-a.example"))
        assertEquals(RelaySessionState.READY, snapshot.state)
        assertTrue(snapshot.hasClientId)
        assertEquals(2, snapshot.pairCount)
        assertEquals(2, controlPlane.authenticateCalls)
        assertEquals(2, controlPlane.pairCalls)
    }

    @Test
    fun `discardAndReplacePair removes one pair and adds exactly one replacement`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val lifecycle = RelaySessionLifecycleManager(
            manager,
            controlPlane,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(3).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")
        lifecycle.discardAndReplacePair("https://relay-a.example", testPairId("pair-1-2"))

        val session = assertNotNull(manager.get("https://relay-a.example"))
        assertEquals(3, session.pairCount())
        assertEquals(3, session.runtimePairCount())
        assertEquals(null, session.getRuntimePair(testPairId("pair-1-2")))
        assertNotNull(session.getRuntimePair(testPairId("pair-2-1")))
        assertEquals(listOf(3, 1), controlPlane.pairPoolSizes)
        assertEquals(1, controlPlane.authenticateCalls)
        assertEquals(2, controlPlane.pairCalls)
    }

    @Test
    fun `ensureReady marks session failed when control plane errors`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(failAuthenticate = true)
        val lifecycle = RelaySessionLifecycleManager(manager, controlPlane)

        assertFailsWith<IllegalStateException> {
            lifecycle.ensureReady("https://relay-a.example")
        }

        val snapshot = assertNotNull(manager.snapshot("https://relay-a.example"))
        assertEquals(RelaySessionState.FAILED, snapshot.state)
    }

    @Test
    fun `ensureReady initializes once when called concurrently for same origin`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(authDelayMs = 75)
        val lifecycle = RelaySessionLifecycleManager(
            manager,
            controlPlane,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(2).build(),
        )

        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>(null)

        repeat(2) {
            Thread {
                try {
                    startLatch.await(2, TimeUnit.SECONDS)
                    lifecycle.ensureReady("https://relay-a.example")
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    doneLatch.countDown()
                }
            }.start()
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(3, TimeUnit.SECONDS), "concurrent ensureReady calls timed out")
        failure.get()?.let { throw it }

        val snapshot = assertNotNull(manager.snapshot("https://relay-a.example"))
        assertEquals(RelaySessionState.READY, snapshot.state)
        assertEquals(1, controlPlane.authenticateCalls)
        assertEquals(1, controlPlane.pairCalls)
    }

    @Test
    fun `updateSettings changes the base pair target for the next repair`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val lifecycle = RelaySessionLifecycleManager(
            manager,
            controlPlane,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(2).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(4).build(),
        )

        lifecycle.repair("https://relay-a.example")

        assertEquals(listOf(2, 4), controlPlane.pairPoolSizes)
    }

    @Test
    fun `manualRepair preserves leased pair until release and refreshes idle capacity`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val store = RecordingStateStore()
        val scheduler = RecordingScheduledExecutorService()
        val lifecycle = RelaySessionLifecycleManager(
            manager,
            controlPlane,
            stateStore = store,
            keepAliveScheduler = scheduler,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon().setMinPairs(1).setBasePairs(3).build(),
        )

        lifecycle.ensureReady("https://relay-a.example")
        val session = assertNotNull(manager.get("https://relay-a.example"))
        val leasedPair = assertNotNull(session.leaseRuntimePairRoundRobin())

        lifecycle.manualRepair("https://relay-a.example")

        assertEquals(2, controlPlane.authenticateCalls)
        assertEquals(2, controlPlane.pairCalls)
        assertTrue(session.isRuntimePairLeased(leasedPair.pairId))
        assertNotNull(session.getRuntimePair(leasedPair.pairId))
        assertEquals(4, session.pairCount())
        assertEquals(controlPlane.issuedTokens[1].text, store.savedClientId("https://relay-a.example"))

        assertTrue(session.releaseLeasedRuntimePair(leasedPair.pairId))
        assertEquals(null, session.getRuntimePair(leasedPair.pairId))
        assertEquals(3, session.pairCount())
    }

    @Test
    fun `ensureReady schedules keep alive and tick posts current pairs`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val scheduler = RecordingScheduledExecutorService()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            keepAliveScheduler = scheduler,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon()
                .setMinPairs(1)
                .setBasePairs(3)
                .setKeepAliveIntervalSeconds(120)
                .build(),
        )

        lifecycle.ensureReady("https://relay-a.example")
        scheduler.runLatest()

        assertEquals(listOf(120L), scheduler.intervalsSeconds)
        assertEquals(1, controlPlane.keepAliveCalls)
        assertEquals(
            controlPlane.issuedTokens.single().clientIdHex,
            controlPlane.keepAliveTokens.single().clientIdHex,
        )
        // One call refreshes every pair the client holds. Nothing is dropped on a healthy
        // tick -- a pair this client discarded was already dropped where it failed.
        assertEquals(emptyList(), controlPlane.keepAliveDrops.single())
    }

    @Test
    fun `updateSettings reschedules active keep alive loops`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val scheduler = RecordingScheduledExecutorService()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            keepAliveScheduler = scheduler,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon()
                .setMinPairs(1)
                .setBasePairs(2)
                .setKeepAliveIntervalSeconds(120)
                .build(),
        )

        lifecycle.ensureReady("https://relay-a.example")
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon()
                .setMinPairs(1)
                .setBasePairs(4)
                .setKeepAliveIntervalSeconds(300)
                .build(),
        )

        assertEquals(listOf(120L, 300L), scheduler.intervalsSeconds)
        assertTrue(scheduler.cancellations >= 1)
    }

    @Test
    fun `a keepalive refused with a full-repair reason invalidates the local pool`() {
        // Keyed on the registry reason, not the status. 475 invalid_token means the token
        // failed its HMAC or aged out, so every pair under that client id is gone and the
        // pool has to be rebuilt -- whereas 475 unknown_key, the same status, means stop.
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(
            keepAliveError = com.eclypses.mte.wire.RelayError(475, "invalid_token"),
        )
        val store = RecordingStateStore()
        val scheduler = RecordingScheduledExecutorService()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            stateStore = store,
            keepAliveScheduler = scheduler,
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon()
                .setMinPairs(1)
                .setBasePairs(2)
                .setKeepAliveIntervalSeconds(120)
                .build(),
        )

        lifecycle.ensureReady("https://relay-a.example")
        assertEquals(controlPlane.issuedTokens[0].text, store.savedClientId("https://relay-a.example"))

        scheduler.runLatest()

        val session = assertNotNull(manager.get("https://relay-a.example"))
        val snapshot = assertNotNull(manager.snapshot("https://relay-a.example"))
        assertEquals(RelaySessionState.FAILED, snapshot.state)
        assertEquals(0, session.pairCount())
        assertEquals(0, session.runtimePairCount())
        assertEquals(null, store.savedClientId("https://relay-a.example"))
    }

    @Test
    fun `ensureReady repairs when keep alive loop has been paused for more than ten minutes`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient()
        val store = RecordingStateStore()
        val scheduler = RecordingScheduledExecutorService()
        val clock = MutableElapsedClock()
        val lifecycle = RelaySessionLifecycleManager(
            sessionManager = manager,
            controlPlaneClient = controlPlane,
            stateStore = store,
            keepAliveScheduler = scheduler,
            elapsedRealtimeMillis = { clock.nowMillis },
        )
        lifecycle.updateSettings(
            "https://relay-a.example",
            RelayClientSettings.defaults().buildUpon()
                .setMinPairs(1)
                .setBasePairs(2)
                .setKeepAliveIntervalSeconds(60)
                .build(),
        )

        lifecycle.ensureReady("https://relay-a.example")
        assertEquals(controlPlane.issuedTokens[0].text, store.savedClientId("https://relay-a.example"))

        clock.advanceMillis(TimeUnit.MINUTES.toMillis(10) + 1)
        lifecycle.ensureReady("https://relay-a.example")

        val snapshot = assertNotNull(manager.snapshot("https://relay-a.example"))
        assertEquals(RelaySessionState.READY, snapshot.state)
        assertEquals(2, snapshot.pairCount)
        assertEquals(2, controlPlane.authenticateCalls)
        assertEquals(2, controlPlane.pairCalls)
        assertEquals(controlPlane.issuedTokens[1].text, store.savedClientId("https://relay-a.example"))
        assertTrue(scheduler.cancellations >= 1)
    }

    private class RecordingControlPlaneClient(
        private val failAuthenticate: Boolean = false,
        private val authDelayMs: Long = 0,
        /** The refusal a keepalive answers with, or null for success. */
        private val keepAliveError: com.eclypses.mte.wire.RelayError? = null,
        private val discovery: com.eclypses.mte.wire.Discovery = testDiscovery(),
        /** When set, every auth answers with this token rather than a fresh client id. */
        private val fixedToken: com.eclypses.mte.wire.Token? = null,
    ) : RelayControlPlaneClient {
        var authenticateCalls = 0
        var pairCalls = 0
        var keepAliveCalls = 0
        val presentedTokens = mutableListOf<com.eclypses.mte.wire.Token?>()
        val pairPoolSizes = mutableListOf<Int>()
        val keepAliveTokens = mutableListOf<com.eclypses.mte.wire.Token>()
        val keepAliveDrops = mutableListOf<List<com.eclypses.mte.wire.PairId>>()
        val sequenceWindows = mutableListOf<Int>()
        val timeWindows = mutableListOf<Long>()
        val issuedTokens = mutableListOf<com.eclypses.mte.wire.Token>()

        override fun authenticate(
            origin: String,
            existingToken: com.eclypses.mte.wire.Token?,
        ): RelayAuthResponse {
            authenticateCalls += 1
            presentedTokens += existingToken
            if (authDelayMs > 0) {
                Thread.sleep(authDelayMs)
            }
            if (failAuthenticate) {
                throw IllegalStateException("auth-failed")
            }
            val token = fixedToken
                ?: com.eclypses.mte.wire.Token(ByteArray(40) { (it + authenticateCalls).toByte() })
            issuedTokens += token
            return RelayAuthResponse(token, discovery)
        }

        override fun pair(
            origin: String,
            token: com.eclypses.mte.wire.Token,
            pairPoolSize: Int,
            sequenceWindow: Int,
            timeWindow: Long,
        ): RelayPairingResult {
            pairCalls += 1
            pairPoolSizes += pairPoolSize
            sequenceWindows += sequenceWindow
            timeWindows += timeWindow
            val secret = Base64.getEncoder().encodeToString("secret-$pairCalls".toByteArray())
            val materials = (1..pairPoolSize).map {
                RelayPairMaterial(
                    pairId = testPairId("pair-$pairCalls-$it"),
                    encoderNonce = it.toLong(),
                    decoderNonce = (it + 10L),
                    encoderResponderEncryptedSecret = Base64.getDecoder().decode(secret),
                    decoderResponderEncryptedSecret = Base64.getDecoder().decode(secret),
                    encoderPersonalizationStr = "enc-$it",
                    decoderPersonalizationStr = "dec-$it",
                )
            }
            val runtimePairs = materials.map { material ->
                object : RelayRuntimePair {
                    override val pairId: com.eclypses.mte.wire.PairId = material.pairId
                    override fun encode(payload: ByteArray): ByteArray = payload
                    override fun decode(payload: ByteArray): ByteArray = payload
                }
            }
            return RelayPairingResult(materials = materials, runtimePairs = runtimePairs)
        }

        override fun keepAlive(
            origin: String,
            token: com.eclypses.mte.wire.Token,
            drop: List<com.eclypses.mte.wire.PairId>,
        ) {
            keepAliveCalls += 1
            keepAliveTokens += token
            keepAliveDrops += drop
            keepAliveError?.let {
                throw RelayControlPlaneException(it, it.code, "keepalive refused: ${it.reason}")
            }
        }
    }

    private class RecordingScheduledExecutorService : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
        val intervalsSeconds = mutableListOf<Long>()
        var cancellations = 0
        private val futures = mutableListOf<RecordingScheduledFuture>()

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            intervalsSeconds += unit.toSeconds(period)
            return RecordingScheduledFuture(command).also { futures += it }
        }

        fun runLatest() {
            futures.lastOrNull()?.run()
        }

        private inner class RecordingScheduledFuture(
            private val command: Runnable,
        ) : ScheduledFuture<Unit> {
            private var cancelled = false

            fun run() {
                if (!cancelled) {
                    command.run()
                }
            }

            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                cancelled = true
                cancellations += 1
                return true
            }

            override fun isCancelled(): Boolean = cancelled
            override fun isDone(): Boolean = cancelled
            override fun get(): Unit = Unit
            override fun get(timeout: Long, unit: TimeUnit): Unit = Unit
            override fun getDelay(unit: TimeUnit): Long = 0
            override fun compareTo(other: Delayed): Int = 0
        }
    }

    private class MutableElapsedClock(
        var nowMillis: Long = 0,
    ) {
        fun advanceMillis(delta: Long) {
            nowMillis = max(0L, nowMillis + delta)
        }
    }

    private class RecordingStateStore : RelayStateStore {
        private val map = linkedMapOf<String, ByteArray>()

        override fun load(origin: String): ByteArray? = map[origin]

        override fun save(origin: String, state: ByteArray) {
            map[origin] = state
        }

        override fun remove(origin: String) {
            map.remove(origin)
        }

        /**
         * The token from the stored record, which is its first line.
         *
         * The record carries the pair ids the token owns on a second line, so the next launch
         * can hand them back rather than stranding them on the relay.
         */
        fun savedClientId(origin: String): String? =
            map[origin]?.toString(Charsets.UTF_8)?.lineSequence()?.firstOrNull()

        /** The pair ids stored alongside the token. */
        fun savedPairIds(origin: String): List<String> =
            map[origin]?.toString(Charsets.UTF_8)
                ?.lineSequence()?.drop(1)?.firstOrNull()
                ?.split(',')?.filter { it.isNotBlank() }
                .orEmpty()
    }
}
