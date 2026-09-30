package com.eclypses.relay.streaming

import com.eclypses.relay.LogHelper
import com.eclypses.relay.RelayComponents
import com.eclypses.relay.RelayFileRequestProperties
import com.eclypses.relay.RelaySseListener
import com.eclypses.relay.RelayStreamCompletionCallback
import com.eclypses.relay.RelayStreamResponseListener
import com.eclypses.relay.RelayWarnings
import com.eclypses.mte.wire.Discovery
import com.eclypses.mte.wire.MteType
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.RelayAction
import com.eclypses.mte.wire.Token
import com.eclypses.relay.protocol.RelayFrameEvent
import com.eclypses.relay.protocol.RelayFrameReader
import com.eclypses.relay.protocol.RelayFrameWriter
import com.eclypses.relay.protocol.RelayFramedErrorException
import com.eclypses.relay.protocol.RelayProtocolException
import com.eclypses.relay.session.OkHttpRelayControlPlaneClient
import com.eclypses.relay.session.RelayOriginSession
import com.eclypses.relay.session.RelayRequestTooLargeException
import com.eclypses.relay.session.forwardsPlainHeader
import com.eclypses.relay.session.isPassThrough
import com.eclypses.relay.session.RelayRuntimePair
import com.eclypses.relay.session.RelaySessionLifecycleManager
import com.eclypses.relay.session.RelaySessionManager
import com.eclypses.relay.session.RelaySessionState
import com.eclypses.relay.transport.RelayPassThroughRequest
import com.eclypses.relay.transport.RelayStreamingTransportRequest
import com.eclypses.relay.transport.RelayTransport
import com.eclypses.relay.transport.RelayTransportRequest
import com.eclypses.relay.transport.RelayTransportResponse
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
 * event stream, a file upload, a file download — is sent as one REQUEST frame followed by DATA
 * frames, and its response body is read frame by frame and decrypted as it arrives.
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
        unencryptedHeaders: Array<String>?,
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
                unencryptedHeaders = unencryptedHeaders,
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
        var runtimePairId: PairId? = null
        // Resolved before any pairing so a malformed list reports itself rather than the
        // pairing, and split once so the encrypted and plaintext halves cannot disagree.
        val uploadPartition = RelayHeaderPolicy.split(
            reqProperties.origHeaders,
            RelayHeaderPolicy.resolve(reqProperties.unencryptedHeaders),
        )
        try {
            operationState.throwIfCancelled(operationId)
            origin = buildOrigin(reqProperties.serverPath)

            sessionLifecycleManager.ensureReady(origin)
            val session = sessionManager.getOrCreate(origin)

            // The cap applies to a streamed upload now, which it did not before.
            //
            // In the previous generation this path deliberately skipped the check: the frame
            // carried a stream flag, the relay piped chunks straight upstream without
            // buffering, and `maxRequestBodyBytes` genuinely did not apply. Frame v2 has no
            // stream flag, so the relay measures the whole body and refuses past the cap --
            // measured against the dev relay, whose cap is 3 MiB: 2 MiB uploads in 2 s and
            // 8 MiB hangs, because the relay stops reading and this client goes on writing
            // into a connection nobody is draining until the body timeout fires.
            //
            // Checking here turns that hang into an immediate refusal naming both numbers.
            // It does NOT make large uploads work: a relay that must accept them needs
            // MAX_REQUEST_BODY_MIB raised, and that is a deployment decision.
            val uploadLimit = session.requireDiscovery().maxRequestBodyBytes
            val declaredLength = parseContentLengthHint(reqProperties.origHeaders)?.toLong()
            if (uploadLimit != null && uploadLimit > 0 &&
                declaredLength != null && declaredLength > uploadLimit
            ) {
                throw RelayRequestTooLargeException(declaredLength, uploadLimit)
            }

            val runtimePair = session.selectRuntimePairRoundRobin()
                ?: throw IllegalStateException("no runtime pairs available for $origin")
            runtimePairId = runtimePair.pairId

            synchronized(runtimePair) {
                operationState.throwIfCancelled(operationId)

                val token = requireNotNull(session.getToken()) { "missing token for $origin" }
                val writer = frameWriter(runtimePair, session)
                val requestFrame = ByteArrayOutputStream().apply {
                    // No END: DATA frames follow. A request body that ends without one is
                    // 477 eof_before_end, which poisons the pair on the relay's side.
                    writer.writeRequest(
                        out = this,
                        method = HTTP_POST,
                        pairId = runtimePair.pairId,
                        token = token,
                        path = resolveRoute(ensureRoute(reqProperties.route), reqProperties.pathnamePrefix),
                        headers = toMultiValue(uploadPartition.encrypted),
                        end = false,
                    )
                }.toByteArray()

                val origContentLength = parseContentLengthHint(reqProperties.origHeaders) ?: 0
                // Chunked. Frame v2 splits the body into DATA frames, each one an encode with
                // its own overhead, so the total is not known before the last one is built --
                // and OkHttp reads a negative length as "chunked" rather than as an error.
                val totalContentLength = -1L

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
                            headers = RelayHeaderPolicy.mergePlaintext(
                                frameHeaders(session),
                                uploadPartition.plaintext,
                            ),
                            bodyWriter = { sink ->
                                sink.write(requestFrame)

                                // One DATA frame per chunk, each a complete encode. The last
                                // one carries END -- and a body that turns out to be empty
                                // still sends one DATA whose plaintext is the flags byte
                                // alone, because a length 0 frame performs no operation and
                                // so binds no END at all.
                                val buffer = ByteArray(writer.maxChunkBytes().coerceAtMost(CHUNK_SIZE))
                                var pending: ByteArray? = null
                                var bytesRead: Int
                                // fillFrom, not read: a pipe hands back whatever is buffered
                                // right now, which is one caller write. Emitting a frame per
                                // write turned a 94 MB upload into ~24000 DATA frames -- one
                                // whole codec operation each -- and it timed out where the
                                // previous generation's incremental encryptChunk did not.
                                while (fillFrom(pipedIn, buffer).also { bytesRead = it } != -1) {
                                    operationState.throwIfCancelled(operationId)
                                    // Held back one chunk so the last one can carry END
                                    // without needing to know the length in advance.
                                    pending?.let { writer.writeData(sink, it, end = false) }
                                    pending = buffer.copyOf(bytesRead)
                                    val completed = bytesWritten.addAndGet(bytesRead.toLong())
                                    completionCallback?.onProgressUpdate(
                                        completed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                        origContentLength,
                                    )
                                }
                                writer.writeData(sink, pending ?: ByteArray(0), end = true)
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

                if (transportResponse.statusCode !in SUCCESS_STATUS_RANGE) {
                    relayErrorOf(transportResponse.statusCode, transportResponse.headers)
                        ?.let { applyRelayRecovery(it, origin, runtimePair.pairId, session) }
                    listener.relayStreamResponse(
                        transportResponse.statusCode,
                        false,
                        null,
                        payloadToMessage(transportResponse.payload, transportResponse.statusCode),
                        toListenerHeaders(transportResponse.headers),
                    )
                    return
                }

                val reader = frameReader(
                    transportResponse.payload.inputStream(), runtimePair, session,
                )
                val head = reader.readResponse()
                val bodyBuffer = ByteArrayOutputStream()
                while (true) {
                    val event = reader.readNext()
                    if (event !is RelayFrameEvent.Data) break
                    bodyBuffer.write(event.bytes)
                    if (event.end) break
                }
                val statusCode = head.status
                val responseBody = bodyBuffer.toByteArray()
                val responseHeaders = flatten(head.headers)
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
        listener: RelayStreamResponseListener,
    ) {
        downloadFile(beginOperation(), reqProperties, listener)
    }

    fun downloadFile(
        operationId: String,
        reqProperties: RelayFileRequestProperties,
        listener: RelayStreamResponseListener,
    ) {
        // The same field uploadFile reads. This used to be a parameter as well, and the
        // parameter won silently.
        val pathnamePrefix = reqProperties.pathnamePrefix
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
                    unencryptedHeaders = reqProperties.unencryptedHeaders,
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
    /**
     * Turns an event stream that died without ever delivering data into a message that names
     * the likely cause.
     *
     * The relay closes a streamed response idle longer than `sseWriteDeadlineSeconds`, and
     * what the caller sees is a bare connection abort that reads like a network fault. If the
     * upstream simply had nothing to say for that long, the fix is a heartbeat, and saying so
     * is the difference between a five-minute diagnosis and an afternoon.
     */
    private fun explainIdleStreamAbort(origin: String?, sawData: Boolean, message: String): String {
        if (sawData || origin == null) return message
        // Not a typed discovery member: it is relay deployment policy rather than protocol,
        // so it stays in the raw document instead of widening the type SocketX also reads.
        val deadline = (
            sessionManager.get(origin)?.getDiscovery()?.raw?.get("sseWriteDeadlineSeconds")
                as? com.eclypses.mte.wire.JsonNumber
            )?.raw?.toIntOrNull()?.takeIf { it > 0 } ?: return message
        return "$message — the relay closes a streamed response idle for more than " +
            "${deadline}s, and no data arrived on this one. If the upstream can be idle that " +
            "long, it must send a heartbeat more often than every ${deadline}s."
    }

    fun streamServerSentEvents(
        operationId: String,
        serverPath: String,
        route: String,
        pathnamePrefix: String?,
        headers: Map<String, String>,
        unencryptedHeaders: Array<String>?,
        listener: RelaySseListener,
        method: String = HTTP_GET,
        body: ByteArray = ByteArray(0),
    ) {
        val operationState = operations.computeIfAbsent(operationId) { OperationState() }
        // A stream that dies having delivered nothing is the shape an idle-deadline abort
        // takes, so whether any data arrived decides how the failure is explained.
        var sawData = false
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
                unencryptedHeaders = unencryptedHeaders,
                leasePair = true,
                onOpened = { statusCode, frameHeaders ->
                    listener.onOpened(operationId, statusCode, toListenerHeaders(frameHeaders))
                },
                onChunk = { chunk ->
                    sawData = true
                    listener.onData(operationId, chunk)
                },
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
                listener.onError(
                    operationId,
                    -1,
                    explainIdleStreamAbort(
                        origin = runCatching { buildOrigin(serverPath) }.getOrNull(),
                        sawData = sawData,
                        message = error.message ?: "event stream failed",
                    ),
                    null,
                )
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
        unencryptedHeaders: Array<String>?,
        leasePair: Boolean,
        onOpened: (Int, Map<String, String>) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ): RelayStreamResult {
        var origin: String? = null
        var runtimePairId: PairId? = null
        var decryptStarted = false
        // The relay's own refusal, as opposed to a status the origin returned.
        //
        // They are different things and only one of them is a failure here. A framed 404
        // is the origin's answer and its body is what the caller asked for; a relay
        // refusal arrives as an HTTP status with an ERROR frame body and never reaches the
        // framing at all. Keying on the framed status would turn every upstream 4xx into a
        // transport failure and throw away the body with it.
        var relayRefusal: com.eclypses.mte.wire.RelayError? = null
        // Resolved before any pairing so a malformed list reports itself rather than the
        // pairing, and split once so the encrypted and plaintext halves cannot disagree.
        val partition = RelayHeaderPolicy.split(headers, RelayHeaderPolicy.resolve(unencryptedHeaders))
        try {
            operationState.throwIfCancelled(operationId)
            origin = buildOrigin(serverPath)

            sessionLifecycleManager.ensureReady(origin)
            val session = sessionManager.getOrCreate(origin)
            val discovery = session.requireDiscovery()

            // A pass-through route is forwarded upstream without a frame, so there is nothing
            // for the relay to decrypt and a framed request to it cannot work. Checked before
            // a pair is leased: reserving one for a request that will not use it would take it
            // out of the pool for nothing. Discovery is only known after ensureReady, which is
            // why this is not the first thing the method does.
            if (discovery.isPassThrough(ensureRoute(route))) {
                return passThroughRequest(
                    operationId = operationId,
                    operationState = operationState,
                    origin = origin,
                    route = ensureRoute(route),
                    method = method,
                    body = body,
                    headers = headers,
                    onOpened = onOpened,
                    onChunk = onChunk,
                )
            }

            warnAboutUnforwardedPlainHeaders(session, discovery, partition.plaintext.keys)

            // A first, cheap filter: a body already over the limit cannot fit in a frame that
            // is larger still, and catching it here avoids encrypting megabytes to no purpose.
            // The exact check is on the built frame below, because that is what the relay
            // measures — verified against a live relay, which 413s a body sized exactly at the
            // limit.
            val bodyLimit = discovery.maxRequestBodyBytes
            if (bodyLimit != null && bodyLimit > 0 && body.size > bodyLimit) {
                throw RelayRequestTooLargeException(body.size.toLong(), bodyLimit)
            }

            val runtimePair = (
                if (leasePair) session.leaseRuntimePairRoundRobin() else session.selectRuntimePairRoundRobin()
                ) ?: throw IllegalStateException("no available runtime pairs for $origin")
            runtimePairId = runtimePair.pairId

            synchronized(runtimePair) {
                operationState.throwIfCancelled(operationId)

                val token = requireNotNull(session.getToken()) { "missing token for $origin" }
                val writer = frameWriter(runtimePair, session)
                val frameBytes = ByteArrayOutputStream().apply {
                    // END on the REQUEST when there is no body at all; otherwise DATA
                    // follows and carries it. The two cases differ on the wire and a
                    // request whose body ends without END is 477 eof_before_end.
                    writer.writeRequest(
                        out = this,
                        method = method,
                        pairId = runtimePair.pairId,
                        token = token,
                        path = resolveRoute(ensureRoute(route), pathnamePrefix),
                        headers = toMultiValue(partition.encrypted),
                        end = body.isEmpty(),
                    )
                    if (body.isNotEmpty()) {
                        // Split at the relay's own frame bound rather than sent whole: a
                        // body over maxFrameBytes is 481 frame_too_large, and the limit is
                        // on the frame, not on the caller's bytes.
                        val chunk = writer.maxChunkBytes()
                        var offset = 0
                        while (offset < body.size) {
                            val next = minOf(offset + chunk, body.size)
                            writer.writeData(this, body.copyOfRange(offset, next), end = next == body.size)
                            offset = next
                        }
                    }
                }.toByteArray()

                // The relay's limit is on what it receives, which is the frame rather than the
                // caller's body. Still before any network call, so nothing is uploaded.
                if (bodyLimit != null && bodyLimit > 0 && frameBytes.size > bodyLimit) {
                    throw RelayRequestTooLargeException(
                        frameBytes.size.toLong(),
                        bodyLimit,
                        RelayRequestTooLargeException.MEASURED_FRAME,
                    )
                }

                val transportResponse = transport.executeDownloadStreaming(
                    RelayTransportRequest(
                        origin = origin,
                        route = RELAY_PROXY_ROUTE,
                        payload = frameBytes,
                        headers = RelayHeaderPolicy.mergePlaintext(
                            frameHeaders(session),
                            partition.plaintext,
                        ),
                    ),
                ) { httpStatusCode, httpHeaders, bodyStream ->
                    decryptStarted = true
                    relayRefusal = relayErrorOf(httpStatusCode, httpHeaders)
                    decodeStreamedResponse(
                        headerFilter = { decoded ->
                            stripAndReportPlainForwarding(session, decoded, partition.plaintext.keys)
                        },
                        httpStatusCode = httpStatusCode,
                        httpHeaders = httpHeaders,
                        bodyStream = bodyStream,
                        runtimePair = runtimePair,
                        session = session,
                        operationState = operationState,
                        operationId = operationId,
                        onOpened = onOpened,
                        onChunk = onChunk,
                    )
                }

                val refusal = relayRefusal
                if (refusal != null) {
                    applyRelayRecovery(refusal, origin, runtimePair.pairId, session)
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
     * still open. Reads the RESPONSE frame, then each DATA frame as it arrives, handing the
     * decrypted application bytes of each to [onChunk].
     *
     * The caller sees the status and headers before the origin has sent a body byte, which is
     * what section 9 requires of the relay and therefore what this has to preserve.
     */
    @Suppress("LongParameterList")
    private fun decodeStreamedResponse(
        httpStatusCode: Int,
        httpHeaders: Map<String, String>,
        bodyStream: InputStream,
        runtimePair: RelayRuntimePair,
        session: RelayOriginSession,
        operationState: OperationState,
        operationId: String,
        onOpened: (Int, Map<String, String>) -> Unit,
        onChunk: (ByteArray) -> Unit,
        /**
         * Applied to the decrypted frame headers before anything else sees them. The relay
         * puts a per-request signal in there that the caller must never receive, and it is
         * the only place that signal exists.
         */
        headerFilter: (Map<String, String>) -> Map<String, String> = { it },
    ): RelayTransportResponse {
        // A relay-generated failure carries an ERROR frame as its whole body, and the same
        // code and reason in the X-MTE-Relay-Error header. Read it here rather than framing:
        // there is no RESPONSE in front of it.
        if (httpStatusCode !in SUCCESS_STATUS_RANGE) {
            return RelayTransportResponse(httpStatusCode, bodyStream.readBytes(), httpHeaders)
        }

        val reader = frameReader(BufferedInputStream(bodyStream), runtimePair, session)
        val head = reader.readResponse()
        val callerHeaders = mergeResponseHeaders(httpHeaders, headerFilter(flatten(head.headers)))

        // A non-success status from the *origin* is still a normal framed response: the body
        // is the origin's error page and the caller wanted it. Only a relay-generated failure
        // takes the branch above.
        onOpened(head.status, callerHeaders)

        while (true) {
            operationState.throwIfCancelled(operationId)
            val event = reader.readNext()
            if (event !is RelayFrameEvent.Data) break
            if (event.bytes.isNotEmpty()) onChunk(event.bytes)
            if (event.end) break
        }

        return RelayTransportResponse(head.status, ByteArray(0), callerHeaders)
    }

    /**
     * Applies the pool recovery the relay asked for. The current request still fails — it is never
     * transparently resent — but the session is healed so the next one is not wedged.
     *
     * Keyed on the registry **reason**, never on the status. Five codes carry reasons whose
     * actions differ and two carry opposite ones: 477 `invalid_frame` leaves the pair intact
     * while 477 `eof_before_end` means the relay poisoned it, and 503
     * `state_store_unavailable` means nothing executed while `state_store_write_failed` means
     * something did. A status range cannot tell those apart, and the previous generation's
     * 559..569 range did not try.
     */
    private fun applyRelayRecovery(
        error: com.eclypses.mte.wire.RelayError,
        origin: String,
        pairId: PairId,
        session: RelayOriginSession,
    ) {
        when (error.action) {
            RelayAction.REPLACE_PAIR, RelayAction.RETRY_ONCE_THEN_REPLACE ->
                healPair(origin, pairId)

            RelayAction.FULL_REPAIR -> {
                session.transition(RelaySessionState.REPAIRING)
                runCatching { sessionLifecycleManager.repair(origin) }.onFailure { failure ->
                    LogHelper.error("RelayStreamingExecutor", "full repair failed for $origin: ${failure.message}", failure)
                }
            }

            RelayAction.BACK_OFF, RelayAction.BACK_OFF_NOT_OWNER ->
                Thread.sleep(RELAY_BACKOFF_MILLIS)

            // SURFACE, SURFACE_SECURITY, RECONNECT, RETRY_SAME and STOP leave the pool
            // untouched: the pair is intact and replacing it would cost a round trip and
            // discard working state for nothing.
            else -> Unit
        }
    }

    /**
     * The relay error behind a failed response, from the `X-MTE-Relay-Error` header.
     *
     * The header is the second carrier of what the ERROR frame body says, and it is the one
     * that survives a proxy replacing the body. Null when the status is not a relay-generated
     * failure at all.
     */
    private fun relayErrorOf(
        statusCode: Int,
        headers: Map<String, String>,
    ): com.eclypses.mte.wire.RelayError? {
        if (statusCode in SUCCESS_STATUS_RANGE) return null
        val raw = headers.entries
            .firstOrNull { it.key.equals(com.eclypses.mte.wire.RelayError.HEADER, ignoreCase = true) }
            ?.value
        return raw?.let { com.eclypses.mte.wire.RelayError.parseHeader(it) }
            // No header: an intermediary answered, or a relay too old to send one. Nothing is
            // known about the pair, so the code alone decides and it decides "surface".
            ?: com.eclypses.mte.wire.RelayError(statusCode, "")
    }

    /** Discards a pair whose MTE state is no longer trustworthy and replaces it in the background. */
    private fun healPair(origin: String?, runtimePairId: PairId?) {
        if (origin.isNullOrBlank() || runtimePairId == null) {
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



    private fun resolveRoute(route: String, pathnamePrefix: String?): String {
        if (pathnamePrefix.isNullOrBlank()) return route
        return pathnamePrefix.trimEnd('/') + "/" + route.trimStart('/')
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

    /**
     * Fills [buffer] from [input], returning the count, or -1 at end of stream.
     *
     * `InputStream.read(byte[])` is allowed to return as soon as one byte is available and
     * a `PipedInputStream` takes that literally: it hands back exactly what the writing
     * thread has put in, which is one caller write. Each partial read here becomes a DATA
     * frame and therefore a full Encode, so a caller writing in 4 KB pieces would produce
     * sixteen frames where one belongs.
     */
    private fun fillFrom(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) return if (offset == 0) -1 else offset
            offset += read
        }
        return offset
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

    /**
     * Sends a request the relay forwards upstream unframed, with no frame and no pair.
     *
     * The caller's headers all travel as ordinary headers — there is no encrypted channel to
     * split them across — and the transport's cookie store applies as it does to framed
     * traffic. The response is delivered through the same [onOpened]/[onChunk] contract as a
     * framed one so every caller of the streaming core handles it identically.
     */
    @Suppress("LongParameterList")
    private fun passThroughRequest(
        operationId: String,
        operationState: OperationState,
        origin: String,
        route: String,
        method: String,
        body: ByteArray,
        headers: Map<String, String>,
        onOpened: (Int, Map<String, String>) -> Unit,
        onChunk: (ByteArray) -> Unit,
    ): RelayStreamResult {
        LogHelper.debug(
            "RelayStreamingExecutor",
            "Route $route is a pass-through route on $origin; sending it unframed.",
        )
        operationState.throwIfCancelled(operationId)
        val response = transport.executePassThrough(
            RelayPassThroughRequest(
                url = origin.trimEnd('/') + route,
                method = method,
                headers = headers,
                body = body.takeIf { it.isNotEmpty() },
            ),
        )
        operationState.throwIfCancelled(operationId)
        onOpened(response.statusCode, response.headers)
        if (response.payload.isNotEmpty()) {
            onChunk(response.payload)
        }
        return RelayStreamResult(
            statusCode = response.statusCode,
            headers = response.headers,
        )
    }

    /**
     * Warns once per name that a header the caller asked to send in the clear will not reach
     * the origin, because the relay's own allowlist does not cover it.
     *
     * This is the failure the denylist cannot prevent: the header is exposed on the hop and
     * then dropped, so the caller pays the privacy cost and gets none of the benefit. Silent
     * otherwise — the request still succeeds, and the operator, not the caller, decides.
     */
    private fun warnAboutUnforwardedPlainHeaders(
        session: RelayOriginSession,
        discovery: Discovery,
        plainHeaderNames: Set<String>,
    ) {
        if (!RelayWarnings.enabled || plainHeaderNames.isEmpty()) return
        for (name in plainHeaderNames) {
            // Null means the relay states no list at all; saying nothing is not a promise that
            // nothing is forwarded, so there is nothing to warn about.
            if (discovery.forwardsPlainHeader(name) != false) continue
            if (!session.shouldWarn("plain-not-forwarded:${name.lowercase()}")) continue
            LogHelper.warn(
                "RelayStreamingExecutor",
                "\"$name\" is sent unencrypted to ${session.origin} but the relay does not " +
                    "forward it to the origin, so it is exposed on the hop and never arrives. " +
                    "Ask whoever operates the relay to allow it, or stop sending it in the clear.",
            )
        }
    }

    /**
     * Removes the relay's per-request forwarding signal from the decrypted headers, and warns
     * once per name for any header sent in the clear that the relay did not pass on.
     *
     * The signal exists only here, so reading it has to happen before the headers reach the
     * caller — and the caller must never see it, since it is the relay talking about itself
     * rather than anything the origin sent.
     */
    private fun stripAndReportPlainForwarding(
        session: RelayOriginSession,
        decoded: Map<String, String>,
        plainHeaderNames: Set<String>,
    ): Map<String, String> {
        val signalEntry = decoded.entries
            .firstOrNull { it.key.equals(PLAIN_FORWARDED_HEADER, ignoreCase = true) }
            ?: return decoded

        if (RelayWarnings.enabled && plainHeaderNames.isNotEmpty()) {
            val value = signalEntry.value.trim()
            val forwardedAll = value == "*"
            val forwarded = value.split(",").map { it.trim().lowercase() }.toSet()
            if (!forwardedAll) {
                for (name in plainHeaderNames) {
                    val lowered = name.lowercase()
                    if (lowered in forwarded) continue
                    if (!session.shouldWarn("plain-not-forwarded:$lowered")) continue
                    LogHelper.warn(
                        "RelayStreamingExecutor",
                        "The relay did not forward \"$name\" to the origin; it was exposed on " +
                            "the hop and dropped.",
                    )
                }
            }
        }

        return decoded.filterKeys { !it.equals(PLAIN_FORWARDED_HEADER, ignoreCase = true) }
    }

    /**
     * What the caller sees: the hop's own response headers with the decrypted frame's layered
     * on top.
     *
     * The frame carries what the origin sent, so it wins any collision. The hop is included at
     * all because a v5 relay deliberately moves some of the origin's response out of the frame
     * onto the real response — `Set-Cookie` under `forward_browser_cookies` is the case that
     * matters — and a caller who would have seen those headers without the relay should still
     * see them. Building the response from the frame alone silently dropped them.
     *
     * Two groups are excluded from the hop's half:
     *
     *  - `x-mte-relay-*`, which is the relay talking to this client rather than anything the
     *    origin sent. `X-MTE-Relay-Route` is echoed on every response, so without this the
     *    routing token would surface to callers.
     *  - `Content-*`, which describes the frame: the type is always `application/octet-stream`
     *    and the length is the encrypted frame's. The real ones are inside the frame, and
     *    letting the hop's through would misdescribe the caller's own body whenever the origin
     *    sent none.
     */
    private fun mergeResponseHeaders(
        transport: Map<String, String>,
        frame: Map<String, String>,
    ): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        for ((name, value) in transport) {
            val lowered = name.lowercase()
            if (lowered.startsWith(RELAY_INTERNAL_HEADER_PREFIX)) continue
            if (lowered.startsWith("content-")) continue
            merged[name] = value
        }
        merged.putAll(frame)
        return merged
    }

    /**
     * Headers the frame POST itself carries, as opposed to the caller headers that travel
     * inside the frame.
     *
     * `Content-Type` must be `application/octet-stream` or the relay does not parse the
     * frame, and `Accept-Encoding: identity` because the relay reads the body itself rather
     * than a decompressed view of it.
     *
     * The route header is the **client id**, one value for the client's life. The previous
     * generation sent the pair id here, a different value per request, which scattered one
     * client's pairs across replicas -- the opposite of what a consistent hash in front of a
     * multi-replica relay is for, and unworkable under owner mode where the pair state lives
     * on one pod. The pair id no longer appears on the plain hop at all; it is inside the
     * encrypted REQUEST frame.
     */
    private fun frameHeaders(session: RelayOriginSession): Map<String, String> {
        val headers = linkedMapOf(
            CONTENT_TYPE_HEADER to CONTENT_TYPE_BINARY,
            // Section 9: the relay parses the frame only under octet-stream, and it reads
            // the body itself rather than a decompressed view of it.
            ACCEPT_ENCODING_HEADER to IDENTITY,
        )
        session.getToken()?.let {
            headers[com.eclypses.mte.wire.Discovery.ROUTE_HEADER] = it.clientIdHex
        }
        return headers
    }

    /** One writer per request: every frame it writes advances the pair one codec operation. */
    private fun frameWriter(pair: RelayRuntimePair, session: RelayOriginSession) =
        RelayFrameWriter(
            encode = { payload -> encodeOrDiscard(pair, session, payload) },
            mteType = if (useMke) MteType.MKE else MteType.MTE,
            maxFrameBytes = maxFrameBytes(session),
        )

    /**
     * One encoder operation, discarding the pair if the codec refuses it.
     *
     * An encoder that fails will fail the same way next time. State is saved only on success,
     * so the pair keeps the state it had and the next request that selects it repeats the
     * failure -- a poisoned pair left in the pool to be handed out again.
     *
     * Nothing else in the client is watching for this. Pair replacement keys on relay status
     * codes, and those only arrive once a request reaches the relay; a local codec failure
     * happens before a byte is sent.
     *
     * `mte_status_drbg_seedlife_reached` is the case that prompted this and the least likely
     * one to occur: the interval is 2^48 operations per codec, and against a pair that cannot
     * outlive `maxPairSeconds` that is about 2.9 billion operations a second on one pair. What
     * this actually catches is the ordinary kind -- a saved state that did not restore, a
     * state corrupted in storage -- which it handles by not asking why.
     *
     * Encode only. A decode failure can mean the payload was wrong rather than the pair, and
     * the relay-driven recovery already decides what a refusal implies for the pair.
     */
    private fun encodeOrDiscard(
        pair: RelayRuntimePair,
        session: RelayOriginSession,
        payload: ByteArray,
    ): ByteArray {
        try {
            return pair.encode(payload)
        } catch (failure: Throwable) {
            LogHelper.error(
                "RelayStreamingExecutor",
                "Discarding pair ${pair.pairId.hex} for ${session.origin}: " +
                    "the encoder refused an operation: ${failure.message}",
                failure,
            )
            // Removes it from the pool, owes the relay a drop for it, and pairs a replacement.
            healPair(session.origin, pair.pairId)
            throw failure
        }
    }

    private fun frameReader(
        stream: InputStream,
        pair: RelayRuntimePair,
        session: RelayOriginSession,
    ) = RelayFrameReader(
        stream = stream,
        decode = pair::decode,
        maxFrameBytes = maxFrameBytes(session),
    )

    private fun maxFrameBytes(session: RelayOriginSession): Int =
        session.requireDiscovery().maxFrameBytes
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Header maps cross this layer single-valued; the wire form allows repeats. */
    private fun toMultiValue(headers: Map<String, String>): Map<String, List<String>> =
        headers.mapValues { listOf(it.value) }

    /**
     * Flattens repeated header values for the callback surfaces, which are single-valued.
     *
     * Joined with a comma rather than dropped: that is the RFC 9110 equivalence for every
     * header except `set-cookie`, which the relay moves onto the real HTTP response and
     * OkHttp's cookie jar takes from there, so it does not reach here.
     */
    private fun flatten(headers: Map<String, List<String>>): Map<String, String> =
        headers.mapValues { it.value.joinToString(", ") }

    companion object {
        private const val DEFAULT_UPLOAD_BODY_TIMEOUT_MILLIS = 30_000L
        private const val CALLBACK_DRAIN_TIMEOUT_SECONDS = 5L
        private const val HTTP_GET = "GET"
        private const val RELAY_PROXY_ROUTE = "/"
        private const val CONTENT_TYPE_HEADER = "Content-Type"

        /**
         * The relay adds this to the encrypted response headers naming what it merged from the
         * plain hop. It is the relay describing itself, never something the origin sent, so it
         * is stripped before the caller sees the response.
         */
        private const val PLAIN_FORWARDED_HEADER = "x-mte-plain-forwarded"

        /** Headers the relay addresses to this client, never anything the origin sent. */
        private const val RELAY_INTERNAL_HEADER_PREFIX = "x-mte-relay-"
        private const val CONTENT_TYPE_BINARY = "application/octet-stream"
        private const val ACCEPT_ENCODING_HEADER = "Accept-Encoding"
        private const val IDENTITY = "identity"
        private const val HTTP_POST = "POST"
        private val SUCCESS_STATUS_RANGE = 200..299
        private const val RELAY_BACKOFF_MILLIS = 75L
        private const val CHUNK_SIZE = 64 * 1024          // 64 KB encrypt/write chunks
        private const val PIPE_BUFFER_SIZE = 256 * 1024   // 256 KB pipe buffer
        private const val RAW_RESPONSE_PEEK_LIMIT = 8 * 1024
    }
}
