package com.eclypses.relay.streaming

import com.eclypses.relay.RelayFileRequestProperties
import com.eclypses.relay.RelaySseListener
import com.eclypses.relay.RelayStreamCallback
import com.eclypses.relay.RelayStreamCompletionCallback
import com.eclypses.relay.RelayStreamResponseListener
import com.eclypses.mte.wire.DataPlaintext
import com.eclypses.mte.wire.Discovery
import com.eclypses.mte.wire.Envelope
import com.eclypses.mte.wire.Json
import com.eclypses.mte.wire.JsonNumber
import com.eclypses.mte.wire.JsonObject
import com.eclypses.mte.wire.JsonString
import com.eclypses.mte.wire.JsonValue
import com.eclypses.mte.wire.Kind
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.RequestFrame
import com.eclypses.mte.wire.ResponseFrame
import com.eclypses.mte.wire.Token
import com.eclypses.relay.session.RelayAuthResponse
import com.eclypses.relay.session.RelayControlPlaneClient
import com.eclypses.relay.session.RelayRequestTooLargeException
import com.eclypses.relay.session.RelayOriginSession
import com.eclypses.relay.session.RelayPairingResult
import com.eclypses.relay.session.RelayPairMaterial
import com.eclypses.relay.session.RelayRuntimePair
import com.eclypses.relay.session.RelaySessionLifecycleManager
import com.eclypses.relay.session.RelaySessionManager
import com.eclypses.relay.session.RelaySessionState
import com.eclypses.relay.transport.RelayStreamingTransportRequest
import com.eclypses.relay.transport.RelayTransport
import com.eclypses.relay.transport.RelayPassThroughRequest
import com.eclypses.relay.transport.RelayTransportRequest
import com.eclypses.relay.transport.RelayTransportResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class RelayStreamingExecutorTest {

    @Test
    fun uploadFile_cancelledOperation_propagatesDeterministicError() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-upload-cancel"))
        val (sm, slm) = readySession("https://relay.example", "client-upload-cancel", fakePair)
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeStreaming(request: RelayStreamingTransportRequest): RelayTransportResponse =
                throw AssertionError("a cancelled upload must not reach the transport")
        }

        val requestProperties = RelayFileRequestProperties(
            "https://relay.example",
            "/upload",
            null,
            linkedMapOf("Content-Type" to "application/octet-stream", "Content-Length" to "1024"),
            null,
            RelayStreamCallback { output ->
                output.write(ByteArray(1024))
            },
        )

        val listenerResult = AtomicReference<StreamListenerResult>()
        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
        )
        val operationId = executor.beginOperation()
        assertTrue(executor.cancelOperation(operationId))

        executor.uploadFile(
            operationId = operationId,
            reqProperties = requestProperties,
            listener = recordingListener(listenerResult),
            completionCallback = null,
        )

        val listener = assertNotNull(listenerResult.get())
        assertEquals(-1, listener.statusCode)
        assertFalse(listener.success)
        assertTrue(listener.errorMessage?.contains("cancelled") == true)
    }

    @Test
    fun downloadFile_cancelledOperation_propagatesDeterministicError() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-download-cancel"))
        val (sm, slm) = readySession("https://relay.example", "client-download-cancel", fakePair)
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse = throw AssertionError("a cancelled download must not reach the transport")
        }

        val targetFile = File.createTempFile("relay-download-cancel-", ".tmp")
        targetFile.delete()

        val requestProperties = RelayFileRequestProperties(
            "https://relay.example",
            "/download",
            targetFile.absolutePath,
            null,
            linkedMapOf(),
            null,
        )

        val listenerResult = AtomicReference<StreamListenerResult>()
        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
        )
        val operationId = executor.beginOperation()
        assertTrue(executor.cancelOperation(operationId))

        executor.downloadFile(
            operationId = operationId,
            reqProperties = requestProperties,
            listener = recordingListener(listenerResult),
        )

        val listener = assertNotNull(listenerResult.get())
        assertEquals(-1, listener.statusCode)
        assertFalse(listener.success)
        assertTrue(listener.errorMessage?.contains("cancelled") == true)
        assertFalse(targetFile.exists())
        assertNull(listener.responseStr)
    }


    private fun recordingListener(resultRef: AtomicReference<StreamListenerResult>): RelayStreamResponseListener {
        return RelayStreamResponseListener { statusCode, success, responseStr, errorMessage, responseHeaders ->
            resultRef.set(
                StreamListenerResult(
                    statusCode = statusCode,
                    success = success,
                    responseStr = responseStr,
                    errorMessage = errorMessage,
                    headerCount = responseHeaders?.size ?: 0,
                ),
            )
        }
    }

    private data class StreamListenerResult(
        val statusCode: Int,
        val success: Boolean,
        val responseStr: String?,
        val errorMessage: String?,
        val headerCount: Int,
    )

    // ---- streaming upload integration tests ------------------------------------

    /** A pair whose codec is the identity, so a test can read what the writer produced. */
    private class FakeRuntimePair(
        override val pairId: PairId,
    ) : RelayRuntimePair {
        override fun encode(payload: ByteArray) = payload
        override fun decode(payload: ByteArray) = payload
    }

    private inner class FakeControlPlaneClient(
        private val token: Token,
        private val runtimePairs: List<RelayRuntimePair>,
    ) : RelayControlPlaneClient {
        override fun authenticate(origin: String, existingToken: Token?) =
            RelayAuthResponse(token, fakeDiscovery())

        override fun pair(
            origin: String,
            token: Token,
            pairPoolSize: Int,
            sequenceWindow: Int,
            timeWindow: Long,
        ) = RelayPairingResult(emptyList(), runtimePairs)

        override fun keepAlive(origin: String, token: Token, drop: List<PairId>) {
        }
    }

    /** Configures a [RelaySessionManager] with a READY session holding [pair] and [clientId]. */
    @Test
    fun framePost_carriesTheClientIdAsTheRouteHeader() {
        // Section 11: the route header is the client id hex, the same value for the
        // client's life. The previous generation sent the pair id -- a different value per
        // request -- which scattered one client's pairs across replicas, the opposite of
        // what a consistent hash in front of a multi-replica relay is for, and unworkable
        // under owner mode where a client's pair state lives on one pod.
        val fakePair = FakeRuntimePair(pairIdNamed("pair-routing-1"))
        val (sm, slm) = readySession("https://relay.example", "client-routing", fakePair)
        val token = assertNotNull(sm.getOrCreate("https://relay.example").getToken())

        val captured = AtomicReference<Map<String, String>>()
        val transport = capturingTransport(captured)

        RelayStreamingExecutor(sm, slm, transport)
            .executeBuffered(
                "https://relay.example", "/api/data", null, "GET", ByteArray(0), linkedMapOf(), null,
            )

        val headers = assertNotNull(captured.get())
        assertEquals(token.clientIdHex, headers["X-MTE-Relay-Route"])
        assertEquals(32, token.clientIdHex.length, "the client id is 16 bytes as hex")
        assertEquals("application/octet-stream", headers["Content-Type"])
        // The relay reads the frame itself, not a decompressed view of it.
        assertEquals("identity", headers["Accept-Encoding"])
    }

    @Test
    fun framePost_doesNotLeakThePairIdOntoThePlainHop() {
        // The pair id used to ride the hop as the routing value. It is inside the
        // encrypted REQUEST frame now and nowhere else, so an observer counting distinct
        // header values can no longer count a client's pairs.
        val fakePair = FakeRuntimePair(pairIdNamed("pair-routing-2"))
        val (sm, slm) = readySession("https://relay.example", "client-routing", fakePair)

        val captured = AtomicReference<Map<String, String>>()
        val transport = capturingTransport(captured)

        RelayStreamingExecutor(sm, slm, transport)
            .executeBuffered(
                "https://relay.example", "/api/data", null, "GET", ByteArray(0), linkedMapOf(), null,
            )

        val headers = assertNotNull(captured.get())
        val pairText = pairIdNamed("pair-routing-2").text
        val pairHex = pairIdNamed("pair-routing-2").hex
        assertTrue(
            headers.values.none { it.contains(pairText) || it.contains(pairHex) },
            "the pair id must not appear in any plain header: $headers",
        )
    }

    @Test
    fun framePost_callerCannotOverrideTheRouteHeader() {
        // x-mte-relay-route is reserved, so naming it in unencryptedHeaders throws before any
        // network work. This pins that the SDK's own value is what reaches the hop.
        val fakePair = FakeRuntimePair(pairIdNamed("pair-routing-3"))
        val (sm, slm) = readySession("https://relay.example", "client-routing", fakePair)
        val token = assertNotNull(sm.getOrCreate("https://relay.example").getToken())

        val captured = AtomicReference<Map<String, String>>()
        val transport = capturingTransport(captured)

        RelayStreamingExecutor(sm, slm, transport).executeBuffered(
            "https://relay.example",
            "/api/data",
            null,
            "GET",
            ByteArray(0),
            linkedMapOf("X-MTE-Relay-Route" to "attacker-chosen"),
            null,
        )

        val headers = assertNotNull(captured.get())
        assertEquals(token.clientIdHex, headers["X-MTE-Relay-Route"], "the SDK owns this header")
    }

    @Test
    fun passThroughRoute_isSentUnframedAndLeasesNoPair() {
        // Without this the request becomes a frame to POST /, which a pass-through route has
        // no pair state for — the route is simply unreachable through the SDK.
        val fakePair = FakeRuntimePair(pairIdNamed("pair-passthrough"))
        val (sm, slm) = readySession("https://relay.example", "client-pt", fakePair)
        sm.getOrCreate("https://relay.example")
            .setDiscovery(fakeDiscovery(""","passThroughRoutes":["/health"]"""))

        val seen = AtomicReference<RelayPassThroughRequest>()
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse = throw AssertionError("a pass-through route must not be framed")

            override fun executePassThrough(request: RelayPassThroughRequest): RelayTransportResponse {
                seen.set(request)
                return RelayTransportResponse(200, "OK".toByteArray(), mapOf("X-Health" to "green"))
            }
        }

        val response = RelayStreamingExecutor(sm, slm, transport)
            .executeBuffered(
                "https://relay.example", "/health", null, "GET", ByteArray(0), linkedMapOf(), null,
            )

        val sent = assertNotNull(seen.get())
        assertEquals("https://relay.example/health", sent.url)
        assertEquals("GET", sent.method)
        assertNull(sent.body, "a bodyless GET must stay bodyless")
        assertEquals(200, response.statusCode)
        assertEquals("OK", String(response.payload))
        assertEquals("green", response.headers["X-Health"])
        assertFalse(
            sm.getOrCreate("https://relay.example").isRuntimePairLeased(pairIdNamed("pair-passthrough")),
            "no pair should be reserved for a request that never uses one",
        )
    }

    @Test
    fun passThroughRoute_carriesTheCallerHeadersAndBody() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-pt-body"))
        val (sm, slm) = readySession("https://relay.example", "client-pt", fakePair)
        sm.getOrCreate("https://relay.example")
            .setDiscovery(fakeDiscovery(""","passThroughRoutes":["/public/*"]"""))

        val seen = AtomicReference<RelayPassThroughRequest>()
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executePassThrough(request: RelayPassThroughRequest): RelayTransportResponse {
                seen.set(request)
                return RelayTransportResponse(204, ByteArray(0), emptyMap())
            }
        }

        RelayStreamingExecutor(sm, slm, transport).executeBuffered(
            "https://relay.example",
            "/public/submit",
            null,
            "POST",
            "hello".toByteArray(),
            linkedMapOf("X-Tenant-Id" to "acme"),
            null,
        )

        val sent = assertNotNull(seen.get())
        // Nothing is framed, so there is no encrypted channel and every header travels as-is.
        assertEquals("acme", sent.headers["X-Tenant-Id"])
        assertEquals("hello", String(assertNotNull(sent.body)))
    }

    @Test
    fun oversizedBody_failsBeforeAnyNetworkCall() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-toolarge"))
        val (sm, slm) = readySession("https://relay.example", "client-large", fakePair)
        sm.getOrCreate("https://relay.example")
            .setDiscovery(fakeDiscovery(""","maxRequestBodyBytes":10"""))

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse = throw AssertionError("an oversized body must not be sent")
        }

        val error = assertFailsWith<RelayRequestTooLargeException> {
            RelayStreamingExecutor(sm, slm, transport).executeBuffered(
                "https://relay.example", "/api", null, "POST", ByteArray(11), linkedMapOf(), null,
            )
        }

        // Both numbers, so the caller can tell a body they can shrink from a limit they cannot.
        assertEquals(11L, error.sizeBytes)
        assertEquals(10L, error.limitBytes)
    }

    /** A pair whose encoder always refuses, as a codec with unusable state would. */
    private class RefusingRuntimePair(
        override val pairId: PairId,
    ) : RelayRuntimePair {
        override fun encode(payload: ByteArray): ByteArray = throw IllegalStateException("encode failed")
        override fun decode(payload: ByteArray) = payload
    }

    /**
     * The failure nothing else in the client is watching for.
     *
     * Pair replacement keys on relay status codes, and those only arrive once a request
     * reaches the relay. A local codec failure happens before a byte is sent, so without this
     * the pair stays in the pool and the next request that selects it fails the same way --
     * state is saved only on success, so nothing about it has changed.
     */
    @Test
    fun aPairWhoseEncoderRefuses_isDiscardedAndOwedToTheRelay() {
        val pairId = pairIdNamed("pair-refusing")
        val (sm, slm) = readySession("https://relay.example", "client-refusing", RefusingRuntimePair(pairId))
        val session = sm.getOrCreate("https://relay.example")
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw AssertionError("must not be sent")
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse = throw AssertionError("must not be sent")
        }

        runCatching {
            RelayStreamingExecutor(sm, slm, transport).executeBuffered(
                "https://relay.example", "/api", null, "GET", ByteArray(0), linkedMapOf(), null,
            )
        }

        // The drop list is the claim: the pair was removed, and the relay is owed a delete for
        // it. Pool membership is not asserted because the fake control plane answers the
        // replacement request with the same pair id, which a real relay never does -- that
        // would be testing the fixture.
        assertTrue(
            session.pendingDropIds(10).contains(pairId),
            "a pair whose encoder refused must be discarded and owed to the relay",
        )
    }

    @Test
    fun bodyAtTheLimit_isRejectedBecauseTheFrameAroundItIsLarger() {
        // The relay's limit is on what it receives, which is the frame, not the caller's body.
        // A live relay 413s a body sized exactly at its limit — the frame carrying it is bigger
        // by the metadata section and the MKE overhead. Rejecting here spares the upload.
        val fakePair = FakeRuntimePair(pairIdNamed("pair-atlimit"))
        val (sm, slm) = readySession("https://relay.example", "client-atlimit", fakePair)
        sm.getOrCreate("https://relay.example")
            .setDiscovery(fakeDiscovery(""","maxRequestBodyBytes":10"""))

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse = throw AssertionError("must not be sent")
        }

        val error = assertFailsWith<RelayRequestTooLargeException> {
            RelayStreamingExecutor(sm, slm, transport).executeBuffered(
                "https://relay.example", "/api", null, "POST", ByteArray(10), linkedMapOf(), null,
            )
        }

        assertEquals(RelayRequestTooLargeException.MEASURED_FRAME, error.measured)
        assertTrue(error.sizeBytes > 10, "the frame is larger than the body it carries")
        assertEquals(10L, error.limitBytes)
    }

    @Test
    fun bodyComfortablyUnderTheLimit_isSentNormally() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-underlimit"))
        val (sm, slm) = readySession("https://relay.example", "client-underlimit", fakePair)
        sm.getOrCreate("https://relay.example")
            .setDiscovery(fakeDiscovery(""","maxRequestBodyBytes":4096"""))

        val captured = AtomicReference<Map<String, String>>()
        RelayStreamingExecutor(sm, slm, capturingTransport(captured))
            .executeBuffered(
                "https://relay.example", "/api", null, "POST", ByteArray(64), linkedMapOf(), null,
            )

        assertNotNull(captured.get())
    }

    @Test
    fun zeroMaxRequestBodyBytes_meansUnlimited() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-unlimited"))
        val (sm, slm) = readySession("https://relay.example", "client-unlimited", fakePair)
        sm.getOrCreate("https://relay.example")
            .setDiscovery(fakeDiscovery(""","maxRequestBodyBytes":0"""))

        val captured = AtomicReference<Map<String, String>>()
        RelayStreamingExecutor(sm, slm, capturingTransport(captured))
            .executeBuffered(
                "https://relay.example", "/api", null, "POST", ByteArray(5000), linkedMapOf(), null,
            )

        assertNotNull(captured.get(), "0 is the server's way of saying unlimited")
    }

    @Test
    fun response_carriesTheOriginsHeadersFromBothChannels() {
        // A v5 relay moves some of the origin's response out of the frame onto the real HTTP
        // response — Set-Cookie under forward_browser_cookies. Building the caller's response
        // from the frame alone dropped those silently, which iOS did not, so the two clients
        // handed callers different headers for the same request.
        val fakePair = FakeRuntimePair(pairIdNamed("pair-merge"))
        val (sm, slm) = readySession("https://relay.example", "client-merge", fakePair)

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                val frame = buildResponseFrames(
                    statusCode = 200,
                    headersJson = "{\"X-From-Origin\":\"yes\"}",
                    body = "ok".toByteArray(),
                    encode = { it },
                )
                // What the hop itself returned, as a real relay would.
                val hopHeaders = mapOf(
                    "Set-Cookie" to "session=abc",
                    "X-MTE-Relay-Route" to "replica-7",
                    "Content-Type" to "application/octet-stream",
                    "Content-Length" to "9999",
                )
                return responseBodyHandler(200, hopHeaders, java.io.ByteArrayInputStream(frame))
            }
        }

        val response = RelayStreamingExecutor(sm, slm, transport)
            .executeBuffered(
                "https://relay.example", "/api", null, "GET", ByteArray(0), linkedMapOf(), null,
            )

        // The origin's own header, from inside the frame.
        assertEquals("yes", response.headers["X-From-Origin"])
        // And the one the relay moved onto the real response, which the caller would have seen
        // without the relay in the path.
        assertEquals("session=abc", response.headers["Set-Cookie"])
    }

    @Test
    fun response_hidesTheRelaysOwnHopHeaders() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-hop-hidden"))
        val (sm, slm) = readySession("https://relay.example", "client-hop", fakePair)

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                val frame = buildResponseFrames(
                    statusCode = 200,
                    headersJson = null,
                    body = "ok".toByteArray(),
                    encode = { it },
                )
                return responseBodyHandler(
                    200,
                    mapOf(
                        "X-MTE-Relay-Route" to "replica-7",
                        "Content-Type" to "application/octet-stream",
                        "Content-Length" to "9999",
                    ),
                    java.io.ByteArrayInputStream(frame),
                )
            }
        }

        val response = RelayStreamingExecutor(sm, slm, transport)
            .executeBuffered(
                "https://relay.example", "/api", null, "GET", ByteArray(0), linkedMapOf(), null,
            )

        // The routing token is the relay addressing this client; the origin never sent it.
        assertNull(response.headers["X-MTE-Relay-Route"])
        // Content-* on the hop describe the frame: the type is always octet-stream and the
        // length is the encrypted frame's. Letting them through would misdescribe the body the
        // caller is holding.
        assertNull(response.headers["Content-Type"])
        assertNull(response.headers["Content-Length"])
    }

    /** Captures the headers of the frame POST and answers with a minimal 200 frame. */
    private fun capturingTransport(sink: AtomicReference<Map<String, String>>): RelayTransport =
        object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                sink.set(request.headers)
                val frame = buildResponseFrames(
                    statusCode = 200,
                    headersJson = null,
                    body = "ok".toByteArray(),
                    encode = { it },
                )
                return responseBodyHandler(200, emptyMap(), java.io.ByteArrayInputStream(frame))
            }
        }

    private fun readySession(
        origin: String,
        clientId: String,
        pair: RelayRuntimePair,
        discovery: Discovery = fakeDiscovery(),
    ): Pair<RelaySessionManager, RelaySessionLifecycleManager> {
        val sm = RelaySessionManager()
        val session: RelayOriginSession = sm.getOrCreate(origin)
        val token = fakeToken(clientId.hashCode() and 0x3F)
        session.setToken(token)
        // Set before any request: the frame bound and the pairing windows come from here,
        // and reaching a request without it is a programming error rather than a relay
        // that said nothing.
        session.setDiscovery(discovery)
        session.setPairMaterials(listOf(fakePairMaterial(pair.pairId)))
        session.setRuntimePairs(listOf(pair))
        session.transition(RelaySessionState.READY)
        val slm = RelaySessionLifecycleManager(sm, FakeControlPlaneClient(token, listOf(pair)))
        return sm to slm
    }

    private inner class ReplacementTrackingControlPlaneClient(
        private val token: Token,
    ) : RelayControlPlaneClient {
        val pairPoolSizes = mutableListOf<Int>()
        var pairCalls = 0
        var authenticateCalls = 0

        override fun authenticate(origin: String, existingToken: Token?): RelayAuthResponse {
            authenticateCalls += 1
            return RelayAuthResponse(token, fakeDiscovery())
        }

        override fun pair(
            origin: String,
            token: Token,
            pairPoolSize: Int,
            sequenceWindow: Int,
            timeWindow: Long,
        ): RelayPairingResult {
            pairCalls += 1
            pairPoolSizes += pairPoolSize
            return RelayPairingResult(
                materials = emptyList(),
                runtimePairs = (1..pairPoolSize).map { index ->
                    FakeRuntimePair(pairIdNamed("replacement-$pairCalls-$index"))
                },
            )
        }

        override fun keepAlive(origin: String, token: Token, drop: List<PairId>) {
        }
    }


    @Test
    fun uploadFile_writesTheRequestFrameThenTheBodyAsDataFrames() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-001"))
        val (sm, slm) = readySession("https://relay.example", "test-client", fakePair)

        val capturedContentLength = AtomicLong(-1)
        val capturedBody = ByteArrayOutputStream()
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeStreaming(request: RelayStreamingTransportRequest): RelayTransportResponse {
                capturedContentLength.set(request.contentLength)
                request.bodyWriter(capturedBody)
                return RelayTransportResponse(
                    statusCode = 200,
                    payload = buildResponseFrames(200, null, "{}".toByteArray()) { it },
                )
            }
        }

        val bodyBytes = "hello world multipart body".toByteArray()
        val reqProperties = RelayFileRequestProperties(
            "https://relay.example/ignored",
            "/upload/file",
            null, // pathnamePrefix
            linkedMapOf(
                "Content-Type" to "multipart/form-data; boundary=border",
                "Content-Length" to bodyBytes.size.toString(),
            ),
            // Both are reserved and always encrypted; naming them is an error.
            null,
            RelayStreamCallback { out ->
                out.write(bodyBytes)
                out.close()
            },
        )

        val progressEvents = mutableListOf<Pair<Int, Int>>()
        val listenerResult = AtomicReference<StreamListenerResult>()

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
            uploadBodyTimeoutMillis = 10_000,
        )

        executor.uploadFile(
            reqProperties = reqProperties,
            listener = recordingListener(listenerResult),
            completionCallback = RelayStreamCompletionCallback { completed, total ->
                progressEvents += completed to total
            },
        )

        val fullWritten = capturedBody.toByteArray()

        // Chunked, not length-declared. Frame v2 splits an upload into DATA frames, each
        // one an encode with its own overhead, so the total is not known before the last
        // frame is built -- and OkHttp reads a negative content length as "chunked".
        assertEquals(-1L, capturedContentLength.get(), "a framed upload must be chunked")

        // The REQUEST does not carry END: DATA follows it, and the last DATA carries END,
        // because a body ending without one is 477 eof_before_end on the relay's side.
        assertTrue(!Envelope.read(fullWritten).isEnd, "the REQUEST must not end the request")

        // One REQUEST, then the body as DATA frames, the last carrying END.
        val parsed = parseRequestFrames(fullWritten) { it }
        assertTrue(
            parsed.body.contentEquals(bodyBytes),
            "the DATA frames must reassemble to the caller's body",
        )

        // Progress callbacks were fired and are monotonically non-decreasing
        assertTrue(progressEvents.isNotEmpty(), "at least one progress event must be fired")
        assertTrue(progressEvents.zipWithNext().all { (a, b) -> b.first >= a.first }, "progress bytes must be non-decreasing")

        // Listener received 200 success
        val result = assertNotNull(listenerResult.get())
        assertEquals(200, result.statusCode)
        assertTrue(result.success)
    }

    @Test
    fun uploadFile_streamsAsDataFrames_andIsRefusedPastMaxRequestBodyBytes() {
        // This reversed with frame v2, so the history is worth keeping.
        //
        // The previous generation set a stream flag on the frame; the relay then piped the
        // chunks straight upstream without buffering and did not measure them, so a streamed
        // upload was genuinely uncapped and checking the limit here refused uploads the relay
        // would have taken. Frame v2 has no stream flag. The relay measures the whole body and
        // refuses past `maxRequestBodyBytes` -- measured live against the dev relay, whose cap
        // is 3 MiB: a 2 MiB upload completes in 2 s and an 8 MiB one hangs, because the relay
        // stops reading and this client goes on writing into a connection nobody drains until
        // the body timeout fires.
        //
        // So the check is back, and its job is to turn that hang into an immediate refusal
        // that names both numbers.
        val fakePair = FakeRuntimePair(pairIdNamed("pair-capped"))
        val (sm, slm) = readySession("https://relay.example", "client-capped", fakePair)
        sm.getOrCreate("https://relay.example")
            .setDiscovery(fakeDiscovery(""","maxRequestBodyBytes":16"""))

        val capturedBody = ByteArrayOutputStream()
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeStreaming(request: RelayStreamingTransportRequest): RelayTransportResponse {
                request.bodyWriter(capturedBody)
                return RelayTransportResponse(
                    statusCode = 200,
                    payload = buildResponseFrames(200, null, "{}".toByteArray()) { it },
                )
            }
        }

        // Comfortably over the 16-byte limit the session was told about.
        val bodyBytes = ByteArray(4096) { 'x'.code.toByte() }
        val listenerResult = AtomicReference<StreamListenerResult>()

        RelayStreamingExecutor(sm, slm, transport).uploadFile(
            reqProperties = RelayFileRequestProperties(
                "https://relay.example/ignored",
                "/upload/file",
                null,
                linkedMapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Length" to bodyBytes.size.toString(),
                ),
                null,
                RelayStreamCallback { out ->
                    out.write(bodyBytes)
                    out.close()
                },
            ),
            listener = recordingListener(listenerResult),
            completionCallback = RelayStreamCompletionCallback { _, _ -> },
        )

        // Nothing was sent: the point of checking here is that the caller learns before the
        // bytes go out, rather than after the whole upload has been paid for over metered
        // data and then hung.
        assertEquals(0, capturedBody.toByteArray().size, "nothing may be sent past the cap")

        val result = assertNotNull(listenerResult.get())
        assertFalse(result.success)
        // Both numbers, because the caller cannot otherwise tell a body they can shrink from
        // a server limit somebody has to raise.
        assertTrue(result.errorMessage!!.contains("4096"), result.errorMessage!!)
        assertTrue(result.errorMessage!!.contains("16"), result.errorMessage!!)
    }

    @Test
    fun uploadFile_fullRepairReason_triggersRepairAndReturnsError_noRetry() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-upload-repair"))
        val (sm, slm) = readySession("https://relay.example", "client-upload-repair", fakePair)

        val transportCallCount = AtomicInteger(0)
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeStreaming(request: RelayStreamingTransportRequest): RelayTransportResponse {
                transportCallCount.incrementAndGet()
                // Drain pipe to avoid blocking the callback thread
                val sink = ByteArrayOutputStream()
                request.bodyWriter(sink)
                // 475 invalid_token: the token failed its HMAC or aged out, so the whole
                // session is rebuilt. The reason decides that, not the status -- 475
                // unknown_key is the same status and means stop.
                return RelayTransportResponse(
                    statusCode = 475,
                    payload = com.eclypses.mte.wire.ErrorFrame(475, "invalid_token", "expired")
                        .toByteArray(),
                    headers = mapOf("X-MTE-Relay-Error" to "475 invalid_token"),
                )
            }
        }

        val reqProperties = RelayFileRequestProperties(
            "https://relay.example/ignored",
            "/upload",
            null,
            linkedMapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Length" to "3",
            ),
            // Both are reserved and always encrypted; naming them is an error.
            null,
            RelayStreamCallback { out ->
                out.write(byteArrayOf(1, 2, 3))
                out.close()
            },
        )

        val listenerResult = AtomicReference<StreamListenerResult>()

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
            uploadBodyTimeoutMillis = 10_000,
        )

        executor.uploadFile(
            reqProperties = reqProperties,
            listener = recordingListener(listenerResult),
            completionCallback = null,
        )

        // Transport called exactly once — no auto-retry
        assertEquals(1, transportCallCount.get(), "transport must be called exactly once; no retry")

        // Session must be in REPAIRING or READY state (repair was executed after 559)
        val snapshotState = sm.get("https://relay.example")?.state
        assertTrue(
            snapshotState == RelaySessionState.REPAIRING || snapshotState == RelaySessionState.READY,
            "session must transition to REPAIRING (and optionally back to READY) after a full repair",
        )

        val result = assertNotNull(listenerResult.get())
        assertEquals(475, result.statusCode)
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun downloadFileV5_streamsBodyToFile_withoutBufferingFullPayload() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-dl-001"))
        val (sm, slm) = readySession("https://relay.example", "client-dl", fakePair)

        // Build a real V5 response frame to serve from the fake transport
        val responseBody = "Hello streaming download!".toByteArray()
        // We need a codec to build the response frame — use the fakePair (identity encode/decode)
        val codec = { b: ByteArray -> b }
        // Build a hand-crafted minimal V5 response frame: [MAGIC][protoLen][metadata][body]
        // Use encodeRequestFrame on a minimal response shape — easiest is just to call
        // decodeResponseFrame in reverse.  Instead, build the raw binary manually mirroring
        // BinaryRelayProtocolEngine.decodeResponseFrame expected layout.
        val responseFrame = buildResponseFrames(
            statusCode = 200,
            headersJson = null,
            body = responseBody,
            encode = codec,
        )

        val tempFile = File.createTempFile("relay-dl-test-", ".tmp")
        tempFile.deleteOnExit()

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                val stream = java.io.ByteArrayInputStream(responseFrame)
                return responseBodyHandler(200, emptyMap(), stream)
            }
        }

        val reqProperties = RelayFileRequestProperties(
            "https://relay.example/ignored",
            "/download/file",
            tempFile.absolutePath,
            null,
            linkedMapOf(),
            null,
        )

        val listenerResult = AtomicReference<StreamListenerResult>()
        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
        )

        executor.downloadFile(
            reqProperties = reqProperties,
            listener = recordingListener(listenerResult),
        )

        val result = assertNotNull(listenerResult.get())
        assertEquals(200, result.statusCode)
        assertTrue(result.success)
        assertNull(result.errorMessage)

        // Verify downloaded file contents match the original body
        val writtenBytes = tempFile.readBytes()
        assertTrue(writtenBytes.contentEquals(responseBody), "downloaded file must contain the original response body")
    }

    @Test
    fun downloadFileV5_honoursThePathnamePrefixOnTheProperties() {
        // The prefix used to be a separate argument to downloadFile as well, and the argument
        // won: a caller who set the field and passed null got no prefix and no warning. The
        // Flutter plugin had defensively started doing both. One field, one meaning — and this
        // pins that the field is the one read, on the same object uploadFile reads it from.
        val fakePair = FakeRuntimePair(pairIdNamed("pair-dl-prefix"))
        val (sm, slm) = readySession("https://relay.example", "client-dl-prefix", fakePair)

        val capturedRoute = AtomicReference<String>()
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                capturedRoute.set(routeFromIdentityFrame(request.payload))
                val codec = { b: ByteArray -> b }
                return responseBodyHandler(
                    200,
                    emptyMap(),
                    java.io.ByteArrayInputStream(
                        buildResponseFrames(200, null, ByteArray(0), codec),
                    ),
                )
            }
        }

        val tempFile = File.createTempFile("relay-dl-prefix", ".bin").apply { deleteOnExit() }
        val listenerResult = AtomicReference<StreamListenerResult>()

        RelayStreamingExecutor(sm, slm, transport).downloadFile(
            reqProperties = RelayFileRequestProperties(
                "https://relay.example/ignored",
                "/download/file",
                tempFile.absolutePath,
                "edge/v1",                       // the prefix, on the properties
                linkedMapOf(),
                null,
            ),
            listener = recordingListener(listenerResult),
        )

        assertEquals(200, assertNotNull(listenerResult.get()).statusCode)
        assertEquals(
            "edge/v1/download/file",
            capturedRoute.get(),
            "the route must carry the prefix from reqProperties.pathnamePrefix",
        )
    }

    @Test
    fun downloadFileV5_559response_triggersRepairAndReturnsError() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-dl-559"))
        val (sm, slm) = readySession("https://relay.example", "client-dl-559", fakePair)

        val transportCallCount = AtomicInteger(0)
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                transportCallCount.incrementAndGet()
                val errorPayload = "relay session expired".toByteArray()
                val stream = java.io.ByteArrayInputStream(errorPayload)
                return responseBodyHandler(559, emptyMap(), stream)
            }
        }

        val tempFile = File.createTempFile("relay-dl-559-", ".tmp")
        tempFile.deleteOnExit()

        val reqProperties = RelayFileRequestProperties(
            "https://relay.example/ignored",
            "/download/file",
            tempFile.absolutePath,
            null,
            linkedMapOf(),
            null,
        )

        val listenerResult = AtomicReference<StreamListenerResult>()
        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
        )

        executor.downloadFile(
            reqProperties = reqProperties,
            listener = recordingListener(listenerResult),
        )

        assertEquals(1, transportCallCount.get(), "transport called exactly once; no retry on 559")

        val result = assertNotNull(listenerResult.get())
        assertEquals(559, result.statusCode)
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun streamServerSentEventsV5_emitsOpenDataAndCompleted() {
        val fakePair = FakeRuntimePair(pairIdNamed("pair-sse-001"))
        val (sm, slm) = readySession("https://relay.example", "client-sse", fakePair)

        val responseFrame = buildResponseFrames(
            statusCode = 200,
            headersJson = "{\"content-type\":\"text/event-stream\"}",
            body = "data: first\n\ndata: second\n\n".toByteArray(),
            encode = { it },
        )

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()

            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                return responseBodyHandler(200, emptyMap(), java.io.ByteArrayInputStream(responseFrame))
            }
        }

        val listener = RecordingSseListener()
        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
        )

        executor.streamServerSentEvents(
            operationId = "stream-001",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events?events=2",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            unencryptedHeaders = null,
            listener = listener,
        )

        assertEquals(listOf("stream-001:200"), listener.opened)
        // The transport does not parse text/event-stream: the caller gets the decrypted bytes
        // exactly as the server sent them, SSE framing intact, and parses them itself.
        assertEquals("data: first\n\ndata: second\n\n", listener.events.joinToString(""))
        assertEquals(listOf("stream-001"), listener.completed)
        assertEquals(emptyList(), listener.cancelled)
        assertEquals(emptyList(), listener.errors)
    }

    @Test
    fun streamServerSentEventsV5_cancelledStream_discardsAndReplacesPair() {
        val initialPair = FakeRuntimePair(pairIdNamed("pair-cancel-001"))
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate("https://relay.example")
        val token = fakeToken(8)
        session.setToken(token)
        session.setDiscovery(fakeDiscovery())
        session.setPairMaterials(listOf(fakePairMaterial(initialPair.pairId)))
        session.setRuntimePairs(listOf(initialPair))
        session.transition(RelaySessionState.READY)
        val controlPlane = ReplacementTrackingControlPlaneClient(token)
        val lifecycleManager = RelaySessionLifecycleManager(sessionManager, controlPlane)

        val responseFrame = buildResponseFrames(
            statusCode = 200,
            headersJson = "{\"content-type\":\"text/event-stream\"}",
            body = "data: payload\n\n".toByteArray(),
            encode = { it },
        )

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()

            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                return responseBodyHandler(200, emptyMap(), java.io.ByteArrayInputStream(responseFrame))
            }
        }

        lateinit var executor: RelayStreamingExecutor
        val listener = object : RelaySseListener {
            val cancelled = mutableListOf<String>()

            override fun onOpened(streamId: String, statusCode: Int, responseHeaders: Map<String, List<String>>) {
                executor.cancelOperation(streamId)
            }

            override fun onData(streamId: String, data: ByteArray) = Unit

            override fun onCompleted(streamId: String) = Unit

            override fun onCancelled(streamId: String) {
                cancelled += streamId
            }

            override fun onError(
                streamId: String,
                statusCode: Int,
                errorMessage: String,
                responseHeaders: Map<String, List<String>>?,
            ) = Unit
        }

        executor = RelayStreamingExecutor(
            sessionManager = sessionManager,
            sessionLifecycleManager = lifecycleManager,
            transport = transport,
        )

        executor.streamServerSentEvents(
            operationId = "stream-cancel",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events?events=1",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            unencryptedHeaders = null,
            listener = listener,
        )

        assertEquals(1, controlPlane.pairCalls)
        assertEquals(listOf(1), controlPlane.pairPoolSizes)
        assertEquals(null, session.getRuntimePair(pairIdNamed("pair-cancel-001")))
        assertNotNull(session.getRuntimePair(pairIdNamed("replacement-1-1")))
    }

    @Test
    fun streamServerSentEventsV5_terminalError_discardsAndReplacesPair() {
        val initialPair = FakeRuntimePair(pairIdNamed("pair-error-001"))
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate("https://relay.example")
        val token = fakeToken(8)
        session.setToken(token)
        session.setDiscovery(fakeDiscovery())
        session.setPairMaterials(listOf(fakePairMaterial(initialPair.pairId)))
        session.setRuntimePairs(listOf(initialPair))
        session.transition(RelaySessionState.READY)
        val controlPlane = ReplacementTrackingControlPlaneClient(token)
        val lifecycleManager = RelaySessionLifecycleManager(sessionManager, controlPlane)

        // A relay refusal, not an origin status: an HTTP status outside 2xx, an ERROR
        // frame as the whole body, and the X-MTE-Relay-Error header carrying the same code
        // and reason in case a proxy strips the body. 473 decode_failed means the relay's
        // decoder could not read the frame, so this pair is desynchronised.
        val errorPayload = com.eclypses.mte.wire.ErrorFrame(473, "decode_failed", "relay downstream failure")
            .toByteArray()
        val errorFrame = ByteArrayOutputStream().apply {
            write(Envelope(Kind.ERROR, Envelope.FLAG_END, errorPayload.size.toLong()).toByteArray())
            write(errorPayload)
        }.toByteArray()

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()

            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                return responseBodyHandler(
                    473,
                    mapOf("X-MTE-Relay-Error" to "473 decode_failed"),
                    java.io.ByteArrayInputStream(errorFrame),
                )
            }
        }

        val listener = RecordingSseListener()
        val executor = RelayStreamingExecutor(
            sessionManager = sessionManager,
            sessionLifecycleManager = lifecycleManager,
            transport = transport,
        )

        executor.streamServerSentEvents(
            operationId = "stream-error",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events?events=1",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            unencryptedHeaders = null,
            listener = listener,
        )

        assertEquals(1, controlPlane.pairCalls)
        assertEquals(listOf(1), controlPlane.pairPoolSizes)
        assertEquals(null, session.getRuntimePair(pairIdNamed("pair-error-001")))
        assertNotNull(session.getRuntimePair(pairIdNamed("replacement-1-1")))
        assertTrue(
            listener.errors.single().startsWith("stream-error:473:"),
            "got ${listener.errors}",
        )
    }

    /**
     * The companion case, and the one the previous generation got wrong: a status the
     * *origin* returned is not a relay failure. The pair is intact, the body is the
     * origin's error page, and the caller asked for both -- burning a shared pair on an
     * upstream 500 costs a round trip and discards working state for nothing.
     */
    @Test
    fun streamServerSentEvents_originErrorStatus_keepsThePairAndDeliversTheBody() {
        val pair = FakeRuntimePair(pairIdNamed("pair-origin-500"))
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate("https://relay.example")
        val token = fakeToken(11)
        session.setToken(token)
        session.setDiscovery(fakeDiscovery())
        session.setPairMaterials(listOf(fakePairMaterial(pair.pairId)))
        session.setRuntimePairs(listOf(pair))
        session.transition(RelaySessionState.READY)
        val controlPlane = ReplacementTrackingControlPlaneClient(token)
        val lifecycleManager = RelaySessionLifecycleManager(sessionManager, controlPlane)

        val responseFrame = buildResponseFrames(
            statusCode = 500,
            headersJson = """{"content-type":"text/plain"}""",
            body = "upstream exploded".toByteArray(),
            encode = { it },
        )
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse =
                responseBodyHandler(200, emptyMap(), java.io.ByteArrayInputStream(responseFrame))
        }

        val listener = RecordingSseListener()
        RelayStreamingExecutor(sessionManager, lifecycleManager, transport).streamServerSentEvents(
            operationId = "stream-origin-500",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            unencryptedHeaders = null,
            listener = listener,
        )

        assertEquals(0, controlPlane.pairCalls, "an origin status must not replace a pair")
        assertNotNull(session.getRuntimePair(pairIdNamed("pair-origin-500")))
        assertEquals(listOf("stream-origin-500:500"), listener.opened)
        assertEquals(listOf("upstream exploded"), listener.events)
    }

    @Test
    fun streamServerSentEventsV5_successfulCompletion_releasesLeasedPair() {
        val initialPair = FakeRuntimePair(pairIdNamed("pair-success-001"))
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate("https://relay.example")
        val token = fakeToken(8)
        session.setToken(token)
        session.setDiscovery(fakeDiscovery())
        session.setPairMaterials(listOf(fakePairMaterial(initialPair.pairId)))
        session.setRuntimePairs(listOf(initialPair))
        session.transition(RelaySessionState.READY)
        val lifecycleManager = RelaySessionLifecycleManager(sessionManager, ReplacementTrackingControlPlaneClient(token))

        val responseFrame = buildResponseFrames(
            statusCode = 200,
            headersJson = "{\"content-type\":\"text/event-stream\"}",
            body = "data: payload\n\n".toByteArray(),
            encode = { it },
        )

        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()

            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                return responseBodyHandler(200, emptyMap(), java.io.ByteArrayInputStream(responseFrame))
            }
        }

        val listener = RecordingSseListener()
        val executor = RelayStreamingExecutor(
            sessionManager = sessionManager,
            sessionLifecycleManager = lifecycleManager,
            transport = transport,
        )

        executor.streamServerSentEvents(
            operationId = "stream-success",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events?events=1",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            unencryptedHeaders = null,
            listener = listener,
        )

        assertFalse(session.isRuntimePairLeased(pairIdNamed("pair-success-001")))
        assertEquals(listOf("stream-success"), listener.completed)
    }

    /**
     * Builds a frame version 2 response: one RESPONSE, then one DATA carrying [body].
     *
     * The status appears twice -- in the RESPONSE payload and inside the encoded metadata
     * -- because the reader compares them; a builder that sets only one produces a frame
     * the client is right to refuse.
     */
    private fun buildResponseFrames(
        statusCode: Int,
        headersJson: String?,
        body: ByteArray,
        encode: (ByteArray) -> ByteArray,
    ): ByteArray {
        val members = LinkedHashMap<String, JsonValue>()
        members["v"] = JsonNumber("2")
        members["status"] = JsonNumber(statusCode.toString())
        if (headersJson != null) {
            val parsed = Json.parseObject(headersJson)
            members["headers"] = parsed
        }
        val metadata = encode(Json.canonical(JsonObject(members)).toByteArray(Charsets.UTF_8))
        val responsePayload = ResponseFrame(statusCode, metadata).toByteArray()

        val out = ByteArrayOutputStream()
        val end = body.isEmpty()
        out.write(Envelope(Kind.RESPONSE, if (end) Envelope.FLAG_END else 0, responsePayload.size.toLong()).toByteArray())
        out.write(responsePayload)
        if (!end) {
            val enc = encode(DataPlaintext.build(Envelope.FLAG_END, body))
            out.write(Envelope(Kind.DATA, Envelope.FLAG_END, enc.size.toLong()).toByteArray())
            out.write(enc)
        }
        return out.toByteArray()
    }

    /** A deterministic pair id from a readable name, so failures still name the pair. */
    private fun pairIdNamed(name: String): PairId {
        val raw = ByteArray(16)
        val bytes = name.toByteArray(Charsets.UTF_8)
        for (i in raw.indices) raw[i] = bytes[i % bytes.size]
        return PairId(raw)
    }

    /** The token every fake session carries. Its client id is what the route header shows. */
    private fun fakeToken(seed: Int = 7): Token = Token(ByteArray(40) { (it + seed).toByte() })

    /** Discovery a fake relay would answer with: enough to pair and to bound a frame. */
    private fun fakeDiscovery(extra: String = ""): Discovery =
        com.eclypses.relay.session.testDiscovery(extra)

    private fun fakePairMaterial(pairId: PairId): RelayPairMaterial {
        return RelayPairMaterial(
            pairId = pairId,
            encoderNonce = 1L,
            decoderNonce = 2L,
            encoderResponderEncryptedSecret = byteArrayOf(0x01),
            decoderResponderEncryptedSecret = byteArrayOf(0x02),
            encoderPersonalizationStr = "mte-relay/2/http/${pairId.text}",
            decoderPersonalizationStr = "mte-relay/2/http/${pairId.text}d",
        )
    }

    private class RecordingSseListener : RelaySseListener {
        val opened = mutableListOf<String>()
        val events = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun onOpened(streamId: String, statusCode: Int, responseHeaders: Map<String, List<String>>) {
            opened += "$streamId:$statusCode"
        }

        override fun onData(streamId: String, data: ByteArray) {
            events += String(data)
        }

        override fun onCompleted(streamId: String) {
            completed += streamId
        }

        override fun onCancelled(streamId: String) {
            cancelled += streamId
        }

        override fun onError(
            streamId: String,
            statusCode: Int,
            errorMessage: String,
            responseHeaders: Map<String, List<String>>?,
        ) {
            errors += "$streamId:$statusCode:$errorMessage"
        }
    }

    // ---- Buffered request core (Relay.send / interceptor mode) -----------------

    @Test
    fun executeBuffered_encodesRequestFrame_andFoldsStreamedChunksIntoOnePayload() {
        val operationLog = mutableListOf<String>()
        val requestBody = "hello-body".toByteArray()
        val responseBody = "hello-response".toByteArray()
        val pair = RecordingRuntimePair(
            pairIdNamed("pair-buffered"), requestBody, operationLog)
        val (sm, slm) = readySession("https://relay.example", "client-buffered", pair)

        val capturedFrame = AtomicReference<ByteArray>()
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse {
                assertEquals("/", request.route)
                assertEquals("application/octet-stream", request.headers["Content-Type"])
                capturedFrame.set(request.payload)
                val responseFrame = buildResponseFrames(
                    statusCode = 202,
                    headersJson = "{\"x-order\":\"ok\"}",
                    body = responseBody,
                    encode = ::invertBytes,
                )
                return responseBodyHandler(200, emptyMap(), java.io.ByteArrayInputStream(responseFrame))
            }
        }

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/messages",
            route = "/api/messages",
            pathnamePrefix = "/edge",
            method = "PUT",
            body = requestBody,
            headers = mapOf("x-exposed" to "plain", "x-secret" to "encrypted"),
            unencryptedHeaders = arrayOf("x-exposed"),
        )

        assertEquals(202, response.statusCode)
        assertEquals(mapOf("x-order" to "ok"), response.headers)
        assertTrue(response.payload.contentEquals(responseBody), "every streamed chunk must be folded into the payload")

        // The request frame carries the method, the encrypted route (with its pathname prefix),
        // only the headers the caller asked to encrypt, and the encrypted body.
        val frame = parseRelayRequestFrame(assertNotNull(capturedFrame.get()))
        assertEquals("edge/api/messages", frame.route)
        // Only the header the caller did NOT name reaches the encrypted metadata; the
        // named one travels in the clear on the hop instead.
        assertEquals(mapOf("x-secret" to "encrypted"), frame.headers)
        assertEquals(pairIdNamed("pair-buffered"), frame.pairId)
        assertTrue(frame.body.contentEquals(requestBody))

        // Exactly four operations, in this order: the REQUEST metadata, one DATA for the
        // body, the RESPONSE metadata, one DATA for the response body. The relay's pair
        // counts the same four. There is no separate chunked-decrypt session any more --
        // each DATA frame is one whole operation, which is why startDecrypt no longer
        // appears.
        assertEquals(
            listOf("encode:metadata", "encode:body", "decode:metadata", "decode:data"),
            operationLog,
        )
    }

    @Test
    fun executeBuffered_replacePairReason_replacesOnlyThatPair_andSurfacesStatus() {
        val (sm, slm, controlPlane) = trackedReadySession("https://relay.example", "client-473", FakeRuntimePair(pairIdNamed("pair-473")))

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = relayStatusTransport(473, "decode_failed"),
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/messages",
            route = "/api/messages",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            unencryptedHeaders = null,
        )

        // 473 decode_failed: the relay's decoder could not read the frame, so this pair
        // is desynchronised and must be replaced. The session survives.
        assertEquals(473, response.statusCode)
        assertEquals(0, controlPlane.authenticateCalls, "a single-pair repair must not re-authenticate")
        assertEquals(1, controlPlane.pairCalls)
        assertEquals(listOf(1), controlPlane.pairPoolSizes)

        val session = assertNotNull(sm.get("https://relay.example"))
        assertNull(session.getRuntimePair(pairIdNamed("pair-473")), "the desynced pair must be discarded")
        assertNotNull(session.getRuntimePair(pairIdNamed("replacement-1-1")))
    }

    @Test
    fun executeBuffered_fullRepairReason_reauthenticates_withoutResending() {
        val (sm, slm, controlPlane) = trackedReadySession("https://relay.example", "client-475", FakeRuntimePair(pairIdNamed("pair-475")))

        val transportCalls = AtomicInteger(0)
        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = relayStatusTransport(475, "invalid_client_id", transportCalls),
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/messages",
            route = "/api/messages",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            unencryptedHeaders = null,
        )

        assertEquals(475, response.statusCode)
        assertEquals(1, transportCalls.get(), "the request must not be transparently resent")
        assertEquals(1, controlPlane.authenticateCalls, "a full repair re-authenticates the session")
        assertEquals(1, controlPlane.pairCalls)
        assertEquals(RelaySessionState.READY, assertNotNull(sm.snapshot("https://relay.example")).state)
    }

    @Test
    fun executeBuffered_surfaceReason_surfacesStatus_withoutMutatingThePool() {
        val (sm, slm, controlPlane) = trackedReadySession("https://relay.example", "client-482", FakeRuntimePair(pairIdNamed("pair-482")))

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = relayStatusTransport(482, "metadata_invalid"),
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/messages",
            route = "/api/messages",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            unencryptedHeaders = null,
        )

        assertEquals(482, response.statusCode)
        assertEquals(0, controlPlane.authenticateCalls)
        assertEquals(0, controlPlane.pairCalls)
        assertNotNull(assertNotNull(sm.get("https://relay.example")).getRuntimePair(pairIdNamed("pair-482")))
    }

    @Test
    fun executeBuffered_upstreamErrorStatus_surfacesDecodedBody_andKeepsThePair() {
        val (sm, slm, controlPlane) = trackedReadySession("https://relay.example", "client-404", FakeRuntimePair(pairIdNamed("pair-404")))

        val responseFrame = buildResponseFrames(
            statusCode = 404,
            headersJson = "{\"content-type\":\"text/plain\"}",
            body = "not found".toByteArray(),
            encode = { it },
        )
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeDownloadStreaming(
                request: RelayTransportRequest,
                responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
            ): RelayTransportResponse =
                responseBodyHandler(200, emptyMap(), java.io.ByteArrayInputStream(responseFrame))
        }

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            transport = transport,
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/missing",
            route = "/api/missing",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            unencryptedHeaders = null,
        )

        assertEquals(404, response.statusCode)
        assertTrue(response.payload.contentEquals("not found".toByteArray()), "the decoded error body must be surfaced")
        // An upstream error says nothing about the pair — a shared pair must not be burned on a 404.
        assertEquals(0, controlPlane.pairCalls)
        assertNotNull(assertNotNull(sm.get("https://relay.example")).getRuntimePair(pairIdNamed("pair-404")))
    }

    /** Transport that answers every request with a relay [statusCode] and a plain (unframed) body. */
    /**
     * A relay answering with a refusal: the status, an ERROR frame body, and the
     * `X-MTE-Relay-Error` header that carries the same code and reason as a second
     * carrier in case a proxy strips the body.
     *
     * The reason is what the client keys on. Passing only a status here would test
     * something the client no longer does.
     */
    private fun relayStatusTransport(
        statusCode: Int,
        reason: String,
        callCount: AtomicInteger = AtomicInteger(0),
        message: String = "relay-error",
    ): RelayTransport = object : RelayTransport {
        override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
        override fun executeDownloadStreaming(
            request: RelayTransportRequest,
            responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
        ): RelayTransportResponse {
            callCount.incrementAndGet()
            val payload = com.eclypses.mte.wire.ErrorFrame(statusCode, reason, message).toByteArray()
            val body = ByteArrayOutputStream()
            body.write(
                Envelope(Kind.ERROR, Envelope.FLAG_END, payload.size.toLong()).toByteArray(),
            )
            body.write(payload)
            return responseBodyHandler(
                statusCode,
                mapOf("X-MTE-Relay-Error" to "$statusCode $reason"),
                java.io.ByteArrayInputStream(body.toByteArray()),
            )
        }
    }

    /** As [readySession], but with a control plane that records what recovery asked of it. */
    private fun trackedReadySession(
        origin: String,
        clientId: String,
        pair: RelayRuntimePair,
    ): Triple<RelaySessionManager, RelaySessionLifecycleManager, ReplacementTrackingControlPlaneClient> {
        val sm = RelaySessionManager()
        val session = sm.getOrCreate(origin)
        val token = fakeToken(clientId.hashCode() and 0x3F)
        session.setToken(token)
        session.setDiscovery(fakeDiscovery())
        session.setPairMaterials(listOf(fakePairMaterial(pair.pairId)))
        session.setRuntimePairs(listOf(pair))
        session.transition(RelaySessionState.READY)
        val controlPlane = ReplacementTrackingControlPlaneClient(token)
        return Triple(sm, RelaySessionLifecycleManager(sm, controlPlane), controlPlane)
    }

    /**
     * Runtime pair whose codec inverts every byte (so a test can build a response the pair will
     * "decrypt" back to plaintext) and records the order in which the core uses it.
     */
    /**
     * A pair that records the order of its codec operations.
     *
     * The count is what matters: one operation per REQUEST, per RESPONSE, and per DATA
     * above length zero, on both sides. A client that spends one extra or one fewer
     * desynchronises the pair permanently, and nothing in the resulting failure says
     * which frame did it -- so the log is asserted in full rather than sampled.
     *
     * A DATA plaintext is the frame's flags byte followed by the application bytes, which
     * is how the body is told apart from the metadata here.
     */
    private class RecordingRuntimePair(
        override val pairId: PairId,
        private val requestBody: ByteArray,
        private val operationLog: MutableList<String>,
    ) : RelayRuntimePair {
        override fun encode(payload: ByteArray): ByteArray {
            val isBody = payload.size == requestBody.size + 1 &&
                payload.copyOfRange(1, payload.size).contentEquals(requestBody)
            operationLog += if (isBody) "encode:body" else "encode:metadata"
            return invertBytes(payload)
        }

        override fun decode(payload: ByteArray): ByteArray {
            val plaintext = invertBytes(payload)
            // A response metadata blob is JSON; a DATA plaintext opens with the flags byte.
            operationLog += if (plaintext.isNotEmpty() && plaintext[0] == '{'.code.toByte()) {
                "decode:metadata"
            } else {
                "decode:data"
            }
            return plaintext
        }




    }

    private data class ParsedRequestFrame(
        val clientIdHex: String,
        val pairId: PairId,
        val route: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    /**
     * Reads the path out of a request written for an identity codec.
     *
     * [parseRelayRequestFrame] inverts the metadata because it is written for the
     * inverting fake; feeding it an identity frame scrambles the JSON instead of reading it.
     */
    private fun routeFromIdentityFrame(bytes: ByteArray): String =
        parseRequestFrames(bytes) { it }.route

    private fun parseRelayRequestFrame(bytes: ByteArray): ParsedRequestFrame =
        parseRequestFrames(bytes, ::invertBytes)

    /**
     * Parses one REQUEST frame and every DATA frame after it, the way the relay would.
     *
     * The assertion that the body ended with END is deliberate: a request whose body stops
     * short is 477 eof_before_end on the relay's side, which poisons the pair -- so a
     * client bug that drops the flag has to fail here rather than in production.
     */
    private fun parseRequestFrames(
        bytes: ByteArray,
        decode: (ByteArray) -> ByteArray,
    ): ParsedRequestFrame {
        var i = 0
        val envelope = Envelope.read(bytes, i)
        assertEquals(Kind.REQUEST, envelope.kind)
        i += Envelope.SIZE
        val request = RequestFrame.read(bytes.copyOfRange(i, i + envelope.length.toInt()))
        i += envelope.length.toInt()

        val json = org.json.JSONObject(
            String(decode(request.metadata), java.nio.charset.StandardCharsets.UTF_8),
        )
        val headerObject = json.optJSONObject("headers")
        val headers = buildMap {
            headerObject?.keys()?.forEach { put(it, headerObject.getString(it)) }
        }

        val body = ByteArrayOutputStream()
        var sawEnd = envelope.isEnd
        while (i < bytes.size) {
            val d = Envelope.read(bytes, i)
            assertEquals(Kind.DATA, d.kind)
            i += Envelope.SIZE
            val payload = bytes.copyOfRange(i, i + d.length.toInt())
            i += d.length.toInt()
            if (d.length > 0) body.write(DataPlaintext.split(decode(payload), d.flags))
            if (d.isEnd) sawEnd = true
        }
        assertTrue(sawEnd, "the request body never carried END")

        return ParsedRequestFrame(
            clientIdHex = request.token.copyOfRange(0, 16).joinToString("") { "%02x".format(it) },
            pairId = PairId(request.pairId),
            route = json.optString("path"),
            headers = headers,
            body = body.toByteArray(),
        )
    }
}

private fun invertBytes(value: ByteArray): ByteArray {
    return ByteArray(value.size) { index -> value[index].toInt().inv().toByte() }
}
