package com.eclypses.relay.transport

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream

/**
 * Native OkHttp transport adapter for V5 binary relay payload execution.
 */
class OkHttpRelayTransport(
    // internal rather than private so the module's tests can assert that this client and the
    // control-plane client share one cookie store, which is what keeps a load balancer's
    // affinity cookie consistent across auth, pair, keepalive and frame POSTs.
    internal val httpClient: OkHttpClient,
) : RelayTransport {

    override fun execute(request: RelayTransportRequest): RelayTransportResponse {
        val targetUrl = request.origin.trimEnd('/') + "/" + request.route.trimStart('/')
        val httpRequestBuilder = Request.Builder()
            .url(targetUrl)
            .post(request.payload.toRequestBody(BINARY_MEDIA_TYPE))

        request.headers.forEach { (name, value) ->
            httpRequestBuilder.addHeader(name, value)
        }

        httpClient.newCall(httpRequestBuilder.build()).execute().use { response ->
            val payload = response.body?.bytes() ?: ByteArray(0)
            val headers = linkedMapOf<String, String>()
            response.headers.names().forEach { name ->
                val value = response.header(name)
                if (value != null) headers[name] = value
            }
            return RelayTransportResponse(
                statusCode = response.code,
                payload = payload,
                headers = headers,
            )
        }
    }

    override fun executeDownloadStreaming(
        request: RelayTransportRequest,
        responseBodyHandler: (statusCode: Int, headers: Map<String, String>, bodyStream: InputStream) -> RelayTransportResponse,
    ): RelayTransportResponse {
        val targetUrl = request.origin.trimEnd('/') + "/" + request.route.trimStart('/')
        val httpRequestBuilder = Request.Builder()
            .url(targetUrl)
            .post(request.payload.toRequestBody(BINARY_MEDIA_TYPE))
        request.headers.forEach { (name, value) ->
            httpRequestBuilder.addHeader(name, value)
        }
        httpClient.newCall(httpRequestBuilder.build()).execute().use { response ->
            val headers = linkedMapOf<String, String>()
            response.headers.names().forEach { name ->
                val value = response.header(name)
                if (value != null) headers[name] = value
            }
            val body = response.body
                ?: return RelayTransportResponse(response.code, ByteArray(0), headers)
            // Keep the connection open and pass the live stream to the handler
            return responseBodyHandler(response.code, headers, body.byteStream())
        }
    }

    override fun executeStreaming(request: RelayStreamingTransportRequest): RelayTransportResponse {
        val targetUrl = request.origin.trimEnd('/') + "/" + request.route.trimStart('/')
        val body = object : RequestBody() {
            override fun contentType(): MediaType = BINARY_MEDIA_TYPE
            override fun contentLength(): Long = request.contentLength
            override fun writeTo(sink: BufferedSink) {
                sink.outputStream().use { out -> request.bodyWriter(out) }
            }
        }
        val httpRequestBuilder = Request.Builder()
            .url(targetUrl)
            .post(body)
        request.headers.forEach { (name, value) ->
            httpRequestBuilder.addHeader(name, value)
        }
        httpClient.newCall(httpRequestBuilder.build()).execute().use { response ->
            val payload = response.body?.bytes() ?: ByteArray(0)
            val headers = linkedMapOf<String, String>()
            response.headers.names().forEach { name ->
                val value = response.header(name)
                if (value != null) headers[name] = value
            }
            return RelayTransportResponse(
                statusCode = response.code,
                payload = payload,
                headers = headers,
            )
        }
    }

    override fun executePassThrough(request: RelayPassThroughRequest): RelayTransportResponse {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (name, value) -> builder.addHeader(name, value) }

        // The caller's own Content-Type belongs on this request: nothing is framed, so there
        // is no octet-stream envelope to describe. A verb that does not take a body keeps a
        // null one, so a GET stays a valid GET rather than carrying a zero-length body.
        val body = when {
            request.body != null -> {
                val contentType = request.headers.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.toMediaTypeOrNull()
                request.body.toRequestBody(contentType)
            }
            request.method.uppercase() in METHODS_REQUIRING_BODY ->
                ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(request.method, body)

        httpClient.newCall(builder.build()).execute().use { response ->
            val headers = linkedMapOf<String, String>()
            response.headers.names().forEach { name ->
                response.header(name)?.let { headers[name] = it }
            }
            return RelayTransportResponse(
                statusCode = response.code,
                payload = response.body?.bytes() ?: ByteArray(0),
                headers = headers,
            )
        }
    }

    companion object {
        private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()

        /**
         * Verbs OkHttp requires a request body for. Spelled out rather than taken from
         * `okhttp3.internal.http.HttpMethod`, which is internal API and free to move.
         */
        private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}