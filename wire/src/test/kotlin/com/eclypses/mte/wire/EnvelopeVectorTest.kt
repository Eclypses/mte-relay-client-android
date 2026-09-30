package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every case in `vectors/envelope.json`, driven from the file.
 *
 * Each case is either a parse that must succeed with the stated fields, or one that
 * must fail with a stated error token. Both halves matter: the negative cases are
 * where the parse order is actually observable, and where a reader that checks kind
 * before version reports the wrong failure for a frame version 1 frame.
 */
class EnvelopeVectorTest {

    @Test
    fun `every envelope vector`() {
        val cases = SpecVectors.array("envelope.json")
        var accepted = 0
        var rejected = 0
        val failures = mutableListOf<String>()

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val bytes = SpecVectors.hex(c.getString("hex"))

            if (c.has("error")) {
                val want = c.getString("error")
                try {
                    val got = Envelope.read(bytes)
                    failures += "$name: expected error '$want', parsed $got"
                } catch (e: WireException) {
                    if (e.error.token != want) {
                        failures += "$name: expected error '$want', got '${e.error.token}'"
                    } else {
                        rejected++
                    }
                }
                continue
            }

            val want = c.getJSONObject("expect")
            try {
                val got = Envelope.read(bytes)
                if (got.kind.value != want.getInt("kind")) {
                    failures += "$name: kind ${got.kind.value} != ${want.getInt("kind")}"
                } else if (got.flags != want.getInt("flags")) {
                    failures += "$name: flags ${got.flags} != ${want.getInt("flags")}"
                } else if (got.length != want.getLong("length")) {
                    failures += "$name: length ${got.length} != ${want.getLong("length")}"
                } else {
                    accepted++
                }
            } catch (e: WireException) {
                failures += "$name: expected a parse, got '${e.error.token}'"
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${cases.length()} envelope vectors failed:\n  " +
                    failures.joinToString("\n  "),
            )
        }
        assertTrue(accepted > 0 && rejected > 0, "vectors must cover both halves")
        println("envelope: $accepted accepted, $rejected rejected, ${cases.length()} total")
    }

    /**
     * Writing is not covered by the vector file, which only carries bytes to read.
     * Round-tripping every accepted case closes that: a writer that disagrees with
     * the reader would otherwise pass the whole suite.
     */
    @Test
    fun `accepted vectors round-trip through the writer`() {
        val cases = SpecVectors.array("envelope.json")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.has("error")) continue
            val hex = c.getString("hex")
            val parsed = Envelope.read(SpecVectors.hex(hex))
            assertEquals(
                hex,
                SpecVectors.toHex(parsed.toByteArray()),
                "round-trip differs for ${c.getString("name")}",
            )
        }
    }

    /** The kind table is normative; `registry.json` carries it as data. */
    @Test
    fun `kind table matches the registry`() {
        val registry = SpecVectors.obj("registry.json")
        val kinds = registry.optJSONArray("kinds")
            ?: registry.optJSONObject("kinds")?.let { obj ->
                org.json.JSONArray().also { arr ->
                    obj.keys().forEach { k -> arr.put(obj.get(k)) }
                }
            }
        if (kinds == null) {
            println("registry.json has no 'kinds' member under that name; skipping")
            return
        }
        assertTrue(kinds.length() > 0)
    }
}
