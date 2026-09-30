package com.eclypses.relay.protocol

import com.eclypses.mte.wire.DataPlaintext
import com.eclypses.mte.wire.Envelope
import com.eclypses.mte.wire.ErrorFrame
import com.eclypses.mte.wire.Json
import com.eclypses.mte.wire.JsonNumber
import com.eclypses.mte.wire.JsonObject
import com.eclypses.mte.wire.JsonString
import com.eclypses.mte.wire.JsonValue
import com.eclypses.mte.wire.Kind
import com.eclypses.mte.wire.MteType
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.RequestFrame
import com.eclypses.mte.wire.ResponseFrame
import com.eclypses.mte.wire.Token
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The HTTP framing, driven end to end against a stand-in relay.
 *
 * The codec here is a reversible transform rather than the identity, so a layer that
 * forgot to encode or decode fails instead of passing on bytes that happen to match. It
 * also **counts operations**, because the count is the thing that desynchronises a pair
 * permanently and the thing no single-frame assertion would catch.
 */
class RelayHttpFramingTest {

    /** Reversible, order-independent, and nothing like the identity. */
    private class CountingCodec {
        var encodes = 0
        var decodes = 0
        fun encode(b: ByteArray): ByteArray {
            encodes++
            return ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() }
        }
        fun decode(b: ByteArray): ByteArray {
            decodes++
            return ByteArray(b.size) { (b[it].toInt() xor 0x5A).toByte() }
        }
    }

    private val pairId = PairId.parse("AAECAwQFBgcICQoLDA0ODw")
    private val token = Token(ByteArray(40) { it.toByte() })
    private val maxFrame = 65536

    private fun writer(codec: CountingCodec, type: MteType = MteType.MKE) =
        RelayFrameWriter(codec::encode, type, maxFrame)

    // ---- the stand-in relay -------------------------------------------------

    /** Parses what the writer produced, the way the relay would. */
    private fun parseRequest(bytes: ByteArray, codec: CountingCodec): Triple<RequestFrame, String, ByteArray> {
        var i = 0
        val env = Envelope.read(bytes, i); i += Envelope.SIZE
        assertEquals(Kind.REQUEST, env.kind)
        val request = RequestFrame.read(bytes.copyOfRange(i, i + env.length.toInt()))
        i += env.length.toInt()
        val metadata = String(codec.decode(request.metadata), Charsets.UTF_8)

        val body = ByteArrayOutputStream()
        var sawEnd = env.isEnd
        while (i < bytes.size) {
            val d = Envelope.read(bytes, i); i += Envelope.SIZE
            assertEquals(Kind.DATA, d.kind)
            val payload = bytes.copyOfRange(i, i + d.length.toInt()); i += d.length.toInt()
            body.write(DataPlaintext.split(codec.decode(payload), d.flags))
            if (d.isEnd) sawEnd = true
        }
        assertTrue(sawEnd, "the request body never carried END")
        return Triple(request, metadata, body.toByteArray())
    }

    /** Builds a response the way the relay would. */
    private fun buildResponse(
        codec: CountingCodec,
        status: Int,
        headers: Map<String, String>,
        chunks: List<ByteArray>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val members = LinkedHashMap<String, JsonValue>()
        members["v"] = JsonNumber("2")
        members["status"] = JsonNumber(status.toString())
        if (headers.isNotEmpty()) {
            members["headers"] = JsonObject(
                headers.entries.associateTo(LinkedHashMap()) { it.key to JsonString(it.value) },
            )
        }
        val metadata = codec.encode(
            Json.canonical(JsonObject(members)).toByteArray(Charsets.UTF_8),
        )
        val payload = ResponseFrame(status, metadata).toByteArray()
        val end = chunks.isEmpty()
        out.write(Envelope(Kind.RESPONSE, if (end) Envelope.FLAG_END else 0, payload.size.toLong()).toByteArray())
        out.write(payload)

        chunks.forEachIndexed { index, chunk ->
            val flags = if (index == chunks.lastIndex) Envelope.FLAG_END else 0
            val enc = codec.encode(DataPlaintext.build(flags, chunk))
            out.write(Envelope(Kind.DATA, flags, enc.size.toLong()).toByteArray())
            out.write(enc)
        }
        return out.toByteArray()
    }

    // ---- requests -----------------------------------------------------------

    @Test
    fun aGetWithNoBodyIsOneRequestFrameWithEnd() {
        val codec = CountingCodec()
        val out = ByteArrayOutputStream()
        writer(codec).writeRequest(out, "GET", pairId, token, "/api/items", emptyMap(), end = true)

        val bytes = out.toByteArray()
        assertEquals(Envelope.FLAG_END, Envelope.read(bytes).flags, "a body-less request must set END")

        val (request, metadata, body) = parseRequest(bytes, codec)
        assertEquals(RelayHttpMethod.GET.wireValue, request.methodByte)
        assertContentEquals(pairId.raw, request.pairId)
        assertContentEquals(token.raw, request.token)
        assertEquals(0, body.size)
        // Canonical: keys sorted by UTF-8 bytes, no insignificant whitespace. And the
        // path carries NO leading slash -- section 8.1's member is relative, and a writer
        // that emits one is refused with 482 on every request.
        assertEquals("""{"method":"GET","path":"api/items","v":2}""", metadata)
        // Exactly one operation: the metadata. No body, no DATA, no second encode.
        assertEquals(1, codec.encodes)
    }

    @Test
    fun aPostBodyTravelsAsDataFramesAndTheLastCarriesEnd() {
        val codec = CountingCodec()
        val out = ByteArrayOutputStream()
        val w = writer(codec)
        w.writeRequest(out, "POST", pairId, token, "/api/upload", mapOf("accept" to listOf("application/json")), end = false)
        w.writeData(out, "hello ".toByteArray(), end = false)
        w.writeData(out, "world".toByteArray(), end = true)

        val (request, metadata, body) = parseRequest(out.toByteArray(), codec)
        assertEquals(RelayHttpMethod.POST.wireValue, request.methodByte)
        assertEquals("hello world", String(body))
        assertEquals("""{"headers":{"accept":"application/json"},"method":"POST","path":"api/upload","v":2}""", metadata)
        // One for the metadata, one per DATA frame.
        assertEquals(3, codec.encodes)
    }

    /**
     * A method the byte does not encode defers to the metadata string, and the two must
     * agree: a byte that disagrees with the string is 483 header_mismatch.
     */
    @Test
    fun anUnknownMethodUsesTheExtendedByteAndTheMetadataString() {
        val codec = CountingCodec()
        val out = ByteArrayOutputStream()
        writer(codec).writeRequest(out, "PROPFIND", pairId, token, "/dav", emptyMap(), end = true)

        val (request, metadata, _) = parseRequest(out.toByteArray(), codec)
        assertEquals(RequestFrame.METHOD_EXTENDED, request.methodByte)
        assertTrue(metadata.contains(""""method":"PROPFIND""""), metadata)
    }

    /** Header names are lowercase on the wire in both directions; Metadata refuses otherwise. */
    @Test
    fun headerNamesAreFoldedToLowercase() {
        val codec = CountingCodec()
        val out = ByteArrayOutputStream()
        writer(codec).writeRequest(
            out, "GET", pairId, token, "/x",
            mapOf("Content-Type" to listOf("text/plain"), "X-Trace" to listOf("abc")),
            end = true,
        )
        val metadata = parseRequest(out.toByteArray(), codec).second
        assertTrue(metadata.contains(""""x-trace":"abc""""), metadata)
        // content-type is not stripped; content-length and the hop-by-hop names are.
        assertTrue(metadata.contains(""""content-type":"text/plain""""), metadata)
    }

    @Test
    fun aRepeatedHeaderTravelsAsAnArray() {
        val codec = CountingCodec()
        val out = ByteArrayOutputStream()
        writer(codec).writeRequest(
            out, "GET", pairId, token, "/x",
            mapOf("accept" to listOf("text/html", "application/json")),
            end = true,
        )
        assertTrue(
            parseRequest(out.toByteArray(), codec).second
                .contains(""""accept":["text/html","application/json"]"""),
        )
    }

    /** Hop-by-hop names describe the frame POST, never the request inside it. */
    @Test
    fun strippedHeadersDoNotTravel() {
        val codec = CountingCodec()
        val out = ByteArrayOutputStream()
        writer(codec).writeRequest(
            out, "POST", pairId, token, "/x",
            mapOf(
                "content-length" to listOf("99"),
                "connection" to listOf("keep-alive"),
                "host" to listOf("example.test"),
                "x-keep" to listOf("yes"),
            ),
            end = true,
        )
        val metadata = parseRequest(out.toByteArray(), codec).second
        assertTrue(metadata.contains("x-keep"), metadata)
        for (stripped in listOf("content-length", "connection", "host")) {
            assertTrue(!metadata.contains(stripped), "$stripped survived: $metadata")
        }
    }

    @Test
    fun aFrameOverTheRelayBoundIsRefusedBeforeItIsSent() {
        val codec = CountingCodec()
        val small = RelayFrameWriter(codec::encode, MteType.MKE, maxFrameBytes = 64)
        assertFailsWith<RelayProtocolException> {
            small.writeData(ByteArrayOutputStream(), ByteArray(4096), end = true)
        }
    }

    /**
     * Standard MTE expands a plaintext of n bytes to 8n plus 4, so its chunk bound has to
     * be an eighth of the frame rather than the frame itself.
     */
    @Test
    fun theChunkBoundFollowsTheEncodeType() {
        val codec = CountingCodec()
        assertEquals((maxFrame - 16) / 8, writer(codec, MteType.MTE).maxChunkBytes())
        assertTrue(writer(codec, MteType.MKE).maxChunkBytes() > writer(codec, MteType.MTE).maxChunkBytes())
    }

    // ---- responses ----------------------------------------------------------

    @Test
    fun readsAResponseAndItsBody() {
        val codec = CountingCodec()
        val bytes = buildResponse(
            codec, 200, mapOf("content-type" to "application/json"),
            listOf("""{"ok":""".toByteArray(), """true}""".toByteArray()),
        )
        val reader = RelayFrameReader(bytes.inputStream(), codec::decode, maxFrame)

        val head = reader.readResponse()
        assertEquals(200, head.status)
        assertEquals(listOf("application/json"), head.headers["content-type"])
        assertTrue(!head.end, "a response with a body must not set END")

        val body = StringBuilder()
        while (true) {
            val e = reader.readNext()
            if (e !is RelayFrameEvent.Data) break
            body.append(String(e.bytes))
            if (e.end) break
        }
        assertEquals("""{"ok":true}""", body.toString())
        assertEquals(RelayFrameEvent.Complete, reader.readNext())
    }

    @Test
    fun aBodylessResponseSetsEndOnTheResponseFrame() {
        val codec = CountingCodec()
        val reader = RelayFrameReader(
            buildResponse(codec, 204, emptyMap(), emptyList()).inputStream(), codec::decode, maxFrame,
        )
        assertEquals(204, reader.readResponse().status)
        assertEquals(RelayFrameEvent.Complete, reader.readNext())
    }

    /**
     * The status is carried twice -- in the frame and inside the encrypted metadata -- so a
     * rewritten plaintext one can be caught. This is that check.
     */
    @Test
    fun aStatusThatDisagreesWithItsMetadataIsRefused() {
        val codec = CountingCodec()
        val bytes = buildResponse(codec, 200, emptyMap(), emptyList())
        // Rewrite only the plaintext status, at its fixed offset inside the RESPONSE payload.
        bytes[Envelope.SIZE] = 0x01
        bytes[Envelope.SIZE + 1] = 0xF4.toByte() // 500
        val e = assertFailsWith<RelayProtocolException> {
            RelayFrameReader(bytes.inputStream(), codec::decode, maxFrame).readResponse()
        }
        assertTrue(e.message!!.contains("status"), e.message!!)
    }

    /** A truncated body must never reach the caller as a complete one. */
    @Test
    fun aBodyThatEndsBeforeEndIsRefused() {
        val codec = CountingCodec()
        val full = buildResponse(codec, 200, emptyMap(), listOf("abc".toByteArray()))
        // Drop the final DATA frame entirely: the RESPONSE said a body follows and none does.
        val truncated = full.copyOfRange(0, Envelope.SIZE + Envelope.read(full).length.toInt())
        val reader = RelayFrameReader(truncated.inputStream(), codec::decode, maxFrame)
        reader.readResponse()
        val e = assertFailsWith<RelayProtocolException> { reader.readNext() }
        assertTrue(e.message!!.contains("eof_before_end"), e.message!!)
    }

    /**
     * The flags byte is inside the ciphertext. Flipping END in the envelope alone -- which is
     * all an on-path party can reach -- must not turn a truncation into a clean completion.
     */
    @Test
    fun flippingEndInTheEnvelopeIsCaught() {
        val codec = CountingCodec()
        val bytes = buildResponse(codec, 200, emptyMap(), listOf("abc".toByteArray(), "def".toByteArray()))
        // Find the first DATA prefix and set its END bit.
        var i = Envelope.SIZE + Envelope.read(bytes).length.toInt()
        bytes[i + 5] = (bytes[i + 5].toInt() or Envelope.FLAG_END).toByte()

        val reader = RelayFrameReader(bytes.inputStream(), codec::decode, maxFrame)
        reader.readResponse()
        assertFailsWith<com.eclypses.mte.wire.WireException> { reader.readNext() }
    }

    /** A length 0 DATA performs no operation, so its flags are bound into nothing. */
    @Test
    fun aLengthZeroDataCarryingFlagsIsRefused() {
        val codec = CountingCodec()
        val out = ByteArrayOutputStream()
        out.write(buildResponse(codec, 200, emptyMap(), emptyList()).copyOfRange(0, 0))
        val head = buildResponse(codec, 200, emptyMap(), listOf("x".toByteArray()))
        val responseLen = Envelope.SIZE + Envelope.read(head).length.toInt()
        out.write(head, 0, responseLen)
        out.write(Envelope(Kind.DATA, Envelope.FLAG_END, 0).toByteArray())

        val reader = RelayFrameReader(out.toByteArray().inputStream(), codec::decode, maxFrame)
        reader.readResponse()
        val e = assertFailsWith<RelayProtocolException> { reader.readNext() }
        assertTrue(e.message!!.contains("length 0"), e.message!!)
    }

    /** An ERROR frame in place of the RESPONSE carries the registry reason and its action. */
    @Test
    fun anErrorFrameInsteadOfAResponse() {
        val codec = CountingCodec()
        val payload = ErrorFrame(470, "pair_not_found", "the pair expired").toByteArray()
        val out = ByteArrayOutputStream()
        out.write(Envelope(Kind.ERROR, Envelope.FLAG_END, payload.size.toLong()).toByteArray())
        out.write(payload)

        val e = assertFailsWith<RelayFramedErrorException> {
            RelayFrameReader(out.toByteArray().inputStream(), codec::decode, maxFrame).readResponse()
        }
        assertEquals("pair_not_found", e.error.reason)
        assertEquals(com.eclypses.mte.wire.RelayAction.REPLACE_PAIR, e.error.action)
        // Not encoded: no operation was spent reading it.
        assertEquals(0, codec.decodes)
    }

    /**
     * Section 5: a response that is not a valid frame is a transport error, never
     * application data -- whatever the status says. This is a captive portal or a WAF page.
     */
    @Test
    fun aReplacedPageIsATransportErrorAndNotABody() {
        val codec = CountingCodec()
        val html = "<html><body>Blocked by policy</body></html>".toByteArray()
        val e = assertFailsWith<RelayProtocolException> {
            RelayFrameReader(html.inputStream(), codec::decode, maxFrame).readResponse()
        }
        assertTrue(e.message!!.contains("frame version 2"), e.message!!)
    }

    /** A frame version 1 response is named as such, not reported as garbage. */
    @Test
    fun aFrameVersion1ResponseSaysSo() {
        val codec = CountingCodec()
        val legacy = byteArrayOf(0x4D, 0x54, 0x45, 0x00, 0x20) + ByteArray(32)
        val e = assertFailsWith<RelayProtocolException> {
            RelayFrameReader(legacy.inputStream(), codec::decode, maxFrame).readResponse()
        }
        assertTrue(e.message!!.contains("frame version 1"), e.message!!)
    }

    /** The writer's rule: the member is relative, and emitting a slash is 482. */
    @Test
    fun theLeadingSlashIsStrippedFromTheCallersRoute() {
        val codec = CountingCodec()
        for (route in listOf("/a/b?x=1", "a/b?x=1")) {
            val out = ByteArrayOutputStream()
            writer(codec).writeRequest(out, "GET", pairId, token, route, emptyMap(), end = true)
            assertTrue(
                parseRequest(out.toByteArray(), codec).second.contains(""""path":"a/b?x=1""""),
                "route $route",
            )
        }
    }

    @Test
    fun aFrameOverTheAdvertisedBoundIsRefused() {
        val codec = CountingCodec()
        val oversized = Envelope(Kind.RESPONSE, 0, 900_000).toByteArray()
        val e = assertFailsWith<RelayProtocolException> {
            RelayFrameReader(oversized.inputStream(), codec::decode, maxFrameBytes = 65536).readResponse()
        }
        assertTrue(e.message!!.contains("maxFrameBytes"), e.message!!)
    }
}
