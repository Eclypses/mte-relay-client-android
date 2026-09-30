package com.eclypses.mte.wire

/**
 * The REQUEST payload, section 2.
 *
 * ```
 * Offset  Size  Field
 * 0       1     MTE type: 0 standard MTE, 1 MKE. Connections require 1.
 * 1       1     Method byte: 0..8 for GET..CONNECT; 255 defers to metadata "method"
 * 2       16    Pair id, binary. All zero on a connection.
 * 18      2     Client token length: 40 on HTTP, 0 on a connection.
 * 20      var   Client token
 * var     4     Metadata length, uint32
 * var     var   Encoded metadata. The payload ends exactly here.
 * ```
 *
 * One layout for every transport, so a reader never needs to know the transport to
 * parse it: a connection request keeps the id fields and zeroes them.
 */
public data class RequestFrame(
    public val mteType: MteType,
    /** 0..8, or [METHOD_EXTENDED] when the metadata `method` string is authoritative. */
    public val methodByte: Int,
    public val pairId: ByteArray,
    public val token: ByteArray,
    public val metadata: ByteArray,
) {
    /** True when this opens a connection: zeroed pair id and no token. */
    public val isConnection: Boolean
        get() = token.isEmpty() && pairId.all { it.toInt() == 0 }

    public fun toByteArray(): ByteArray {
        require(pairId.size == PAIR_ID_SIZE) { "pair id must be $PAIR_ID_SIZE bytes" }
        require(metadata.isNotEmpty()) { "metadata must not be empty" }
        val out = ByteArray(20 + token.size + 4 + metadata.size)
        out[0] = mteType.value.toByte()
        out[1] = methodByte.toByte()
        pairId.copyInto(out, 2)
        out[18] = (token.size ushr 8 and 0xFF).toByte()
        out[19] = (token.size and 0xFF).toByte()
        token.copyInto(out, 20)
        var i = 20 + token.size
        out[i++] = (metadata.size ushr 24 and 0xFF).toByte()
        out[i++] = (metadata.size ushr 16 and 0xFF).toByte()
        out[i++] = (metadata.size ushr 8 and 0xFF).toByte()
        out[i++] = (metadata.size and 0xFF).toByte()
        metadata.copyInto(out, i)
        return out
    }

    // Kotlin does not give data classes a useful equals for ByteArray members.
    override fun equals(other: Any?): Boolean =
        this === other || (other is RequestFrame &&
            mteType == other.mteType &&
            methodByte == other.methodByte &&
            pairId.contentEquals(other.pairId) &&
            token.contentEquals(other.token) &&
            metadata.contentEquals(other.metadata))

    override fun hashCode(): Int {
        var h = mteType.hashCode()
        h = 31 * h + methodByte
        h = 31 * h + pairId.contentHashCode()
        h = 31 * h + token.contentHashCode()
        h = 31 * h + metadata.contentHashCode()
        return h
    }

    public companion object {
        public const val PAIR_ID_SIZE: Int = 16
        public const val TOKEN_SIZE: Int = 40
        public const val METHOD_EXTENDED: Int = 255

        public fun read(p: ByteArray): RequestFrame {
            if (p.size < 20) throw WireException(WireError.PAYLOAD, "request under 20 bytes")

            val mteType = MteType.of(p[0].toInt() and 0xFF)
                ?: throw WireException(WireError.PAYLOAD, "unknown MTE type ${p[0].toInt() and 0xFF}")

            val method = p[1].toInt() and 0xFF
            if (method !in 0..8 && method != METHOD_EXTENDED) {
                // Section 2: "Any other value MUST be rejected before any decoder is
                // touched." Rejecting late would cost a codec operation on a frame
                // that was never going to be delivered, and the counts never recover.
                throw WireException(WireError.PAYLOAD, "method byte $method")
            }

            val pairId = p.copyOfRange(2, 2 + PAIR_ID_SIZE)
            val tokenLen = ((p[18].toInt() and 0xFF) shl 8) or (p[19].toInt() and 0xFF)
            if (tokenLen != TOKEN_SIZE && tokenLen != 0) {
                // "40 on HTTP, 0 on a connection" -- no other value parses.
                throw WireException(WireError.PAYLOAD, "token length $tokenLen")
            }
            if (p.size < 20 + tokenLen + 4) {
                throw WireException(WireError.PAYLOAD, "truncated before metadata length")
            }
            val token = p.copyOfRange(20, 20 + tokenLen)

            var i = 20 + tokenLen
            val metaLen = ((p[i].toLong() and 0xFF) shl 24) or
                ((p[i + 1].toLong() and 0xFF) shl 16) or
                ((p[i + 2].toLong() and 0xFF) shl 8) or
                (p[i + 3].toLong() and 0xFF)
            i += 4

            if (metaLen == 0L) throw WireException(WireError.PAYLOAD, "empty metadata")
            // "The payload ends exactly here": short is truncation, long is a
            // trailing byte an on-path party appended. Both are the same refusal.
            if (p.size.toLong() - i != metaLen) {
                throw WireException(
                    WireError.PAYLOAD,
                    "metadata length $metaLen but ${p.size - i} bytes remain",
                )
            }
            return RequestFrame(mteType, method, pairId, token, p.copyOfRange(i, p.size))
        }
    }
}

