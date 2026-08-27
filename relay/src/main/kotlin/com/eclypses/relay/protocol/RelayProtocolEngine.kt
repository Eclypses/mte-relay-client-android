package com.eclypses.relay.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal frame contract for the V5 binary protocol engine.
 */
data class RelayFrame(
    val type: Int,
    val payload: ByteArray,
)

enum class RelayHttpMethod(val wireValue: Int) {
    GET(0),
    POST(1),
    PUT(2),
    PATCH(3),
    DELETE(4),
    HEAD(5),
    OPTIONS(6),
    TRACE(7),
    CONNECT(8),
}

data class RelayRequestFrame(
    val method: RelayHttpMethod,
    val clientId: String,
    val pairId: String,
    val useMke: Boolean,
    val route: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    /**
     * Asks the relay server to disable its normal HTTP streaming handling of the *upstream* request
     * and redirect it for non-standard processing. This is a directive about how the relay talks to
     * the origin server — unrelated to how this client reads the response, which is always streamed.
     *
     * Carried in the trailing flag byte of the frame's metadata section. Implicitly `false` for
     * streaming uploads and downloads.
     */
    val preventStreaming: Boolean = false,
)

data class RelayResponseFrame(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val isMteFrame: Boolean,
)

/**
 * Parsed V5 response frame header, returned by [RelayProtocolEngine.decodeResponseFrameHeader].
 * After this call returns the [InputStream] is positioned at the start of the (still-encoded) body.
 */
data class RelayResponseFrameHeader(
    val statusCode: Int,
    val headers: Map<String, String>,
    val hasBody: Boolean,
)

fun interface RelayPayloadCodec {
    fun encode(payload: ByteArray): ByteArray

    fun decode(payload: ByteArray): ByteArray {
        return payload
    }
}

object IdentityPayloadCodec : RelayPayloadCodec {
    override fun encode(payload: ByteArray): ByteArray {
        return payload
    }

    override fun decode(payload: ByteArray): ByteArray {
        return payload
    }
}

class RelayProtocolException(message: String) : IllegalArgumentException(message)

interface RelayProtocolEngine {
    fun encode(frame: RelayFrame): ByteArray
    fun decode(bytes: ByteArray): RelayFrame

    fun encodeRequestFrame(
        request: RelayRequestFrame,
        codec: RelayPayloadCodec,
    ): ByteArray {
        throw UnsupportedOperationException("encodeRequestFrame not implemented")
    }

    fun decodeResponseFrame(
        responseBytes: ByteArray,
        codec: RelayPayloadCodec,
    ): RelayResponseFrame {
        throw UnsupportedOperationException("decodeResponseFrame not implemented")
    }

    /**
     * Builds the V5 binary frame header for a streaming upload.  The frame declares bodyFlag=TRUE
     * so the server knows encrypted body bytes will follow, but the body is NOT appended here —
     * the caller streams the encrypted body bytes immediately after writing this header.
     */
    fun encodeStreamingRequestFrameMetadata(
        request: RelayRequestFrame,
        codec: RelayPayloadCodec,
    ): ByteArray {
        throw UnsupportedOperationException("encodeStreamingRequestFrameMetadata not implemented")
    }

    /**
     * Reads the V5 binary response frame header from [stream] and returns the parsed metadata.
     * The method reads exactly the header portion (5 bytes of prefix + variable-length metadata)
     * and leaves the stream positioned at the first byte of the (still-encoded) body.
     * The caller is responsible for streaming and decoding the body bytes.
     */
    fun decodeResponseFrameHeader(
        stream: InputStream,
        codec: RelayPayloadCodec,
    ): RelayResponseFrameHeader {
        throw UnsupportedOperationException("decodeResponseFrameHeader not implemented")
    }
}

class BinaryRelayProtocolEngine : RelayProtocolEngine {

    override fun encode(frame: RelayFrame): ByteArray {
        return frame.payload
    }

    override fun decode(bytes: ByteArray): RelayFrame {
        return RelayFrame(type = 1, payload = bytes)
    }

