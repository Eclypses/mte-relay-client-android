package com.eclypses.relay.session

import com.eclypses.mte.wire.Discovery
import com.eclypses.mte.wire.Metadata
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.Token
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The frame v2 control plane as it appears on the wire.
 *
 * Two things replaced a round trip here. The route header used to carry an opaque value
 * the relay minted at auth and the client echoed; section 11 makes it the client id hex,
 * derived from the token, the same for the client's life -- so there is nothing to mint,
 * nothing to echo, and nothing to lose track of. And the keepalive body used to list
 * every pair to keep; it now names only the pairs to drop, and the relay refreshes the
 * whole client in one store operation.
 *
 * A client that still sends `pairIds` gets 200 and no effect, which is exactly the kind
 * of silent wrong answer these assertions exist to catch.
 */
class RelayControlPlaneHttpTest {

    private val captured = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()

    /** A 40 byte token whose client id is the first 16 bytes. */
    private val token = Token(ByteArray(40) { (it + 1).toByte() })

    private val discovery = """
        {"discoverySchema":1,"buildVersion":"5.0.0","frameVersions":[2],"features":[],
         "mteProfile":"mte/4.2.1;kyber=1024","kyberStrength":1024,
         "sequenceWindow":-63,"timeWindow":1000,
         "maxFrameBytes":65536,"maxMessageBytes":1048576,
         "maxMetadataBytes":{"MKE":61439,"MTE":61439},"transports":["http"]}
    """.trimIndent()

    private fun client(
        authBody: String = """{"clientId":"${token.text}","relay":$discovery}""",
        status: Int = 200,
        errorHeader: String? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                captured += request
                bodies += okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                val body = when {
                    request.url.encodedPath.endsWith("mte-relay") -> authBody
                    request.url.encodedPath.endsWith("mte-keepalive") -> """{"touched":1}"""
                    else -> "[]"
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("ok")
                    .apply { errorHeader?.let { addHeader("X-MTE-Relay-Error", it) } }
                    .body(body.toByteArray().toResponseBody())
                    .build()
            },
        )
        .build()

    private fun route(index: Int = 0): String? = captured[index].header("X-MTE-Relay-Route")

    // ---- auth ---------------------------------------------------------------

    /**
     * Mandatory, camelCase, plural, and a query parameter rather than a header -- a header
     * would add a CORS preflight to every cross-origin auth. Omitting it is how a
     * pre-version-2 SDK identifies itself, and the relay answers 478.
     */
    @Test
    fun `auth names the frame versions it speaks`() {
        OkHttpRelayControlPlaneClient(client()).authenticate("https://relay.example.com", null)

        val url = captured[0].url
        assertEquals("/api/mte-relay", url.encodedPath)
        assertEquals("2", url.queryParameter("frameVersions"))
    }

    @Test
    fun `the route header is the client id, not a minted token`() {
        val subject = OkHttpRelayControlPlaneClient(client())

        val response = subject.authenticate("https://relay.example.com", token)

        assertEquals(token.clientIdHex, route())
        assertEquals(32, token.clientIdHex.length)
        assertEquals(token.clientIdHex, response.token.clientIdHex)
    }

    @Test
    fun `a first authentication sends no client id and no route`() {
        OkHttpRelayControlPlaneClient(client()).authenticate("https://relay.example.com", null)

        assertEquals(null, captured[0].url.queryParameter("clientId"))
        assertEquals(null, route())
    }

    @Test
    fun `a refresh presents the current token`() {
        OkHttpRelayControlPlaneClient(client()).authenticate("https://relay.example.com", token)

        assertEquals(token.text, captured[0].url.queryParameter("clientId"))
    }

    /**
     * Frame v2 makes discovery mandatory: the sequence and time windows are pairing inputs,
     * so there is nothing safe to assume when it is missing. The previous generation
     * degraded gracefully here, which is now the wrong answer.
     */
    @Test
    fun `auth without a relay object is refused`() {
        val subject = OkHttpRelayControlPlaneClient(
            client(authBody = """{"clientId":"${token.text}"}"""),
        )
        val e = assertFailsWith<IllegalStateException> {
            subject.authenticate("https://relay.example.com", null)
        }
        assertTrue(e.message!!.contains("discovery"), e.message!!)
    }

