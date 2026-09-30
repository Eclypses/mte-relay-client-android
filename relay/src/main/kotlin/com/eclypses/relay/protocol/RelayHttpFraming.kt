package com.eclypses.relay.protocol

import com.eclypses.mte.wire.DataPlaintext
import com.eclypses.mte.wire.Envelope
import com.eclypses.mte.wire.ErrorFrame
import com.eclypses.mte.wire.Json
import com.eclypses.mte.wire.JsonArray
import com.eclypses.mte.wire.JsonNumber
import com.eclypses.mte.wire.JsonObject
import com.eclypses.mte.wire.JsonString
import com.eclypses.mte.wire.JsonValue
import com.eclypses.mte.wire.Kind
import com.eclypses.mte.wire.Metadata
import com.eclypses.mte.wire.MteType
import com.eclypses.mte.wire.Path
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.RequestFrame
import com.eclypses.mte.wire.ResponseFrame
import com.eclypses.mte.wire.Token
import com.eclypses.mte.wire.WireException
import java.io.InputStream
import java.io.OutputStream

/** A frame the peer sent that this client will not treat as application data. */
class RelayProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/** A relay-generated ERROR frame, carrying the registry reason and its action. */
class RelayFramedErrorException(val error: ErrorFrame) :
    IllegalStateException("relay error ${error.code} ${error.reason}: ${error.message}")

/** The HTTP methods the method byte encodes. 255 defers to the metadata string. */
enum class RelayHttpMethod(val wireValue: Int) {
    GET(0), POST(1), PUT(2), PATCH(3), DELETE(4),
    HEAD(5), OPTIONS(6), TRACE(7), CONNECT(8),
    ;

