package com.eclypses.mte.wire

/**
 * The ERROR payload of section 5: `uint16 code` then canonical JSON.
 *
 * ```
 * {"reason":"<registry token>","message":"...","cause":{"code":N,"hop":N,"reason":"..."}}
 * ```
 *
 * **It is not encoded.** The operation count rule names REQUEST, RESPONSE, DATA with a
 * length above zero, and encoded OPEN/OPEN_ACK -- "and nothing else". A relay that
 * refuses a frame usually cannot run a codec operation on the pair anyway, so an
 * encoded error would be unreadable exactly when it matters most.
 *
 * On HTTP this payload is the body of every relay-generated response, `code` equals the
 * HTTP status, and the same code and reason repeat in the `X-MTE-Relay-Error` header so
 * a proxy that strips one does not hide the other. A body under a relay status that is
 * not a valid ERROR frame is a replaced page -- a captive portal, a WAF block -- and
 * section 5 is explicit that it is a transport error and never application data,
 * whatever the status or content type says.
 */
public data class ErrorFrame(
    public val code: Int,
    public val reason: String,
    public val message: String = "",
    public val cause: Cause? = null,
) {
    /**
     * The error that came first: a downstream relay's on a MAR hop (`hop` 2), or with
     * `hop` 0 this relay's own earlier error that a later one replaced -- a 481 that a
     * failed drain turned into 477 `eof_before_end`.
     *
     * A receiver acts on the **outer** code and reason alone. The cause is for the log.
     */
    public data class Cause(
        public val code: Int,
        public val hop: Int,
        public val reason: String,
    )

    /** The registry action for this error. Keyed on the reason, never the code alone. */
    public val action: RelayAction get() = RelayRegistry.action(code, reason)

    public fun toRelayError(): RelayError = RelayError(code, reason, message)

    public fun toByteArray(): ByteArray {
        val members = LinkedHashMap<String, JsonValue>()
        members["reason"] = JsonString(reason)
        if (message.isNotEmpty()) members["message"] = JsonString(message)
        cause?.let {
            members["cause"] = JsonObject(
                linkedMapOf(
                    "code" to JsonNumber(it.code.toString()),
                    "hop" to JsonNumber(it.hop.toString()),
                    "reason" to JsonString(it.reason),
                ),
            )
        }
        val json = Json.canonical(JsonObject(members)).toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + json.size)
        out[0] = (code ushr 8 and 0xFF).toByte()
        out[1] = (code and 0xFF).toByte()
        json.copyInto(out, 2)
        return out
    }

    public companion object {
        public const val MAX_MESSAGE_BYTES: Int = 4096

        public fun read(p: ByteArray): ErrorFrame {
            if (p.size < 2) {
                throw WireException(WireError.PAYLOAD, "error payload is ${p.size} bytes")
            }
            val code = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
            val obj = try {
                // Json.parseObject refuses duplicate keys at any level, which the vectors
                // require: two "reason" members would otherwise let a writer show one
                // reason to a strict parser and another to a lenient one.
                Json.parseObject(String(p, 2, p.size - 2, Charsets.UTF_8))
            } catch (e: WireException) {
                throw WireException(WireError.PAYLOAD, "error json: ${e.message}")
            }

            val reason = (obj["reason"] as? JsonString)?.value
                ?: throw WireException(WireError.PAYLOAD, "error json has no \"reason\" string")
            // The shape, not the membership. A receiver accepts a reason newer than its
            // own registry copy and falls back to the code's default action; refusing it
            // would make every new reason a breaking change for every shipped client.
            if (!RelayError.isReasonToken(reason)) {
                throw WireException(WireError.PAYLOAD, "\"$reason\" is not a registry reason token")
            }

            val message = when (val m = obj["message"]) {
                null, is JsonNull -> ""
                is JsonString -> m.value
                else -> throw WireException(WireError.PAYLOAD, "\"message\" is not a string")
            }
            if (message.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                throw WireException(WireError.PAYLOAD, "\"message\" over $MAX_MESSAGE_BYTES bytes")
            }

            val cause = (obj["cause"] as? JsonObject)?.let { c ->
                val causeReason = (c["reason"] as? JsonString)?.value
                    ?: throw WireException(WireError.PAYLOAD, "\"cause\" has no \"reason\"")
                if (!RelayError.isReasonToken(causeReason)) {
                    throw WireException(WireError.PAYLOAD, "cause reason \"$causeReason\" is not a token")
                }
                val causeCode = (c["code"] as? JsonNumber)?.raw?.toIntOrNull()
                    ?: throw WireException(WireError.PAYLOAD, "\"cause\" has no integer \"code\"")
                if (causeCode !in 0..0xFFFF) {
                    throw WireException(WireError.PAYLOAD, "cause code $causeCode out of range")
                }
                val hop = (c["hop"] as? JsonNumber)?.raw?.toIntOrNull() ?: 0
                Cause(causeCode, hop, causeReason)
            }

            // Unknown members are ignored rather than refused: the object is the
            // protocol's extension point on this frame as on every other.
            return ErrorFrame(code, reason, message, cause)
        }
    }
}
