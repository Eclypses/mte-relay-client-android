package com.eclypses.relay.streaming

/**
 * A validated `unencryptedHeaders` list.
 *
 * Mirrors `UnencryptedHeaderPolicy` in the iOS and browser SDKs so the three behave the
 * same and read the same. [all] is the `["*"]` wildcard; [names] is empty when [all] is
 * true.
 */
data class UnencryptedHeaderPolicy(
    val all: Boolean,
    val names: Set<String>,
) {
    companion object {
        @JvmField
        val ENCRYPT_EVERYTHING = UnencryptedHeaderPolicy(all = false, names = emptySet())
    }
}

/**
 * The split of a caller's headers into the two channels available to a relayed request.
 *
 * [encrypted] travels inside the encrypted request metadata; the relay server decrypts it
 * and forwards those headers to the origin. [plaintext] travels as ordinary headers on the
 * client to relay hop, where infrastructure between the two (an API gateway, load
 * balancer, WAF, tracing collector) can read them.
 */
data class RelayHeaderPartition(
    val encrypted: Map<String, String>,
    val plaintext: Map<String, String>,
)

object RelayHeaderPolicy {

    /**
     * Names that are always encrypted and may never be listed in `unencryptedHeaders`.
     *
     * `content-type`, `x-mte-relay-route` and `x-mte-relay-client` are the three the v5
     * client guide reserves. `content-length` and `host` are a deliberate addition on this
     * platform: the guide is written against a browser client, where the Fetch standard
     * forbids a caller from setting either, so the browser SDK never has to. OkHttp will
     * set both — a plain `Host` would aim the relay hop at the wrong vhost, and a plain
     * `Content-Length` would describe the caller's body rather than the frame.
     *
     * This means a list containing `host` or `content-length` throws here and is accepted
     * by the browser SDK. That divergence is intended and was confirmed with the relay
     * team: a mobile runtime can reach these names, so a mobile client reserves them.
     */
    @JvmField
    val RESERVED: Set<String> = setOf(
        "content-type",
        "content-length",
        "host",
        // The relay's sticky-session routing token. The server routes on whatever value
        // arrives, so a caller must not be able to put one on the wire — the SDK owns this
        // header and sets it itself.
        "x-mte-relay-route",
        // Marks a request as coming from a relay acting as a client, and a relay that sees
        // it skips the cookie merge for that request. A caller who set it would silently
        // lose cookie forwarding for their own traffic. Only a relay ever sends it.
        "x-mte-relay-client",
    )

    /**
     * Connection-scoped headers (RFC 7230 section 6.1). They describe a single transport
     * hop rather than the message, so they are never copied from the caller's request onto
     * the relay hop: the caller's `Transfer-Encoding` says nothing about the frame we are
     * actually sending. Every proxy strips and regenerates these, so no application
     * semantics are lost.
     */
    @JvmField
    val CONNECTION_SCOPED: Set<String> = setOf(
        "connection",
        "proxy-connection",
        "keep-alive",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "proxy-authenticate",
        "proxy-authorization",
    )

    /** RFC 7230 token characters, minus uppercase: names are lowercased before checking. */
    private val TOKEN_CHARS =
        "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyz".toSet()

    /**
     * Validates and normalises a caller's `unencryptedHeaders` list.
     *
     * Every failure throws rather than being silently dropped. The list is a security
     * instruction: a caller who misspells a name and gets no signal believes a header is
     * exposed for a gateway to read when it is not, and learns otherwise in production.
     *
     * Rules are applied in the same order as the other SDKs, so the same input reaches the
     * same verdict everywhere except on [RESERVED]: `host` and `content-length` are
     * rejected here and accepted by the browser SDK, for the reason given there.
     *
     * @throws IllegalArgumentException if the list cannot be honoured.
     */
    @JvmStatic
    @JvmOverloads
    fun resolve(
        input: Array<String>?,
        fieldName: String = "unencryptedHeaders",
    ): UnencryptedHeaderPolicy {
        if (input == null) return UnencryptedHeaderPolicy.ENCRYPT_EVERYTHING

        val normalized = input.map { it.trim().lowercase() }

        if (normalized.any { it.isEmpty() }) {
            throw IllegalArgumentException("$fieldName must not contain empty names.")
        }

        if (normalized.contains("*")) {
            require(normalized.toSet().size == 1) {
                "$fieldName: \"*\" must be the only entry."
            }
            return UnencryptedHeaderPolicy(all = true, names = emptySet())
        }

        for (name in normalized) {
            require(name.all { TOKEN_CHARS.contains(it) }) {
                "$fieldName contains an invalid header name: $name."
            }
            require(!RESERVED.contains(name)) {
                "$fieldName must not list $name; it is always encrypted."
            }
        }

        return UnencryptedHeaderPolicy(all = false, names = normalized.toSet())
    }

    /**
     * Names listed inside a `Connection` header value are themselves connection-scoped for
     * that message (RFC 7230 section 6.1), so the set is per-request rather than fixed.
     */
    @JvmStatic
    fun connectionScopedNames(headers: Map<String, String>): Set<String> {
        val names = CONNECTION_SCOPED.toMutableSet()
        headers.entries
            .filter { it.key.lowercase() == "connection" }
            .forEach { entry ->
                entry.value.split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .forEach { names.add(it) }
            }
        return names
    }

    /**
     * Splits [headers] according to an already-validated [policy].
     *
     * Everything is encrypted by default. A header is sent in the clear only when the
     * caller named it, so a header nobody thought about is protected rather than exposed —
     * the failure mode of the whitelist this replaced.
     */
    @JvmStatic
    fun split(
        headers: Map<String, String>,
        policy: UnencryptedHeaderPolicy,
    ): RelayHeaderPartition {
        val hopByHop = connectionScopedNames(headers)

        val encrypted = linkedMapOf<String, String>()
        val plaintext = linkedMapOf<String, String>()

        for ((name, value) in headers) {
            val lowercased = name.lowercase()

            // Not the caller's to send on this hop, and not meaningful to the origin.
            if (hopByHop.contains(lowercased)) continue

            // The origin's body is rebuilt by the relay from the decrypted frame, so its
            // length is that body's length, not the caller's.
            if (lowercased == "content-length") continue

            // The wildcard exposes everything eligible. It is not a way to turn encryption
            // off: a reserved name stays encrypted under it.
            if ((policy.all || policy.names.contains(lowercased)) && !RESERVED.contains(lowercased)) {
                plaintext[name] = value
            } else {
                encrypted[name] = value
            }
        }

        return RelayHeaderPartition(encrypted = encrypted, plaintext = plaintext)
    }

    /**
     * Merges the plaintext half into the outbound relay request's headers without
     * disturbing the ones the frame owns.
     *
     * This re-checks both reserved sets rather than trusting its input. [split] has already
     * removed them, so the guard is redundant today — but a future caller that assembles a
     * plaintext map without going through [split] would otherwise put connection-scoped
     * headers straight onto the wire with nothing to catch it.
     */
    @JvmStatic
    fun mergePlaintext(
        frameHeaders: Map<String, String>,
        plaintext: Map<String, String>,
    ): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        for ((name, value) in plaintext) {
            val lowercased = name.lowercase()
            if (RESERVED.contains(lowercased) || CONNECTION_SCOPED.contains(lowercased)) continue
            merged[name] = value
        }
        // The frame's own headers are applied last so they always win.
        merged.putAll(frameHeaders)
        return merged
    }
}
