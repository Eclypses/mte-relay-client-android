package com.eclypses.relay.streaming

import com.eclypses.relay.RelayFileRequestProperties
import com.eclypses.relay.RelaySseListener
import com.eclypses.relay.RelayStreamCallback
import com.eclypses.relay.RelayStreamCompletionCallback
import com.eclypses.relay.RelayStreamResponseListener
import com.eclypses.relay.protocol.BinaryRelayProtocolEngine
import com.eclypses.relay.session.RelayAuthResponse
import com.eclypses.relay.session.RelayControlPlaneClient
import com.eclypses.relay.session.RelayOriginSession
import com.eclypses.relay.session.RelayPairingResult
import com.eclypses.relay.session.RelayPairMaterial
import com.eclypses.relay.session.RelayRuntimePair
import com.eclypses.relay.session.RelaySessionLifecycleManager
import com.eclypses.relay.session.RelaySessionManager
import com.eclypses.relay.session.RelaySessionState
import com.eclypses.relay.transport.RelayStreamingTransportRequest
import com.eclypses.relay.transport.RelayTransport
import com.eclypses.relay.transport.RelayTransportRequest
import com.eclypses.relay.transport.RelayTransportResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class RelayStreamingExecutorTest {

    @Test
    fun uploadFile_cancelledOperation_propagatesDeterministicError() {
        val fakePair = FakeRuntimePair("pair-upload-cancel")
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
            protocolEngine = BinaryRelayProtocolEngine(),
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
        val fakePair = FakeRuntimePair("pair-download-cancel")
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
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
        )
        val operationId = executor.beginOperation()
        assertTrue(executor.cancelOperation(operationId))

        executor.downloadFile(
            operationId = operationId,
            reqProperties = requestProperties,
            pathnamePrefix = null,
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

    // ---- V5 streaming upload integration tests --------------------------------

    /**
     * Passthrough pair: encode/decode are identity; encrypt operations are no-ops except
     * [finishEncrypt] which returns [trailing].  [encryptFinishBytes] reports the trailing size.
     */
    private class FakeRuntimePair(
        override val pairId: String,
        private val trailing: ByteArray = ByteArray(0),
    ) : RelayRuntimePair {
        override fun encode(payload: ByteArray) = payload
        override fun decode(payload: ByteArray) = payload
        override fun startEncrypt() {}
        override fun encryptChunk(buffer: ByteArray, length: Int) {} // in-place no-op
        override fun finishEncrypt() = trailing
        override fun encryptFinishBytes() = trailing.size
        override fun startDecrypt() {}
        override fun decryptChunk(buffer: ByteArray): ByteArray = buffer // passthrough no-op
        override fun finishDecrypt(): ByteArray = ByteArray(0)
    }

    private class FakeControlPlaneClient(
        private val clientId: String,
        private val runtimePairs: List<RelayRuntimePair>,
    ) : RelayControlPlaneClient {
        override fun authenticate(origin: String, existingClientId: String?) =
            RelayAuthResponse(clientId)

        override fun pair(origin: String, clientId: String, pairPoolSize: Int) =
            RelayPairingResult(emptyList(), runtimePairs)

        override fun keepAlive(origin: String, clientId: String, pairIds: List<String>) {
        }
    }

    /** Configures a [RelaySessionManager] with a READY session holding [pair] and [clientId]. */
    private fun readySession(
        origin: String,
        clientId: String,
        pair: RelayRuntimePair,
    ): Pair<RelaySessionManager, RelaySessionLifecycleManager> {
        val sm = RelaySessionManager()
        val session: RelayOriginSession = sm.getOrCreate(origin)
        session.setClientId(clientId)
        session.setPairMaterials(listOf(fakePairMaterial(pair.pairId)))
        session.setRuntimePairs(listOf(pair))
        session.transition(RelaySessionState.READY)
        val fakeControlPlane = FakeControlPlaneClient(clientId, listOf(pair))
        val slm = RelaySessionLifecycleManager(sm, fakeControlPlane)
        return sm to slm
    }

    private class ReplacementTrackingControlPlaneClient(
        private val clientId: String,
    ) : RelayControlPlaneClient {
        val pairPoolSizes = mutableListOf<Int>()
        var pairCalls = 0
        var authenticateCalls = 0

        override fun authenticate(origin: String, existingClientId: String?): RelayAuthResponse {
            authenticateCalls += 1
            return RelayAuthResponse(clientId)
        }

        override fun pair(origin: String, clientId: String, pairPoolSize: Int): RelayPairingResult {
            pairCalls += 1
            pairPoolSizes += pairPoolSize
            return RelayPairingResult(
                materials = emptyList(),
                runtimePairs = (1..pairPoolSize).map { index -> FakeRuntimePair("replacement-$pairCalls-$index") },
            )
        }

        override fun keepAlive(origin: String, clientId: String, pairIds: List<String>) {
        }
    }


    @Test
    fun uploadFileV5_writesFrameMetadataThenBody_andContentLengthMatchesPrediction() {
        val trailingBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val fakePair = FakeRuntimePair("pair-001", trailing = trailingBytes)
        val (sm, slm) = readySession("https://relay.example", "test-client", fakePair)

        val capturedContentLength = AtomicLong(-1)
        val capturedBody = ByteArrayOutputStream()
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeStreaming(request: RelayStreamingTransportRequest): RelayTransportResponse {
                capturedContentLength.set(request.contentLength)
                request.bodyWriter(capturedBody)
                // Return a non-MTE payload so decodeResponseFrame routes through statusCode passthrough
                return RelayTransportResponse(statusCode = 200, payload = "{}".toByteArray())
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
            arrayOf("Content-Type", "Content-Length"),
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
            protocolEngine = BinaryRelayProtocolEngine(),
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

        // Content-Length prediction must equal actual bytes written
        assertEquals(capturedContentLength.get(), fullWritten.size.toLong(), "predicted content-length must match bytes actually written")

        // Verify frame starts with MTE magic
        assertEquals('M'.code.toByte(), fullWritten[0])
        assertEquals('T'.code.toByte(), fullWritten[1])
        assertEquals('E'.code.toByte(), fullWritten[2])

        // Parse ProtoLen to find where the metadata header ends
        val protoLen = ((fullWritten[3].toInt() and 0xFF) shl 8) or (fullWritten[4].toInt() and 0xFF)
        val metadataEnd = 5 + protoLen

        // Body bytes must follow immediately after frame metadata
        val bodyStart = metadataEnd
        val bodyEnd = fullWritten.size - trailingBytes.size
        assertTrue(bodyStart < bodyEnd, "body bytes must be present after frame metadata")
        val extractedBody = fullWritten.copyOfRange(bodyStart, bodyEnd)
        assertTrue(extractedBody.contentEquals(bodyBytes), "body bytes after frame metadata must match original content")

        // Trailing finishEncrypt bytes must be appended at the end
        val extractedTrailing = fullWritten.copyOfRange(bodyEnd, fullWritten.size)
        assertTrue(extractedTrailing.contentEquals(trailingBytes), "finishEncrypt trailing bytes must be at end of written payload")

        // Content-Length decomposition matches
        val predictedTotal = metadataEnd.toLong() + bodyBytes.size.toLong() + trailingBytes.size.toLong()
        assertEquals(predictedTotal, capturedContentLength.get(), "frameMetadata + bodyBytes + trailingBytes must equal predicted content-length")

        // Progress callbacks were fired and are monotonically non-decreasing
        assertTrue(progressEvents.isNotEmpty(), "at least one progress event must be fired")
        assertTrue(progressEvents.zipWithNext().all { (a, b) -> b.first >= a.first }, "progress bytes must be non-decreasing")

        // Listener received 200 success
        val result = assertNotNull(listenerResult.get())
        assertEquals(200, result.statusCode)
        assertTrue(result.success)
    }

    @Test
    fun uploadFileV5_559response_triggersRepairAndReturnsError_noRetry() {
        val fakePair = FakeRuntimePair("pair-559")
        val (sm, slm) = readySession("https://relay.example", "client-559", fakePair)

        val transportCallCount = AtomicInteger(0)
        val transport = object : RelayTransport {
            override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
            override fun executeStreaming(request: RelayStreamingTransportRequest): RelayTransportResponse {
                transportCallCount.incrementAndGet()
                // Drain pipe to avoid blocking the callback thread
                val sink = ByteArrayOutputStream()
                request.bodyWriter(sink)
                return RelayTransportResponse(
                    statusCode = 559,
                    payload = "relay session expired".toByteArray(),
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
            arrayOf("Content-Type", "Content-Length"),
            RelayStreamCallback { out ->
                out.write(byteArrayOf(1, 2, 3))
                out.close()
            },
        )

        val listenerResult = AtomicReference<StreamListenerResult>()

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
            uploadBodyTimeoutMillis = 10_000,
        )

        executor.uploadFile(
            reqProperties = reqProperties,
            listener = recordingListener(listenerResult),
            completionCallback = null,
        )

        // Transport called exactly once — no auto-retry
        assertEquals(1, transportCallCount.get(), "transport must be called exactly once; no retry on 559")

        // Session must be in REPAIRING or READY state (repair was executed after 559)
        val snapshotState = sm.get("https://relay.example")?.state
        assertTrue(
            snapshotState == RelaySessionState.REPAIRING || snapshotState == RelaySessionState.READY,
            "session must transition to REPAIRING (and optionally back to READY) after 559",
        )

        // Listener must receive error with 559 status
        val result = assertNotNull(listenerResult.get())
        assertEquals(559, result.statusCode)
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun downloadFileV5_streamsBodyToFile_withoutBufferingFullPayload() {
        val fakePair = FakeRuntimePair("pair-dl-001")
        val (sm, slm) = readySession("https://relay.example", "client-dl", fakePair)

        // Build a real V5 response frame to serve from the fake transport
        val responseBody = "Hello streaming download!".toByteArray()
        val protocolEngine = BinaryRelayProtocolEngine()
        // We need a codec to build the response frame — use the fakePair (identity encode/decode)
        val codec = com.eclypses.relay.protocol.RelayPayloadCodec { it }
        // Build a hand-crafted minimal V5 response frame: [MAGIC][protoLen][metadata][body]
        // Use encodeRequestFrame on a minimal response shape — easiest is just to call
        // decodeResponseFrame in reverse.  Instead, build the raw binary manually mirroring
        // BinaryRelayProtocolEngine.decodeResponseFrame expected layout.
        val responseFrame = buildFakeV5ResponseFrame(
            statusCode = 200,
            headersJson = null,
            body = responseBody,
            codec = codec,
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
            protocolEngine = protocolEngine,
            transport = transport,
        )

        executor.downloadFile(
            reqProperties = reqProperties,
            pathnamePrefix = null,
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
    fun downloadFileV5_559response_triggersRepairAndReturnsError() {
        val fakePair = FakeRuntimePair("pair-dl-559")
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
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
        )

        executor.downloadFile(
            reqProperties = reqProperties,
            pathnamePrefix = null,
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
        val fakePair = FakeRuntimePair("pair-sse-001")
        val (sm, slm) = readySession("https://relay.example", "client-sse", fakePair)

        val responseFrame = buildFakeV5ResponseFrame(
            statusCode = 200,
            headersJson = "{\"content-type\":\"text/event-stream\"}",
            body = "data: first\n\ndata: second\n\n".toByteArray(),
            codec = com.eclypses.relay.protocol.RelayPayloadCodec { it },
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
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
        )

        executor.streamServerSentEvents(
            operationId = "stream-001",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events?events=2",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            headersToEncrypt = null,
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
        val initialPair = FakeRuntimePair("pair-cancel-001")
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate("https://relay.example")
        session.setClientId("client-sse")
        session.setPairMaterials(listOf(fakePairMaterial(initialPair.pairId)))
        session.setRuntimePairs(listOf(initialPair))
        session.transition(RelaySessionState.READY)
        val controlPlane = ReplacementTrackingControlPlaneClient("client-sse")
        val lifecycleManager = RelaySessionLifecycleManager(sessionManager, controlPlane)

        val responseFrame = buildFakeV5ResponseFrame(
            statusCode = 200,
            headersJson = "{\"content-type\":\"text/event-stream\"}",
            body = "data: payload\n\n".toByteArray(),
            codec = com.eclypses.relay.protocol.RelayPayloadCodec { it },
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
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
        )

        executor.streamServerSentEvents(
            operationId = "stream-cancel",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events?events=1",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            headersToEncrypt = null,
            listener = listener,
        )

        assertEquals(1, controlPlane.pairCalls)
        assertEquals(listOf(1), controlPlane.pairPoolSizes)
        assertEquals(null, session.getRuntimePair("pair-cancel-001"))
        assertNotNull(session.getRuntimePair("replacement-1-1"))
    }

    @Test
    fun streamServerSentEventsV5_terminalError_discardsAndReplacesPair() {
        val initialPair = FakeRuntimePair("pair-error-001")
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate("https://relay.example")
        session.setClientId("client-sse")
        session.setPairMaterials(listOf(fakePairMaterial(initialPair.pairId)))
        session.setRuntimePairs(listOf(initialPair))
        session.transition(RelaySessionState.READY)
        val controlPlane = ReplacementTrackingControlPlaneClient("client-sse")
        val lifecycleManager = RelaySessionLifecycleManager(sessionManager, controlPlane)

        val responseFrame = buildFakeV5ResponseFrame(
            statusCode = 500,
            headersJson = "{\"content-type\":\"text/plain\"}",
            body = "relay downstream failure".toByteArray(),
            codec = com.eclypses.relay.protocol.RelayPayloadCodec { it },
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
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
        )

        executor.streamServerSentEvents(
            operationId = "stream-error",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events?events=1",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            headersToEncrypt = null,
            listener = listener,
        )

        assertEquals(1, controlPlane.pairCalls)
        assertEquals(listOf(1), controlPlane.pairPoolSizes)
        assertEquals(null, session.getRuntimePair("pair-error-001"))
        assertNotNull(session.getRuntimePair("replacement-1-1"))
        assertTrue(listener.errors.single().contains("stream-error:500:relay downstream failure"))
    }

    @Test
    fun streamServerSentEventsV5_successfulCompletion_releasesLeasedPair() {
        val initialPair = FakeRuntimePair("pair-success-001")
        val sessionManager = RelaySessionManager()
        val session = sessionManager.getOrCreate("https://relay.example")
        session.setClientId("client-sse")
        session.setPairMaterials(listOf(fakePairMaterial(initialPair.pairId)))
        session.setRuntimePairs(listOf(initialPair))
        session.transition(RelaySessionState.READY)
        val lifecycleManager = RelaySessionLifecycleManager(sessionManager, ReplacementTrackingControlPlaneClient("client-sse"))

        val responseFrame = buildFakeV5ResponseFrame(
            statusCode = 200,
            headersJson = "{\"content-type\":\"text/event-stream\"}",
            body = "data: payload\n\n".toByteArray(),
            codec = com.eclypses.relay.protocol.RelayPayloadCodec { it },
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
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
        )

        executor.streamServerSentEvents(
            operationId = "stream-success",
            serverPath = "https://relay.example/ignored",
            route = "/api/server-side-events?events=1",
            pathnamePrefix = null,
            headers = linkedMapOf(),
            headersToEncrypt = null,
            listener = listener,
        )

        assertFalse(session.isRuntimePairLeased("pair-success-001"))
        assertEquals(listOf("stream-success"), listener.completed)
    }

    /**
     * Builds a minimal valid V5 binary response frame mirroring the layout that
     * [BinaryRelayProtocolEngine.decodeResponseFrameHeader] expects:
     *
     * [MAGIC 3][protoLen 2][StrType 1][Method 1][StatusCode 2][clientId lpfx][pairId lpfx]
     * [MteType 1][PathFlag 1][HeadFlag 1][BodyFlag 1][StreamFlag 1] || [encoded body bytes]
     */
    private fun buildFakeV5ResponseFrame(
        statusCode: Int,
        headersJson: String?,
        body: ByteArray,
        codec: com.eclypses.relay.protocol.RelayPayloadCodec,
    ): ByteArray {
        val meta = ByteArrayOutputStream()

        meta.write(0x01)                              // StrType = UTF8
        meta.write(0x00)                              // Method = GET
        meta.write((statusCode ushr 8) and 0xFF)      // StatusCode high byte
        meta.write(statusCode and 0xFF)               // StatusCode low byte

        val emptyLpfx = byteArrayOf(0x00, 0x00)
        meta.write(emptyLpfx)                         // clientId = ""
        meta.write(emptyLpfx)                         // pairId = ""
        meta.write(0x00)                              // MTE Type = MTE

        meta.write(0x00)                              // PathFlag = FALSE

        if (headersJson != null) {
            val encodedHeaders = codec.encode(headersJson.toByteArray())
            meta.write(0x01)                          // HeadFlag = TRUE
            meta.write((encodedHeaders.size ushr 8) and 0xFF)
            meta.write(encodedHeaders.size and 0xFF)
            meta.write(encodedHeaders)
        } else {
            meta.write(0x00)                          // HeadFlag = FALSE
        }

        meta.write(if (body.isNotEmpty()) 0x01 else 0x00) // BodyFlag
        meta.write(0x00)                              // StreamFlag reserved

        val metaBytes = meta.toByteArray()
        val protoLen = metaBytes.size

        val out = ByteArrayOutputStream()
        out.write('M'.code)
        out.write('T'.code)
        out.write('E'.code)
        out.write((protoLen ushr 8) and 0xFF)
        out.write(protoLen and 0xFF)
        out.write(metaBytes)

        if (body.isNotEmpty()) {
            val encodedBody = codec.encode(body)
            out.write(encodedBody)
        }

        return out.toByteArray()
    }

    private fun fakePairMaterial(pairId: String): RelayPairMaterial {
        return RelayPairMaterial(
            pairId = pairId,
            encoderNonce = 1L,
            decoderNonce = 2L,
            encoderResponderEncryptedSecret = byteArrayOf(0x01),
            decoderResponderEncryptedSecret = byteArrayOf(0x02),
            encoderPersonalizationStr = "enc-$pairId",
            decoderPersonalizationStr = "dec-$pairId",
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
        val pair = RecordingRuntimePair("pair-buffered", requestBody, operationLog)
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
                val responseFrame = buildFakeV5ResponseFrame(
                    statusCode = 202,
                    headersJson = "{\"x-order\":\"ok\"}",
                    body = responseBody,
                    codec = com.eclypses.relay.protocol.RelayPayloadCodec { invertBytes(it) },
                )
                return responseBodyHandler(200, emptyMap(), java.io.ByteArrayInputStream(responseFrame))
            }
        }

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/messages",
            route = "/api/messages",
            pathnamePrefix = "/edge",
            method = "PUT",
            body = requestBody,
            headers = mapOf("x-keep" to "keep", "x-drop" to "drop"),
            headersToEncrypt = arrayOf("x-keep"),
        )

        assertEquals(202, response.statusCode)
        assertEquals(mapOf("x-order" to "ok"), response.headers)
        assertTrue(response.payload.contentEquals(responseBody), "every streamed chunk must be folded into the payload")

        // The request frame carries the method, the encrypted route (with its pathname prefix),
        // only the headers the caller asked to encrypt, and the encrypted body.
        val frame = parseRelayRequestFrame(assertNotNull(capturedFrame.get()))
        assertEquals("edge/api/messages", frame.route)
        assertEquals("{\"x-keep\":\"keep\"}", frame.headersJson)
        assertEquals("pair-buffered", frame.pairId)
        assertTrue(frame.body.contentEquals(requestBody))

        // Metadata is encoded before the body; the response headers are decoded one-shot while the
        // body arrives through the chunked streaming decrypt.
        assertEquals(
            listOf("encode:metadata", "encode:body", "decode:headers", "startDecrypt", "decrypt:chunk"),
            operationLog,
        )
    }

    @Test
    fun executeBuffered_560response_replacesOnlyThatPair_andSurfacesStatus() {
        val (sm, slm, controlPlane) = trackedReadySession("https://relay.example", "client-560", FakeRuntimePair("pair-560"))

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = relayStatusTransport(560, "relay-error"),
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/messages",
            route = "/api/messages",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            headersToEncrypt = null,
        )

        assertEquals(560, response.statusCode)
        assertTrue(response.payload.contentEquals("relay-error".toByteArray()))
        assertEquals(0, controlPlane.authenticateCalls, "a single-pair repair must not re-authenticate")
        assertEquals(1, controlPlane.pairCalls)
        assertEquals(listOf(1), controlPlane.pairPoolSizes)

        val session = assertNotNull(sm.get("https://relay.example"))
        assertNull(session.getRuntimePair("pair-560"), "the desynced pair must be discarded")
        assertNotNull(session.getRuntimePair("replacement-1-1"))
    }

    @Test
    fun executeBuffered_564response_runsFullRepair_withoutResending() {
        val (sm, slm, controlPlane) = trackedReadySession("https://relay.example", "client-564", FakeRuntimePair("pair-564"))

        val transportCalls = AtomicInteger(0)
        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = relayStatusTransport(564, "relay-error", transportCalls),
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/messages",
            route = "/api/messages",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            headersToEncrypt = null,
        )

        assertEquals(564, response.statusCode)
        assertEquals(1, transportCalls.get(), "the request must not be transparently resent")
        assertEquals(1, controlPlane.authenticateCalls, "a full repair re-authenticates the session")
        assertEquals(1, controlPlane.pairCalls)
        assertEquals(RelaySessionState.READY, assertNotNull(sm.snapshot("https://relay.example")).state)
    }

    @Test
    fun executeBuffered_567response_surfacesStatus_withoutMutatingThePool() {
        val (sm, slm, controlPlane) = trackedReadySession("https://relay.example", "client-567", FakeRuntimePair("pair-567"))

        val executor = RelayStreamingExecutor(
            sessionManager = sm,
            sessionLifecycleManager = slm,
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = relayStatusTransport(567, "relay-error"),
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/messages",
            route = "/api/messages",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            headersToEncrypt = null,
        )

        assertEquals(567, response.statusCode)
        assertEquals(0, controlPlane.authenticateCalls)
        assertEquals(0, controlPlane.pairCalls)
        assertNotNull(assertNotNull(sm.get("https://relay.example")).getRuntimePair("pair-567"))
    }

    @Test
    fun executeBuffered_upstreamErrorStatus_surfacesDecodedBody_andKeepsThePair() {
        val (sm, slm, controlPlane) = trackedReadySession("https://relay.example", "client-404", FakeRuntimePair("pair-404"))

        val responseFrame = buildFakeV5ResponseFrame(
            statusCode = 404,
            headersJson = "{\"content-type\":\"text/plain\"}",
            body = "not found".toByteArray(),
            codec = com.eclypses.relay.protocol.RelayPayloadCodec { it },
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
            protocolEngine = BinaryRelayProtocolEngine(),
            transport = transport,
        )

        val response = executor.executeBuffered(
            serverPath = "https://relay.example/api/missing",
            route = "/api/missing",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            headersToEncrypt = null,
        )

        assertEquals(404, response.statusCode)
        assertTrue(response.payload.contentEquals("not found".toByteArray()), "the decoded error body must be surfaced")
        // An upstream error says nothing about the pair — a shared pair must not be burned on a 404.
        assertEquals(0, controlPlane.pairCalls)
        assertNotNull(assertNotNull(sm.get("https://relay.example")).getRuntimePair("pair-404"))
    }

    /** Transport that answers every request with a relay [statusCode] and a plain (unframed) body. */
    private fun relayStatusTransport(
        statusCode: Int,
        payload: String,
        callCount: AtomicInteger = AtomicInteger(0),
    ): RelayTransport = object : RelayTransport {
        override fun execute(request: RelayTransportRequest) = throw UnsupportedOperationException()
        override fun executeDownloadStreaming(
            request: RelayTransportRequest,
            responseBodyHandler: (Int, Map<String, String>, java.io.InputStream) -> RelayTransportResponse,
        ): RelayTransportResponse {
            callCount.incrementAndGet()
            return responseBodyHandler(
                statusCode,
                emptyMap(),
                java.io.ByteArrayInputStream(payload.toByteArray()),
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
        session.setClientId(clientId)
        session.setPairMaterials(listOf(fakePairMaterial(pair.pairId)))
        session.setRuntimePairs(listOf(pair))
        session.transition(RelaySessionState.READY)
        val controlPlane = ReplacementTrackingControlPlaneClient(clientId)
        return Triple(sm, RelaySessionLifecycleManager(sm, controlPlane), controlPlane)
    }

    /**
     * Runtime pair whose codec inverts every byte (so a test can build a response the pair will
     * "decrypt" back to plaintext) and records the order in which the core uses it.
     */
    private class RecordingRuntimePair(
        override val pairId: String,
        private val requestBody: ByteArray,
        private val operationLog: MutableList<String>,
    ) : RelayRuntimePair {
        override fun encode(payload: ByteArray): ByteArray {
            operationLog += if (payload.contentEquals(requestBody)) "encode:body" else "encode:metadata"
            return invertBytes(payload)
        }

        override fun decode(payload: ByteArray): ByteArray {
            operationLog += "decode:headers"
            return invertBytes(payload)
        }

        override fun startEncrypt() {}
        override fun encryptChunk(buffer: ByteArray, length: Int) {}
        override fun finishEncrypt(): ByteArray = ByteArray(0)
        override fun encryptFinishBytes(): Int = 0

        override fun startDecrypt() {
            operationLog += "startDecrypt"
        }

        override fun decryptChunk(buffer: ByteArray): ByteArray {
            operationLog += "decrypt:chunk"
            return invertBytes(buffer)
        }

        override fun finishDecrypt(): ByteArray = ByteArray(0)
    }

    private data class ParsedRequestFrame(
        val clientId: String,
        val pairId: String,
        val route: String,
        val headersJson: String,
        val body: ByteArray,
    )

    /** Parses a frame built by [BinaryRelayProtocolEngine.encodeRequestFrame] for a [RecordingRuntimePair]. */
    private fun parseRelayRequestFrame(bytes: ByteArray): ParsedRequestFrame {
        assertEquals('M'.code.toByte(), bytes[0])
        assertEquals('T'.code.toByte(), bytes[1])
        assertEquals('E'.code.toByte(), bytes[2])

        val protoLen = readU16(bytes, 3)
        val metadataEnd = 5 + protoLen
        var cursor = 5
        cursor += 1 // StrType
        cursor += 1 // Method
        cursor += 2 // RespCode

        val clientId = readLengthPrefixed(bytes, cursor)
        cursor += clientId.totalSize
        val pairId = readLengthPrefixed(bytes, cursor)
        cursor += pairId.totalSize
        cursor += 1 // MTE type

        assertEquals(1, bytes[cursor].toInt() and 0xFF, "path/metadata flag must be set")
        cursor += 1
        val encodedMetadata = readLengthPrefixed(bytes, cursor)
        cursor += encodedMetadata.totalSize
        val metadata = invertBytes(encodedMetadata.value)

        val pathLength = readU16(metadata, 0)
        val route = String(metadata, 2, pathLength, java.nio.charset.StandardCharsets.UTF_8)
        val headersLength = readU16(metadata, 2 + pathLength)
        val headersJson = String(metadata, 4 + pathLength, headersLength, java.nio.charset.StandardCharsets.UTF_8)

        cursor += 1 // Head flag
        val bodyFlag = bytes[cursor].toInt() and 0xFF
        cursor += 1
        cursor += 1 // Stream flag
        assertEquals(metadataEnd, cursor)

        val body = if (bodyFlag == 1) invertBytes(bytes.copyOfRange(metadataEnd, bytes.size)) else ByteArray(0)
        return ParsedRequestFrame(
            clientId = String(clientId.value, java.nio.charset.StandardCharsets.UTF_8),
            pairId = String(pairId.value, java.nio.charset.StandardCharsets.UTF_8),
            route = route,
            headersJson = headersJson,
            body = body,
        )
    }

    private data class LengthPrefixedValue(val value: ByteArray, val totalSize: Int)

    private fun readLengthPrefixed(bytes: ByteArray, offset: Int): LengthPrefixedValue {
        val length = readU16(bytes, offset)
        val start = offset + 2
        return LengthPrefixedValue(bytes.copyOfRange(start, start + length), 2 + length)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
}

private fun invertBytes(value: ByteArray): ByteArray {
    return ByteArray(value.size) { index -> value[index].toInt().inv().toByte() }
}
