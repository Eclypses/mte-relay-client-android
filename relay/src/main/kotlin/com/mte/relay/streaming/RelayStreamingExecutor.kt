package com.mte.relay.streaming

import com.mte.relay.LogHelper
import com.mte.relay.RelayComponents
import com.mte.relay.RelayFileRequestProperties
import com.mte.relay.RelaySseListener
import com.mte.relay.RelayStreamCompletionCallback
import com.mte.relay.RelayStreamResponseListener
import com.mte.relay.protocol.RelayHttpMethod
import com.mte.relay.protocol.RelayProtocolEngine
import com.mte.relay.protocol.RelayProtocolException
import com.mte.relay.protocol.RelayRequestFrame
import com.mte.relay.protocol.RelayPayloadCodec
import com.mte.relay.session.RelayOriginSession
import com.mte.relay.session.RelayRuntimePair
import com.mte.relay.session.RelaySessionLifecycleManager
import com.mte.relay.session.RelaySessionManager
import com.mte.relay.session.RelaySessionState
import com.mte.relay.transport.RelayStreamingTransportRequest
import com.mte.relay.transport.RelayTransport
import com.mte.relay.transport.RelayTransportRequest
import com.mte.relay.transport.RelayTransportResponse
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

/** Buffered result of [RelayStreamingExecutor.executeBuffered]. */
data class RelayBufferedResponse(
    val statusCode: Int,
    val payload: ByteArray,
    val headers: Map<String, String>,
)

/**
 * The single relay transport core. Every request — an ordinary buffered reply, a long-lived
 * event stream, a file upload, a file download — is sent as one V5 binary frame and its response
 * body is MKE-decrypted chunk by chunk as it arrives.
 *
 * Callers differ only in how those chunks are delivered:
 *   - [executeBuffered] folds them into one payload and returns when the stream completes.
 *   - [streamServerSentEvents] hands each chunk to the caller live, as raw bytes.
 *   - [downloadFile] writes them straight to disk.
 *   - [uploadFile] additionally streams the *request* body as encrypted chunks.
 *
 * The transport never interprets the body — no `text/event-stream` parsing, no decoding. It hands
 * back what the server sent, decrypted, exactly as OkHttp would have without the relay.
 *
 * Recovery happens at the transport boundary: a relay repair status heals the pool in the
 * background and the request fails with that status. Requests are never transparently resent.
 */
