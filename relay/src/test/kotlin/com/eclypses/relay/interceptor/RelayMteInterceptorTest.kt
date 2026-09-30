package com.eclypses.relay.interceptor

import com.eclypses.relay.protocol.RelayHttpMethod
import com.eclypses.relay.session.RelayAuthResponse
import com.eclypses.relay.session.RelayControlPlaneClient
import com.eclypses.relay.session.RelayPairMaterial
import com.eclypses.relay.session.RelayPairingResult
import com.eclypses.relay.session.RelayRuntimePair
import com.eclypses.relay.session.RelaySessionLifecycleManager
import com.eclypses.relay.session.RelaySessionManager
import com.eclypses.relay.session.RelaySessionState
import com.eclypses.relay.streaming.RelayStreamingExecutor
import com.eclypses.relay.transport.RelayTransport
import com.eclypses.relay.transport.RelayTransportRequest
import com.eclypses.relay.transport.RelayTransportResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/**
 * The interceptor rides the same streaming core as [com.eclypses.relay.Relay.send], so these tests
 * assert what actually reaches the transport: the relay frame the interceptor caused to be sent,
 * and the `okhttp3.Response` it builds from the relay's answer.
 */
class RelayMteInterceptorTest {

    // region RelayRequestOptions

    @Test
    fun `RelayRequestOptions defaults are null unencryptedHeaders and null pathnamePrefix`() {
        val opts = RelayRequestOptions()

        assertNull(opts.unencryptedHeaders)
        assertNull(opts.pathnamePrefix)
    }

    @Test
    fun `RelayRequestOptions stores provided values`() {
        val opts = RelayRequestOptions(
            unencryptedHeaders = arrayOf("traceparent", "x-request-id"),
            pathnamePrefix = "edge/v1",
        )

        assertContentEquals(arrayOf("traceparent", "x-request-id"), opts.unencryptedHeaders)
        assertEquals("edge/v1", opts.pathnamePrefix)
    }

    // endregion

    // region Interceptor — tag handling

    @Test
    fun `intercept encrypts every header except those named by the RelayRequestOptions tag`() {
        val transport = RecordingTransport(frameResponse(statusCode = 200))
        val interceptor = RelayMteInterceptor(executorWith(transport))

        val request = Request.Builder()
            .url("https://api.example.com/data")
            .addHeader("Authorization", "token")
            .addHeader("traceparent", "00-trace-id-01")
            .tag(
                RelayRequestOptions::class.java,
                RelayRequestOptions(unencryptedHeaders = arrayOf("traceparent")),
            )
            .get()
            .build()

        executeWithFakeChain(interceptor, request)

        val frame = parseRequestFrame(transport.capturedPayload())
        // The named header is the exposed one; everything else, including the credential
        // the caller never thought about, stays in the encrypted metadata.
        //
        // Lowercase: section 8.2 makes header names lowercase ASCII tokens on the wire in
        // both directions, and the metadata rules refuse an uppercase one rather than
        // folding it, so the fold happens on the way in. HTTP header names are
        // case-insensitive, so nothing downstream can tell.
        assertEquals(mapOf("authorization" to "token"), frame.headers)
        assertEquals("data", frame.route)
    }

    @Test
    fun `intercept encrypts every header and uses no prefix when the tag is absent`() {
        val transport = RecordingTransport(frameResponse(statusCode = 200))
        val interceptor = RelayMteInterceptor(executorWith(transport))

        val request = Request.Builder()
            .url("https://api.example.com/ping")
            .addHeader("X-Custom", "value")
            .get()
            .build()

        executeWithFakeChain(interceptor, request)

        val frame = parseRequestFrame(transport.capturedPayload())
        assertEquals(mapOf("x-custom" to "value"), frame.headers)
        assertEquals("ping", frame.route)
    }