    override fun encodeRequestFrame(
        request: RelayRequestFrame,
        codec: RelayPayloadCodec,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(0)
        out.write(0)

        out.write(STR_TYPE_UTF8)
        out.write(request.method.wireValue)
        writeU16(out, 0)
        writeLengthPrefixed(out, request.clientId.toByteArray(StandardCharsets.UTF_8))
        writeLengthPrefixed(out, request.pairId.toByteArray(StandardCharsets.UTF_8))
        out.write(if (request.useMke) MTE_TYPE_MKE else MTE_TYPE_MTE)

        out.write(FLAG_TRUE)
        val metadataRaw = buildRequestMetadata(request.route, request.headers)
        val encodedMetadata = codec.encode(metadataRaw)
        writeLengthPrefixed(out, encodedMetadata)

        out.write(FLAG_FALSE)
        val hasBody = request.body.isNotEmpty()
        out.write(if (hasBody) FLAG_TRUE else FLAG_FALSE)
        out.write(if (request.preventStreaming) FLAG_TRUE else FLAG_FALSE)

        val frameBytes = out.toByteArray()
        val protoLen = frameBytes.size - HEADER_SIZE
        writeU16At(frameBytes, PROTO_LEN_OFFSET, protoLen)

        if (!hasBody) {
            return frameBytes
        }

        val encodedBody = codec.encode(request.body)
        return frameBytes + encodedBody
    }

    /**
     * Builds the V5 binary frame header with bodyFlag=TRUE but without appending the body.
     * The caller is responsible for streaming the MKE-encrypted body bytes immediately after.
     */
    override fun encodeStreamingRequestFrameMetadata(
        request: RelayRequestFrame,
        codec: RelayPayloadCodec,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(0)
        out.write(0)

        out.write(STR_TYPE_UTF8)
        out.write(request.method.wireValue)
        writeU16(out, 0)
        writeLengthPrefixed(out, request.clientId.toByteArray(StandardCharsets.UTF_8))
        writeLengthPrefixed(out, request.pairId.toByteArray(StandardCharsets.UTF_8))
        out.write(if (request.useMke) MTE_TYPE_MKE else MTE_TYPE_MTE)

        out.write(FLAG_TRUE)
        val metadataRaw = buildRequestMetadata(request.route, request.headers)
        val encodedMetadata = codec.encode(metadataRaw)
        writeLengthPrefixed(out, encodedMetadata)

        out.write(FLAG_FALSE)
        out.write(FLAG_TRUE)           // bodyFlag = TRUE: body follows (as streaming MKE bytes)
        out.write(if (request.preventStreaming) FLAG_TRUE else FLAG_FALSE)

        val frameBytes = out.toByteArray()
        val protoLen = frameBytes.size - HEADER_SIZE
        writeU16At(frameBytes, PROTO_LEN_OFFSET, protoLen)
        return frameBytes
    }

    override fun decodeResponseFrame(
        responseBytes: ByteArray,
        codec: RelayPayloadCodec,
    ): RelayResponseFrame {
        if (!hasMagic(responseBytes)) {
            return RelayResponseFrame(
                statusCode = 0,
                headers = emptyMap(),
                body = responseBytes,
                isMteFrame = false,
            )
        }

        ensureSize(responseBytes, HEADER_SIZE, "response too short for frame header")
        val protoLen = readU16(responseBytes, PROTO_LEN_OFFSET)
        val metadataEnd = HEADER_SIZE + protoLen
        ensureSize(responseBytes, metadataEnd, "response metadata truncated")

        var cursor = HEADER_SIZE
        cursor += 1 // StrType
        cursor += 1 // Method
        val statusCode = readU16(responseBytes, cursor)
        cursor += 2

        cursor += readLengthPrefixed(responseBytes, cursor).totalSize
        cursor += readLengthPrefixed(responseBytes, cursor).totalSize
        cursor += 1 // MTE Type

        val pathFlag = readByte(responseBytes, cursor)
        cursor += 1
        if (pathFlag == FLAG_TRUE) {
            cursor += readLengthPrefixed(responseBytes, cursor).totalSize
        }

        val headFlag = readByte(responseBytes, cursor)
        cursor += 1
        val headers = if (headFlag == FLAG_TRUE) {
            val encodedHeaders = readLengthPrefixed(responseBytes, cursor)
            cursor += encodedHeaders.totalSize
            decodeHeaders(codec.decode(encodedHeaders.value))
        } else {
            emptyMap()
        }

        val bodyFlag = readByte(responseBytes, cursor)
        cursor += 1
        cursor += 1 // Stream flag

        if (cursor != metadataEnd) {
            throw RelayProtocolException("metadata parse ended at $cursor but expected $metadataEnd")
        }

        val bodyPayload = responseBytes.copyOfRange(metadataEnd, responseBytes.size)
        val decodedBody = if (bodyFlag == FLAG_TRUE && bodyPayload.isNotEmpty()) {
            codec.decode(bodyPayload)
        } else {
            ByteArray(0)
        }

        return RelayResponseFrame(
            statusCode = statusCode,
            headers = headers,
            body = decodedBody,
            isMteFrame = true,
        )
    }

