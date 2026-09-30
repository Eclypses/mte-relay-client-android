package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.fail

/** `vectors/personalization.json`. */
class PersonalizationVectorTest {

    private fun transport(token: String) = when (token) {
        "http" -> Personalization.Transport.HTTP
        "ws" -> Personalization.Transport.WS
        "tcp" -> Personalization.Transport.TCP
        else -> error("unknown transport $token")
    }

    @Test
    fun `every personalization case`() {
        val doc = SpecVectors.obj("personalization.json")
        val cases = doc.getJSONArray("cases")
        val failures = mutableListOf<String>()
        var accepted = 0
        var refused = 0

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val t = transport(c.getString("transport"))

            // "A case carrying a pad appends char, count times, to value before
            // anything is measured." It keeps a 1024 byte fixture on one line.
            var value = c.getString("value")
            c.optJSONObject("pad")?.let { pad ->
                value += pad.getString("char").repeat(pad.getInt("count"))
            }

            if (c.has("error")) {
                val want = c.getString("error")
                try {
                    Personalization.validate(value, t)
                    failures += "$name: expected '$want', accepted"
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
                Personalization.validate(value, t)
                accepted++
            } catch (e: WireException) {
                failures += "$name: expected acceptance, got '${e.error.token}'"
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.length()} personalization cases failed:\n  " +
                failures.joinToString("\n  "))
        }
        println("personalization: $accepted accepted, $refused refused, ${cases.length()} total")
    }

    /**
     * The `minted` property: a generator must produce a string its own validator
     * accepts for the transport it was minted for, and refuses on the other two.
     * No single case above catches a generator that gets this wrong.
     */
    @Test
    fun `a minted string validates only on its own transport`() {
        val doc = SpecVectors.obj("personalization.json")
        val hosts = doc.getJSONObject("minted").optJSONArray("hosts")
        val hostList = buildList {
            add(null)
            if (hosts != null) for (i in 0 until hosts.length()) add(hosts.getString(i))
        }

        for (t in Personalization.Transport.entries) {
            for (host in hostList) {
                val minted = Personalization.mint(t, host)
                Personalization.validate(minted, t)   // own transport: accepted
                for (other in Personalization.Transport.entries.filter { it != t }) {
                    try {
                        Personalization.validate(minted, other)
                        fail("minted for ${t.token} was accepted on ${other.token}: $minted")
                    } catch (e: WireException) {
                        if (e.error != WireError.PERSONALIZATION_PREFIX) {
                            fail("minted for ${t.token} on ${other.token}: '${e.error.token}'")
                        }
                    }
                }
            }
        }
        println("personalization/minted: every transport round-trips")
    }
}
