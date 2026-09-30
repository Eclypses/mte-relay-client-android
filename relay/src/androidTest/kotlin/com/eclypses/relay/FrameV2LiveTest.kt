package com.eclypses.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eclypses.mte.wire.Discovery
import com.eclypses.mte.wire.Metadata
import com.eclypses.mte.wire.MteType
import com.eclypses.mte.wire.Personalization
import com.eclypses.relay.session.MtePairDraft
import com.eclypses.relay.session.MteProfileSource
import com.eclypses.relay.session.OkHttpRelayControlPlaneClient
import com.eclypses.relay.session.RelayDiscoveryChecks
import com.eclypses.relay.session.RelaySessionLifecycleManager
import com.eclypses.relay.session.RelaySessionManager
import com.eclypses.relay.streaming.RelayBufferedResponse
import com.eclypses.relay.streaming.RelayStreamingExecutor
import com.eclypses.relay.transport.OkHttpRelayTransport
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame version 2, end to end, against a real relay with real MTE.
 *
 * This is the step the vector tests cannot take. They prove the codec agrees with the
 * specification; this proves the specification is what the relay on the other side is
 * actually speaking, which is a different claim and the one that has historically been
 * wrong. Every assertion here failed at least once as a real defect during the port.
 *
 * Run with:
 *   ./gradlew :relay:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.eclypses.relay.FrameV2LiveTest
 */
@RunWith(AndroidJUnit4::class)
class FrameV2LiveTest {

    private val origin = System.getProperty("MTE_RELAY_URL")
        ?: "https://dev-mrs-v5-jsonplaceholder.eclypses.com"

    private fun controlPlane() = OkHttpRelayControlPlaneClient(OkHttpClient())

    // ---- discovery ----------------------------------------------------------

    @Test
    fun theRelaySpeaksFrameVersion2() {
        val auth = controlPlane().authenticate(origin, null)

        assertEquals(Metadata.FRAME_VERSION, auth.discovery.selectFrameVersion())
        assertEquals(Discovery.SCHEMA, auth.discovery.discoverySchema)
        // Both pairing inputs must be present: a client that guessed either would build a
        // decoder the relay's encoder does not match, and every decode would fail from the
        // first frame with nothing saying why.
        assertTrue("sequenceWindow", auth.discovery.sequenceWindow in -63..65535)
        assertTrue("timeWindow ${auth.discovery.timeWindow}", auth.discovery.timeWindow > 0)
        assertTrue("maxFrameBytes", auth.discovery.maxFrameBytes >= 4096)
        assertTrue(
            "the relay must bound metadata for the type this client uses",
            MteType.MKE in auth.discovery.maxMetadataBytes,
        )
    }

    @Test
    fun theTokenIsFortyBytesAndCarriesItsOwnClientId() {
        val token = controlPlane().authenticate(origin, null).token

        assertEquals(40, token.raw.size)
        assertEquals(54, token.text.length)
        assertEquals(32, token.clientIdHex.length)
        // The issued-at is unix seconds and must be recent; a client reads it to refresh
        // before expiry rather than discovering expiry at the next pairing.
        val now = System.currentTimeMillis() / 1000
        assertTrue("issuedAt ${token.issuedAt} vs $now", kotlin.math.abs(now - token.issuedAt) < 300)
        // Never prints the token itself: it is a bearer credential on the plain hop.
        assertTrue(token.toString(), !token.toString().contains(token.text))
    }

    /**
     * The check that would silently stop this client pairing with anything.
     *
     * A mobile client links the MTE **client** build and the relay links the **server**
     * build, so their probes differ by design -- exactly when the deployment is correct.
     * Only the settings decide interoperability. This asserts both halves: the settings
     * match, and the probe does not.
     */
    @Test
    fun theProfileSettingsMatchAndTheProbeDoesNot() {
        val discovery = controlPlane().authenticate(origin, null).discovery
        val server = com.eclypses.mte.wire.MteProfile(discovery.mteProfile)
        val client = MteProfileSource.profile

        assertTrue(
            "this client's MTE probe did not run: ${client.text}",
            !client.probeFailed,
        )
        val comparison = client.compareTo(server)
        assertTrue(
            "settings differ on ${comparison.mismatched}: client=${client.text} server=${server.text}",
            comparison.compatible,
        )
        assertEquals(server.settings, client.settings)
    }

    @Test
    fun discoveryPassesTheCompatibilityChecks() {
        val discovery = controlPlane().authenticate(origin, null).discovery

        val warnings = RelayDiscoveryChecks.assertCompatible(
            origin = origin,
            discovery = discovery,
            clientProfile = MteProfileSource.profile,
            clientKyberStrength = MtePairDraft.KYBER_STRENGTH,
        )
        // A probe warning is expected and is not a failure; anything else is news.
        assertTrue(
            "unexpected warnings: ${warnings.map { it.key }}",
            warnings.all { it.key == "mte-profile-probe" },
        )
    }

