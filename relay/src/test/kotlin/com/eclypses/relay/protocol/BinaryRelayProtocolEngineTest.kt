package com.eclypses.relay.protocol

import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class BinaryRelayProtocolEngineTest {

    private val engine = BinaryRelayProtocolEngine()

    @Test
    fun `encodeRequestFrame matches golden fixture`() {
        val frame = RelayRequestFrame(
            method = RelayHttpMethod.POST,
            clientId = "client-token",
            pairId = "pair-123",
            useMke = true,
            route = "/api/data",
            headers = linkedMapOf("X-Test" to "1"),
            body = "abc".toByteArray(),
        )

        val bytes = engine.encodeRequestFrame(frame, IdentityPayloadCodec)

        assertEquals(
            "4d5445003d01010000000c636c69656e742d746f6b656e0008706169722d3132330101001a00086170692f64617461000e7b22582d54657374223a2231227d000100616263",
            bytes.toHex(),
        )
    }

    @Test
    fun `encodeRequestFrame sets the trailing flag byte from preventStreaming`() {
        fun frame(preventStreaming: Boolean) = RelayRequestFrame(
            method = RelayHttpMethod.GET,
            clientId = "c",
            pairId = "p",
            useMke = true,
            route = "/x",
            preventStreaming = preventStreaming,
        )

        // With no body, the frame ends at the trailing flag byte, so it is the last byte emitted.
        val off = engine.encodeRequestFrame(frame(preventStreaming = false), IdentityPayloadCodec)
        val on = engine.encodeRequestFrame(frame(preventStreaming = true), IdentityPayloadCodec)

        assertEquals(0x00.toByte(), off.last(), "preventStreaming=false must leave the flag byte 0")
        assertEquals(0x01.toByte(), on.last(), "preventStreaming=true must set the flag byte to 1")
        // Nothing else changes: the two frames differ in exactly that one trailing byte.
        assertContentEquals(off.copyOf(off.size - 1), on.copyOf(on.size - 1))
    }

    @Test
    fun `encodeRequestFrame encodes metadata before body`() {
        val codec = RecordingCodec()
        val frame = RelayRequestFrame(
            method = RelayHttpMethod.PUT,
            clientId = "client-1",
            pairId = "pair-1",
            useMke = false,
            route = "/v1/items",
            headers = linkedMapOf("Content-Type" to "application/json"),
            body = "{\"id\":1}".toByteArray(),
        )

        engine.encodeRequestFrame(frame, codec)

        assertEquals(2, codec.encodeInputs.size)
        val metadata = codec.encodeInputs[0]
        val body = codec.encodeInputs[1]
        assertTrue(metadata.toHex().startsWith("000876312f6974656d73"))
        assertContentEquals("{\"id\":1}".toByteArray(), body)
    }

    @Test
    fun `decodeResponseFrame parses response and decodes headers before body`() {
        val codec = RecordingCodec()
        val responseHex = "4d54450022010100c90002633100027031010001000f7b22736572766572223a226f6b227d0100646f6e65"

        val response = engine.decodeResponseFrame(responseHex.hexToBytes(), codec)

        assertTrue(response.isMteFrame)
        assertEquals(201, response.statusCode)
        assertEquals("ok", response.headers["server"])
        assertContentEquals("done".toByteArray(), response.body)
        assertEquals(2, codec.decodeInputs.size)
        assertEquals("7b22736572766572223a226f6b227d", codec.decodeInputs[0].toHex())
        assertEquals("646f6e65", codec.decodeInputs[1].toHex())
    }

    @Test
    fun `decodeResponseFrame returns passthrough body for non MTE payload`() {
        val raw = "relay error text".toByteArray()

        val response = engine.decodeResponseFrame(raw, IdentityPayloadCodec)

        assertFalse(response.isMteFrame)
        assertEquals(0, response.statusCode)
        assertTrue(response.headers.isEmpty())
        assertContentEquals(raw, response.body)
    }

    @Test
    fun `decodeResponseFrame parses header values with commas and colons`() {
        val headersJson = "{\"Set-Cookie\":\"session=abc; Expires=Wed, 21 Oct 2015 07:28:00 GMT\",\"WWW-Authenticate\":\"Digest realm=\\\"MTE\\\", qop=auth\"}"
        val response = engine.decodeResponseFrame(
            buildResponseFrame(
                statusCode = 200,
                headersJson = headersJson,
                body = "ok".toByteArray(),
            ),
            IdentityPayloadCodec,
        )

        assertEquals(200, response.statusCode)
        assertEquals("session=abc; Expires=Wed, 21 Oct 2015 07:28:00 GMT", response.headers["Set-Cookie"])
        assertEquals("Digest realm=\"MTE\", qop=auth", response.headers["WWW-Authenticate"])
        assertContentEquals("ok".toByteArray(), response.body)
    }

    @Test
    fun `decodeResponseFrame throws when metadata length is truncated`() {
        val truncated = "4d54450020010100c900026331".hexToBytes()

        assertFailsWith<RelayProtocolException> {
            engine.decodeResponseFrame(truncated, IdentityPayloadCodec)
        }
    }

    // ---- encodeStreamingRequestFrameMetadata tests ----

    @Test
    fun `encodeStreamingRequestFrameMetadata sets bodyFlag TRUE and appends no body bytes`() {
        val frame = RelayRequestFrame(
            method = RelayHttpMethod.POST,
            clientId = "c1",
            pairId = "p1",
            useMke = true,
            route = "/upload",
            headers = mapOf("Content-Type" to "multipart/form-data; boundary=abc"),
            body = ByteArray(0),
        )

        val result = engine.encodeStreamingRequestFrameMetadata(frame, IdentityPayloadCodec)

        // MTE magic prefix
        assertEquals('M'.code.toByte(), result[0])
        assertEquals('T'.code.toByte(), result[1])
        assertEquals('E'.code.toByte(), result[2])

        // ProtoLen covers the entire frame (no body appended)
        val protoLen = ((result[3].toInt() and 0xFF) shl 8) or (result[4].toInt() and 0xFF)
        assertEquals(result.size - 5, protoLen, "ProtoLen should cover entire frame with no trailing body")

        // Last three bytes: headFlag=FALSE, bodyFlag=TRUE, streamFlag=0
        assertEquals(0x00.toByte(), result[result.size - 3], "headFlag (position -3) must be FALSE (0x00)")
        assertEquals(0x01.toByte(), result[result.size - 2], "bodyFlag (position -2) must be TRUE (0x01) for streaming")
        assertEquals(0x00.toByte(), result[result.size - 1], "streamFlag (position -1) must be reserved 0x00")
    }

    @Test
    fun `encodeStreamingRequestFrameMetadata calls codec once for metadata only`() {
        val codec = RecordingCodec()
        val frame = RelayRequestFrame(
            method = RelayHttpMethod.POST,
            clientId = "c2",
            pairId = "p2",
            useMke = true,
            route = "/data",
            headers = mapOf("Content-Length" to "1024"),
            body = ByteArray(0),
        )

        engine.encodeStreamingRequestFrameMetadata(frame, codec)

        assertEquals(1, codec.encodeInputs.size, "codec must be called exactly once (for metadata, never for body)")
    }

    @Test
    fun `encodeStreamingRequestFrameMetadata differs from encodeRequestFrame only in bodyFlag byte`() {
        val frame = RelayRequestFrame(
            method = RelayHttpMethod.POST,
            clientId = "cmp",
            pairId = "pmp",
            useMke = true,
            route = "/compare",
            headers = mapOf("X-Test" to "compare"),
            body = ByteArray(0), // encodeRequestFrame with empty body → bodyFlag=FALSE
        )

        val streamingResult = engine.encodeStreamingRequestFrameMetadata(frame, IdentityPayloadCodec)
        val regularResult = engine.encodeRequestFrame(frame, IdentityPayloadCodec)

        // Both frames should be the same size (no body in either case)
        assertEquals(
            regularResult.size, streamingResult.size,
            "streaming frame and empty-body frame must be the same size",
        )

        // They must differ only at the bodyFlag byte (second-to-last)
        val bodyFlagIndex = streamingResult.size - 2
        for (i in streamingResult.indices) {
            if (i == bodyFlagIndex) {
                assertEquals(0x01.toByte(), streamingResult[i], "streaming bodyFlag must be TRUE (0x01)")
                assertEquals(0x00.toByte(), regularResult[i], "regular empty-body bodyFlag must be FALSE (0x00)")
            } else {
                assertEquals(regularResult[i], streamingResult[i], "frames must be identical at byte $i")
            }
        }
    }

    private class RecordingCodec : RelayPayloadCodec {
        val encodeInputs = mutableListOf<ByteArray>()
        val decodeInputs = mutableListOf<ByteArray>()

        override fun encode(payload: ByteArray): ByteArray {
            encodeInputs += payload.copyOf()
            return payload
        }

        override fun decode(payload: ByteArray): ByteArray {
            decodeInputs += payload.copyOf()
            return payload
        }
    }

    private fun ByteArray.toHex(): String {
        return joinToString(separator = "") { "%02x".format(it) }
    }

    private fun String.hexToBytes(): ByteArray {
        val cleaned = trim()
        require(cleaned.length % 2 == 0)
        return ByteArray(cleaned.length / 2) { index ->
            cleaned.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun buildResponseFrame(
        statusCode: Int,
        headersJson: String,
        body: ByteArray,
    ): ByteArray {
        val metadata = ByteArrayOutputStream()
        metadata.write(0x01) // StrType UTF8
        metadata.write(RelayHttpMethod.POST.wireValue)
        writeU16(metadata, statusCode)
        writeLengthPrefixed(metadata, "c1".toByteArray())
        writeLengthPrefixed(metadata, "p1".toByteArray())
        metadata.write(0x01) // MTE type
        metadata.write(0x00) // Path absent
        metadata.write(0x01) // Headers present
        writeLengthPrefixed(metadata, headersJson.toByteArray())
        metadata.write(if (body.isNotEmpty()) 0x01 else 0x00) // Body flag
        metadata.write(0x00) // Stream flag reserved

        val metadataBytes = metadata.toByteArray()
        val frame = ByteArrayOutputStream()
        frame.write(byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte()))
        writeU16(frame, metadataBytes.size)
        frame.write(metadataBytes)
        frame.write(body)
        return frame.toByteArray()
    }

    private fun writeLengthPrefixed(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeU16(out, bytes.size)
        out.write(bytes)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
