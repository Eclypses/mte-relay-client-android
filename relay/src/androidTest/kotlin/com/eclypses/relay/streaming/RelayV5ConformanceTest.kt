package com.eclypses.relay.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eclypses.relay.Relay
import com.eclypses.relay.session.forwardsPlainHeader
import com.eclypses.relay.session.isPassThrough
import com.eclypses.relay.RelayOkHttpRequestListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v5 client guide's acceptance run, against a real relay, on a device with real MTE.
 *
 * Two vantage points are needed, because the whole point of the header work is that a name
 * travels on exactly one of two channels and neither is visible from the other:
 *
 *  - **The hop.** A network interceptor on the client the relay is built with records every
 *    request as it actually goes out — after OkHttp's cookie jar has run, so the `Cookie`
 *    header is visible. This is the only way to see what is in the clear between the app and
 *    the relay.
 *  - **The origin.** The echo service reflects the request it received in `X-Echo-Headers`,
 *    which is the source of truth for what came out the other side.
 *
 * Steps needing domain configuration this relay does not have — `pass_through_routes`,
 * `forward_plain_headers`, a non-zero `MAX_REQUEST_BODY_MIB` — skip themselves with the
 * reason rather than silently passing.
 *
 * Run with:
 *   ./gradlew :relay:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.MTE_V5_RELAY_URL=https://your-relay
 */
@RunWith(AndroidJUnit4::class)
class RelayV5ConformanceTest {

    private companion object {
        /**
         * The relay with the complete upstream, so every case here is meaningful.
         *
         * dev-mrs takes server builds first and is what to pass through `MTE_V5_RELAY_URL`
         * when trying one, but its upstream is a narrower deployment: no `api/credit-card`,
         * no `api/kyc`, and no `setCookie_` on `/echo`, so the two cookie cases fail there
         * for a reason that has nothing to do with this client.
         */
        const val DEFAULT_RELAY = "https://mrs-v5-jsonplaceholder.eclypses.com"
        const val ROUTE_HEADER = "X-MTE-Relay-Route"

        /** Every request that actually went out on the hop, in order. */
        val hops = CopyOnWriteArrayList<Hop>()

        /**
         * The relay is a singleton, so the client it is built with is fixed by whichever test
         * runs first. One shared recorder, cleared per test, keeps that from mattering.
         */
        val relay: Relay by lazy {
            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(
                    Interceptor { chain ->
                        val request = chain.request()
                        hops += Hop(
                            url = request.url.toString(),
                            method = request.method,
                            headers = request.headers.names()
                                .associateWith { request.headers.values(it).joinToString(", ") },
                        )
                        chain.proceed(request)
                    },
                )
                .build()
            Relay.getInstance(
                InstrumentationRegistry.getInstrumentation().targetContext,
                client,
            )
        }
    }

    data class Hop(val url: String, val method: String, val headers: Map<String, String>)

    private val relayUrl: String
        get() = InstrumentationRegistry.getArguments().getString("MTE_V5_RELAY_URL")
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_RELAY

    @Before
    fun clearHops() {
        hops.clear()
    }

    // region helpers

