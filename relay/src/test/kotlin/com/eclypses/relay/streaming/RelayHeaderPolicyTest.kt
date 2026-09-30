package com.eclypses.relay.streaming

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The header partition is a security boundary: whatever lands in `plaintext` is readable
 * by anything between the client and the relay. These cases pin the rules that decide
 * which side a header falls on, and mirror the iOS suite so a divergence between the two
 * platforms shows up as a differing test rather than a production surprise.
 */
class RelayHeaderPolicyTest {

    private val headers = mapOf(
        "Content-Type" to "application/json",
        "Authorization" to "Bearer secret-token",
        "traceparent" to "00-trace-id-01",
        "X-Tenant-Id" to "acme",
    )

    private fun split(
        headers: Map<String, String>,
        unencrypted: Array<String>?,
    ) = RelayHeaderPolicy.split(headers, RelayHeaderPolicy.resolve(unencrypted))

    // region The default

    @Test
    fun `null list encrypts everything`() {
        val p = split(headers, null)

        assertTrue(p.plaintext.isEmpty(), "nothing may be exposed unless the caller asked")
        assertEquals("Bearer secret-token", p.encrypted["Authorization"])
        assertEquals("00-trace-id-01", p.encrypted["traceparent"])
    }

    /** Empty and null must be indistinguishable, or whether a header is encrypted starts
     *  depending on how a caller spelled "no options". */
    @Test
    fun `empty list behaves exactly like null`() {
        val withNull = split(headers, null)
        val withEmpty = split(headers, emptyArray())

        assertEquals(withNull.encrypted, withEmpty.encrypted)
        assertEquals(withNull.plaintext, withEmpty.plaintext)
    }

    // endregion

    // region Opting a header out

    @Test
    fun `named header travels in the clear and only that one`() {
        val p = split(headers, arrayOf("traceparent"))

        assertEquals(mapOf("traceparent" to "00-trace-id-01"), p.plaintext)
        assertNull(p.encrypted["traceparent"])
        assertEquals("Bearer secret-token", p.encrypted["Authorization"],
            "an unnamed header must stay encrypted")
    }

    /** Header names are case-insensitive and HTTP/2 lowercases them on the wire. An
     *  exact-match comparison would silently encrypt a header the caller asked to expose. */
    @Test
    fun `matching is case insensitive`() {
        val p = split(headers, arrayOf("TRACEPARENT"))

        assertEquals("00-trace-id-01", p.plaintext["traceparent"])
        assertNull(p.encrypted["traceparent"])
    }

    @Test
    fun `naming an absent header changes nothing`() {
        val p = split(headers, arrayOf("x-not-present"))

        assertTrue(p.plaintext.isEmpty())
        assertEquals(headers.size, p.encrypted.size)
    }

    // endregion

    // region The wildcard

    @Test
    fun `wildcard leaves every eligible header in the clear`() {
        val p = split(headers, arrayOf("*"))

        assertEquals("Bearer secret-token", p.plaintext["Authorization"])
        assertEquals("00-trace-id-01", p.plaintext["traceparent"])
        assertEquals("acme", p.plaintext["X-Tenant-Id"])
    }

    /** The wildcard cannot hand the relay hop a second Content-Type. "Encrypt nothing"
     *  still excepts the headers the frame owns. */
    @Test
    fun `wildcard still cannot expose reserved headers`() {
        val p = split(headers, arrayOf("*"))

        assertNull(p.plaintext["Content-Type"])
        assertEquals("application/json", p.encrypted["Content-Type"])
    }

    /** A relay that sees x-mte-relay-client treats the request as coming from another
     *  relay and skips the cookie merge for it, so a caller must never put one on the hop. */
    @Test
    fun `x-mte-relay-client is never exposed even under the wildcard`() {
        val p = split(mapOf("X-MTE-Relay-Client" to "someone-else"), arrayOf("*"))

        assertNull(p.plaintext["X-MTE-Relay-Client"])
        assertEquals("someone-else", p.encrypted["X-MTE-Relay-Client"])
    }

    @Test
    fun `host is never exposed even under the wildcard`() {
        val p = split(mapOf("Host" to "api.origin.example"), arrayOf("*"))

        assertNull(p.plaintext["Host"])
        assertEquals("api.origin.example", p.encrypted["Host"])
    }

    // endregion

    // region Headers that never travel

    /** The origin's body is rebuilt by the relay from the decrypted frame, so its length
     *  is that body's length. Copying the caller's onto a hop carrying a larger frame
     *  would declare a length that does not match the bytes on the wire. */
    @Test
    fun `content length is not forwarded on either channel`() {
        val p = split(mapOf("Content-Length" to "42", "Authorization" to "Bearer t"), null)

        assertNull(p.plaintext["Content-Length"])
        assertNull(p.encrypted["Content-Length"])
        assertEquals("Bearer t", p.encrypted["Authorization"])
    }

