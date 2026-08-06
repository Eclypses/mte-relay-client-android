package com.mte.relay.transport

import java.io.InputStream
import java.io.OutputStream

data class RelayTransportRequest(
    val origin: String,
    val route: String,
    val payload: ByteArray,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Streaming upload request. The transport writes [contentLength] bytes total to the sink by
 * invoking [bodyWriter] with an [OutputStream]. The caller is responsible for writing exactly
 * [contentLength] bytes.
 */
data class RelayStreamingTransportRequest(
    val origin: String,
    val route: String,
    val contentLength: Long,
    val headers: Map<String, String> = emptyMap(),
    val bodyWriter: (OutputStream) -> Unit,
)

data class RelayTransportResponse(
    val statusCode: Int,
    val payload: ByteArray,
    val headers: Map<String, String> = emptyMap(),
)

interface RelayTransport {
    fun execute(request: RelayTransportRequest): RelayTransportResponse

    fun executeStreaming(request: RelayStreamingTransportRequest): RelayTransportResponse {
        throw UnsupportedOperationException("executeStreaming is not supported by this transport")
    }

    /**
     * Executes a request and delivers the raw response body [InputStream] to [responseBodyHandler]
     * while the HTTP connection is still open.  The handler must read the stream to completion and
     * return a [RelayTransportResponse] capturing the decoded status and headers.  The transport
     * closes the connection once the handler returns.
     */
    fun executeDownloadStreaming(
        request: RelayTransportRequest,
        responseBodyHandler: (statusCode: Int, headers: Map<String, String>, bodyStream: InputStream) -> RelayTransportResponse,
    ): RelayTransportResponse {
        throw UnsupportedOperationException("executeDownloadStreaming is not supported by this transport")
    }

    companion object {
        /** Convenience factory preserving SAM-style creation: `RelayTransport { req -> ... }` */
        operator fun invoke(block: (RelayTransportRequest) -> RelayTransportResponse): RelayTransport =
            object : RelayTransport {
                override fun execute(request: RelayTransportRequest) = block(request)
            }
    }
}
