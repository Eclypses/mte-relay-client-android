package com.eclypses.relay.transport

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * In-memory cookie store shared by every call the relay makes: auth, pair, keepalive and
 * the frame POSTs.
 *
 * Two things depend on it. A layer 7 load balancer in front of a multi-replica relay pins
 * a client with an affinity cookie (`AWSALB`, `ApplicationGatewayAffinity`) set on the
 * auth response; a client that does not return it can land on a different replica for
 * every request and the relay has to fetch pair state from Redis or fail with a 562.
 * Separately, an origin that authenticates with a session cookie relies on the relay
 * copying the plain-hop `Cookie` header upstream, which only works if the cookie is on
 * the hop in the first place.
 *
 * Held in memory for the life of the process and never persisted: a relay session does
 * not outlive the process either, and writing the affinity and session cookies of an
 * authenticated origin to disk is a liability the transport has no reason to take on.
 *
 * OkHttp's [Cookie.matches] applies the RFC 6265 domain, path and secure rules, so a flat
 * store keyed by identity is correctly scoped without a per-host index.
 */
class RelayCookieJar : CookieJar {

    /**
     * RFC 6265 §5.3 cookie identity. Two cookies replace one another when all three match,
     * which is why this is not [Cookie] itself — that also compares value and expiry, so a
     * refreshed session cookie would accumulate beside the one it replaces rather than
     * evicting it.
     */
    private data class Key(val name: String, val domain: String, val path: String)

    private val store = linkedMapOf<Key, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            val key = Key(cookie.name, cookie.domain, cookie.path)
            // A server expires a cookie by re-sending it with a past date, so an expired
            // arrival is a deletion instruction rather than something to store and skip.
            if (cookie.expiresAt <= now) {
                store.remove(key)
            } else {
                store[key] = cookie
            }
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val matched = mutableListOf<Cookie>()
        val expired = mutableListOf<Key>()

        for ((key, cookie) in store) {
            if (cookie.expiresAt <= now) {
                expired += key
            } else if (cookie.matches(url)) {
                matched += cookie
            }
        }

        expired.forEach { store.remove(it) }
        return matched
    }

    /** Drops every stored cookie. The next relay call starts with no cookie header. */
    @Synchronized
    fun clear() {
        store.clear()
    }

    /** Number of unexpired cookies currently held. Diagnostics only. */
    @Synchronized
    fun size(): Int {
        val now = System.currentTimeMillis()
        return store.values.count { it.expiresAt > now }
    }
}