    @Test
    fun `intercept applies the pathnamePrefix from the tag to the encrypted route`() {
        val transport = RecordingTransport(frameResponse(statusCode = 200))
        val interceptor = RelayMteInterceptor(executorWith(transport))

        val request = Request.Builder()
            .url("https://api.example.com/upload")
            .tag(RelayRequestOptions::class.java, RelayRequestOptions(pathnamePrefix = "api/v2"))
            .post("body".toByteArray().toRequestBody("text/plain".toMediaType()))
            .build()

        executeWithFakeChain(interceptor, request)

        assertEquals("api/v2/upload", parseRequestFrame(transport.capturedPayload()).route)
    }

    // endregion

    // region Interceptor — request/response mapping

    @Test
    fun `intercept sends the method, route with query, and body, then returns the decoded response`() {
        val transport = RecordingTransport(
            frameResponse(statusCode = 200, body = "relay-result".toByteArray()),
        )
        val interceptor = RelayMteInterceptor(executorWith(transport))

        val bodyBytes = "request-payload".toByteArray()
        val request = Request.Builder()
            .url("https://api.example.com/api/items?page=2")
            .addHeader("X-Custom", "value")
            .post(bodyBytes.toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeWithFakeChain(interceptor, request)

        val frame = parseRequestFrame(transport.capturedPayload())
        assertEquals(RelayHttpMethod.POST.wireValue, frame.method)
        assertEquals("api/items?page=2", frame.route)
        assertContentEquals(bodyBytes, frame.body)

        assertEquals(200, response.code)
        assertContentEquals("relay-result".toByteArray(), response.body?.use { it.bytes() } ?: ByteArray(0))
    }

    @Test
    fun `intercept returns the relay error response on 559 without auto-retry`() {
        val transport = RecordingTransport(RawResponse(559, "repair-required".toByteArray()))
        val interceptor = RelayMteInterceptor(executorWith(transport))

        val request = Request.Builder()
            .url("https://api.example.com/data")
            .get()
            .build()

        val response = executeWithFakeChain(interceptor, request)

        assertEquals(559, response.code)
        assertEquals(1, transport.callCount, "a relay repair status must not be retried")
    }

    @Test
    fun `intercept preserves response headers from the decoded frame`() {
        val transport = RecordingTransport(
            frameResponse(
                statusCode = 201,
                headersJson = "{\"Location\":\"/items/42\",\"X-Request-Id\":\"abc\"}",
            ),
        )
        val interceptor = RelayMteInterceptor(executorWith(transport))

        val request = Request.Builder()
            .url("https://api.example.com/items")
            .post(ByteArray(0).toRequestBody())
            .build()

        val response = executeWithFakeChain(interceptor, request)

        assertEquals(201, response.code)
        assertEquals("/items/42", response.header("Location"))
        assertEquals("abc", response.header("X-Request-Id"))
    }

    // endregion

    // region Helpers

    // region Failures reach OkHttp as IOException

    /**
     * An enqueued call whose relay fails for a relay reason must end in `onFailure` and nowhere
     * else. OkHttp rethrows a non-IOException from an interceptor on its dispatcher thread after
     * `onFailure`, which on Android kills the app; this is the crash the wrapping exists for.
     */
    @Test
    fun `an enqueued call's relay failure reaches onFailure and is not rethrown`() {
        val interceptor = RelayMteInterceptor(executorWith(RecordingTransport(notAFrame())))
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()

        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.set(e) }
        try {
            val failure = java.util.concurrent.CompletableFuture<java.io.IOException>()
            client.newCall(Request.Builder().url("https://api.example.com/users").build())
                .enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        failure.complete(e)
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        failure.completeExceptionally(AssertionError("expected a failure"))
                    }
                })

            val e = failure.get(10, java.util.concurrent.TimeUnit.SECONDS)
            val relayFailure = kotlin.test.assertIs<RelayCallException>(e)
            kotlin.test.assertIs<com.eclypses.relay.protocol.RelayProtocolException>(relayFailure.cause)

            // The rethrow, if any, happens on the dispatcher thread right after onFailure.
            client.dispatcher.executorService.shutdown()
            client.dispatcher.executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
            assertNull(uncaught.get(), "a relay failure escaped onto OkHttp's dispatcher thread")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `a synchronous call's relay failure is thrown as RelayCallException carrying the cause`() {
        val interceptor = RelayMteInterceptor(executorWith(RecordingTransport(notAFrame())))

        val e = kotlin.test.assertFailsWith<RelayCallException> {
            executeWithFakeChain(interceptor, Request.Builder().url("https://api.example.com/users").build())
        }
        kotlin.test.assertIs<com.eclypses.relay.protocol.RelayProtocolException>(e.cause)
    }

    /** A 200 whose body is not frame version 2 -- the client refuses it as a protocol error. */
    private fun notAFrame() = RawResponse(200, "definitely not a frame".toByteArray())

    // endregion

    /**
     * Runs the interceptor against a real OkHttpClient chain that never reaches the network — the
     * interceptor short-circuits by executing the relay request itself.
     */
    private fun executeWithFakeChain(interceptor: RelayMteInterceptor, request: Request): okhttp3.Response {
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(599)
                    .message("BUG: RelayMteInterceptor did not short-circuit")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()
        return client.newCall(request).execute()
    }

    /** A streaming executor over a READY session holding one identity (passthrough) pair. */
    private fun executorWith(transport: RelayTransport): RelayStreamingExecutor {
        val origin = "https://api.example.com"
        val pair = IdentityRuntimePair(com.eclypses.relay.session.testPairId("pair-1"))
        val token = com.eclypses.mte.wire.Token(ByteArray(40) { (it + 5).toByte() })
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate(origin)
        session.setToken(token)
        // Frame v2 has no "relay without discovery" case: the frame bound and both pairing
        // windows come from here, so a session without it cannot build a request at all.
        session.setDiscovery(com.eclypses.relay.session.testDiscovery())
        session.setPairMaterials(
            listOf(
                RelayPairMaterial(
                    pairId = pair.pairId,
                    encoderNonce = 1L,
                    decoderNonce = 2L,
                    encoderResponderEncryptedSecret = byteArrayOf(0x01),
                    decoderResponderEncryptedSecret = byteArrayOf(0x02),
                    encoderPersonalizationStr = "enc",
                    decoderPersonalizationStr = "dec",
                ),
            ),
        )
        session.setRuntimePairs(listOf(pair))
        session.transition(RelaySessionState.READY)

        return RelayStreamingExecutor(
            sessionManager = sessionManager,
            sessionLifecycleManager = RelaySessionLifecycleManager(
                sessionManager,
                StubControlPlaneClient(token, pair),
            ),
            transport = transport,
        )
    }

    private class RawResponse(val statusCode: Int, val body: ByteArray)

    /** Serves one canned response and records the relay frame it was asked to send. */
    private class RecordingTransport(private val canned: RawResponse) : RelayTransport {
        private var payload: ByteArray? = null
        var callCount = 0
            private set

        fun capturedPayload(): ByteArray = assertNotNull(payload, "the interceptor never sent a relay frame")

        override fun execute(request: RelayTransportRequest): RelayTransportResponse =
            throw UnsupportedOperationException("interceptor mode must ride the streaming core")

        override fun executeDownloadStreaming(
            request: RelayTransportRequest,
            responseBodyHandler: (Int, Map<String, String>, InputStream) -> RelayTransportResponse,
        ): RelayTransportResponse {
            callCount += 1
            payload = request.payload
            return responseBodyHandler(
                if (canned.statusCode in 200..299) 200 else canned.statusCode,
                emptyMap(),
                ByteArrayInputStream(canned.body),
            )
        }
    }

    /** Builds a frame version 2 response: one RESPONSE, then one DATA when there is a body. */
    private fun frameResponse(
        statusCode: Int,
        headersJson: String? = null,
        body: ByteArray = ByteArray(0),
    ): RawResponse {
        val members = LinkedHashMap<String, com.eclypses.mte.wire.JsonValue>()
        members["v"] = com.eclypses.mte.wire.JsonNumber("2")
        // The status travels twice -- here and in the frame -- so a rewritten plaintext
        // one is caught. A builder that sets only one makes a frame the client refuses.
        members["status"] = com.eclypses.mte.wire.JsonNumber(statusCode.toString())
        if (headersJson != null) {
            members["headers"] = com.eclypses.mte.wire.Json.parseObject(headersJson)
        }
        val metadata = com.eclypses.mte.wire.Json
            .canonical(com.eclypses.mte.wire.JsonObject(members))
            .toByteArray(StandardCharsets.UTF_8)
        val responsePayload = com.eclypses.mte.wire.ResponseFrame(statusCode, metadata).toByteArray()

        val out = ByteArrayOutputStream()
        val end = body.isEmpty()
        out.write(
            com.eclypses.mte.wire.Envelope(
                com.eclypses.mte.wire.Kind.RESPONSE,
                if (end) com.eclypses.mte.wire.Envelope.FLAG_END else 0,
                responsePayload.size.toLong(),
            ).toByteArray(),
        )
        out.write(responsePayload)
        if (!end) {
            val plaintext = com.eclypses.mte.wire.DataPlaintext.build(
                com.eclypses.mte.wire.Envelope.FLAG_END, body,
            )
            out.write(
                com.eclypses.mte.wire.Envelope(
                    com.eclypses.mte.wire.Kind.DATA,
                    com.eclypses.mte.wire.Envelope.FLAG_END,
                    plaintext.size.toLong(),
                ).toByteArray(),
            )
            out.write(plaintext)
        }
        return RawResponse(statusCode, out.toByteArray())
    }

    private class ParsedFrame(
        val method: Int,
        val route: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    /** Parses a REQUEST frame and the DATA frames after it, for an identity pair. */
    private fun parseRequestFrame(bytes: ByteArray): ParsedFrame {
        var i = 0
        val envelope = com.eclypses.mte.wire.Envelope.read(bytes, i)
        i += com.eclypses.mte.wire.Envelope.SIZE
        val request = com.eclypses.mte.wire.RequestFrame
            .read(bytes.copyOfRange(i, i + envelope.length.toInt()))
        i += envelope.length.toInt()

        val json = JSONObject(String(request.metadata, StandardCharsets.UTF_8))
        val headerObject = json.optJSONObject("headers")
        val headers = buildMap {
            headerObject?.keys()?.forEach { put(it, headerObject.getString(it)) }
        }

        val body = ByteArrayOutputStream()
        while (i < bytes.size) {
            val d = com.eclypses.mte.wire.Envelope.read(bytes, i)
            i += com.eclypses.mte.wire.Envelope.SIZE
            val payload = bytes.copyOfRange(i, i + d.length.toInt())
            i += d.length.toInt()
            if (d.length > 0) {
                body.write(com.eclypses.mte.wire.DataPlaintext.split(payload, d.flags))
            }
        }

        return ParsedFrame(
            method = request.methodByte,
            route = json.optString("path"),
            headers = headers,
            body = body.toByteArray(),
        )
    }

    private class IdentityRuntimePair(
        override val pairId: com.eclypses.mte.wire.PairId,
    ) : RelayRuntimePair {
        override fun encode(payload: ByteArray) = payload
        override fun decode(payload: ByteArray) = payload
    }

    private class StubControlPlaneClient(
        private val token: com.eclypses.mte.wire.Token,
        private val pair: RelayRuntimePair,
    ) : RelayControlPlaneClient {
        override fun authenticate(origin: String, existingToken: com.eclypses.mte.wire.Token?) =
            RelayAuthResponse(token, com.eclypses.relay.session.testDiscovery())

        override fun pair(
            origin: String,
            token: com.eclypses.mte.wire.Token,
            pairPoolSize: Int,
            sequenceWindow: Int,
            timeWindow: Long,
        ) = RelayPairingResult(emptyList(), listOf(pair))

        override fun keepAlive(
            origin: String,
            token: com.eclypses.mte.wire.Token,
            drop: List<com.eclypses.mte.wire.PairId>,
        ) {}
    }

    // endregion
}
