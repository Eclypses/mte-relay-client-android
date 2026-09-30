package com.eclypses.relay

import com.eclypses.relay.session.OkHttpRelayControlPlaneClient
import com.eclypses.relay.transport.OkHttpRelayTransport
import com.eclypses.relay.transport.RelayCookieJar
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The relay makes four kinds of call — auth, pair, keepalive and the frame POSTs — across
 * two OkHttp clients. A load balancer sets its affinity cookie on the auth response and
 * expects it back on the pair request that follows, so those two clients sharing one cookie
 * store is a correctness requirement, not a tidiness one: without it a client can land on a
 * different replica per call and the relay serves pair state from Redis or fails with 562.
 */
class RelayComponentsCookieWiringTest {

    private fun controlPlaneClientOf(components: RelayComponents): OkHttpClient =
        (components.sessionLifecycleManager.controlPlaneClient as OkHttpRelayControlPlaneClient)
            .httpClient

    private fun transportClientOf(components: RelayComponents): OkHttpClient =
        (components.transport as OkHttpRelayTransport).httpClient

    @Test
    fun `a client with no cookie jar gets the relay default`() {
        val components = RelayComponents.create(OkHttpClient(), useMke = true)

        val jar = controlPlaneClientOf(components).cookieJar
        assertNotEquals(CookieJar.NO_COOKIES, jar, "OkHttp's default drops every cookie")
        assertTrue(jar is RelayCookieJar, "expected the relay's own jar, got ${jar::class}")
    }

    @Test
    fun `the control plane and the transport share one cookie store`() {
        val components = RelayComponents.create(OkHttpClient(), useMke = true)

        assertSame(
            controlPlaneClientOf(components).cookieJar,
            transportClientOf(components).cookieJar,
            "auth/pair/keepalive and frame POSTs must see the same cookies",
        )
    }

    @Test
    fun `a cookie stored via the control plane client is sent by the transport client`() {
        val components = RelayComponents.create(OkHttpClient(), useMke = true)
        val url = HttpUrl.Builder().scheme("https").host("relay.example.com").build()

        // Stands in for the affinity cookie a load balancer sets on the auth response.
        controlPlaneClientOf(components).cookieJar.saveFromResponse(
            url,
            listOf(
                Cookie.Builder().name("AWSALB").value("replica-1")
                    .domain("relay.example.com").path("/").build(),
            ),
        )

        val onFramePost = transportClientOf(components).cookieJar.loadForRequest(url)
        assertEquals(1, onFramePost.size, "the frame POST must carry the affinity cookie")
        assertEquals("replica-1", onFramePost[0].value)
    }

    @Test
    fun `a caller supplied cookie jar is left alone`() {
        val callerJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        }
        val components = RelayComponents.create(
            OkHttpClient.Builder().cookieJar(callerJar).build(),
            useMke = true,
        )

        assertSame(callerJar, controlPlaneClientOf(components).cookieJar)
        assertSame(callerJar, transportClientOf(components).cookieJar)
    }

    @Test
    fun `cookiesEnabled false turns cookies off on every relay client`() {
        val components = RelayComponents.create(
            OkHttpClient(),
            useMke = true,
            cookiesEnabled = false,
        )

        assertSame(CookieJar.NO_COOKIES, controlPlaneClientOf(components).cookieJar)
        assertSame(CookieJar.NO_COOKIES, transportClientOf(components).cookieJar)
    }

    @Test
    fun `cookiesEnabled false overrides a caller supplied jar`() {
        // The flag is the switch, so it has to win over whatever jar came in — otherwise
        // "off" would depend on which client the caller happened to hand us.
        val callerJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        }

        val components = RelayComponents.create(
            OkHttpClient.Builder().cookieJar(callerJar).build(),
            useMke = true,
            cookiesEnabled = false,
        )

        assertSame(CookieJar.NO_COOKIES, controlPlaneClientOf(components).cookieJar)
        assertSame(CookieJar.NO_COOKIES, transportClientOf(components).cookieJar)
    }

    @Test
    fun `passing NO_COOKIES is not mistaken for the off switch`() {
        // NO_COOKIES is also OkHttp's default, so it cannot signal intent. A caller who
        // wants cookies off uses the flag; this client is treated as unconfigured and gets
        // the relay's jar. Pinned because the opposite reading is the tempting one.
        val components = RelayComponents.create(
            OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).build(),
            useMke = true,
        )

        assertTrue(controlPlaneClientOf(components).cookieJar is RelayCookieJar)
    }
}