    @Test
    fun `a relay that does not speak frame version 2 is refused`() {
        val older = discovery.replace(""""frameVersions":[2]""", """"frameVersions":[1]""")
        val subject = OkHttpRelayControlPlaneClient(
            client(authBody = """{"clientId":"${token.text}","relay":$older}"""),
        )
        val e = assertFailsWith<IllegalStateException> {
            subject.authenticate("https://relay.example.com", null)
        }
        assertTrue(e.message!!.contains("frame version"), e.message!!)
    }

    @Test
    fun `discovery is parsed into the pairing inputs`() {
        val response = OkHttpRelayControlPlaneClient(client())
            .authenticate("https://relay.example.com", null)

        assertEquals(-63, response.discovery.sequenceWindow)
        assertEquals(1000, response.discovery.timeWindow)
        assertEquals(Metadata.FRAME_VERSION, response.discovery.selectFrameVersion())
    }

    // ---- keepalive ----------------------------------------------------------

    @Test
    fun `keepalive names only the pairs to drop`() {
        val drop = PairId.random()
        OkHttpRelayControlPlaneClient(client())
            .keepAlive("https://relay.example.com", token, listOf(drop))

        assertEquals("""{"clientId":"${token.text}","drop":["${drop.text}"]}""", bodies[0])
        assertTrue(!bodies[0].contains("pairIds"), "pairIds gets 200 and no effect")
        assertEquals(token.clientIdHex, route())
    }

    /** Matching the browser and TypeScript clients: an empty list is omitted, not sent. */
    @Test
    fun `an empty drop list is omitted`() {
        OkHttpRelayControlPlaneClient(client())
            .keepAlive("https://relay.example.com", token, emptyList())

        assertEquals("""{"clientId":"${token.text}"}""", bodies[0])
    }

    // ---- errors -------------------------------------------------------------

    /**
     * Behaviour keys on the reason. Both of these are 475 and their actions are opposite:
     * one re-authenticates, the other stops and pages a human.
     */
    @Test
    fun `a refusal carries the registry reason and its action`() {
        val subject = OkHttpRelayControlPlaneClient(
            client(status = 475, errorHeader = "475 invalid_token"),
        )
        val e = assertFailsWith<RelayControlPlaneException> {
            subject.authenticate("https://relay.example.com", null)
        }
        assertEquals(475, e.error.code)
        assertEquals("invalid_token", e.error.reason)
        assertEquals(com.eclypses.mte.wire.RelayAction.FULL_REPAIR, e.error.action)

        captured.clear()
        val stopping = OkHttpRelayControlPlaneClient(
            client(status = 475, errorHeader = "475 unknown_key"),
        )
        val stop = assertFailsWith<RelayControlPlaneException> {
            stopping.authenticate("https://relay.example.com", null)
        }
        assertEquals(com.eclypses.mte.wire.RelayAction.STOP, stop.error.action)
    }

    /** A proxy that strips the header leaves the code, and the code alone says "surface". */
    @Test
    fun `a refusal with no error header still reports the status`() {
        val subject = OkHttpRelayControlPlaneClient(client(status = 503))
        val e = assertFailsWith<RelayControlPlaneException> {
            subject.authenticate("https://relay.example.com", null)
        }
        assertEquals(503, e.httpStatus)
        assertEquals("", e.error.reason)
    }

    // ---- endpoints ----------------------------------------------------------

    @Test
    fun `the endpoints are the ones discovery names`() {
        assertEquals("/api/mte-relay", Discovery.ENDPOINT)
        assertEquals("/api/mte-pair", Discovery.PAIR_ENDPOINT)
        assertEquals("/api/mte-keepalive", Discovery.KEEPALIVE_ENDPOINT)
        assertEquals("X-MTE-Relay-Route", Discovery.ROUTE_HEADER)
    }
}
