package com.eclypses.mte.wire

/**
 * The 12 byte prefix that begins every frame on every transport.
 *
 * `spec/frame-v2.md` section 1:
 * ```
 * Offset  Size  Field
 * 0       3     Signature: 0x89 0x4D 0x54
 * 3       1     Version: 2. 0 is forbidden.
 * 4       1     Kind
 * 5       1     Flags: bit0 END, bit1 MORE, bit2 TEXT, bit3 HALF; bits 4..7 reserved
 * 6       2     Reserved, must be 0
 * 8       4     Length, uint32 big endian: payload bytes that follow this prefix
 * ```
 */
public data class Envelope(
    public val kind: Kind,
    public val flags: Int,
    public val length: Long,
) {
    init {
        require(flags and RESERVED_FLAG_MASK == 0) { "reserved flag bit set" }
        require(length in 0..MAX_LENGTH) { "length out of range" }
    }

    public val isEnd: Boolean get() = flags and FLAG_END != 0
    public val isMore: Boolean get() = flags and FLAG_MORE != 0
    public val isText: Boolean get() = flags and FLAG_TEXT != 0
    public val isHalf: Boolean get() = flags and FLAG_HALF != 0

    /** Writes this prefix. The payload is the caller's to append. */
    public fun toByteArray(): ByteArray {
        val out = ByteArray(SIZE)
        SIGNATURE.copyInto(out, 0)
        out[3] = VERSION.toByte()
        out[4] = kind.value.toByte()
        out[5] = flags.toByte()
        // bytes 6 and 7 stay zero
        out[8] = (length ushr 24 and 0xFF).toByte()
        out[9] = (length ushr 16 and 0xFF).toByte()
        out[10] = (length ushr 8 and 0xFF).toByte()
        out[11] = (length and 0xFF).toByte()
        return out
    }

    public companion object {
        public const val SIZE: Int = 12
        public const val VERSION: Int = 2
        public const val MAX_LENGTH: Long = 0xFFFFFFFFL

        public val SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x4D, 0x54)

        /** The frame version 1 prefix, `MTE`. Recognised forever, never accepted. */
        public val LEGACY_SIGNATURE: ByteArray = byteArrayOf(0x4D, 0x54, 0x45)

        public const val FLAG_END: Int = 1 shl 0
        public const val FLAG_MORE: Int = 1 shl 1
        public const val FLAG_TEXT: Int = 1 shl 2
        public const val FLAG_HALF: Int = 1 shl 3
        public const val RESERVED_FLAG_MASK: Int = 0xF0

        /**
         * Reads the prefix from [src] at [offset].
         *
         * The order of these checks is normative, not stylistic. Section 1 requires
         * signature, then version, then reserved bits, then kind, then length, and
         * says so because a frame version 1 frame has a different byte at offset 3:
         * check the kind first and you interpret a v1 length as a v2 kind and report
         * the wrong failure for the one case that has a prescribed answer (478, with
         * the minimum SDK versions).
         */
        public fun read(src: ByteArray, offset: Int = 0): Envelope {
            if (src.size - offset < SIZE) throw WireException(WireError.SHORT, "need $SIZE bytes")

            if (!src.regionMatches(offset, SIGNATURE)) {
                if (src.regionMatches(offset, LEGACY_SIGNATURE)) {
                    throw WireException(
                        WireError.LEGACY_SIGNATURE,
                        "frame version 1 prefix; this build speaks frame version $VERSION",
                    )
                }
                throw WireException(WireError.SIGNATURE, "not an MTE Relay frame")
            }

            val version = src[offset + 3].toInt() and 0xFF
            if (version != VERSION) {
                throw WireException(WireError.VERSION, "frame version $version is not supported")
            }

            val flags = src[offset + 5].toInt() and 0xFF
            if (flags and RESERVED_FLAG_MASK != 0) {
                throw WireException(WireError.RESERVED, "reserved flag bit set")
            }
            if (src[offset + 6].toInt() != 0 || src[offset + 7].toInt() != 0) {
                throw WireException(WireError.RESERVED, "reserved byte is nonzero")
            }

            val kindByte = src[offset + 4].toInt() and 0xFF
            val kind = Kind.of(kindByte)
                ?: throw WireException(WireError.KIND, "unknown kind $kindByte")

            val length = ((src[offset + 8].toLong() and 0xFF) shl 24) or
                ((src[offset + 9].toLong() and 0xFF) shl 16) or
                ((src[offset + 10].toLong() and 0xFF) shl 8) or
                (src[offset + 11].toLong() and 0xFF)

            return Envelope(kind, flags, length)
        }

        private fun ByteArray.regionMatches(offset: Int, other: ByteArray): Boolean {
            if (size - offset < other.size) return false
            for (i in other.indices) if (this[offset + i] != other[i]) return false
            return true
        }
    }
}

/**
 * The frame kinds of section 1.
 *
 * Only assigned values parse. 11..199 are unassigned, 200..254 are experimental and
 * "never appear on a public relay", 255 is reserved -- all of them are rejected
 * rather than ignored, because a reader that skips an unknown kind on a stream has
 * no way to know whether the codec operation count moved.
 */
public enum class Kind(public val value: Int) {
    REQUEST(0),
    RESPONSE(1),
    DATA(2),
    ERROR(3),
    OPEN(4),
    OPEN_ACK(5),
    CLOSE(6),
    PING(7),
    PONG(8),
    GOAWAY(9),
    REKEY_REQUEST(10),
    ;

    public companion object {
        private val BY_VALUE: Map<Int, Kind> = entries.associateBy { it.value }

        public fun of(value: Int): Kind? = BY_VALUE[value]
    }
}

/**
 * Why a frame was rejected.
 *
 * These names are the `error` tokens in `vectors/envelope.json`, so the vector suite
 * asserts on them directly rather than on message text.
 */
public enum class WireError(public val token: String) {
    SHORT("short"),
    SIGNATURE("signature"),
    LEGACY_SIGNATURE("legacy_signature"),
    VERSION("version"),
    RESERVED("reserved"),
    KIND("kind"),

    /** A frame payload that does not parse: the vectors call every such case "payload". */
    PAYLOAD("payload"),

    /** A DATA plaintext whose bound flags byte disagrees with the envelope. */
    FLAGS_MISMATCH("flags_mismatch"),

    /** Metadata that section 8 refuses. On a request this is 482, pair intact. */
    METADATA("metadata"),

    // The personalization tokens of vectors/personalization.json. Separate values
    // rather than one, because the vectors pin which failure is reported first.
    PERSONALIZATION_PREFIX("prefix"),
    PERSONALIZATION_RANDOMNESS("randomness"),
    PERSONALIZATION_TOO_LARGE("too_large"),
    PERSONALIZATION_CONTROL("control"),

    /** A discovery document this build cannot read. */
    DISCOVERY("discovery"),

    /** A client token of the wrong size or encoding. */
    TOKEN("token"),
    ;

    public companion object {
        public fun of(token: String): WireError? = entries.firstOrNull { it.token == token }
    }
}

public class WireException(
    public val error: WireError,
    message: String,
) : RuntimeException("${error.token}: $message")
