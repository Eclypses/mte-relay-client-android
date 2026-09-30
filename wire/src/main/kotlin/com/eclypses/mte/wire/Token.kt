package com.eclypses.mte.wire

import java.util.Base64

/**
 * The 40 byte client token of section 10.
 *
 * ```
 * Offset  Size  Field
 * 0       16    Client id
 * 16      8     Issued at, unix seconds, big endian
 * 24      16    First 16 bytes of HMAC-SHA256 under the domain secret over
 *               "mte-relay/token/2" followed by the first 24 bytes
 * ```
 *
 * A client never mints one: the MAC needs the domain secret, which only the relay
 * has. The client receives a token from auth, carries it, reads its issued-at to
 * refresh before expiry, and derives the client id for `X-MTE-Relay-Route`.
 *
 * The text form is base64url with no padding, exactly 54 characters, and is what the
 * auth, pair, keepalive and OPEN bodies carry. The REQUEST frame carries the raw 40
 * bytes instead, which is why both forms live here.
 */
public data class Token(public val raw: ByteArray) {

    init {
        if (raw.size != SIZE) {
            throw WireException(WireError.TOKEN, "token is ${raw.size} bytes, expected $SIZE")
        }
    }

    /** The 16 byte client id. */
    public val clientId: ByteArray get() = raw.copyOfRange(0, 16)

    /**
     * The client id as 32 lowercase hex, which is the form it takes in logs, store
     * keys, `X-MTE-Relay-Route` and the WebSocket `?route=`.
     */
    public val clientIdHex: String
        get() = clientId.joinToString("") { "%02x".format(it) }

    /** Issued at, unix seconds. */
    public val issuedAt: Long
        get() {
            var v = 0L
            for (i in 16 until 24) v = (v shl 8) or (raw[i].toLong() and 0xFF)
            return v
        }

    /** The text form: base64url, no padding, 54 characters. */
    public val text: String get() = ENCODER.encodeToString(raw)

    /**
     * Whether this token should be refreshed now.
     *
     * Section 10: the client refreshes proactively before `tokenMaxAgeSeconds` by
     * calling auth with its current token; the client id and the pool are preserved.
     * Refreshing at [fraction] of the life rather than at expiry means a slow auth
     * call does not strand the pool.
     *
     * Max age is checked at pair, keepalive and the initial OPEN, never on the data
     * path -- which verifies the signature only -- and never on a rekey OPEN. So an
     * expired token does not break requests in flight; it breaks the next pairing.
     */
    public fun needsRefresh(
        maxAgeSeconds: Long,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        fraction: Double = 0.8,
    ): Boolean = nowSeconds - issuedAt >= (maxAgeSeconds * fraction)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Token && raw.contentEquals(other.raw))

    override fun hashCode(): Int = raw.contentHashCode()

    /** Never log a token: it is a bearer credential on the plain hop. */
    override fun toString(): String = "Token(clientId=$clientIdHex, issuedAt=$issuedAt)"

    public companion object {
        public const val SIZE: Int = 40
        public const val TEXT_LENGTH: Int = 54

        /** The HMAC domain separation string. Here for the registry test; we never MAC. */
        public const val DOMAIN: String = "mte-relay/token/2"

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getUrlDecoder()

        public fun parse(text: String): Token {
            if (text.length != TEXT_LENGTH) {
                throw WireException(
                    WireError.TOKEN,
                    "token text is ${text.length} characters, expected $TEXT_LENGTH",
                )
            }
            val bytes = try {
                DECODER.decode(text)
            } catch (e: IllegalArgumentException) {
                throw WireException(WireError.TOKEN, "token is not base64url")
            }
            return Token(bytes)
        }
    }
}

/**
 * The keepalive body of section 11: `{"clientId":"<token>","drop":["<pairId>",...]}`.
 *
 * The server refreshes every pair of the client in one operation and deletes the
 * listed ones. That is the whole change from the previous generation, which sent
 * every pair id and cost two store calls per pair; this is O(1) on the server.
 *
 * A client that still sends `pairIds` gets 200 and no effect -- silently, which is
 * why this builder exists rather than a hand written map at the call site.
 */
public object Keepalive {

    public fun body(token: Token, drop: List<String> = emptyList()): String {
        val members = LinkedHashMap<String, JsonValue>()
        members["clientId"] = JsonString(token.text)
        // Omitted when empty, matching the browser and TypeScript socket clients.
        // The server accepts either form -- its field is `json:"drop,omitempty"` and
        // it reads len() -- so this is consistency rather than correctness, and
        // consistency across implementations is the whole point of the refactor.
        // `drop` is bounded by maxPairsPerClient.
        if (drop.isNotEmpty()) {
            members["drop"] = JsonArray(drop.map { JsonString(it) })
        }
        return Json.canonical(JsonObject(members))
    }
}

/**
 * CLOSE, PING and PONG payloads, section 7.
 *
 * CLOSE is `uint16 code` plus a UTF-8 reason of at most 123 bytes -- the WebSocket
 * close code, or 0 on TCP. PING and PONG are exactly 8 opaque bytes, echoed.
 */
public object Control {

    public const val PING_SIZE: Int = 8
    public const val MAX_CLOSE_REASON_BYTES: Int = 123

    public data class Close(public val code: Int, public val reason: String)

    public fun readClose(p: ByteArray): Close {
        if (p.size < 2) throw WireException(WireError.PAYLOAD, "close under 2 bytes")
        val code = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val reason = String(p, 2, p.size - 2, Charsets.UTF_8)
        if (p.size - 2 > MAX_CLOSE_REASON_BYTES) {
            throw WireException(WireError.PAYLOAD, "close reason over $MAX_CLOSE_REASON_BYTES bytes")
        }
        return Close(code, reason)
    }

    public fun writeClose(code: Int, reason: String): ByteArray {
        val r = reason.toByteArray(Charsets.UTF_8)
        if (r.size > MAX_CLOSE_REASON_BYTES) {
            throw WireException(WireError.PAYLOAD, "close reason over $MAX_CLOSE_REASON_BYTES bytes")
        }
        val out = ByteArray(2 + r.size)
        out[0] = (code ushr 8 and 0xFF).toByte()
        out[1] = (code and 0xFF).toByte()
        r.copyInto(out, 2)
        return out
    }

    public fun readPing(p: ByteArray): ByteArray {
        if (p.size != PING_SIZE) {
            throw WireException(WireError.PAYLOAD, "ping is ${p.size} bytes, expected $PING_SIZE")
        }
        return p.copyOf()
    }

    /**
     * The WebSocket close code for a relay error: `4000 + code - 400`, which puts
     * 470..490 in 4070..4090, inside the RFC 6455 application range. A code outside
     * 400..1399 would land outside that range, so it closes as 1011, internal error.
     */
    public fun closeCodeFor(relayCode: Int): Int =
        if (relayCode in 400..1399) 4000 + relayCode - 400 else 1011
}
