package com.eclypses.mte.wire

import java.security.SecureRandom
import java.util.Base64

/**
 * A pair id: 16 random bytes with three spellings.
 *
 * The three are not interchangeable and each is the only accepted form where it
 * appears, which is why this type exists rather than a String passed around:
 *
 *  - **raw** -- what the REQUEST frame carries, at offset 2 of its payload.
 *  - **[text]** -- base64url **without padding**, what the pair request and the
 *    keepalive `drop` list carry. Kyber public keys in the *same* JSON object are
 *    standard base64 **with** padding; mixing the two is silent on both sides until
 *    the decode fails.
 *  - **[hex]** -- 32 lowercase hex, which is how the relay keys its own store, so it
 *    is the spelling that appears in server logs.
 *
 * The previous generation used 32 random alphanumeric characters here. Nothing
 * decoded it; it was an opaque string. Frame v2 puts it in the envelope at a fixed
 * width, so it is bytes now and the length is checked on both sides.
 */
public data class PairId(public val raw: ByteArray) {

    init {
        if (raw.size != RequestFrame.PAIR_ID_SIZE) {
            throw WireException(
                WireError.PAYLOAD,
                "pair id is ${raw.size} bytes, expected ${RequestFrame.PAIR_ID_SIZE}",
            )
        }
        // The server rejects an all-zero id: it is the "no pair" sentinel an OPEN
        // frame carries, so accepting it as a real id would make the two indistinguishable.
        if (raw.all { it.toInt() == 0 }) {
            throw WireException(WireError.PAYLOAD, "pair id must not be all zero")
        }
    }

    /** base64url, no padding. The wire form in every control-plane body. */
    public val text: String get() = ENCODER.encodeToString(raw)

    /** 32 lowercase hex. The relay's own store key, and what its logs print. */
    public val hex: String get() = raw.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean =
        this === other || (other is PairId && raw.contentEquals(other.raw))

    override fun hashCode(): Int = raw.contentHashCode()

    override fun toString(): String = text

    public companion object {
        private val RANDOM = SecureRandom()
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getUrlDecoder()

        public fun random(): PairId {
            val b = ByteArray(RequestFrame.PAIR_ID_SIZE)
            do {
                RANDOM.nextBytes(b)
            } while (b.all { it.toInt() == 0 })
            return PairId(b)
        }

        /** Parses the base64url text form. Padding is tolerated on the way in. */
        public fun parse(text: String): PairId {
            val bytes = try {
                DECODER.decode(text.trimEnd('='))
            } catch (e: IllegalArgumentException) {
                throw WireException(WireError.PAYLOAD, "pair id is not base64url")
            }
            return PairId(bytes)
        }
    }
}
