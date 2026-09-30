package com.eclypses.mte.wire

/**
 * The metadata object of sections 8.1 to 8.3.
 *
 * [canonicalize] takes whatever a caller wrote and returns the exact bytes that go
 * into one codec operation: validated against the member rules, stripped of what
 * does not travel, and written in canonical form.
 *
 * It is one function rather than a builder because the output has to be byte exact
 * and there is only one correct answer for a given input. The vectors assert on
 * that string.
 */
public object Metadata {

    public const val FRAME_VERSION: Int = 2

    /**
     * Header names a writer strips and a reader drops, section 8.2. These are hop
     * by hop or are re derived by the transport that carries the frame, so sending
     * them would let a caller contradict the relay's own framing.
     */
    public val STRIPPED_HEADERS: Set<String> = setOf(
        "content-length",
        "transfer-encoding",
        "connection",
        "host",
        "keep-alive",
        "proxy-connection",
        "te",
        "trailer",
        "upgrade",
        "proxy-authenticate",
        "proxy-authorization",
    )

    private const val MAX_HEADER_NAMES = 256
    private const val MAX_VALUES_PER_NAME = 64
    private const val MAX_VALUE_BYTES = 8 * 1024
    private const val MAX_HEADER_NAME_BYTES = 256
    private const val MAX_METHOD_BYTES = 32
    private const val MAX_TARGET_BYTES = 256

    /** RFC 9110 token: the characters a method name may use. */
    private val TOKEN = Regex("""[!#$%&'*+\-.^_`|~0-9A-Za-z]+""")

    /** Header names are lowercase ASCII tokens on the wire, in both directions. */
    private val HEADER_NAME = Regex("""[!#$%&'*+\-.^_`|~0-9a-z]+""")

    /**
     * Validates and canonicalizes [text].
     *
     * @throws WireException [WireError.METADATA] on anything section 8 refuses. On a
     * request that is 482 with the decoder state saved: the decoder already advanced
     * to produce this text, so the pair survives and only the message is refused.
     */
    public fun canonicalize(text: String): String =
        Json.canonical(validate(Json.parseObject(text)))

    /** The same, returning the object, for callers that want to read a member. */
    public fun validate(obj: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonValue>()

        // `v` is required everywhere and is compared to the envelope version. It is
        // checked first because every other member is only meaningful under it.
        val v = obj["v"] ?: throw WireException(WireError.METADATA, "\"v\" is required")
        if (v !is JsonNumber || !v.isInteger) {
            throw WireException(WireError.METADATA, "\"v\" must be an integer")
        }
        if (v.raw != FRAME_VERSION.toString()) {
            throw WireException(WireError.METADATA, "\"v\" is ${v.raw}, expected $FRAME_VERSION")
        }
        out["v"] = v

        for ((key, value) in obj.members) {
            when {
                key == "v" -> Unit // already taken

                key == "method" -> {
                    val s = string(value, "method")
                    if (utf8Len(s) > MAX_METHOD_BYTES || !TOKEN.matches(s)) {
                        throw WireException(WireError.METADATA, "\"method\" is not an RFC 9110 token")
                    }
                    out[key] = JsonString(s)
                }

                key == "status" -> {
                    val n = integer(value, "status")
                    val code = n.raw.toIntOrNull()
                        ?: throw WireException(WireError.METADATA, "\"status\" out of range")
                    if (code !in 100..599) {
                        throw WireException(WireError.METADATA, "\"status\" $code out of range")
                    }
                    out[key] = n
                }

                key == "path" -> {
                    // The reader's rule, which is deliberately wider than the writer's:
                    // a leading slash is tolerated here and never normalised away, and a
                    // traversal passes through because `Path.toTarget` is what refuses it.
                    // A writer calls `Path.write` before it gets here.
                    val s = string(value, "path")
                    Path.read(s)
                    out[key] = JsonString(s)
                }

                key == "target" -> {
                    val s = string(value, "target")
                    if (utf8Len(s) > MAX_TARGET_BYTES) {
                        throw WireException(WireError.METADATA, "\"target\" over $MAX_TARGET_BYTES bytes")
                    }
                    out[key] = JsonString(s)
                }

                key == "port" -> {
                    val n = integer(value, "port")
                    val port = n.raw.toIntOrNull()
                        ?: throw WireException(WireError.METADATA, "\"port\" out of range")
                    if (port !in 1..65535) {
                        throw WireException(WireError.METADATA, "\"port\" $port out of range")
                    }
                    out[key] = n
                }

                key == "id" -> out[key] = integer(value, "id")

                key == "headers" -> headers(value)?.let { out[key] = it }

                // Vendor members travel through unchanged, but still canonicalized:
                // the bytes are part of one codec operation, so a nested object that
                // kept its input order would make the same metadata encode two ways.
                key.startsWith("x-") -> out[key] = value

                // Experimental members are ignored, and unknown members are dropped
                // rather than forwarded. A forwarded unknown member is a channel the
                // protocol does not describe and neither end validates.
                key.startsWith("_") -> Unit
                else -> Unit
            }
        }
        return JsonObject(out)
    }

