package com.eclypses.mte.wire

/**
 * What a client does about a relay error.
 *
 * Section 5: the protocol "defines behavior by reason, not code". Five codes carry
 * several reasons with different actions -- `477 invalid_frame` means surface with the pair
 * intact while `477 eof_before_end` means the server poisoned the pair and the
 * client must replace it. Same status, opposite action. Keying on the code alone is
 * the bug this enum exists to make impossible.
 */
public enum class RelayAction(public val token: String) {
    /** Hand it to the caller. The pair is intact. */
    SURFACE("surface"),

    /** Surface, pair intact, and log it as a security event. */
    SURFACE_SECURITY("surface_security"),

    /** This one pair is unusable. Replace it; the session survives. */
    REPLACE_PAIR("replace_pair"),

    /** Re-authenticate over TLS and rebuild. At most one per 30 s. */
    FULL_REPAIR("full_repair"),

    /** Back off with jitter and honour `Retry-After`. Never replace the pair. */
    BACK_OFF("back_off"),

    /** Retry once after a short delay, then replace the pair. */
    RETRY_ONCE_THEN_REPLACE("retry_once_then_replace"),

    /** Back off with jitter up to `ownerSeconds`, then re-auth for a new client id. */
    BACK_OFF_NOT_OWNER("back_off_not_owner"),

    /** Close and open again with a new OPEN. */
    RECONNECT("reconnect"),

    /** Nothing executed: retry the same request after `Retry-After`, pair intact. */
    RETRY_SAME("retry_same"),

    /** Do not retry. Surface to the operator; a human has to act. */
    STOP("stop"),
    ;

    public companion object {
        public fun of(token: String): RelayAction? = entries.firstOrNull { it.token == token }
    }
}

/**
 * One relay error: the code, the registry reason, and the action they imply.
 *
 * Errors arrive two ways and both carry the same pair, so a proxy that strips one
 * does not hide the other: an ERROR frame (kind 3) as the body, and the
 * `X-MTE-Relay-Error: <code> <reason>` response header.
 */
public data class RelayError(
    public val code: Int,
    public val reason: String,
    public val message: String = "",
) {
    /**
     * The action for this code and reason.
     *
     * An unregistered reason under a registered code takes the code's default, which
     * is what stops a relay adding a reason from turning a shipped client into a
     * pair-replacement storm. An unregistered *code* has no default and is surfaced.
     */
    public val action: RelayAction
        get() = RelayRegistry.action(code, reason)

    public companion object {
        public const val HEADER: String = "X-MTE-Relay-Error"

        /** Parses `X-MTE-Relay-Error: <code> <reason>`. */
        public fun parseHeader(value: String): RelayError? {
            val sp = value.indexOf(' ')
            if (sp <= 0) return null
            val code = value.substring(0, sp).toIntOrNull() ?: return null
            val reason = value.substring(sp + 1).trim()
            if (!isReasonToken(reason)) return null
            return RelayError(code, reason)
        }

        /**
         * The registry reason shape: lowercase letters, digits and underscores,
         * starting with a letter, at most 64 bytes.
         *
         * A receiver accepts a reason of this shape even when it is newer than its
         * own registry copy -- that is what forward compatibility means here, and
         * why the shape is checked rather than the membership.
         */
        public fun isReasonToken(s: String): Boolean {
            if (s.isEmpty() || s.length > 64) return false
            if (s[0] !in 'a'..'z') return false
            return s.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
        }
    }
}

/**
 * The code and reason tables of `spec/registry.md`.
 *
 * These are written out here rather than parsed at runtime so the library carries no
 * data file, but they are **measured against `vectors/registry.json` by the test
 * suite in both directions** -- exactly as `wire/registryvectors_test.go` and
 * `mte-relay-browser/src/protocol/registryVectors.test.ts` do on the other two
 * implementations. A row added on one side and not here fails our build rather than
 * waiting for someone to compare two documents.
 */
public object RelayRegistry {