    @Test
    fun `connection scoped headers never reach either channel`() {
        val hopByHop = mapOf(
            "Connection" to "keep-alive",
            "Proxy-Connection" to "keep-alive",
            "Keep-Alive" to "timeout=5",
            "TE" to "trailers",
            "Trailer" to "Expires",
            "Transfer-Encoding" to "chunked",
            "Upgrade" to "websocket",
            "Proxy-Authorization" to "Basic abc",
        )
        val p = split(hopByHop + ("X-Keep" to "yes"), null)

        hopByHop.keys.forEach {
            assertNull(p.plaintext[it], "$it must not be copied onto the relay hop")
            assertNull(p.encrypted[it], "$it describes one hop, not the message")
        }
        assertEquals("yes", p.encrypted["X-Keep"], "ordinary headers are unaffected")
    }

    /** RFC 7230 section 6.1: names listed in a Connection value are connection-scoped for
     *  that message, so the hop-by-hop set is per-request rather than a fixed list. */
    @Test
    fun `names listed in connection are treated as hop by hop`() {
        val p = split(
            mapOf(
                "Connection" to "X-Custom-Hop, close",
                "X-Custom-Hop" to "internal",
                "X-Keep" to "yes",
            ),
            null,
        )

        assertNull(p.encrypted["X-Custom-Hop"], "named in Connection, so scoped to one hop")
        assertNull(p.plaintext["X-Custom-Hop"])
        assertEquals("yes", p.encrypted["X-Keep"])
    }

    // endregion

    // region Validation

    /** A list is a security instruction. Silently dropping an entry leaves the caller
     *  believing a header is exposed for a gateway to read when it is not. */
    @Test
    fun `reserved names are rejected rather than ignored`() {
        listOf(
            "content-type",
            "Content-Length",
            "host",
            "x-mte-relay-route",
            "X-MTE-Relay-Client",
        ).forEach {
            assertFailsWith<IllegalArgumentException>("$it must be rejected") {
                RelayHeaderPolicy.resolve(arrayOf(it))
            }
        }
    }

    @Test
    fun `wildcard must be the only entry`() {
        assertFailsWith<IllegalArgumentException> {
            RelayHeaderPolicy.resolve(arrayOf("*", "x-request-id"))
        }
    }

    @Test
    fun `repeated wildcard is still valid`() {
        assertTrue(RelayHeaderPolicy.resolve(arrayOf("*", "*")).all)
    }

    @Test
    fun `empty and whitespace only names are rejected`() {
        assertFailsWith<IllegalArgumentException> { RelayHeaderPolicy.resolve(arrayOf("")) }
        assertFailsWith<IllegalArgumentException> { RelayHeaderPolicy.resolve(arrayOf("   ")) }
    }

    @Test
    fun `invalid header name characters are rejected`() {
        listOf("x request id", "x-request-id:", "héader").forEach {
            assertFailsWith<IllegalArgumentException>("$it must be rejected") {
                RelayHeaderPolicy.resolve(arrayOf(it))
            }
        }
    }

    @Test
    fun `valid names resolve lowercased and trimmed`() {
        val policy = RelayHeaderPolicy.resolve(arrayOf("X-Request-Id", "  TraceParent "))

        assertTrue(!policy.all)
        assertEquals(setOf("x-request-id", "traceparent"), policy.names)
    }

    // endregion

    // region Merging onto the outbound hop

    @Test
    fun `merge cannot overwrite the frames own headers`() {
        val merged = RelayHeaderPolicy.mergePlaintext(
            mapOf("Content-Type" to "application/octet-stream"),
            mapOf("Content-Type" to "application/json", "traceparent" to "00-trace-id-01"),
        )

        assertEquals("application/octet-stream", merged["Content-Type"],
            "the frame's content type must survive")
        assertEquals("00-trace-id-01", merged["traceparent"])
    }

    /** mergePlaintext must not trust its input: split already filters these, but the map
     *  reaches it as a bare dictionary and a future caller could assemble one directly. */
    @Test
    fun `merge rejects connection scoped headers it is handed`() {
        val merged = RelayHeaderPolicy.mergePlaintext(
            mapOf("Content-Type" to "application/octet-stream"),
            mapOf("Connection" to "keep-alive", "traceparent" to "00-trace-id-01"),
        )

        assertNull(merged["Connection"])
        assertEquals("00-trace-id-01", merged["traceparent"])
    }

    // endregion
}
