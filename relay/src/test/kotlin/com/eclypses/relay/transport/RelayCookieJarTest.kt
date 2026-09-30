package com.eclypses.relay.transport

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The jar is what keeps a load balancer's affinity cookie on the wire across auth, pair,
 * keepalive and every frame POST. These cases pin the storage rules; the scoping rules
 * themselves belong to OkHttp's [Cookie.matches] and are exercised here only where the
 * relay depends on them.
 */
class RelayCookieJarTest {

    private val relay = "https://relay.example.com/".toHttpUrl()
    private val other = "https://other.example.org/".toHttpUrl()

    private fun cookie(
        name: String,
        value: String,
        domain: String = "relay.example.com",
        path: String = "/",
        expiresAt: Long = Long.MAX_VALUE,
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain(domain)
        .path(path)
        .apply { if (expiresAt != Long.MAX_VALUE) expiresAt(expiresAt) }
        .build()

    @Test
    fun `a cookie set on one response is returned on the next request`() {
        val jar = RelayCookieJar()

        jar.saveFromResponse(relay, listOf(cookie("AWSALB", "abc")))

        val sent = jar.loadForRequest(relay)
        assertEquals(1, sent.size)
        assertEquals("AWSALB", sent[0].name)
        assertEquals("abc", sent[0].value)
    }

    @Test
    fun `a cookie is not sent to a host it was not set for`() {
        val jar = RelayCookieJar()

        jar.saveFromResponse(relay, listOf(cookie("session", "xyz")))

        assertTrue(jar.loadForRequest(other).isEmpty(), "cookies must not cross hosts")
    }

    @Test
    fun `re-setting a cookie replaces it rather than accumulating`() {
        val jar = RelayCookieJar()

        jar.saveFromResponse(relay, listOf(cookie("session", "first")))
        jar.saveFromResponse(relay, listOf(cookie("session", "second")))

        val sent = jar.loadForRequest(relay)
        assertEquals(1, sent.size, "same name/domain/path is one cookie")
        assertEquals("second", sent[0].value)
    }

    @Test
    fun `the same name on a different path is a separate cookie`() {
        val jar = RelayCookieJar()

        jar.saveFromResponse(relay, listOf(cookie("token", "root", path = "/")))
        jar.saveFromResponse(
            "https://relay.example.com/api/x".toHttpUrl(),
            listOf(cookie("token", "api", path = "/api")),
        )

        val sent = jar.loadForRequest("https://relay.example.com/api/x".toHttpUrl())
        assertEquals(2, sent.size, "RFC 6265 identity is name + domain + path")
        assertEquals(setOf("root", "api"), sent.map { it.value }.toSet())
    }

    @Test
    fun `an expired cookie is neither stored nor sent`() {
        val jar = RelayCookieJar()
        val past = System.currentTimeMillis() - 60_000

        jar.saveFromResponse(relay, listOf(cookie("gone", "1", expiresAt = past)))

        assertTrue(jar.loadForRequest(relay).isEmpty())
        assertEquals(0, jar.size())
    }

    @Test
    fun `re-sending a cookie with a past expiry deletes the stored one`() {
        val jar = RelayCookieJar()
        jar.saveFromResponse(relay, listOf(cookie("session", "live")))
        assertEquals(1, jar.loadForRequest(relay).size)

        // How a server actually deletes a cookie: the same name, already expired.
        jar.saveFromResponse(
            relay,
            listOf(cookie("session", "", expiresAt = System.currentTimeMillis() - 1000)),
        )

        assertTrue(jar.loadForRequest(relay).isEmpty(), "an expired re-set must evict")
    }

    @Test
    fun `a cookie that expires between calls stops being sent`() {
        val jar = RelayCookieJar()
        val soon = System.currentTimeMillis() + 40

        jar.saveFromResponse(relay, listOf(cookie("brief", "1", expiresAt = soon)))
        assertEquals(1, jar.loadForRequest(relay).size, "still valid before the deadline")

        Thread.sleep(80)

        assertTrue(jar.loadForRequest(relay).isEmpty(), "must not be sent after expiry")
        assertEquals(0, jar.size(), "and must be evicted from the store")
    }

    @Test
    fun `a secure cookie is withheld from a plaintext request`() {
        val jar = RelayCookieJar()
        jar.saveFromResponse(
            relay,
            listOf(
                Cookie.Builder()
                    .name("secure-only")
                    .value("1")
                    .domain("relay.example.com")
                    .path("/")
                    .secure()
                    .build(),
            ),
        )

        assertTrue(jar.loadForRequest("http://relay.example.com/".toHttpUrl()).isEmpty())
        assertEquals(1, jar.loadForRequest(relay).size)
    }

    @Test
    fun `clear drops everything`() {
        val jar = RelayCookieJar()
        jar.saveFromResponse(relay, listOf(cookie("a", "1"), cookie("b", "2")))
        assertEquals(2, jar.size())

        jar.clear()

        assertEquals(0, jar.size())
        assertTrue(jar.loadForRequest(relay).isEmpty())
    }
}
