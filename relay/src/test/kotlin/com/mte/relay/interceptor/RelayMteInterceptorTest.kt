package com.mte.relay.interceptor

import com.mte.relay.protocol.BinaryRelayProtocolEngine
import com.mte.relay.protocol.RelayHttpMethod
import com.mte.relay.session.RelayAuthResponse
import com.mte.relay.session.RelayControlPlaneClient
import com.mte.relay.session.RelayPairMaterial
import com.mte.relay.session.RelayPairingResult
import com.mte.relay.session.RelayRuntimePair
import com.mte.relay.session.RelaySessionLifecycleManager
import com.mte.relay.session.RelaySessionManager
import com.mte.relay.session.RelaySessionState
import com.mte.relay.streaming.RelayStreamingExecutor
import com.mte.relay.transport.RelayTransport
import com.mte.relay.transport.RelayTransportRequest
import com.mte.relay.transport.RelayTransportResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import okhttp3.MediaType.Companion.toMediaType
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
 * The interceptor rides the same streaming core as [com.mte.relay.Relay.send], so these tests
 * assert what actually reaches the transport: the relay frame the interceptor caused to be sent,
 * and the `okhttp3.Response` it builds from the relay's answer.
 */
class RelayMteInterceptorTest {

    // region RelayRequestOptions

    @Test
    fun `RelayRequestOptions defaults are null headersToEncrypt, false preventStreaming, null pathnamePrefix`() {
        val opts = RelayRequestOptions()

        assertNull(opts.headersToEncrypt)
        assertEquals(false, opts.preventStreaming)
        assertNull(opts.pathnamePrefix)
    }

    @Test
    fun `RelayRequestOptions stores provided values`() {
        val opts = RelayRequestOptions(
            headersToEncrypt = arrayOf("Authorization", "X-Api-Key"),
            preventStreaming = true,
            pathnamePrefix = "edge/v1",
        )

        assertContentEquals(arrayOf("Authorization", "X-Api-Key"), opts.headersToEncrypt)
        assertEquals(true, opts.preventStreaming)
        assertEquals("edge/v1", opts.pathnamePrefix)
    }

    // endregion

    // region Interceptor — tag handling

    @Test
    fun `intercept encrypts only the headers named by the RelayRequestOptions tag`() {
        val transport = RecordingTransport(frameResponse(statusCode = 200))
        val interceptor = RelayMteInterceptor(executorWith(transport))

        val request = Request.Builder()
            .url("https://api.example.com/data")
            .addHeader("Authorization", "token")
            .addHeader("X-Other", "dropped")
            .tag(
                RelayRequestOptions::class.java,
                RelayRequestOptions(headersToEncrypt = arrayOf("Authorization")),
            )
            .get()
            .build()

        executeWithFakeChain(interceptor, request)

        val frame = parseRequestFrame(transport.capturedPayload())
        assertEquals("{\"Authorization\":\"token\"}", frame.headersJson)
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
        assertEquals("{\"X-Custom\":\"value\"}", frame.headersJson)
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
        val pair = IdentityRuntimePair("pair-1")
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate(origin)
        session.setClientId("client-1")
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
                StubControlPlaneClient("client-1", pair),
            ),
            protocolEngine = BinaryRelayProtocolEngine(),
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

    /** Builds a V5 response frame carrying [statusCode], [headersJson], and [body] (identity codec). */
    private fun frameResponse(
        statusCode: Int,
        headersJson: String? = null,
        body: ByteArray = ByteArray(0),
    ): RawResponse {
        val meta = ByteArrayOutputStream()
        meta.write(0x01)                                 // StrType = UTF8
        meta.write(0x00)                                 // Method (echo)
        meta.write((statusCode ushr 8) and 0xFF)
        meta.write(statusCode and 0xFF)
        meta.write(byteArrayOf(0x00, 0x00))              // clientId = ""
        meta.write(byteArrayOf(0x00, 0x00))              // pairId = ""
        meta.write(0x00)                                 // MTE type
        meta.write(0x00)                                 // Path flag

        if (headersJson != null) {
            val headerBytes = headersJson.toByteArray(StandardCharsets.UTF_8)
            meta.write(0x01)                             // Head flag
            meta.write((headerBytes.size ushr 8) and 0xFF)
            meta.write(headerBytes.size and 0xFF)
            meta.write(headerBytes)
        } else {
            meta.write(0x00)
        }

        meta.write(if (body.isNotEmpty()) 0x01 else 0x00) // Body flag
        meta.write(0x00)                                  // Stream flag

        val metaBytes = meta.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("MTE".toByteArray(StandardCharsets.UTF_8))
        out.write((metaBytes.size ushr 8) and 0xFF)
        out.write(metaBytes.size and 0xFF)
        out.write(metaBytes)
        out.write(body)
        return RawResponse(statusCode, out.toByteArray())
    }

    private class ParsedFrame(
        val method: Int,
        val route: String,
        val headersJson: String,
        val body: ByteArray,
    )

    /** Parses the request frame the executor sent, assuming the identity codec above. */
    private fun parseRequestFrame(bytes: ByteArray): ParsedFrame {
        val protoLen = readU16(bytes, 3)
        val metadataEnd = 5 + protoLen
        var cursor = 5
        cursor += 1                                       // StrType
        val method = bytes[cursor].toInt() and 0xFF
        cursor += 1
        cursor += 2                                       // RespCode
        cursor += 2 + readU16(bytes, cursor)              // clientId
        cursor += 2 + readU16(bytes, cursor)              // pairId
        cursor += 1                                       // MTE type
        cursor += 1                                       // Path/metadata flag

        val metadataLength = readU16(bytes, cursor)
        val metadata = bytes.copyOfRange(cursor + 2, cursor + 2 + metadataLength)
        cursor += 2 + metadataLength
        cursor += 1                                       // Head flag
        val hasBody = (bytes[cursor].toInt() and 0xFF) == 1

        val pathLength = readU16(metadata, 0)
        val route = String(metadata, 2, pathLength, StandardCharsets.UTF_8)
        val headersLength = readU16(metadata, 2 + pathLength)
        val headersJson = String(metadata, 4 + pathLength, headersLength, StandardCharsets.UTF_8)

        return ParsedFrame(
            method = method,
            route = route,
            headersJson = headersJson,
            body = if (hasBody) bytes.copyOfRange(metadataEnd, bytes.size) else ByteArray(0),
        )
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private class IdentityRuntimePair(override val pairId: String) : RelayRuntimePair {
        override fun encode(payload: ByteArray) = payload
        override fun decode(payload: ByteArray) = payload
        override fun startEncrypt() {}
        override fun encryptChunk(buffer: ByteArray, length: Int) {}
        override fun finishEncrypt(): ByteArray = ByteArray(0)
        override fun encryptFinishBytes(): Int = 0
        override fun startDecrypt() {}
        override fun decryptChunk(buffer: ByteArray): ByteArray = buffer
        override fun finishDecrypt(): ByteArray = ByteArray(0)
    }

    private class StubControlPlaneClient(
        private val clientId: String,
        private val pair: RelayRuntimePair,
    ) : RelayControlPlaneClient {
        override fun authenticate(origin: String, existingClientId: String?) = RelayAuthResponse(clientId)

        override fun pair(origin: String, clientId: String, pairPoolSize: Int) =
            RelayPairingResult(emptyList(), listOf(pair))

        override fun keepAlive(origin: String, clientId: String, pairIds: List<String>) {}
    }

    // endregion
}