/**
 * The RESPONSE payload, section 3.
 *
 * ```
 * Offset  Size  Field
 * 0       2     Status, uint16, 100 to 599
 * 2       4     Metadata length, uint32
 * 6       var   Encoded metadata
 * ```
 *
 * No identifiers: a response is answered on the pair that asked.
 */
public data class ResponseFrame(
    public val status: Int,
    public val metadata: ByteArray,
) {
    public fun toByteArray(): ByteArray {
        require(status in 100..599) { "status $status out of range" }
        require(metadata.isNotEmpty()) { "metadata must not be empty" }
        val out = ByteArray(6 + metadata.size)
        out[0] = (status ushr 8 and 0xFF).toByte()
        out[1] = (status and 0xFF).toByte()
        out[2] = (metadata.size ushr 24 and 0xFF).toByte()
        out[3] = (metadata.size ushr 16 and 0xFF).toByte()
        out[4] = (metadata.size ushr 8 and 0xFF).toByte()
        out[5] = (metadata.size and 0xFF).toByte()
        metadata.copyInto(out, 6)
        return out
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ResponseFrame &&
            status == other.status && metadata.contentEquals(other.metadata))

    override fun hashCode(): Int = 31 * status + metadata.contentHashCode()

    public companion object {
        public fun read(p: ByteArray): ResponseFrame {
            if (p.size < 6) throw WireException(WireError.PAYLOAD, "response under 6 bytes")
            val status = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
            if (status !in 100..599) throw WireException(WireError.PAYLOAD, "status $status")
            val metaLen = ((p[2].toLong() and 0xFF) shl 24) or
                ((p[3].toLong() and 0xFF) shl 16) or
                ((p[4].toLong() and 0xFF) shl 8) or
                (p[5].toLong() and 0xFF)
            if (metaLen == 0L) throw WireException(WireError.PAYLOAD, "empty metadata")
            if (p.size.toLong() - 6 != metaLen) {
                throw WireException(
                    WireError.PAYLOAD,
                    "metadata length $metaLen but ${p.size - 6} bytes remain",
                )
            }
            return ResponseFrame(status, p.copyOfRange(6, p.size))
        }
    }
}

/**
 * The DATA plaintext binding, section 4.
 *
 * The plaintext handed to `Encode` is one byte equal to the frame's flags byte,
 * followed by the application bytes. The receiver strips it and compares it to the
 * envelope's flags; a mismatch is 483 on HTTP and fatal on a connection.
 *
 * That binding is the whole point: it puts END, MORE, TEXT and HALF inside the
 * ciphertext, so an on-path party cannot flip a truncation into a clean completion
 * or an empty text message into an empty binary one.
 */
public object DataPlaintext {

    /** Builds the plaintext to encode: flags byte, then the application bytes. */
    public fun build(flags: Int, app: ByteArray): ByteArray {
        require(flags and Envelope.RESERVED_FLAG_MASK == 0) { "reserved flag bit set" }
        val out = ByteArray(1 + app.size)
        out[0] = flags.toByte()
        app.copyInto(out, 1)
        return out
    }

    /**
     * Strips and verifies the leading flags byte against [envelopeFlags].
     *
     * Never call this for a length 0 DATA: section 4 says no operation was
     * performed, so there is no plaintext and the flags byte is bound into nothing.
     * Such a frame must carry no flags at all, which the envelope reader checks.
     */
    public fun split(plaintext: ByteArray, envelopeFlags: Int): ByteArray {
        if (plaintext.isEmpty()) {
            throw WireException(WireError.PAYLOAD, "DATA plaintext carries no flags byte")
        }
        val bound = plaintext[0].toInt() and 0xFF
        if (bound != envelopeFlags) {
            throw WireException(
                WireError.FLAGS_MISMATCH,
                "flags byte $bound does not match envelope $envelopeFlags",
            )
        }
        return plaintext.copyOfRange(1, plaintext.size)
    }
}

/** Section 2: 0 standard MTE, 1 MKE. Connections require MKE. */
public enum class MteType(public val value: Int) {
    MTE(0),
    MKE(1),
    ;

    public companion object {
        public fun of(value: Int): MteType? = entries.firstOrNull { it.value == value }
    }
}