    override fun decodeResponseFrameHeader(
        stream: InputStream,
        codec: RelayPayloadCodec,
    ): RelayResponseFrameHeader {
        // Read MAGIC (3 bytes) + protoLen (2 bytes)
        val prefix = readExactlyFromStream(stream, HEADER_SIZE)
        if (!hasMagic(prefix)) {
            throw RelayProtocolException("not a V5 response frame (missing MAGIC)")
        }
        val protoLen = readU16(prefix, PROTO_LEN_OFFSET)

        // Read the full metadata section
        val metadata = readExactlyFromStream(stream, protoLen)

        var cursor = 0
        cursor += 1 // StrType
        cursor += 1 // Method
        val statusCode = readU16(metadata, cursor)
        cursor += 2

        // Skip clientId and pairId (length-prefixed strings)
        cursor += readLengthPrefixed(metadata, cursor).totalSize
        cursor += readLengthPrefixed(metadata, cursor).totalSize
        cursor += 1 // MTE Type

        val pathFlag = readByte(metadata, cursor)
        cursor += 1
        if (pathFlag == FLAG_TRUE) {
            cursor += readLengthPrefixed(metadata, cursor).totalSize
        }

        val headFlag = readByte(metadata, cursor)
        cursor += 1
        val headers = if (headFlag == FLAG_TRUE) {
            val encodedHeaders = readLengthPrefixed(metadata, cursor)
            cursor += encodedHeaders.totalSize
            decodeHeaders(codec.decode(encodedHeaders.value))
        } else {
            emptyMap()
        }

        val bodyFlag = readByte(metadata, cursor)

        return RelayResponseFrameHeader(
            statusCode = statusCode,
            headers = headers,
            hasBody = bodyFlag == FLAG_TRUE,
        )
    }

