package com.mte.relay.transport

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class OkHttpRelayTransportTest {

    @Test
    fun `execute sends binary post and maps response`() {
        var capturedUrl = ""
        var capturedMethod = ""
        var capturedContentType = ""
        var capturedHeader = ""
        var capturedBody = ByteArray(0)

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    capturedUrl = request.url.toString()
                    capturedMethod = request.method
                    capturedContentType = request.body?.contentType()?.toString() ?: ""
                    capturedHeader = request.header("x-relay") ?: ""
                    val buffer = Buffer()
                    request.body?.writeTo(buffer)
                    capturedBody = buffer.readByteArray()

                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("partial")
                        .addHeader("x-result", "ok")
                        .body("relay-response".toByteArray().toResponseBody())
                        .build()
                },
            )
            .build()

        val transport = OkHttpRelayTransport(client)
        val requestPayload = "relay-request".toByteArray()
        val response = transport.execute(
            RelayTransportRequest(
                origin = "https://relay.example/",
                route = "/",
                payload = requestPayload,
                headers = mapOf("x-relay" to "relay"),
            ),
        )

        assertEquals("https://relay.example/", capturedUrl)
        assertEquals("POST", capturedMethod)
        assertEquals("application/octet-stream", capturedContentType)
        assertEquals("relay", capturedHeader)
        assertContentEquals(requestPayload, capturedBody)

        assertEquals(206, response.statusCode)
        assertEquals("ok", response.headers["x-result"])
        assertContentEquals("relay-response".toByteArray(), response.payload)
    }

    @Test
    fun `executeStreaming sends streaming post with exact contentLength and invokes bodyWriter`() {
        var capturedContentLength = -1L
        var capturedContentType = ""
        var capturedHeader = ""
        var capturedBody = ByteArray(0)

        val expectedBody = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    capturedContentLength = request.body?.contentLength() ?: -1L
                    capturedContentType = request.body?.contentType()?.toString() ?: ""
                    capturedHeader = request.header("x-relay") ?: ""
                    val buffer = Buffer()
                    request.body?.writeTo(buffer)
                    capturedBody = buffer.readByteArray()

                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(201)
                        .message("created")
                        .addHeader("x-relay-result", "streaming-ok")
                        .body("streamed-response".toByteArray().toResponseBody())
                        .build()
                },
            )
            .build()

        val transport = OkHttpRelayTransport(client)
        val response = transport.executeStreaming(
            RelayStreamingTransportRequest(
                origin = "https://relay.example/",
                route = "/",
                contentLength = expectedBody.size.toLong(),
                headers = mapOf("x-relay" to "relay-stream"),
                bodyWriter = { out -> out.write(expectedBody) },
            ),
        )

        assertEquals(expectedBody.size.toLong(), capturedContentLength)
        assertEquals("application/octet-stream", capturedContentType)
        assertEquals("relay-stream", capturedHeader)
        assertContentEquals(expectedBody, capturedBody)

        assertEquals(201, response.statusCode)
        assertEquals("streaming-ok", response.headers["x-relay-result"])
        assertContentEquals("streamed-response".toByteArray(), response.payload)
    }
}