class RelayStreamingExecutor @JvmOverloads constructor(
    private val sessionManager: RelaySessionManager,
    private val sessionLifecycleManager: RelaySessionLifecycleManager,
    private val protocolEngine: RelayProtocolEngine,
    private val transport: RelayTransport,
    private val useMke: Boolean = true,
    private val uploadBodyTimeoutMillis: Long = DEFAULT_UPLOAD_BODY_TIMEOUT_MILLIS,
) {

    @JvmOverloads
    constructor(
        components: RelayComponents,
        uploadBodyTimeoutMillis: Long = DEFAULT_UPLOAD_BODY_TIMEOUT_MILLIS,
    ) : this(
        sessionManager = components.sessionManager,
        sessionLifecycleManager = components.sessionLifecycleManager,
        protocolEngine = components.protocolEngine,
        transport = components.transport,
        useMke = components.useMke,
        uploadBodyTimeoutMillis = uploadBodyTimeoutMillis,
    )

    private val operations = ConcurrentHashMap<String, OperationState>()

    fun beginOperation(): String {
        val operationId = UUID.randomUUID().toString()
        operations[operationId] = OperationState()
        return operationId
    }

    fun cancelOperation(operationId: String): Boolean {
        val state = operations[operationId] ?: return false
        state.cancelled.set(true)
        return true
    }

    /**
     * Buffered request: runs the streaming core and concatenates every decrypted chunk, returning
     * once the response completes. A single-shot reply is just a stream that finishes quickly.
     *
     * Returns the relay/server response even when it is not a success (including relay repair
     * statuses, whose bodies carry the reason) so callers can surface it. Throws when the request
     * never produced a response at all (transport failure, crypto failure, cancellation).
     */
    @JvmOverloads
    fun executeBuffered(
        serverPath: String,
        route: String,
        pathnamePrefix: String?,
        method: String,
        body: ByteArray,
        headers: Map<String, String>,
        headersToEncrypt: Array<String>?,
        preventStreaming: Boolean = false,
        operationId: String = beginOperation(),
    ): RelayBufferedResponse {
        val operationState = operations.computeIfAbsent(operationId) { OperationState() }
        val payload = ByteArrayOutputStream()
        try {
            val result = streamRequest(
                operationId = operationId,
                operationState = operationState,
                serverPath = serverPath,
                route = route,
                pathnamePrefix = pathnamePrefix,
                method = method,
                body = body,
                headers = headers,
                headersToEncrypt = headersToEncrypt,
                preventStreaming = preventStreaming,
                leasePair = false,
                onOpened = { _, _ -> },
                onChunk = { chunk -> payload.write(chunk) },
            )
            return RelayBufferedResponse(result.statusCode, payload.toByteArray(), result.headers)
        } catch (failure: RelayStreamFailure) {
            return RelayBufferedResponse(failure.statusCode, failure.payload, failure.headers)
        } finally {
            operations.remove(operationId)
        }
    }

    fun uploadFile(
        reqProperties: RelayFileRequestProperties,
        listener: RelayStreamResponseListener,
        completionCallback: RelayStreamCompletionCallback?,
    ) {
        uploadFile(beginOperation(), reqProperties, listener, completionCallback)
    }

    fun uploadFile(
        operationId: String,
        reqProperties: RelayFileRequestProperties,
        listener: RelayStreamResponseListener,
        completionCallback: RelayStreamCompletionCallback?,
    ) {
        val operationState = operations.computeIfAbsent(operationId) { OperationState() }
        var origin: String? = null
        var runtimePairId: String? = null
        try {
            operationState.throwIfCancelled(operationId)
            origin = buildOrigin(reqProperties.serverPath)

            sessionLifecycleManager.ensureReady(origin)
            val session = sessionManager.getOrCreate(origin)
            val runtimePair = session.selectRuntimePairRoundRobin()
                ?: throw IllegalStateException("no runtime pairs available for $origin")
            runtimePairId = runtimePair.pairId

            synchronized(runtimePair) {
                operationState.throwIfCancelled(operationId)

                // Frame metadata declares bodyFlag=TRUE; the body follows as streaming MKE chunks.
                val clientId = requireNotNull(session.getClientId()) { "missing clientId for $origin" }
                val codec = runtimePair.asPayloadCodec()
                val frameMetadataBytes = protocolEngine.encodeStreamingRequestFrameMetadata(
                    RelayRequestFrame(
                        method = RelayHttpMethod.POST,
                        clientId = clientId,
                        pairId = runtimePair.pairId,
                        useMke = useMke,
                        route = resolveRoute(ensureRoute(reqProperties.route), reqProperties.pathnamePrefix),
                        headers = resolveHeadersToEncrypt(
                            reqProperties.origHeaders,
                            reqProperties.headersToEncrypt?.toSet() ?: emptySet(),
                        ),
                        body = ByteArray(0),
                    ),
                    codec,
                )

                val origContentLength = parseContentLengthHint(reqProperties.origHeaders) ?: 0
                val totalContentLength = frameMetadataBytes.size.toLong() +
                    origContentLength.toLong() +
                    runtimePair.encryptFinishBytes().toLong()

                // The app writes the body into a pipe on its own thread; the transport thread
                // drains it, encrypting chunk by chunk, so the file is never held in memory.
                val pipedOut = PipedOutputStream()
                val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER_SIZE)
                val callbackExecutor = Executors.newSingleThreadExecutor()
                val bytesWritten = AtomicLong(0)
                val callbackFuture = callbackExecutor.submit<Unit> {
                    try {
                        reqProperties.relayStreamCallback.getRequestBodyStream(pipedOut)
                    } finally {
                        runCatching { pipedOut.close() }
                    }
                }
                callbackExecutor.shutdown()

                val transportResponse = try {
                    transport.executeStreaming(
                        RelayStreamingTransportRequest(
                            origin = origin,
                            route = RELAY_PROXY_ROUTE,
                            contentLength = totalContentLength,
                            headers = mapOf(CONTENT_TYPE_HEADER to CONTENT_TYPE_BINARY),
                            bodyWriter = { sink ->
                                sink.write(frameMetadataBytes)

                                runtimePair.startEncrypt()
                                val buffer = ByteArray(CHUNK_SIZE)
                                var bytesRead: Int
                                while (pipedIn.read(buffer).also { bytesRead = it } != -1) {
                                    operationState.throwIfCancelled(operationId)
                                    runtimePair.encryptChunk(buffer, bytesRead)
                                    sink.write(buffer, 0, bytesRead)
                                    val completed = bytesWritten.addAndGet(bytesRead.toLong())
                                    completionCallback?.onProgressUpdate(
                                        completed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                        origContentLength,
                                    )
                                }
                                val trailingBytes = runtimePair.finishEncrypt()
                                if (trailingBytes.isNotEmpty()) {
                                    sink.write(trailingBytes)
                                }
                                sink.flush()
                            },
                        ),
                    )
                } catch (error: Throwable) {
                    runCatching { callbackFuture.get(CALLBACK_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    throw error
                }

                try {
                    callbackFuture.get(uploadBodyTimeoutMillis, TimeUnit.MILLISECONDS)
                } catch (timeout: TimeoutException) {
                    throw TimeoutException("upload body callback timed out after ${uploadBodyTimeoutMillis}ms")
                }

                if (transportResponse.statusCode in RELAY_REPAIR_STATUS_RANGE) {
                    applyRelayRecovery(transportResponse.statusCode, origin, runtimePair.pairId, session)
                    listener.relayStreamResponse(
                        transportResponse.statusCode,
                        false,
                        null,
                        payloadToMessage(transportResponse.payload, transportResponse.statusCode),
                        toListenerHeaders(transportResponse.headers),
                    )
                    return
                }

                val decodedResponse = protocolEngine.decodeResponseFrame(transportResponse.payload, codec)
                val statusCode = if (decodedResponse.isMteFrame) decodedResponse.statusCode else transportResponse.statusCode
                val responseBody = decodedResponse.body
                val responseHeaders = if (decodedResponse.isMteFrame) decodedResponse.headers else transportResponse.headers
                val listenerHeaders = toListenerHeaders(responseHeaders)

                if (statusCode in SUCCESS_STATUS_RANGE) {
                    listener.relayStreamResponse(
                        statusCode,
                        true,
                        String(responseBody, StandardCharsets.UTF_8),
                        null,
                        listenerHeaders,
                    )
                } else {
                    listener.relayStreamResponse(
                        statusCode,
                        false,
                        null,
                        payloadToMessage(responseBody, statusCode),
                        listenerHeaders,
                    )
                }
            }
        } catch (error: Throwable) {
            // The pair's MKE encrypt state is mid-stream and unusable; heal the pool in the
            // background and fail this request.
            healPair(origin, runtimePairId)
            LogHelper.error("RelayStreamingExecutor", "uploadFile failed [${error::class.java.name}]: ${error.message}", error)
            listener.relayStreamResponse(-1, false, null, error.message ?: "upload failed", null)
        } finally {
            operations.remove(operationId)
        }
    }

    fun downloadFile(
        reqProperties: RelayFileRequestProperties,
        pathnamePrefix: String?,
        listener: RelayStreamResponseListener,
    ) {
        downloadFile(beginOperation(), reqProperties, pathnamePrefix, listener)
    }

    fun downloadFile(
        operationId: String,
        reqProperties: RelayFileRequestProperties,
        pathnamePrefix: String?,
        listener: RelayStreamResponseListener,
    ) {
        val downloadPath = reqProperties.downloadPath
        if (downloadPath.isNullOrBlank()) {
            LogHelper.error("RelayStreamingExecutor", "downloadFile rejected: downloadPath is null or blank")
            listener.relayStreamResponse(
                -1,
                false,
                null,
                "downloadPath must be set on RelayFileRequestProperties to save the downloaded file",
                null,
            )
            operations.remove(operationId)
            return
        }

        val operationState = operations.computeIfAbsent(operationId) { OperationState() }
        val outputFile = File(downloadPath)
        try {
            outputFile.parentFile?.mkdirs()
            val result = FileOutputStream(outputFile).use { fileOut ->
                streamRequest(
                    operationId = operationId,
                    operationState = operationState,
                    serverPath = reqProperties.serverPath,
                    route = reqProperties.route,
                    pathnamePrefix = pathnamePrefix,
                    method = HTTP_GET,
                    body = ByteArray(0),
                    headers = reqProperties.origHeaders,
                    headersToEncrypt = reqProperties.headersToEncrypt,
                    leasePair = false,
                    onOpened = { _, _ -> },
                    onChunk = { chunk -> fileOut.write(chunk) },
                )
            }
            listener.relayStreamResponse(
                result.statusCode,
                true,
                buildDownloadMetadataJson(result.statusCode, outputFile.length(), downloadPath),
                null,
                toListenerHeaders(result.headers),
            )
        } catch (failure: RelayStreamFailure) {
            runCatching { outputFile.delete() }
            listener.relayStreamResponse(
                failure.statusCode,
                false,
                null,
                payloadToMessage(failure.payload, failure.statusCode),
                toListenerHeaders(failure.headers),
            )
        } catch (error: Throwable) {
            runCatching { outputFile.delete() }
            LogHelper.error("RelayStreamingExecutor", "downloadFile failed [${error::class.java.name}]: ${error.message}", error)
            listener.relayStreamResponse(-1, false, null, error.message ?: "download failed", null)
        } finally {
            operations.remove(operationId)
        }
    }

    /**
     * Long-lived event stream. Runs the same core as every other request and delivers each
     * MKE-decrypted chunk to [RelaySseListener.onData] **as raw bytes**.
     *
     * The transport does not parse `text/event-stream`: OkHttp hands a caller raw body bytes, so a
     * caller streaming events already owns a parser, and handing back pre-parsed `data:` values
     * would force them to remove working code — and would silently drop `event:`, `id:`, and
     * `retry:`, which a reconnecting consumer needs. [RelaySseParser] is available as an opt-in
     * helper for callers who want one.
     */
    @JvmOverloads
    fun streamServerSentEvents(
        operationId: String,
        serverPath: String,
        route: String,
        pathnamePrefix: String?,
        headers: Map<String, String>,
        headersToEncrypt: Array<String>?,
        listener: RelaySseListener,
        method: String = HTTP_GET,
        body: ByteArray = ByteArray(0),
    ) {
        val operationState = operations.computeIfAbsent(operationId) { OperationState() }
        try {
            streamRequest(
                operationId = operationId,
                operationState = operationState,
                serverPath = serverPath,
                route = route,
                pathnamePrefix = pathnamePrefix,
                method = method,
                body = body,
                headers = headers,
                headersToEncrypt = headersToEncrypt,
                leasePair = true,
                onOpened = { statusCode, frameHeaders ->
                    listener.onOpened(operationId, statusCode, toListenerHeaders(frameHeaders))
                },
                onChunk = { chunk -> listener.onData(operationId, chunk) },
            )
            operationState.deliverTerminal { listener.onCompleted(operationId) }
        } catch (cancelled: OperationCancelledException) {
            operationState.deliverTerminal { listener.onCancelled(operationId) }
        } catch (failure: RelayStreamFailure) {
            operationState.deliverTerminal {
                listener.onError(
                    operationId,
                    failure.statusCode,
                    payloadToMessage(failure.payload, failure.statusCode),
                    toListenerHeaders(failure.headers),
                )
            }
        } catch (error: Throwable) {
            LogHelper.error("RelayStreamingExecutor", "streamServerSentEvents failed [${error::class.java.name}]: ${error.message}", error)
            operationState.deliverTerminal {
                listener.onError(operationId, -1, error.message ?: "event stream failed", null)
            }
        } finally {
            operations.remove(operationId)
        }
    }

    /**
     * The streaming request core, shared by every entry point above.
     *
     * Sends one V5 binary frame (method + encrypted route/headers + optional encrypted body) and
     * decrypts the response body chunk by chunk as it arrives, handing each chunk to [onChunk]
     * while the connection is still open. [onOpened] fires once the response status and headers
     * are known.
     *
     * A leased pair ([leasePair] = true, long-lived streams) is held for the life of the stream and
     * released on success; on failure it is discarded and replaced, since its MKE stream state is
     * mid-flight. A shared pair (ordinary requests) stays in the round-robin pool and is only
     * replaced when the relay says it is desynced, or when a failure leaves its state dirty.
     *
     * @throws RelayStreamFailure when the relay or upstream server returned a non-success status.
     * @throws OperationCancelledException when the caller cancelled the operation.
     */
    @Suppress("LongParameterList")
    private fun streamRequest(
        operationId: String,
        operationState: OperationState,
        serverPath: String,
        route: String,
        pathnamePrefix: String?,
        method: String,
        body: ByteArray,
        headers: Map<String, String>,
        headersToEncrypt: Array<String>?,
        leasePair: Boolean,
        onOpened: (Int, Map<String, String>) -> Unit,
        onChunk: (ByteArray) -> Unit,
        preventStreaming: Boolean = false,
    ): RelayStreamResult {
        var origin: String? = null
        var runtimePairId: String? = null
        var decryptStarted = false
        try {
            operationState.throwIfCancelled(operationId)
            origin = buildOrigin(serverPath)

            sessionLifecycleManager.ensureReady(origin)
            val session = sessionManager.getOrCreate(origin)
            val runtimePair = (
                if (leasePair) session.leaseRuntimePairRoundRobin() else session.selectRuntimePairRoundRobin()
                ) ?: throw IllegalStateException("no available runtime pairs for $origin")
            runtimePairId = runtimePair.pairId

            synchronized(runtimePair) {
                operationState.throwIfCancelled(operationId)

                val clientId = requireNotNull(session.getClientId()) { "missing clientId for $origin" }
                val codec = runtimePair.asPayloadCodec()
                val frameBytes = protocolEngine.encodeRequestFrame(
                    RelayRequestFrame(
                        method = parseMethod(method),
                        clientId = clientId,
                        pairId = runtimePair.pairId,
                        useMke = useMke,
                        route = resolveRoute(ensureRoute(route), pathnamePrefix),
                        headers = resolveHeadersToEncrypt(headers, headersToEncrypt?.toSet() ?: emptySet()),
                        body = body,
                        preventStreaming = preventStreaming,
                    ),
                    codec,
                )

                val transportResponse = transport.executeDownloadStreaming(
                    RelayTransportRequest(
                        origin = origin,
                        route = RELAY_PROXY_ROUTE,
                        payload = frameBytes,
                        headers = mapOf(CONTENT_TYPE_HEADER to CONTENT_TYPE_BINARY),
                    ),
                ) { httpStatusCode, httpHeaders, bodyStream ->
                    decryptStarted = true
                    decodeStreamedResponse(
                        httpStatusCode = httpStatusCode,
                        httpHeaders = httpHeaders,
                        bodyStream = bodyStream,
                        runtimePair = runtimePair,
                        codec = codec,
                        operationState = operationState,
                        operationId = operationId,
                        onOpened = onOpened,
                        onChunk = onChunk,
                    )
                }

                if (transportResponse.statusCode !in SUCCESS_STATUS_RANGE) {
                    if (transportResponse.statusCode in RELAY_REPAIR_STATUS_RANGE) {
                        applyRelayRecovery(transportResponse.statusCode, origin, runtimePair.pairId, session)
                    }
                    // A leased pair is dedicated to this stream and is never handed back to the
                    // pool mid-flight; drop it (a no-op if recovery already replaced it).
                    if (leasePair) {
                        healPair(origin, runtimePairId)
                    }
                    throw RelayStreamFailure(
                        transportResponse.statusCode,
                        transportResponse.payload,
                        transportResponse.headers,
                    )
                }

                if (leasePair) {
                    session.releaseLeasedRuntimePair(runtimePair.pairId)
                }
                return RelayStreamResult(transportResponse.statusCode, transportResponse.headers)
            }
        } catch (failure: RelayStreamFailure) {
            throw failure
        } catch (error: Throwable) {
            // A failure after the response body opened leaves the pair's MKE decrypt state
            // mid-stream; a leased pair is dedicated to this stream either way. Heal the pool in
            // the background so the next request is not wedged, and fail this one — never resend.
            if (leasePair || decryptStarted) {
                healPair(origin, runtimePairId)
            }
            throw error
        }
    }

    /**
     * Called from inside [RelayTransport.executeDownloadStreaming] while the HTTP connection is
     * still open. Reads the relay response frame header, then streams the remaining MKE-encoded
     * bytes through chunked decrypt, handing each decrypted chunk to [onChunk].
     */
    @Suppress("LongParameterList")
    private fun decodeStreamedResponse(
        httpStatusCode: Int,
        httpHeaders: Map<String, String>,
        bodyStream: InputStream,
        runtimePair: RelayRuntimePair,
        codec: RelayPayloadCodec,
        operationState: OperationState,
        operationId: String,
        onOpened: (Int, Map<String, String>) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ): RelayTransportResponse {
        // Relay repair statuses and HTTP-level errors carry a small plain body, not a relay frame.
        if (httpStatusCode !in SUCCESS_STATUS_RANGE) {
            return RelayTransportResponse(httpStatusCode, bodyStream.readBytes(), httpHeaders)
        }

        val stream = BufferedInputStream(bodyStream)
        stream.mark(RAW_RESPONSE_PEEK_LIMIT)
        val frameHeader = try {
            protocolEngine.decodeResponseFrameHeader(stream, codec)
        } catch (notAFrame: RelayProtocolException) {
            // Not a relay frame (an intermediary answered, or the body is empty). Pass it through
            // untouched rather than failing the request — the app surfaces relay errors verbatim.
            stream.reset()
            val raw = stream.readBytes()
            onOpened(httpStatusCode, httpHeaders)
            if (raw.isNotEmpty()) {
                onChunk(raw)
            }
            return RelayTransportResponse(httpStatusCode, ByteArray(0), httpHeaders)
        }

        // A non-success frame status carries the (encrypted) error body: decode it whole so the
        // caller can surface the reason, rather than streaming it out as content.
        if (frameHeader.statusCode !in SUCCESS_STATUS_RANGE) {
            return RelayTransportResponse(
                statusCode = frameHeader.statusCode,
                payload = decodeRemainingResponseBody(
                    bodyStream = stream,
                    runtimePair = runtimePair,
                    operationState = operationState,
                    operationId = operationId,
                    hasBody = frameHeader.hasBody,
                ),
                headers = frameHeader.headers,
            )
        }

        onOpened(frameHeader.statusCode, frameHeader.headers)
        if (frameHeader.hasBody) {
            runtimePair.startDecrypt()
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                operationState.throwIfCancelled(operationId)
                onChunk(runtimePair.decryptChunk(buffer.copyOf(bytesRead)))
            }
            val trailing = runtimePair.finishDecrypt()
            if (trailing.isNotEmpty()) {
                onChunk(trailing)
            }
        }

        return RelayTransportResponse(frameHeader.statusCode, ByteArray(0), frameHeader.headers)
    }

    private fun decodeRemainingResponseBody(
        bodyStream: InputStream,
        runtimePair: RelayRuntimePair,
        operationState: OperationState,
        operationId: String,
        hasBody: Boolean,
    ): ByteArray {
        if (!hasBody) return ByteArray(0)
        runtimePair.startDecrypt()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(CHUNK_SIZE)
        var bytesRead: Int
        while (bodyStream.read(buffer).also { bytesRead = it } != -1) {
            operationState.throwIfCancelled(operationId)
            output.write(runtimePair.decryptChunk(buffer.copyOf(bytesRead)))
        }
        val trailingBytes = runtimePair.finishDecrypt()
        if (trailingBytes.isNotEmpty()) {
            output.write(trailingBytes)
        }
        return output.toByteArray()
    }

    /**
     * Applies the pool recovery the relay asked for. The current request still fails — it is never
     * transparently resent — but the session is healed so the next one is not wedged.
     */
    private fun applyRelayRecovery(
        statusCode: Int,
        origin: String,
        pairId: String,
        session: RelayOriginSession,
    ) {
        when (statusCode) {
            in RELAY_SINGLE_PAIR_REPAIR_STATUS_RANGE -> {
                healPair(origin, pairId)
            }

            RELAY_FULL_REPAIR_STATUS -> {
                session.transition(RelaySessionState.REPAIRING)
                runCatching { sessionLifecycleManager.repair(origin) }.onFailure { error ->
                    LogHelper.error("RelayStreamingExecutor", "full repair failed for $origin: ${error.message}", error)
                }
            }

            RELAY_BACKOFF_ONLY_STATUS -> {
                Thread.sleep(RELAY_BACKOFF_MILLIS)
            }

            else -> {
                // Surface-only statuses intentionally leave the pool untouched.
            }
        }
    }

    /** Discards a pair whose MTE state is no longer trustworthy and replaces it in the background. */
    private fun healPair(origin: String?, runtimePairId: String?) {
        if (origin.isNullOrBlank() || runtimePairId.isNullOrBlank()) {
            return
        }
        runCatching {
            sessionLifecycleManager.discardAndReplacePair(origin, runtimePairId)
        }.onFailure { error ->
            LogHelper.error(
                "RelayStreamingExecutor",
                "failed to replace runtime pair $runtimePairId for $origin: ${error.message}",
                error,
            )
        }
    }


    private fun parseMethod(method: String): RelayHttpMethod {
        return when (method.uppercase()) {
            "GET" -> RelayHttpMethod.GET
            "POST" -> RelayHttpMethod.POST
            "PUT" -> RelayHttpMethod.PUT
            "PATCH" -> RelayHttpMethod.PATCH
            "DELETE" -> RelayHttpMethod.DELETE
            "HEAD" -> RelayHttpMethod.HEAD
            "OPTIONS" -> RelayHttpMethod.OPTIONS
            "TRACE" -> RelayHttpMethod.TRACE
            "CONNECT" -> RelayHttpMethod.CONNECT
            else -> throw IllegalArgumentException("unsupported HTTP method '$method'")
        }
    }

    private fun resolveHeadersToEncrypt(
        headers: Map<String, String>,
        headersToEncrypt: Set<String>,
    ): Map<String, String> {
        if (headersToEncrypt.isEmpty()) return headers
        val selected = headersToEncrypt.map { it.lowercase() }.toSet()
        return headers.filterKeys { selected.contains(it.lowercase()) }
    }

    private fun resolveRoute(route: String, pathnamePrefix: String?): String {
        if (pathnamePrefix.isNullOrBlank()) return route
        return pathnamePrefix.trimEnd('/') + "/" + route.trimStart('/')
    }

    private fun RelayRuntimePair.asPayloadCodec(): RelayPayloadCodec {
        return object : RelayPayloadCodec {
            override fun encode(payload: ByteArray) = this@asPayloadCodec.encode(payload)
            override fun decode(payload: ByteArray) = this@asPayloadCodec.decode(payload)
        }
    }

    private fun buildOrigin(serverPath: String): String {
        val uri = URI(serverPath)
        val scheme = uri.scheme ?: "https"
        val defaultPort = if (scheme == "https") 443 else 80
        return if (uri.port == -1 || uri.port == defaultPort) {
            "$scheme://${uri.host}"
        } else {
            "$scheme://${uri.host}:${uri.port}"
        }
    }

    private fun ensureRoute(route: String): String {
        if (route.isBlank()) {
            return "/"
        }
        return if (route.startsWith('/')) route else "/$route"
    }

    private fun parseContentLengthHint(headers: Map<String, String>): Int? {
        return headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value
            ?.trim()
            ?.toIntOrNull()
    }

    private fun toListenerHeaders(headers: Map<String, String>): Map<String, List<String>> {
        val mapped = linkedMapOf<String, List<String>>()
        headers.forEach { (key, value) ->
            mapped[key] = listOf(value)
        }
        return mapped
    }

    private fun payloadToMessage(payload: ByteArray, statusCode: Int): String {
        if (payload.isEmpty()) {
            return "Request failed with status $statusCode"
        }
        return String(payload, StandardCharsets.UTF_8)
    }

    private fun buildDownloadMetadataJson(
        statusCode: Int,
        fileSize: Long,
        downloadPath: String,
    ): String {
        return "{\n" +
            "  \"Response\": \"OK\",\n" +
            "  \"Http Response Code\": $statusCode,\n" +
            "  \"File Size\": $fileSize,\n" +
            "  \"Download Location\": \"${escapeJson(downloadPath)}\"\n" +
            "}"
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    /** Status and headers of a response whose body was delivered as chunks. */
    private class RelayStreamResult(
        val statusCode: Int,
        val headers: Map<String, String>,
    )

    /** The relay or upstream server answered, but not with success. Carries the reason. */
    private class RelayStreamFailure(
        val statusCode: Int,
        val payload: ByteArray,
        val headers: Map<String, String>,
    ) : RuntimeException("relay request failed with status $statusCode")

    private class OperationState {
        val cancelled = AtomicBoolean(false)

        fun throwIfCancelled(operationId: String) {
            if (cancelled.get()) {
                throw OperationCancelledException(operationId)
            }
        }

        private val terminalDelivered = AtomicBoolean(false)

        fun deliverTerminal(callback: () -> Unit) {
            if (terminalDelivered.compareAndSet(false, true)) {
                callback()
            }
        }
    }

    private class OperationCancelledException(operationId: String) :
        RuntimeException("streaming operation cancelled: $operationId")

    companion object {
        private const val DEFAULT_UPLOAD_BODY_TIMEOUT_MILLIS = 30_000L
        private const val CALLBACK_DRAIN_TIMEOUT_SECONDS = 5L
        private const val HTTP_GET = "GET"
        private const val RELAY_PROXY_ROUTE = "/"
        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val CONTENT_TYPE_BINARY = "application/octet-stream"
        private val SUCCESS_STATUS_RANGE = 200..299
        private val RELAY_REPAIR_STATUS_RANGE = 559..569
        private val RELAY_SINGLE_PAIR_REPAIR_STATUS_RANGE = 559..563
        private const val RELAY_FULL_REPAIR_STATUS = 564
        private const val RELAY_BACKOFF_ONLY_STATUS = 565
        private const val RELAY_BACKOFF_MILLIS = 75L
        private const val CHUNK_SIZE = 64 * 1024          // 64 KB encrypt/write chunks
        private const val PIPE_BUFFER_SIZE = 256 * 1024   // 256 KB pipe buffer
        private const val RAW_RESPONSE_PEEK_LIMIT = 8 * 1024
    }
}