    /**
     * Section 8.2. A value is a string, or an array of strings only when the name
     * repeats. The array is what keeps every `set-cookie` through a MAR chain, which
     * a `map[string]string` could not.
     *
     * Returns null when the member should be omitted entirely: absent, null, or
     * empty after stripping. An empty `headers` object is not written.
     */
    private fun headers(value: JsonValue): JsonValue? {
        if (value is JsonNull) return null
        if (value !is JsonObject) {
            throw WireException(WireError.METADATA, "\"headers\" must be an object")
        }

        val out = LinkedHashMap<String, JsonValue>()
        for ((name, v) in value.members) {
            if (utf8Len(name) > MAX_HEADER_NAME_BYTES || !HEADER_NAME.matches(name)) {
                // Uppercase fails here too: the rule is lowercase ASCII tokens on the
                // wire in both directions, so `Accept` is refused rather than folded.
                throw WireException(WireError.METADATA, "header name \"$name\" is not a lowercase token")
            }
            if (name in STRIPPED_HEADERS) continue

            when (v) {
                is JsonString -> out[name] = JsonString(headerValue(name, v.value))
                is JsonArray -> {
                    if (v.items.isEmpty()) {
                        throw WireException(WireError.METADATA, "header \"$name\" has an empty array")
                    }
                    if (v.items.size > MAX_VALUES_PER_NAME) {
                        throw WireException(WireError.METADATA, "header \"$name\" over $MAX_VALUES_PER_NAME values")
                    }
                    out[name] = JsonArray(
                        v.items.map {
                            val s = it as? JsonString
                                ?: throw WireException(
                                    WireError.METADATA,
                                    "header \"$name\" has a non string value",
                                )
                            JsonString(headerValue(name, s.value))
                        },
                    )
                }
                else -> throw WireException(
                    WireError.METADATA,
                    "header \"$name\" must be a string or an array of strings",
                )
            }
        }

        if (out.size > MAX_HEADER_NAMES) {
            throw WireException(WireError.METADATA, "over $MAX_HEADER_NAMES header names")
        }
        return if (out.isEmpty()) null else JsonObject(out)
    }

    private fun headerValue(name: String, value: String): String {
        if (utf8Len(value) > MAX_VALUE_BYTES) {
            throw WireException(WireError.METADATA, "header \"$name\" value over $MAX_VALUE_BYTES bytes")
        }
        // No CR, LF or NUL. A newline inside a value is header injection on any hop
        // that rebuilds an HTTP message from this object -- the base64 line break the
        // vectors trap is exactly that, arriving by accident rather than by attack.
        for (c in value) {
            if (c == '\r' || c == '\n' || c.code == 0) {
                throw WireException(WireError.METADATA, "header \"$name\" value contains CR, LF or NUL")
            }
        }
        return value
    }

    private fun string(v: JsonValue, member: String): String =
        (v as? JsonString)?.value
            ?: throw WireException(WireError.METADATA, "\"$member\" must be a string")

    private fun integer(v: JsonValue, member: String): JsonNumber {
        val n = v as? JsonNumber
            ?: throw WireException(WireError.METADATA, "\"$member\" must be a number")
        if (!n.isInteger) throw WireException(WireError.METADATA, "\"$member\" must be an integer")
        return n
    }

    private fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
