package com.eclypses.relay.streaming

import java.nio.charset.StandardCharsets
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Translates between OkHttp's request/response model and the relay streaming core, so wrapper-mode
 * calls ([com.eclypses.relay.Relay.send]) and interceptor-mode calls
 * ([com.eclypses.relay.interceptor.RelayMteInterceptor]) map an `okhttp3.Request` onto the relay frame
 * exactly the same way.
 */
object RelayOkHttpAdapter {

    /**
     * Sends [request] through the relay and folds the streamed response into one buffered
     * `okhttp3.Response`. Blocks until the response completes.
     */
    @JvmStatic
    @JvmOverloads
    fun execute(
        executor: RelayStreamingExecutor,
        request: Request,
        headersToEncrypt: Array<String>?,
        pathnamePrefix: String?,
        preventStreaming: Boolean = false,
    ): Response {
        val relayResponse = executor.executeBuffered(
            serverPath = request.url.toString(),
            route = route(request),
            pathnamePrefix = pathnamePrefix,
            method = request.method,
            body = body(request),
            headers = headers(request),
            headersToEncrypt = headersToEncrypt,
            preventStreaming = preventStreaming,
        )
        return toOkHttpResponse(request, relayResponse)
    }

    /** The relay route: the request's path plus its query string, if any. */
    @JvmStatic
    fun route(request: Request): String {
        val query = request.url.encodedQuery
        return if (query.isNullOrBlank()) {
            request.url.encodedPath
        } else {
            request.url.encodedPath + "?" + query
        }
    }

    @JvmStatic
    fun headers(request: Request): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        request.headers.names().forEach { name ->
            request.header(name)?.let { value -> headers[name] = value }
        }
        return headers
    }

    @JvmStatic
    fun body(request: Request): ByteArray {
        val requestBody = request.body ?: return ByteArray(0)
        val buffer = Buffer()
        requestBody.writeTo(buffer)
        return buffer.readByteArray()
    }

    /** Builds an error response carrying [errorMessage] for a request that never reached the relay. */
    @JvmStatic

    private fun toOkHttpResponse(request: Request, relayResponse: RelayBufferedResponse): Response {
        val headersBuilder = okhttp3.Headers.Builder()
        relayResponse.headers.forEach { (name, value) ->
            headersBuilder.add(name, value)
        }
        val contentType = relayResponse.headers.entries
            .firstOrNull { it.key.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()
            ?: DEFAULT_RESPONSE_MEDIA_TYPE.toMediaType()

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(relayResponse.statusCode.coerceIn(MIN_STATUS_CODE, MAX_STATUS_CODE))
            .message("")
            .headers(headersBuilder.build())
            .body(relayResponse.payload.toResponseBody(contentType))
            .build()
    }

    private const val CONTENT_TYPE_HEADER = "Content-Type"
    private const val DEFAULT_RESPONSE_MEDIA_TYPE = "application/octet-stream"
    private const val ERROR_MEDIA_TYPE = "application/json; charset=utf-8"
    private const val MIN_STATUS_CODE = 100
    private const val MAX_STATUS_CODE = 599
}
