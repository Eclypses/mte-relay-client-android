package com.eclypses.mte.wire

import java.security.SecureRandom

/**
 * The personalization grammar of section 11.
 *
 * ```
 * personalization = "mte-relay/" version "/" transport "/" randomness [ "/" host ]
 * version         = the frame version in decimal, derived from the envelope
 * transport       = "http" | "ws" | "tcp"
 * randomness      = 22 or more characters of [A-Za-z0-9_-], client chosen
 * host            = the relay origin host as the client sees it
 * ```
 *
 * One grammar on every platform, because "two implementations that agree only on
 * the prefix will disagree on everything else". The server rejects a prefix that
 * does not match the negotiated version and the transport it arrived on, and checks
 * **both** strings of a pair, since the server's encoder takes the client's decoder
 * string.
 */
public object Personalization {

    public const val MAX_BYTES: Int = 1024
    public const val RANDOMNESS_MIN: Int = 22

    public enum class Transport(public val token: String) {
        HTTP("http"),
        WS("ws"),
        TCP("tcp"),
        ;

        /** Includes the trailing slash: `mte-relay/2/ws/`. */
        public val prefix: String get() = "mte-relay/${Metadata.FRAME_VERSION}/$token/"
    }

    private val RANDOM = SecureRandom()

    /** The base64url alphabet the grammar names. */
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

    /**
     * Mints a string for [transport], optionally naming [host].
     *
     * The result always satisfies [validate] for its own transport and fails it for
     * the other two. That round trip is the property the prefix exists for, and the
     * one a generator can get wrong without any single vector catching it.
     */
    public fun mint(transport: Transport, host: String? = null, randomnessChars: Int = 32): String {
        require(randomnessChars >= RANDOMNESS_MIN) { "randomness under the floor" }
        val sb = StringBuilder(transport.prefix)
        repeat(randomnessChars) { sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]) }
        if (host != null) sb.append('/').append(host)

        val minted = sb.toString()
        // A host long enough to push the string over the cap is the caller's, not
        // ours to silently truncate: truncation by UTF-16 units is one of the two
        // defects this grammar exists to close.
        if (utf8Len(minted) > MAX_BYTES) {
            throw WireException(
                WireError.PERSONALIZATION_TOO_LARGE,
                "minted string is ${utf8Len(minted)} bytes, over $MAX_BYTES",
            )
        }
        return minted
    }

    /**
     * Validates [value] as offered on [transport].
     *
     * The order of these checks is pinned by the vectors, not chosen: a case names
     * the first failure and not every failure, so a reordering changes the reported
     * reason for strings that break more than one rule. Cap, then prefix, then
     * randomness, then control characters.
     */
    public fun validate(value: String, transport: Transport) {
        // 1. The cap, in UTF-8 bytes. Never UTF-16 code units: the vectors carry two
        //    strings of 538 code units and 1038 bytes precisely to catch a length
        //    check that counts the wrong unit, which is the second defect closed here.
        if (utf8Len(value) > MAX_BYTES) {
            throw WireException(
                WireError.PERSONALIZATION_TOO_LARGE,
                "${utf8Len(value)} bytes, over $MAX_BYTES",
            )
        }

        // 2. The prefix for *this* transport. "mte-relay/2/wss/" must not match ws,
        //    which is why the prefix carries its trailing slash.
        if (!value.startsWith(transport.prefix)) {
            throw WireException(
                WireError.PERSONALIZATION_PREFIX,
                "does not begin with \"${transport.prefix}\"",
            )
        }

        // 3. The randomness floor, measured on the randomness **segment alone**,
        //    which ends at the first slash after the prefix. Measuring the whole
        //    string is the defect both earlier implementations shipped: it lets one
        //    random character pass behind a long host.
        val rest = value.substring(transport.prefix.length)
        val end = rest.indexOf('/')
        val randomness = if (end < 0) rest else rest.substring(0, end)
        // Measured in bytes, and the alphabet is not checked. The grammar says 22
        // characters of [A-Za-z0-9_-]; both shipped implementations count bytes and
        // check no alphabet, so eleven two-byte characters clear the floor. The
        // vectors pin that behaviour rather than the grammar, and they are
        // normative. `personalization.json` carries it as a known gap: closing it
        // is a coordinated change on every implementation at once, not a unilateral
        // strictness we would fail to pair over.
        if (utf8Len(randomness) < RANDOMNESS_MIN) {
            throw WireException(
                WireError.PERSONALIZATION_RANDOMNESS,
                "randomness segment is ${utf8Len(randomness)} bytes, under $RANDOMNESS_MIN",
            )
        }

        // 4. Control characters anywhere in the string: under 0x20, or 0x7f.
        for (c in value) {
            if (c.code < 0x20 || c.code == 0x7F) {
                throw WireException(
                    WireError.PERSONALIZATION_CONTROL,
                    "control character U+%04X".format(c.code),
                )
            }
        }
    }

    private fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