    private fun readExactlyFromStream(stream: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = stream.read(buf, offset, n - offset)
            if (read == -1) {
                throw RelayProtocolException("stream truncated: read $offset of $n expected bytes")
            }
            offset += read
        }
        return buf
    }

    private fun buildRequestMetadata(
        route: String,
        headers: Map<String, String>,
    ): ByteArray {
        val path = route.trimStart('/')
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        val headersBytes = encodeHeaders(headers)
        val out = ByteArrayOutputStream()
        writeU16(out, pathBytes.size)
        out.write(pathBytes)
        writeU16(out, headersBytes.size)
        out.write(headersBytes)
        return out.toByteArray()
    }

    private fun encodeHeaders(headers: Map<String, String>): ByteArray {
        if (headers.isEmpty()) {
            return ByteArray(0)
        }
        val json = headers.entries
            .sortedBy { it.key }
            .joinToString(
                separator = ",",
                prefix = "{",
                postfix = "}",
            ) { (key, value) ->
                "\"${escapeJsonString(key)}\":\"${escapeJsonString(value)}\""
            }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeHeaders(bytes: ByteArray): Map<String, String> {
        if (bytes.isEmpty()) {
            return emptyMap()
        }
        val text = String(bytes, StandardCharsets.UTF_8).trim()
        if (text == "{}") {
            return emptyMap()
        }
        if (!text.startsWith('{') || !text.endsWith('}')) {
            throw RelayProtocolException("invalid headers JSON payload")
        }
        val body = text.substring(1, text.length - 1)
        if (body.isBlank()) {
            return emptyMap()
        }

        val result = linkedMapOf<String, String>()
        val entries = splitOutsideQuotedStrings(body, ',')
        for (entry in entries) {
            if (entry.isBlank()) {
                continue
            }
            val splitIndex = findDelimiterOutsideQuotedStrings(entry, ':')
            if (splitIndex <= 0) {
                throw RelayProtocolException("invalid header entry in JSON payload")
            }
            val rawKey = entry.substring(0, splitIndex).trim()
            val rawValue = entry.substring(splitIndex + 1).trim()
            val key = parseQuotedJsonString(rawKey)
            val value = parseQuotedJsonString(rawValue)
            result[key] = value
        }
        return result
    }

    private fun escapeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun splitOutsideQuotedStrings(value: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var inQuotes = false
        var escaped = false
        for (index in value.indices) {
            val ch = value[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (ch == delimiter && !inQuotes) {
                parts += value.substring(start, index)
                start = index + 1
            }
        }
        if (inQuotes) {
            throw RelayProtocolException("invalid quoted string in header entry")
        }
        parts += value.substring(start)
        return parts
    }

    private fun findDelimiterOutsideQuotedStrings(value: String, delimiter: Char): Int {
        var inQuotes = false
        var escaped = false
        for (index in value.indices) {
            val ch = value[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (ch == delimiter && !inQuotes) {
                return index
            }
        }
        if (inQuotes) {
            throw RelayProtocolException("invalid quoted string in header entry")
        }
        return -1
    }

    private fun parseQuotedJsonString(value: String): String {
        if (value.length < 2 || value.first() != '"' || value.last() != '"') {
            throw RelayProtocolException("invalid quoted string in header entry")
        }
        val input = value.substring(1, value.length - 1)
        if (input.isEmpty()) {
            return ""
        }

        val output = StringBuilder(input.length)
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            if (ch != '\\') {
                output.append(ch)
                index += 1
                continue
            }

            if (index + 1 >= input.length) {
                throw RelayProtocolException("invalid quoted string in header entry")
            }
            val escaped = input[index + 1]
            when (escaped) {
                '"', '\\', '/' -> output.append(escaped)
                'b' -> output.append('\b')
                'f' -> output.append('\u000c')
                'n' -> output.append('\n')
                'r' -> output.append('\r')
                't' -> output.append('\t')
                'u' -> {
                    if (index + 6 > input.length) {
                        throw RelayProtocolException("invalid quoted string in header entry")
                    }
                    val hex = input.substring(index + 2, index + 6)
                    val codePoint = hex.toIntOrNull(16)
                        ?: throw RelayProtocolException("invalid quoted string in header entry")
                    output.append(codePoint.toChar())
                    index += 4
                }
                else -> throw RelayProtocolException("invalid quoted string in header entry")
            }
            index += 2
        }
        return output.toString()
    }

    private fun hasMagic(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) {
            return false
        }
        return bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1] && bytes[2] == MAGIC[2]
    }

    private fun readLengthPrefixed(
        bytes: ByteArray,
        offset: Int,
    ): LengthPrefixedValue {
        val len = readU16(bytes, offset)
        val start = offset + 2
        val end = start + len
        ensureSize(bytes, end, "length-prefixed field truncated")
        return LengthPrefixedValue(
            value = bytes.copyOfRange(start, end),
            totalSize = 2 + len,
        )
    }

    private fun readByte(bytes: ByteArray, offset: Int): Int {
        ensureSize(bytes, offset + 1, "unexpected end of frame")
        return bytes[offset].toInt() and 0xFF
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        ensureSize(bytes, offset + 2, "expected 2-byte integer")
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        require(value in 0..0xFFFF) { "value out of uint16 range: $value" }
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeLengthPrefixed(out: ByteArrayOutputStream, bytes: ByteArray) {
        writeU16(out, bytes.size)
        out.write(bytes)
    }

    private fun writeU16At(target: ByteArray, offset: Int, value: Int) {
        require(value in 0..0xFFFF) { "value out of uint16 range: $value" }
        target[offset] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    private fun ensureSize(bytes: ByteArray, requiredSize: Int, message: String) {
        if (bytes.size < requiredSize) {
            throw RelayProtocolException(message)
        }
    }

    private data class LengthPrefixedValue(
        val value: ByteArray,
        val totalSize: Int,
    )

    companion object {
        private val MAGIC = byteArrayOf('M'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte())
        private const val PROTO_LEN_OFFSET = 3
        private const val HEADER_SIZE = 5

        private const val STR_TYPE_UTF8 = 0x01
        private const val MTE_TYPE_MTE = 0x00
        private const val MTE_TYPE_MKE = 0x01
        private const val FLAG_FALSE = 0x00
        private const val FLAG_TRUE = 0x01
    }
}
