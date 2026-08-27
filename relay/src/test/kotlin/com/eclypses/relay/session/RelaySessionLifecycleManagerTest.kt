package com.eclypses.relay.session

import com.eclypses.relay.RelayClientSettings
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
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RelaySessionLifecycleManagerTest {

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
        assertNotNull(session.getPairMaterial("pair-1-1"))
        assertNotNull(session.getRuntimePair("pair-1-1"))
        assertEquals(1, controlPlane.authenticateCalls)
        assertEquals(1, controlPlane.pairCalls)
        assertEquals(null, controlPlane.existingClientIds.single())
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

        assertEquals(listOf<String?>("persisted-client"), controlPlane.existingClientIds)
        assertEquals("client-1", store.savedClientId("https://relay-a.example"))
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
        lifecycle.discardAndReplacePair("https://relay-a.example", "pair-1-2")

        val session = assertNotNull(manager.get("https://relay-a.example"))
        assertEquals(3, session.pairCount())
        assertEquals(3, session.runtimePairCount())
        assertEquals(null, session.getRuntimePair("pair-1-2"))
        assertNotNull(session.getRuntimePair("pair-2-1"))
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
        assertEquals("client-2", store.savedClientId("https://relay-a.example"))

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
        assertEquals("client-1", controlPlane.keepAliveClientIds.single())
        assertEquals(listOf("pair-1-1", "pair-1-2", "pair-1-3"), controlPlane.keepAlivePairIds.single())
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
    fun `keep alive 564 invalidates local pool for lazy re-pair`() {
        val manager = RelaySessionManager()
        val controlPlane = RecordingControlPlaneClient(keepAliveStatus = 564)
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
        assertEquals("client-1", store.savedClientId("https://relay-a.example"))

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
        assertEquals("client-1", store.savedClientId("https://relay-a.example"))

        clock.advanceMillis(TimeUnit.MINUTES.toMillis(10) + 1)
        lifecycle.ensureReady("https://relay-a.example")

        val snapshot = assertNotNull(manager.snapshot("https://relay-a.example"))
        assertEquals(RelaySessionState.READY, snapshot.state)
        assertEquals(2, snapshot.pairCount)
        assertEquals(2, controlPlane.authenticateCalls)
        assertEquals(2, controlPlane.pairCalls)
        assertEquals("client-2", store.savedClientId("https://relay-a.example"))
        assertTrue(scheduler.cancellations >= 1)
    }

    private class RecordingControlPlaneClient(
        private val failAuthenticate: Boolean = false,
        private val authDelayMs: Long = 0,
        private val keepAliveStatus: Int = 200,
    ) : RelayControlPlaneClient {
        var authenticateCalls = 0
        var pairCalls = 0
        var keepAliveCalls = 0
        val existingClientIds = mutableListOf<String?>()
        val pairPoolSizes = mutableListOf<Int>()
        val keepAliveClientIds = mutableListOf<String>()
        val keepAlivePairIds = mutableListOf<List<String>>()

        override fun authenticate(origin: String, existingClientId: String?): RelayAuthResponse {
            authenticateCalls += 1
            existingClientIds += existingClientId
            if (authDelayMs > 0) {
                Thread.sleep(authDelayMs)
            }
            if (failAuthenticate) {
                throw IllegalStateException("auth-failed")
            }
            return RelayAuthResponse("client-$authenticateCalls")
        }

        override fun pair(origin: String, clientId: String, pairPoolSize: Int): RelayPairingResult {
            pairCalls += 1
            pairPoolSizes += pairPoolSize
            val secret = Base64.getEncoder().encodeToString("secret-$pairCalls".toByteArray())
            val materials = (1..pairPoolSize).map {
                RelayPairMaterial(
                    pairId = "pair-$pairCalls-$it",
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
                    override val pairId: String = material.pairId
                    override fun encode(payload: ByteArray): ByteArray = payload
                    override fun decode(payload: ByteArray): ByteArray = payload
                    override fun startEncrypt() {}
                    override fun encryptChunk(buffer: ByteArray, length: Int) {}
                    override fun finishEncrypt(): ByteArray = ByteArray(0)
                    override fun encryptFinishBytes(): Int = 0
                    override fun startDecrypt() {}
                    override fun decryptChunk(buffer: ByteArray): ByteArray = buffer
                    override fun finishDecrypt(): ByteArray = ByteArray(0)
                }
            }
            return RelayPairingResult(materials = materials, runtimePairs = runtimePairs)
        }

        override fun keepAlive(origin: String, clientId: String, pairIds: List<String>) {
            keepAliveCalls += 1
            keepAliveClientIds += clientId
            keepAlivePairIds += pairIds
            if (keepAliveStatus != 200 && keepAliveStatus != 204) {
                throw IllegalStateException("keepalive request failed with status $keepAliveStatus")
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

        fun savedClientId(origin: String): String? = map[origin]?.toString(Charsets.UTF_8)
    }
}
