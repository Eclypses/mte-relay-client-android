package com.eclypses.relay.transport

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
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
    private val httpClient: OkHttpClient,
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

    companion object {
        private val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
    }
}