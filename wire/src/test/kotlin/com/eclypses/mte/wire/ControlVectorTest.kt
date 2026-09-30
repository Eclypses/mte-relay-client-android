package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** `vectors/control.json`, plus the token and keepalive shapes from the registry. */
class ControlVectorTest {

    @Test
    fun `every close vector`() {
        val cases = SpecVectors.obj("control.json").getJSONArray("close")
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val bytes = SpecVectors.hex(c.getString("hex"))
            if (c.has("error")) {
                try {
                    Control.readClose(bytes)
                    failures += "$name: expected '${c.getString("error")}', parsed"
                } catch (e: WireException) {
                    if (e.error.token != c.getString("error")) {
                        failures += "$name: got '${e.error.token}'"
                    }
                }
                continue
            }
            val want = c.getJSONObject("expect")
            val got = Control.readClose(bytes)
            if (got.code != want.getInt("code") || got.reason != want.getString("reason")) {
                failures += "$name: got $got"
            } else if (SpecVectors.toHex(Control.writeClose(got.code, got.reason)) != c.getString("hex")) {
                failures += "$name: does not round-trip"
            }
        }
        if (failures.isNotEmpty()) fail("close vectors failed:\n  " + failures.joinToString("\n  "))
        println("control/close: ${cases.length()} vectors pass")
    }

    @Test
    fun `every ping vector`() {
        val cases = SpecVectors.obj("control.json").getJSONArray("ping")
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val bytes = SpecVectors.hex(c.getString("hex"))
            if (c.has("error")) {
                try {
                    Control.readPing(bytes)
                    failures += "$name: expected '${c.getString("error")}', parsed"
                } catch (e: WireException) {
                    if (e.error.token != c.getString("error")) failures += "$name: got '${e.error.token}'"
                }
                continue
            }
            if (SpecVectors.toHex(Control.readPing(bytes)) != c.getString("expect")) {
                failures += "$name: ping is echoed verbatim"
            }
        }
        if (failures.isNotEmpty()) fail("ping vectors failed:\n  " + failures.joinToString("\n  "))
        println("control/ping: ${cases.length()} vectors pass")
    }

    @Test
    fun `the close code mapping matches the registry`() {
        // 4000 + code - 400 puts 470..490 in 4070..4090. The registry's own
        // "4073 decode_failed" close vector is the check that this is the right sum.
        assertEquals(4070, Control.closeCodeFor(470))
        assertEquals(4073, Control.closeCodeFor(473))
        assertEquals(4090, Control.closeCodeFor(490))
    }

    @Test
    fun `the token matches its registered size and domain`() {
        val t = SpecVectors.obj("registry.json").getJSONObject("token")
        assertEquals(t.getInt("size"), Token.SIZE)
        assertEquals(t.getString("domain"), Token.DOMAIN)
        println("token: ${Token.SIZE} bytes, domain ${Token.DOMAIN}")
    }

    @Test
    fun `a token round-trips through its text form`() {
        val raw = ByteArray(Token.SIZE) { it.toByte() }
        val token = Token(raw)
        assertEquals(Token.TEXT_LENGTH, token.text.length, "the text form is 54 characters")
        assertEquals(token, Token.parse(token.text))
        assertEquals("000102030405060708090a0b0c0d0e0f", token.clientIdHex)
        // bytes 16..23 are 0x10..0x17 big endian
        assertEquals(0x1011121314151617L, token.issuedAt)
    }

    @Test
    fun `a token of the wrong size is refused`() {
        for (size in intArrayOf(0, 39, 41)) {
            try {
                Token(ByteArray(size))
                fail("accepted a $size byte token")
            } catch (e: WireException) {
                assertEquals(WireError.TOKEN, e.error)
            }
        }
    }

    @Test
    fun `the keepalive body carries clientId and drop`() {
        val token = Token(ByteArray(Token.SIZE) { 1 })
        val body = Keepalive.body(token, listOf("b", "a"))
        // Canonical: keys sorted, and drop keeps the caller's order because an array
        // is a sequence, not a set.
        assertEquals("""{"clientId":"${token.text}","drop":["b","a"]}""", body)
        // Empty drop is omitted, not sent as [], matching the other clients.
        assertEquals("""{"clientId":"${token.text}"}""", Keepalive.body(token))
        println("keepalive: body shape matches the registered members")
    }

    @Test
    fun `the registered keepalive members are the ones we send`() {
        val members = SpecVectors.obj("registry.json").getJSONArray("keepalive_members")
        val names = (0 until members.length()).map { members.getJSONObject(it).getString("member") }.toSet()
        assertEquals(setOf("clientId", "drop"), names, "the registry changed the keepalive body")
    }
}