    /** Reason to action, per code. */
    private val REASONS: Map<Int, Map<String, RelayAction>> = mapOf(
        470 to mapOf("pair_not_found" to RelayAction.REPLACE_PAIR),
        471 to mapOf("decoder_restore_failed" to RelayAction.REPLACE_PAIR),
        472 to mapOf("encoder_restore_failed" to RelayAction.REPLACE_PAIR),
        473 to mapOf("decode_failed" to RelayAction.REPLACE_PAIR),
        474 to mapOf("encode_failed" to RelayAction.REPLACE_PAIR),
        475 to mapOf(
            "invalid_client_id" to RelayAction.FULL_REPAIR,
            "invalid_token" to RelayAction.FULL_REPAIR,
            // The pre-shared key was never issued or was revoked. Retrying auth
            // cannot fix it and only burns rate limit; a human has to act.
            "unknown_key" to RelayAction.STOP,
        ),
        476 to mapOf("pool_exhausted" to RelayAction.BACK_OFF),
        477 to mapOf(
            "invalid_frame" to RelayAction.SURFACE,
            // The server saw the body end before END and poisoned the pair.
            "eof_before_end" to RelayAction.REPLACE_PAIR,
        ),
        478 to mapOf("frame_version_unsupported" to RelayAction.SURFACE),
        479 to mapOf("replay_rejected" to RelayAction.SURFACE_SECURITY),
        480 to mapOf("no_domain_for_host" to RelayAction.SURFACE),
        481 to mapOf(
            "frame_too_large" to RelayAction.SURFACE,
            "body_too_large" to RelayAction.SURFACE,
            // The reassembled WebSocket message bound: on a connection, close and
            // reopen rather than surface.
            "message_too_large" to RelayAction.REPLACE_PAIR,
        ),
        482 to mapOf("metadata_invalid" to RelayAction.SURFACE),
        483 to mapOf("header_mismatch" to RelayAction.SURFACE_SECURITY),
        484 to mapOf(
            "target_forbidden" to RelayAction.STOP,
            "port_out_of_range" to RelayAction.SURFACE,
            "authz_revoked" to RelayAction.STOP,
        ),
        485 to mapOf("upstream_capacity" to RelayAction.BACK_OFF),
        486 to mapOf(
            "pair_busy" to RelayAction.RETRY_ONCE_THEN_REPLACE,
            "not_owner" to RelayAction.BACK_OFF_NOT_OWNER,
        ),
        487 to mapOf("rekey_overdue" to RelayAction.RECONNECT),
        488 to mapOf(
            "connection_limit" to RelayAction.BACK_OFF,
            "rate_limited" to RelayAction.BACK_OFF,
        ),
        489 to mapOf("target_reset" to RelayAction.SURFACE),
        490 to mapOf(
            "invalid_request" to RelayAction.SURFACE,
            "pair_exists" to RelayAction.SURFACE,
            // Both are "surface" in the registry. Section 5's prose describes what
            // an operator should then do -- flush dropped pairs, fix the profile --
            // but the action token is what the client executes, and it is surface for
            // all four of 490's reasons. Reading the prose into a token is how this
            // table was wrong until the registry test caught it.
            "pair_limit" to RelayAction.SURFACE,
            "profile_mismatch" to RelayAction.SURFACE,
        ),
        500 to mapOf(
            "internal" to RelayAction.SURFACE,
            "configuration" to RelayAction.SURFACE,
        ),
        502 to mapOf(
            "upstream_connect_failed" to RelayAction.SURFACE,
            "upstream_response_failed" to RelayAction.SURFACE,
        ),
        503 to mapOf(
            // Nothing executed: the same request is safe to retry.
            "state_store_unavailable" to RelayAction.RETRY_SAME,
            // Executed, and the pair may be desynchronized. Replace, do not retry.
            "state_store_write_failed" to RelayAction.REPLACE_PAIR,
        ),
    )

    /** The action an unregistered reason takes under a registered code. */
    private val DEFAULTS: Map<Int, RelayAction> = mapOf(
        470 to RelayAction.REPLACE_PAIR,
        471 to RelayAction.REPLACE_PAIR,
        472 to RelayAction.REPLACE_PAIR,
        473 to RelayAction.REPLACE_PAIR,
        474 to RelayAction.REPLACE_PAIR,
        475 to RelayAction.FULL_REPAIR,
        476 to RelayAction.BACK_OFF,
        477 to RelayAction.SURFACE,
        478 to RelayAction.SURFACE,
        479 to RelayAction.SURFACE_SECURITY,
        480 to RelayAction.SURFACE,
        481 to RelayAction.SURFACE,
        482 to RelayAction.SURFACE,
        483 to RelayAction.SURFACE_SECURITY,
        484 to RelayAction.STOP,
        485 to RelayAction.BACK_OFF,
        486 to RelayAction.RETRY_ONCE_THEN_REPLACE,
        487 to RelayAction.RECONNECT,
        488 to RelayAction.BACK_OFF,
        489 to RelayAction.SURFACE,
        490 to RelayAction.SURFACE,
        500 to RelayAction.SURFACE,
        502 to RelayAction.SURFACE,
        // Nothing executed is the safe assumption for an unknown 503 reason: the
        // registered split is state_store_unavailable (retry the same request) versus
        // state_store_write_failed (executed, pair may be desynchronized). Defaulting
        // to surface would drop a request that was never run.
        503 to RelayAction.RETRY_SAME,
    )

    public val codes: Set<Int> get() = REASONS.keys

    public fun reasons(code: Int): Set<String> = REASONS[code]?.keys ?: emptySet()

    public fun action(code: Int, reason: String): RelayAction =
        REASONS[code]?.get(reason)
            ?: DEFAULTS[code]
            // An unregistered code. Nothing is known about it, so the pair is left
            // alone and the caller decides -- never a pair replacement on a guess.
            ?: RelayAction.SURFACE

    public fun defaultAction(code: Int): RelayAction? = DEFAULTS[code]
}