    /** Sends through the relay and returns the decrypted response, failing on transport error. */
    private fun send(
        path: String,
        unencryptedHeaders: Array<String>? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Request.Builder().url("$relayUrl$path")
        headers.forEach { (name, value) -> builder.addHeader(name, value) }

        val result = AtomicReference<Response>()
        val failure = AtomicReference<Throwable>()
        val latch = CountDownLatch(1)

        relay.send(
            builder.get().build(),
            unencryptedHeaders,
            null,
            object : RelayOkHttpRequestListener {
                override fun onResponse(response: Response) {
                    result.set(response); latch.countDown()
                }

                override fun onError(response: Response) {
                    result.set(response); latch.countDown()
                }

                override fun onFailure(request: Request, throwable: Throwable) {
                    failure.set(throwable); latch.countDown()
                }
            },
        )

        assertTrue("relay request to $path timed out", latch.await(90, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("relay request to $path failed", it) }
        return requireNotNull(result.get()) { "no response for $path" }
    }

    /** The headers the origin actually saw, from the echo service. */
    private fun originHeaders(response: Response): Map<String, String> {
        val raw = requireNotNull(response.header("X-Echo-Headers")) {
            "echo response carried no X-Echo-Headers; is this route the echo service?"
        }
        val json = JSONObject(raw)
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    private fun originHeader(response: Response, name: String): String? =
        originHeaders(response).entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value

    /** The frame POSTs: everything that is not an auth, pair or keepalive call. */
    private fun frameHops(): List<Hop> =
        hops.filterNot { it.url.contains("/api/mte-") }

    private fun hopHeader(hop: Hop, name: String): String? =
        hop.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** The domain's `forward_plain_headers` list, read live. */
    private fun forwardedPlainHeaders(): List<String> {
        val array = OkHttpClient().newCall(
            // frameVersions is mandatory. Omitting it is how a pre-version-2 SDK identifies
            // itself, and the relay answers 478 with an ERROR frame -- which parsed as JSON
            // is a type mismatch on bytes beginning 0x89 'M' 'T', not a readable failure.
            Request.Builder().url("$relayUrl/api/mte-relay?frameVersions=2").get().build(),
        ).execute().use {
            JSONObject(requireNotNull(it.body).string())
                .optJSONObject("relay")?.optJSONArray("forwardPlainHeaders")
        } ?: return emptyList()
        return (0 until array.length()).map { array.getString(it).lowercase() }
    }

    /** A header name the relay is configured to forward, or null when it forwards none. */
    private fun aForwardedHeaderName(): String? = forwardedPlainHeaders().firstOrNull { it != "*" }

    // endregion

    @Test
    fun aSessionStartsAndAnEncryptedRequestReachesTheOrigin() {
        val response = send("/echo")

        assertEquals(200, response.code)
        // Proves the whole chain: auth, Kyber pairing, frame encode, relay decrypt, origin,
        // relay encrypt, frame decode. If discovery had reported an incompatible protocol,
        // encode type or Kyber strength, session start would have thrown instead.
        assertEquals("GET", response.header("X-Echo-Method"))
        // A decrypted echo response is itself proof that auth and Kyber pairing succeeded;
        // asserting on the auth and pair hops here would depend on test order, because the
        // session is established once per process and reused.
        assertNotNull("the response must be a decrypted echo", response.header("X-Echo-Headers"))
    }

    @Test
    fun headersAreEncryptedByDefaultAndStillReachTheOrigin() {
        val response = send("/echo", headers = mapOf("X-Tenant-Id" to "acme"))

        // Encrypted channel: the origin sees it...
        assertEquals("acme", originHeader(response, "X-Tenant-Id"))
        // ...and nothing on the hop carries it in the clear.
        frameHops().forEach { hop ->
            assertNull(
                "X-Tenant-Id must not be readable on the hop by default",
                hopHeader(hop, "X-Tenant-Id"),
            )
        }
    }

    @Test
    fun aNamedHeaderTravelsInTheClearOnTheHopAndReachesTheOrigin() {
        val forwarded = aForwardedHeaderName()
        assumeTrue("domain forwards no plain headers", forwarded != null)

        val response = send(
            "/echo",
            unencryptedHeaders = arrayOf(forwarded!!),
            headers = mapOf(forwarded to "123", "X-Tenant-Id" to "acme"),
        )

        val framePost = frameHops().firstOrNull()
            ?: throw AssertionError("no frame POST was recorded")

        // The named one is on the hop, where a gateway can read it...
        assertEquals("123", hopHeader(framePost, forwarded))
        // ...the unnamed one is not, and the frame still declares itself binary.
        assertNull(hopHeader(framePost, "X-Tenant-Id"))
        assertEquals("application/octet-stream", hopHeader(framePost, "Content-Type"))

        // Both arrive at the origin, by different routes: one forwarded from the hop because
        // the operator allowed that name, the other carried inside the encrypted frame.
        assertEquals("123", originHeader(response, forwarded))
        assertEquals("acme", originHeader(response, "X-Tenant-Id"))
    }

    @Test
    fun aHeaderTheDomainDoesNotAllowIsExposedButNeverArrives() {
        // The failure the denylist cannot prevent: the caller names a header, pays the cost of
        // putting it in the clear, and the relay drops it because the operator did not allow
        // that name. Exposed and never delivered — which is why the client warns.
        val notAllowed = "x-tenant-id"
        assumeTrue(
            "this name is allowed on the domain, so pick another for this case",
            !forwardedPlainHeaders().contains(notAllowed) && !forwardedPlainHeaders().contains("*"),
        )

        val response = send(
            "/echo",
            unencryptedHeaders = arrayOf(notAllowed),
            headers = mapOf("X-Tenant-Id" to "dropped"),
        )

        val framePost = frameHops().firstOrNull()
            ?: throw AssertionError("no frame POST was recorded")
        assertEquals("it was exposed on the hop", "dropped", hopHeader(framePost, "X-Tenant-Id"))
        assertNull(
            "and the relay did not forward it, which is what the warning is about",
            originHeader(response, "X-Tenant-Id"),
        )
    }

    @Test
    fun everyRelayCallCarriesTheRoutingToken() {
        // Re-pair first so this test observes a full auth and pair rather than inheriting a
        // session an earlier test established. Without it the assertions below depend on which
        // test happens to run first.
        rePair()
        send("/echo")

        assertTrue("expected an auth call", hops.any { it.url.contains("/api/mte-relay") })
        assertTrue("expected a pair call", hops.any { it.url.contains("/api/mte-pair") })

        // Pair follows auth, so by then the session holds the token auth just minted.
        hops.filter { it.url.contains("/api/mte-pair") }.forEach {
            assertNotNull("pair must echo the routing token", hopHeader(it, ROUTE_HEADER))
        }
        // Frame POSTs send the pair id rather than the client-level token.
        val frames = frameHops()
        assertTrue("expected at least one frame POST", frames.isNotEmpty())
        frames.forEach {
            assertNotNull("every frame POST must carry a routing token", hopHeader(it, ROUTE_HEADER))
        }
    }

    /** Forces a fresh auth + pair against the relay and waits for it. */
    private fun rePair() {
        val latch = CountDownLatch(1)
        val error = AtomicReference<String>()
        relay.rePairWithRelayServer(relayUrl, null) { success, message ->
            if (!success) error.set(message)
            latch.countDown()
        }
        assertTrue("re-pair timed out", latch.await(90, TimeUnit.SECONDS))
        error.get()?.let { throw AssertionError("re-pair failed: $it") }
    }

    @Test
    fun aCookieSetByTheOriginRidesTheNextRequest() {
        send("/echo?setCookie_session=xyz")
        hops.clear()
        val second = send("/echo")

        // On the hop, put there by the cookie jar rather than by application code.
        val framePost = frameHops().firstOrNull()
            ?: throw AssertionError("no frame POST was recorded")
        val hopCookie = requireNotNull(hopHeader(framePost, "Cookie")) {
            "the cookie set by the origin must ride the next hop"
        }
        assertTrue("expected session=xyz, got $hopCookie", hopCookie.contains("session=xyz"))

        // And forwarded to the origin, because this domain has forward_browser_cookies on.
        val originCookie = originHeader(second, "Cookie")
        assertNotNull("forward_browser_cookies is on, so the origin should see it", originCookie)
        assertTrue(originCookie!!.contains("session=xyz"))
    }

    @Test
    fun theCallerSeesTheOriginsHeadersAndNoneOfTheRelaysOwn() {
        // The relay moves the origin's Set-Cookie out of the frame onto the real HTTP response
        // so the cookie jar can take it. The caller would have seen that header without the
        // relay in the path, so they still should — this client used to drop it, iOS did not.
        val response = send("/echo?setCookie_session=abc")

        assertEquals(
            "the origin's cookie should reach the caller as it would unrelayed",
            "session=abc",
            response.header("Set-Cookie"),
        )

        // And nothing the relay addressed to this client.
        response.headers.names().forEach { name ->
            assertFalse(
                "the relay's own header reached the caller: $name",
                name.lowercase().startsWith("x-mte-relay-") ||
                    name.equals("x-mte-plain-forwarded", ignoreCase = true),
            )
        }

        // The hop's Content-Type describes the frame, not this body.
        assertFalse(
            "octet-stream is the frame's type, not the origin's",
            response.header("Content-Type") == "application/octet-stream",
        )
    }

    @Test
    fun theClientsViewOfTheDomainMatchesWhatTheServerStates() {
        val raw = OkHttpClient().newCall(
            Request.Builder().url("$relayUrl/api/mte-relay?frameVersions=2").get().build(),
        ).execute().use { requireNotNull(it.body).string() }

        val relayObject = requireNotNull(JSONObject(raw).optJSONObject("relay")) {
            "this server returned no discovery object at all"
        }
        android.util.Log.i("RelayConformance", "discovery: $relayObject")

        val parsed = com.eclypses.mte.wire.Discovery.parse(
            com.eclypses.mte.wire.Json.parseObject(relayObject.toString()),
        )

        assertEquals(relayObject.getInt("discoverySchema"), parsed.discoverySchema)
        assertEquals(relayObject.getInt("kyberStrength"), parsed.kyberStrength)
        assertEquals(relayObject.getInt("sequenceWindow"), parsed.sequenceWindow)
        assertEquals(relayObject.getInt("timeWindow"), parsed.timeWindow)
        assertEquals(relayObject.getLong("maxFrameBytes"), parsed.maxFrameBytes)

        // These two are relay deployment policy rather than protocol, so they stay in the
        // raw document rather than widening the shared type SocketX also consumes. What
        // has to hold is that the behaviour reads them: a route the domain lists must be
        // recognised as pass-through, and one it does not must not be.
        relayObject.optJSONArray("passThroughRoutes")?.let { routes ->
            for (i in 0 until routes.length()) {
                val entry = routes.getString(i)
                if (!entry.endsWith("*")) {
                    assertTrue(
                        "discovery lists $entry but isPassThrough does not recognise it",
                        parsed.isPassThrough(entry),
                    )
                }
            }
            assertFalse(
                "a route the domain does not list must not be pass-through",
                parsed.isPassThrough("/definitely-not-listed-${System.nanoTime()}"),
            )
        }
        relayObject.optJSONArray("forwardPlainHeaders")?.let { names ->
            for (i in 0 until names.length()) {
                val name = names.getString(i)
                if (!name.contains("*")) {
                    assertEquals(
                        "discovery lists $name as forwarded",
                        true,
                        parsed.forwardsPlainHeader(name),
                    )
                }
            }
        }
    }

    /**
     * Behavioural confirmation, independent of what discovery claims: a route the domain does
     * not list is not reachable unframed, and one it does list is. Either result contradicting
     * the discovery object would mean the server is misreporting itself.
     */
    @Test
    fun theServersBehaviourMatchesWhatItAdvertises() {
        val listed = OkHttpClient().newCall(
            Request.Builder().url("$relayUrl/api/mte-relay?frameVersions=2").get().build(),
        ).execute().use {
            JSONObject(requireNotNull(it.body).string())
                .optJSONObject("relay")?.optJSONArray("passThroughRoutes")
        }
        val routes = (0 until (listed?.length() ?: 0)).map { listed!!.getString(it) }

        routes.filterNot { it.endsWith("*") }.forEach { route ->
            val code = OkHttpClient().newCall(
                Request.Builder().url("$relayUrl$route").get().build(),
            ).execute().use { it.code }
            assertEquals("a listed pass-through route should answer: $route", 200, code)
        }

        // A route nothing lists is not served unframed.
        val unlisted = "/definitely-not-configured"
        assumeTrue("unexpectedly configured", !routes.contains(unlisted))
        val code = OkHttpClient().newCall(
            Request.Builder().url("$relayUrl$unlisted").get().build(),
        ).execute().use { it.code }
        assertEquals("an unlisted route must not be served unframed", 404, code)
    }

    @Test
    fun theKeepAliveIntervalIsDerivedFromTheRelaysSessionTimeout() {
        send("/echo")

        val diagnostics = relay.getKeepAliveDiagnostics(relayUrl)
        android.util.Log.i("RelayConformance", "keepalive: $diagnostics")

        val reported = OkHttpClient().newCall(
            Request.Builder().url("$relayUrl/api/mte-relay?frameVersions=2").get().build(),
        ).execute().use {
            JSONObject(requireNotNull(it.body).string())
                .getJSONObject("relay").getInt("sessionTimeoutSeconds")
        }
        val expected = (reported / 3).coerceIn(60, 600)

        assertTrue(
            "expected a ${expected}s interval derived from the relay's ${reported}s timeout, got: $diagnostics",
            diagnostics.contains("Configured keep-alive interval: ${expected}s"),
        )
        assertTrue("the keep-alive loop should be running", diagnostics.contains("Loop active: true"))
    }

    @Test
    fun theRelaysForwardingSignalNeverReachesTheCaller() {
        // A cookie forwarded from the hop is a merge, which is when the relay adds
        // x-mte-plain-forwarded to the encrypted response headers. It describes the relay's
        // own behaviour, not anything the origin sent, so the caller must not receive it.
        send("/echo?setCookie_session=signal")
        val response = send("/echo")

        assertNull(
            "x-mte-plain-forwarded must be stripped before the caller sees the response",
            response.header("x-mte-plain-forwarded"),
        )
        response.headers.names().forEach { name ->
            assertFalse(
                "no case variant of the signal may survive: $name",
                name.equals("x-mte-plain-forwarded", ignoreCase = true),
            )
        }
        // The response is otherwise intact.
        assertNotNull(response.header("X-Echo-Headers"))
    }

    @Test
    fun aPassThroughRouteIsSentUnframed() {
        // The route is whatever the domain lists, not a name guessed here. Operators pick
        // their own — /doctor and /health are both plausible — so probing a hardcoded one
        // would skip for the wrong reason against a domain that is correctly configured.
        val routes = OkHttpClient().newCall(
            Request.Builder().url("$relayUrl/api/mte-relay?frameVersions=2").get().build(),
        ).execute().use {
            JSONObject(requireNotNull(it.body).string())
                .optJSONObject("relay")?.optJSONArray("passThroughRoutes")
        }
        assumeTrue("domain configures no pass_through_routes", routes != null && routes.length() > 0)

        // A prefix entry needs a concrete path under it; an exact entry is used as-is.
        val entry = routes!!.getString(0)
        val route = if (entry.endsWith("*")) entry.dropLast(1) + "probe" else entry

        val response = send(route)

        assertEquals(200, response.code)
        assertTrue(
            "a pass-through route must not be sent as a frame to POST /",
            frameHops().none { it.method == "POST" && it.url.trimEnd('/') == relayUrl.trimEnd('/') },
        )
        assertTrue(
            "a pass-through route must be requested at its own path",
            hops.any { it.url.endsWith(route) },
        )
    }

    /**
     * The relay's limit is on the frame it receives, not on the caller's body, so a body sized
     * exactly at the limit is already too big once wrapped and the client must stop it.
     *
     * Exercised only where the cap is small enough to hold in memory. Both relays have since
     * raised theirs -- 40 MiB and 1 GiB -- and a device cannot allocate either: this test and
     * its two neighbours died with OutOfMemoryError and, at 2 GiB, a NegativeArraySizeException
     * from an Int overflow. The skip names the cap so a run that skips says why.
     */
    /**
     * What the relay says it will buffer, or 0 for unlimited.
     *
     * frameVersions is mandatory: omitting it is how a pre-version-2 SDK identifies itself and
     * the relay answers 478 with an ERROR frame, which parsed as JSON is a type mismatch on
     * bytes beginning 0x89 'M' 'T' rather than a readable failure.
     */
    private fun advertisedBodyLimit(): Long {
        val relayObject = OkHttpClient().newCall(
            Request.Builder().url("$relayUrl/api/mte-relay?frameVersions=2").get().build(),
        ).execute().use { JSONObject(it.body!!.string()).optJSONObject("relay") }
        return relayObject?.optLong("maxRequestBodyBytes") ?: 0L
    }

    @Test
    fun aStreamedUploadIsBoundByMaxRequestBodyBytesToo() {
        val limit = advertisedBodyLimit()
        assumeTrue("domain sets no maxRequestBodyBytes (0 = unlimited)", limit > 0)

        val latch = CountDownLatch(1)
        val status = AtomicReference<Int>()
        val error = AtomicReference<String>()
        val before = hops.size

        try {
            relay.uploadFile(
                com.eclypses.relay.RelayFileRequestProperties(
                    relayUrl,
                    "/echo",
                    null,
                    linkedMapOf(
                        "Content-Type" to "application/octet-stream",
                        "Content-Length" to (limit + 1).toString(),
                    ),
                    null,
                    // Never reached: the refusal happens before a byte is read.
                    com.eclypses.relay.RelayStreamCallback { out -> out.close() },
                ),
                { statusCode, _, _, errorMessage, _ ->
                    status.set(statusCode)
                    error.set(errorMessage)
                    latch.countDown()
                },
                com.eclypses.relay.RelayStreamCompletionCallback { _, _ -> },
            )
        } catch (expected: IllegalArgumentException) {
            // RelayRequestTooLargeException, thrown synchronously.
            latch.countDown()
        }

        assertTrue("upload did not settle", latch.await(60, TimeUnit.SECONDS))
        assertNotEquals("an upload over the cap must not be accepted", 200, status.get())
        assertFalse("an oversized upload must not reach the relay", hops.size > before + 1)
    }

    @Test
    fun anOversizedBodyIsRejectedBeforeUpload() {
        // Needs MAX_REQUEST_BODY_MIB on the domain; discovery reports 0 (unlimited) today.
        val limit = advertisedBodyLimit()
        assumeTrue("domain sets no maxRequestBodyBytes (0 = unlimited)", limit > 0)

        // One byte over a cap that may be 1 GiB cannot be allocated, and (limit + 1).toInt()
        // overflows to a negative size besides. The pre-filter compares sizes, so any body
        // over the cap proves it -- but where the cap is large the declared-length path is the
        // one that can be exercised, and its own test covers it.
        assumeTrue(
            "cap is $limit bytes, too large to materialise on a device",
            limit <= 64L * 1024 * 1024,
        )
        val tooBig = ByteArray((limit + 1).toInt())
        val before = hops.size
        try {
            relay.send(
                Request.Builder().url("$relayUrl/echo")
                    .post(okhttp3.RequestBody.create(null, tooBig)).build(),
                null, null,
                object : RelayOkHttpRequestListener {
                    override fun onResponse(response: Response) = Unit
                    override fun onError(response: Response) = Unit
                    override fun onFailure(request: Request, throwable: Throwable) = Unit
                },
            )
        } catch (expected: IllegalArgumentException) {
            // RelayRequestTooLargeException
        }
        assertFalse("an oversized body must not be uploaded", hops.size > before + 1)
    }
}