    companion object {
        fun of(name: String): RelayHttpMethod? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** What the relay answered: the status and the origin's headers, before any body. */
data class RelayResponseHead(
    val status: Int,
    val headers: Map<String, List<String>>,
    /** END on the RESPONSE: HTTP itself says this response has no body. */
    val end: Boolean,
)

/**
 * Writes the HTTP request body: one REQUEST, then zero or more DATA.
 *
 * Section 9 maps an HTTP relay call to `POST /` with `application/octet-stream` and
 * `Accept-Encoding: identity`. Everything else -- method, path, headers -- moves inside
 * the encoded metadata, which is the point: the plain hop carries no description of the
 * request it is carrying.
 *
 * **One instance per request, and never shared.** Every method here advances the pair's
 * encoder exactly one operation, counted identically by the relay's decoder. One extra
 * or one missing desynchronises the pair permanently, and nothing in the resulting
 * failure says which frame did it.
 */
class RelayFrameWriter(
    private val encode: (ByteArray) -> ByteArray,
    private val mteType: MteType,
    private val maxFrameBytes: Int,
) {

    /**
     * Writes the REQUEST frame.
     *
     * @param end no DATA follows. Set it for a request with no body: a request whose
     *   body ends without END is 477 `eof_before_end`, which poisons the pair.
     */
    fun writeRequest(
        out: OutputStream,
        method: String,
        pairId: PairId,
        token: Token,
        path: String,
        headers: Map<String, List<String>>,
        end: Boolean,
    ) {
        val known = RelayHttpMethod.of(method)
        // 255 means "the metadata string is authoritative". Sending a method byte that
        // disagrees with the string is 483 header_mismatch, so the two are derived from
        // one value here rather than passed separately.
        val methodByte = known?.wireValue ?: RequestFrame.METHOD_EXTENDED

        // Section 8.1: the member is path plus query with NO leading slash. Emitting one
        // is 482 metadata_invalid on every single request -- a client that never works --
        // so the writer's rule is applied here rather than left to the caller.
        val member = path.removePrefix("/")
        Path.write(member)

        val metadata = LinkedHashMap<String, JsonValue>()
        metadata["v"] = JsonNumber(Metadata.FRAME_VERSION.toString())
        metadata["method"] = JsonString(if (known != null) known.name else method.uppercase())
        metadata["path"] = JsonString(member)
        if (headers.isNotEmpty()) metadata["headers"] = headersJson(headers)

        val frame = RequestFrame(
            mteType = mteType,
            methodByte = methodByte,
            pairId = pairId.raw,
            token = token.raw,
            metadata = encodeMetadata(JsonObject(metadata)),
        ).toByteArray()

        writeFrame(out, Kind.REQUEST, if (end) Envelope.FLAG_END else 0, frame)
    }

    /**
     * Writes one DATA frame.
     *
     * The plaintext is the flags byte followed by [app], which is what binds END into
     * the ciphertext: without it an on-path party could append an unauthenticated empty
     * END frame and turn a truncated body into a clean completion.
     *
     * A direction that ends with no application bytes still sends a DATA whose plaintext
     * is the flags byte alone -- not a length 0 frame, which performs no operation and
     * therefore binds nothing.
     */
    fun writeData(out: OutputStream, app: ByteArray, end: Boolean) {
        val flags = if (end) Envelope.FLAG_END else 0
        writeFrame(out, Kind.DATA, flags, encode(DataPlaintext.build(flags, app)))
    }

    /**
     * The largest application chunk that fits one frame.
     *
     * Standard MTE expands a plaintext of n bytes to 8n plus 4, so a type 0 sender has to
     * bound its chunk far lower than an MKE one -- the spec gives `(maxFrameBytes - 16) / 8`.
     * MKE adds a fixed overhead instead, so it can nearly fill the frame.
     */
    fun maxChunkBytes(): Int = when (mteType) {
        MteType.MTE -> ((maxFrameBytes - 16) / 8).coerceAtLeast(1)
        // One byte of the plaintext is the bound flags byte, and MKE adds a header and a
        // MAC block; MKE_OVERHEAD leaves room for both with margin.
        MteType.MKE -> (maxFrameBytes - Envelope.SIZE - MKE_OVERHEAD - 1).coerceAtLeast(1)
    }

    private fun encodeMetadata(obj: JsonObject): ByteArray {
        // Canonicalized before the encode call, and the size checked before it too.
        // Encoding and then refusing would leave this encoder one operation ahead of the
        // relay's decoder -- a worse failure than the one being reported.
        val canonical = Metadata.canonicalize(Json.canonical(obj))
        return encode(canonical.toByteArray(Charsets.UTF_8))
    }

    private fun writeFrame(out: OutputStream, kind: Kind, flags: Int, payload: ByteArray) {
        if (payload.size + Envelope.SIZE > maxFrameBytes) {
            throw RelayProtocolException(
                "frame is ${payload.size + Envelope.SIZE} bytes, over the relay's " +
                    "maxFrameBytes of $maxFrameBytes",
            )
        }
        out.write(Envelope(kind, flags, payload.size.toLong()).toByteArray())
        out.write(payload)
    }

    private fun headersJson(headers: Map<String, List<String>>): JsonValue {
        val out = LinkedHashMap<String, JsonValue>()
        for ((name, values) in headers) {
            if (values.isEmpty()) continue
            // Lowercase on the wire in both directions; Metadata refuses an uppercase
            // name rather than folding it, so fold here where the caller's casing is.
            val key = name.lowercase()
            // A single value is a string and a repeated one an array. The array is what
            // keeps every set-cookie through a MAR chain, which a flat map could not.
            out[key] = if (values.size == 1) JsonString(values[0])
            else JsonArray(values.map { JsonString(it) })
        }
        return JsonObject(out)
    }

    private companion object {
        /** Room for the MKE header and MAC block, with margin. */
        const val MKE_OVERHEAD = 512
    }
}

/** One frame read from a response body. */
sealed interface RelayFrameEvent {
    /** Application bytes. [end] marks the last DATA in this direction. */
    class Data(val bytes: ByteArray, val end: Boolean) : RelayFrameEvent
    /** The body ended cleanly after an END frame. */
    object Complete : RelayFrameEvent
}

/**
 * Reads the HTTP response body: one RESPONSE, then zero or more DATA.
 *
 * The response is chunked and never declares `Content-Length`, so the reader is driven
 * by the frames and not by a length. Section 5: "a response or message that is not a
 * valid frame is a transport error, never application data, whatever the HTTP status or
 * content type says" -- which is what makes a captive portal or a WAF block fail here
 * rather than reach the caller as a body.
 */
class RelayFrameReader(
    private val stream: InputStream,
    private val decode: (ByteArray) -> ByteArray,
    private val maxFrameBytes: Int,
) {
    private var sawEnd = false

    /** Reads the RESPONSE frame, or throws on an ERROR frame in its place. */
    fun readResponse(): RelayResponseHead {
        val frame = readFrame()
            ?: throw RelayProtocolException("the relay closed before sending a RESPONSE")
        val envelope = frame.first
        val payload = frame.second

        if (envelope.kind == Kind.ERROR) throw RelayFramedErrorException(ErrorFrame.read(payload))
        if (envelope.kind != Kind.RESPONSE) {
            throw RelayProtocolException("expected RESPONSE, got ${envelope.kind}")
        }

        val response = ResponseFrame.read(payload)
        val metadata = Json.parseObject(String(decode(response.metadata), Charsets.UTF_8))

        // `v` and `status` are required inside the metadata and must equal the envelope
        // version and the plaintext status. The status appears twice precisely so a
        // rewritten plaintext one can be caught.
        val declaredVersion = (metadata["v"] as? JsonNumber)?.raw?.toIntOrNull()
        if (declaredVersion != Metadata.FRAME_VERSION) {
            throw RelayProtocolException(
                "response metadata says v=$declaredVersion, envelope says ${Metadata.FRAME_VERSION}",
            )
        }
        val declaredStatus = (metadata["status"] as? JsonNumber)?.raw?.toIntOrNull()
        if (declaredStatus != response.status) {
            throw RelayProtocolException(
                "response metadata says status $declaredStatus, frame says ${response.status}",
            )
        }

        sawEnd = envelope.isEnd
        return RelayResponseHead(response.status, readHeaders(metadata), envelope.isEnd)
    }

    /**
     * Reads the next body frame.
     *
     * A clean end of stream is only clean after END. EOF before it is 477
     * `eof_before_end`: the relay treats the same condition on a request body as a
     * poisoned pair, so the client refuses rather than handing a truncated body to the
     * caller as complete.
     */
    fun readNext(): RelayFrameEvent {
        if (sawEnd) return RelayFrameEvent.Complete

        val frame = readFrame()
            ?: throw RelayProtocolException(
                "the response body ended before a frame with END; the body is truncated " +
                    "(eof_before_end)",
            )
        val envelope = frame.first
        val payload = frame.second

        when (envelope.kind) {
            Kind.ERROR -> throw RelayFramedErrorException(ErrorFrame.read(payload))
            Kind.DATA -> Unit
            else -> throw RelayProtocolException("expected DATA, got ${envelope.kind}")
        }

        sawEnd = envelope.isEnd

        // Length 0 performs no operation, so its flags are bound into nothing and an
        // on-path party could set them freely. Section 4 (E2) requires it to carry no
        // flags at all; decoding it would also cost an operation the relay never spent.
        if (envelope.length == 0L) {
            if (envelope.flags != 0) {
                throw RelayProtocolException("a length 0 DATA carried flags ${envelope.flags}")
            }
            return RelayFrameEvent.Data(ByteArray(0), end = false)
        }

        val app = DataPlaintext.split(decode(payload), envelope.flags)
        return RelayFrameEvent.Data(app, envelope.isEnd)
    }

    private fun readHeaders(metadata: JsonObject): Map<String, List<String>> {
        val headers = metadata["headers"] as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for ((name, value) in headers.members) {
            out[name] = when (value) {
                is JsonString -> listOf(value.value)
                is JsonArray -> value.items.mapNotNull { (it as? JsonString)?.value }
                else -> continue
            }
        }
        return out
    }

    /** Returns null at a clean end of stream. */
    private fun readFrame(): Pair<Envelope, ByteArray>? {
        val prefix = readOrNull(Envelope.SIZE) ?: return null
        val envelope = try {
            Envelope.read(prefix)
        } catch (e: WireException) {
            throw RelayProtocolException("not a frame version 2 response: ${e.message}", e)
        }
        if (envelope.length + Envelope.SIZE > maxFrameBytes) {
            throw RelayProtocolException(
                "the relay sent a ${envelope.length + Envelope.SIZE} byte frame, over the " +
                    "maxFrameBytes of $maxFrameBytes it advertised",
            )
        }
        return envelope to readExactly(envelope.length.toInt())
    }

    private fun readOrNull(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = stream.read(buf, off, n - off)
            if (read == -1) {
                if (off == 0) return null
                throw RelayProtocolException("stream ended $off bytes into a $n byte prefix")
            }
            off += read
        }
        return buf
    }

    private fun readExactly(n: Int): ByteArray =
        readOrNull(n) ?: throw RelayProtocolException("stream ended before a $n byte payload")
}