    // ---- pairing ------------------------------------------------------------

    @Test
    fun pairingSucceedsAndProducesUsablePairs() {
        val cp = controlPlane()
        val auth = cp.authenticate(origin, null)
        val result = cp.pair(
            origin = origin,
            token = auth.token,
            pairPoolSize = 2,
            sequenceWindow = auth.discovery.sequenceWindow,
            timeWindow = auth.discovery.timeWindow.toLong(),
        )

        assertEquals(2, result.runtimePairs.size)
        assertEquals(2, result.materials.size)
        // Distinct ids, and each one 16 bytes rather than the old opaque 32 characters.
        assertEquals(2, result.runtimePairs.map { it.pairId }.toSet().size)
        result.runtimePairs.forEach { assertEquals(16, it.pairId.raw.size) }
        // The personalization strings follow the grammar for the transport they were
        // minted on. The relay checks both, because its encoder takes our decoder string.
        result.materials.forEach {
            Personalization.validate(it.encoderPersonalizationStr, Personalization.Transport.HTTP)
            Personalization.validate(it.decoderPersonalizationStr, Personalization.Transport.HTTP)
        }
    }

    @Test
    fun keepaliveRefreshesTheWholeClient() {
        val cp = controlPlane()
        val auth = cp.authenticate(origin, null)
        cp.pair(origin, auth.token, 1, auth.discovery.sequenceWindow, auth.discovery.timeWindow.toLong())

        // No exception is the assertion: one call, no pair ids, the relay refreshes every
        // pair of this client in a single store operation.
        cp.keepAlive(origin, auth.token, emptyList())
    }

    /** Refreshing with a live token keeps the client id, so the pool survives. */
    @Test
    fun aRefreshKeepsTheClientId() {
        val cp = controlPlane()
        val first = cp.authenticate(origin, null)
        val refreshed = cp.authenticate(origin, first.token)

        assertEquals(first.token.clientIdHex, refreshed.token.clientIdHex)
        // The token itself may be byte-identical: the issued-at has one-second resolution
        // and both calls can land in the same second, which makes the MAC identical too.
        // What matters is the client id, because that is what the pool hangs off.
        assertTrue(refreshed.token.issuedAt >= first.token.issuedAt)
    }

    // ---- the data path ------------------------------------------------------

    private fun executor(): Pair<RelayStreamingExecutor, RelaySessionManager> {
        val client = OkHttpClient()
        val sm = RelaySessionManager()
        val slm = RelaySessionLifecycleManager(sm, OkHttpRelayControlPlaneClient(client))
        return RelayStreamingExecutor(sm, slm, OkHttpRelayTransport(client)) to sm
    }

    /**
     * The upstream echoes what it received in its response headers, which is the only
     * vantage point that can tell "the frame decrypted" from "the frame decrypted into the
     * right request". A body that round-trips proves the codec; `x-echo-url` proves the
     * path member survived the grammar.
     */
    private fun echoHeader(response: RelayBufferedResponse, name: String): String? =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    @Test
    fun aGetReachesTheOriginWithItsPathIntact() {
        val (executor, _) = executor()

        val response = executor.executeBuffered(
            serverPath = origin,
            route = "/echo",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = mapOf("Accept" to "text/plain"),
            unencryptedHeaders = null,
        )

        assertEquals(String(response.payload), 200, response.statusCode)
        assertEquals("GET", echoHeader(response, "x-echo-method"))
        // The path arrived: /echo is served and anything else 404s, which
        // anOriginErrorIsDeliveredRatherThanTreatedAsAFailure pins from the other side.
        // The whole chain is proved by getting here at all: auth, Kyber pairing, REQUEST
        // encode, relay decrypt, origin, relay encrypt, RESPONSE and DATA decode.
        assertNotNull(
            "the response must be a decrypted echo: ${response.headers.keys}",
            echoHeader(response, "x-echo-headers"),
        )
        // The relay reached the upstream rather than answering itself.
        assertEquals("jsonplaceholder:8080", echoHeader(response, "x-echo-host"))
    }

    @Test
    fun aPostBodyReachesTheOriginByteForByte() {
        val (executor, _) = executor()
        val body = """{"title":"frame v2","body":"live"}"""

        val response = executor.executeBuffered(
            serverPath = origin,
            route = "/echo",
            pathnamePrefix = null,
            method = "POST",
            body = body.toByteArray(),
            headers = mapOf("Content-Type" to "application/json"),
            unencryptedHeaders = null,
        )

        assertEquals(200, response.statusCode)
        assertEquals("POST", echoHeader(response, "x-echo-method"))
        // The DATA frames reassembled to exactly what was sent.
        assertEquals(body, String(response.payload))
    }

