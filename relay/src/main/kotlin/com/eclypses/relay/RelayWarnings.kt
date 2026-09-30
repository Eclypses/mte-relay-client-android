package com.eclypses.relay

/**
 * Switch for the advisory warnings the relay emits when what the server reports disagrees
 * with how the client is configured — a header named in `unencryptedHeaders` that the relay
 * will not forward to the origin, a keepalive interval longer than the server's session
 * timeout, cookies disabled against a relay that forwards them.
 *
 * None of these stop a request. They describe a configuration that will not do what the
 * caller appears to expect, and the caller may have no way to change the server side, so
 * they are silenceable. Each is emitted once per subject per session regardless.
 *
 * On by default: the conditions they describe are silent otherwise, and the failure they
 * lead to — a header that never arrives, pairs that expire between requests — surfaces far
 * from its cause.
 */
object RelayWarnings {

    @Volatile
    @JvmStatic
    var enabled: Boolean = true
}
