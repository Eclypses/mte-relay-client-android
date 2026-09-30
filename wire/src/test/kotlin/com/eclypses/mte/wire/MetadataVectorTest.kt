package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.fail

/**
 * `vectors/metadata.json`: 39 cases, including the four platform traps that produce
 * valid JSON but non canonical bytes -- slash escaping, base64 line breaks, unsorted
 * keys and duplicate keys.
 */
class MetadataVectorTest {

    @Test
    fun `every metadata vector`() {
        val cases = SpecVectors.array("metadata.json")
        val failures = mutableListOf<String>()
        var canonicalised = 0
        var refused = 0

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val input = c.getString("input")

            if (c.has("error")) {
                val want = c.getString("error")
                try {
                    val got = Metadata.canonicalize(input)
                    failures += "$name: expected '$want', produced $got"
                } catch (e: WireException) {
                    if (e.error.token != want) {
                        failures += "$name: got '${e.error.token}', want '$want'"
                    } else {
                        refused++
                    }
                }
                continue
            }

            try {
                val got = Metadata.canonicalize(input)
                if (got != c.getString("canonical")) {
                    failures += "$name:\n      got  $got\n      want ${c.getString("canonical")}"
                } else {
                    canonicalised++
                }
            } catch (e: WireException) {
                failures += "$name: expected canonical output, got '${e.error.token}': ${e.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.length()} metadata vectors failed:\n  " +
                failures.joinToString("\n  "))
        }
        println("metadata: $canonicalised canonicalised, $refused refused, ${cases.length()} total")
    }

    /** Canonical output must be a fixed point: canonicalizing it again changes nothing. */
    @Test
    fun `canonical output is stable`() {
        val cases = SpecVectors.array("metadata.json")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.has("error")) continue
            val once = Metadata.canonicalize(c.getString("input"))
            val twice = Metadata.canonicalize(once)
            if (once != twice) fail("${c.getString("name")}: not a fixed point\n  $once\n  $twice")
        }
    }
}