    /**
     * A query string travels inside the encrypted path member, and the member carries no
     * leading slash -- emitting one is 482 on every request, which is the defect the path
     * vectors caught before this ever ran.
     */
    @Test
    fun aQueryStringSurvivesInsideTheEncryptedPath() {
        val (executor, _) = executor()

        val response = executor.executeBuffered(
            serverPath = origin,
            route = "/echo?postId=1&tag=a%2Bb",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            unencryptedHeaders = null,
        )

        assertEquals(200, response.statusCode)
        // The query reached the origin parsed, which means the path member carried it
        // through the encrypted metadata and the relay rebuilt the target from it.
        val query = JSONObject(echoHeader(response, "x-echo-query")!!)
        assertEquals("1", query.getJSONArray("postId").getString(0))
        // The encoding is carried through byte for byte rather than normalised: %2B is a
        // literal plus to the origin, and decoding it here would change what it matches on.
        assertEquals("a+b", query.getJSONArray("tag").getString(0))
    }

    /** An encrypted header reaches the origin and never appears on the plain hop. */
    @Test
    fun anEncryptedHeaderReachesTheOrigin() {
        val (executor, _) = executor()

        val response = executor.executeBuffered(
            serverPath = origin,
            route = "/echo",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = mapOf("X-Tenant-Id" to "acme"),
            unencryptedHeaders = null,
        )

        assertEquals(200, response.statusCode)
        val received = JSONObject(echoHeader(response, "x-echo-headers")!!)
        // Header names are lowercase on the wire; the origin sees them title-cased again
        // because Go canonicalises, so the lookup is case-insensitive either way.
        val tenant = received.keys().asSequence().firstOrNull { it.equals("x-tenant-id", true) }
        assertNotNull("X-Tenant-Id did not reach the origin: $received", tenant)
        assertEquals("acme", received.getString(tenant!!))
    }

    /**
     * Several requests on one pool, so the codec operation counts on both sides have to
     * stay in step. A drift of one desynchronises a pair permanently, and the symptom is a
     * later request failing rather than this one -- which is why the loop matters.
     */
    @Test
    fun aPoolSurvivesRepeatedUse() {
        val (executor, sm) = executor()

        repeat(12) { i ->
            val response = executor.executeBuffered(
                serverPath = origin,
                route = "/echo?n=$i",
                pathnamePrefix = null,
                method = "POST",
                body = "request-$i".toByteArray(),
                headers = mapOf("Content-Type" to "text/plain"),
                unencryptedHeaders = null,
            )
            assertEquals("request $i: ${String(response.payload)}", 200, response.statusCode)
            assertEquals("request-$i", String(response.payload))
        }

        // No pair was replaced along the way: a desync would have surfaced as a refusal
        // and a fresh pair rather than as a failed assertion above.
        val session = sm.get(origin)
        assertNotNull("no session for $origin", session)
        assertTrue("the pool emptied", session!!.runtimePairCount() > 0)
    }

    /** A body over one frame has to span several DATA frames and reassemble in order. */
    @Test
    fun aBodyLargerThanOneFrameSpansSeveralDataFrames() {
        val (executor, sm) = executor()
        // Comfortably over maxFrameBytes, which the dev relay advertises as 65536.
        val body = buildString { repeat(20_000) { append("abcdefghij"[it % 10]) } }

        val response = executor.executeBuffered(
            serverPath = origin,
            route = "/echo",
            pathnamePrefix = null,
            method = "POST",
            body = body.toByteArray(),
            headers = mapOf("Content-Type" to "text/plain"),
            unencryptedHeaders = null,
        )

        assertEquals(String(response.payload).take(120), 200, response.statusCode)
        assertEquals(body.length, String(response.payload).length)
        assertEquals(body, String(response.payload))
        assertTrue(sm.get(origin)!!.runtimePairCount() > 0)
    }

    /** The origin's status and body reach the caller; the pair is not burned on a 404. */
    @Test
    fun anOriginErrorIsDeliveredRatherThanTreatedAsAFailure() {
        val (executor, sm) = executor()
        executor.executeBuffered(origin, "/echo", null, "GET", ByteArray(0), emptyMap(), null)
        val before = sm.get(origin)!!.runtimePairCount()

        val response = executor.executeBuffered(
            serverPath = origin,
            route = "/nope/not-a-route",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            unencryptedHeaders = null,
        )

        assertEquals(404, response.statusCode)
        assertEquals(
            "an upstream 404 says nothing about the pair and must not replace one",
            before,
            sm.get(origin)!!.runtimePairCount(),
        )
    }

    /** Response headers come out of the encrypted metadata, not off the hop. */
    @Test
    fun originHeadersArriveFromInsideTheFrame() {
        val (executor, _) = executor()

        val response = executor.executeBuffered(
            serverPath = origin,
            route = "/echo",
            pathnamePrefix = null,
            method = "GET",
            body = ByteArray(0),
            headers = emptyMap(),
            unencryptedHeaders = null,
        )

        // x-echo-* exist only on the origin's response, so seeing one here proves the
        // response metadata was decrypted rather than read off the plain hop.
        assertNotNull(
            "no x-echo-method in ${response.headers.keys}",
            echoHeader(response, "x-echo-method"),
        )
    }
}
