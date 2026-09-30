package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Measures our tables against `vectors/registry.json`, in both directions.
 *
 * `registry.md`: "a row added on one side and not the other fails a build rather
 * than waiting for a reader to compare two documents". `wire/registryvectors_test.go`
 * and `mte-relay-browser/src/protocol/registryVectors.test.ts` do this on the other
 * two implementations; this is the third.
 *
 * Both directions matters. Checking only that the registry's rows exist here would
 * pass a table that invented extra ones, and inventing a code or reason is how a
 * client acts on something no server sends.
 */
class RegistryVectorTest {

    private val registry by lazy { SpecVectors.obj("registry.json") }

    @Test
    fun `codes and reasons match the registry exactly`() {
        val codes = registry.getJSONArray("codes")
        val failures = mutableListOf<String>()
        val seen = mutableSetOf<Int>()

        for (i in 0 until codes.length()) {
            val entry = codes.getJSONObject(i)
            val code = entry.getInt("code")
            seen += code
            if (code !in RelayRegistry.codes) {
                failures += "code $code is in the registry and not in our table"
                continue
            }
            val reasons = entry.getJSONArray("reasons")
            val theirs = mutableSetOf<String>()
            for (j in 0 until reasons.length()) {
                val r = reasons.getJSONObject(j)
                val reason = r.getString("reason")
                theirs += reason
                val wantAction = RelayAction.of(r.getString("action"))
                    ?: fail("registry names an action we do not have: ${r.getString("action")}")
                val got = RelayRegistry.action(code, reason)
                if (got != wantAction) {
                    failures += "$code $reason: we say ${got.token}, registry says ${wantAction.token}"
                }
            }
            val ours = RelayRegistry.reasons(code)
            (ours - theirs).forEach { failures += "$code $it: we invented a reason the registry does not have" }
            (theirs - ours).forEach { failures += "$code $it: registry has a reason we do not" }
        }

        (RelayRegistry.codes - seen).forEach {
            failures += "code $it: we invented a code the registry does not have"
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} registry mismatches:\n  " + failures.joinToString("\n  "))
        }
        println("registry: ${seen.size} codes match in both directions")
    }

    @Test
    fun `an unregistered reason takes the code's default action`() {
        val defaults = registry.getJSONArray("action_defaults")
        var checked = 0
        for (i in 0 until defaults.length()) {
            val d = defaults.getJSONObject(i)
            if (!d.has("code")) continue   // the leading note
            val want = RelayAction.of(d.getString("action"))!!
            val got = RelayRegistry.action(d.getInt("code"), d.getString("reason"))
            assertEquals(want, got, "default for ${d.getInt("code")}")
            checked++
        }
        println("registry/defaults: $checked unregistered reasons fall back correctly")
    }

    /**
     * An unregistered code has no default and is surfaced -- never a pair
     * replacement on a guess. This includes the retired 559..569 block, which reused
     * reason tokens the current block also uses: 559 carries `pair_not_found` and
     * must NOT be acted on as 470 does.
     */
    @Test
    fun `unregistered codes surface rather than act`() {
        val unregistered = registry.getJSONArray("unregistered_codes")
        for (i in 0 until unregistered.length()) {
            val code = unregistered.getInt(i)
            assertEquals(
                RelayAction.SURFACE,
                RelayRegistry.action(code, "pair_not_found"),
                "unregistered code $code must surface",
            )
        }
        println("registry/unregistered: ${unregistered.length()} codes surface")
    }

    @Test
    fun `the reason token shape matches`() {
        val t = registry.getJSONObject("reason_tokens")
        val valid = t.getJSONArray("valid")
        for (i in 0 until valid.length()) {
            val s = valid.getString(i)
            if (!RelayError.isReasonToken(s)) fail("rejected a valid reason token: \"$s\"")
        }
        val invalid = t.getJSONArray("invalid")
        for (i in 0 until invalid.length()) {
            val s = invalid.getString(i)
            if (RelayError.isReasonToken(s)) fail("accepted an invalid reason token: \"$s\"")
        }
        println("registry/tokens: ${valid.length()} valid, ${invalid.length()} invalid")
    }

    @Test
    fun `kinds, flag bits, MTE types and method bytes match`() {
        val failures = mutableListOf<String>()

        val kinds = registry.getJSONArray("kinds")
        for (i in 0 until kinds.length()) {
            val k = kinds.getJSONObject(i)
            val assigned = k.getString("status") == "assigned"
            // Unassigned, experimental and reserved rows describe ranges with
            // "through" and carry no name; every value in the range must be rejected.
            val from = k.getInt("kind")
            val to = k.optInt("through", from)
            for (v in from..to) {
                val got = Kind.of(v)
                if (assigned) {
                    if (got == null) {
                        failures += "kind $v assigned, we reject it"
                    } else if (got.name != k.getString("name")) {
                        failures += "kind $v: we call it ${got.name}, registry says ${k.getString("name")}"
                    }
                } else if (got != null) {
                    failures += "kind $v is ${k.getString("status")}, we accept it as ${got.name}"
                }
            }
        }

        val bits = registry.getJSONArray("flag_bits")
        for (i in 0 until bits.length()) {
            val b = bits.getJSONObject(i)
            val mask = b.getInt("mask")
            val reserved = b.getString("status") != "assigned"
            val weReserve = (Envelope.RESERVED_FLAG_MASK and mask) != 0
            if (reserved != weReserve) {
                failures += "flag bit ${b.getInt("bit")} (${b.getString("status")}): we ${if (weReserve) "reserve" else "assign"} it"
            }
        }

        // Like kinds, the unassigned rows are ranges named by "through" with no
        // name, and every value in one must be refused.
        val types = registry.getJSONArray("mte_types")
        for (i in 0 until types.length()) {
            val t = types.getJSONObject(i)
            val from = t.getInt("value")
            val to = t.optInt("through", from)
            for (v in from..to) {
                val got = MteType.of(v)
                if (t.has("name")) {
                    if (got == null || got.name != t.getString("name")) {
                        failures += "mte type $v: registry says ${t.getString("name")}, we say ${got?.name}"
                    }
                } else if (got != null) {
                    failures += "mte type $v is ${t.getString("status")}, we accept it as ${got.name}"
                }
            }
        }

        val methods = registry.getJSONArray("method_bytes")
        for (i in 0 until methods.length()) {
            val m = methods.getJSONObject(i)
            val from = m.getInt("value")
            val to = m.optInt("through", from)
            val unassigned = m.optString("status") == "unassigned"
            for (v in from..to) {
                val weAccept = v in 0..8 || v == RequestFrame.METHOD_EXTENDED
                if (unassigned && weAccept) {
                    failures += "method byte $v is unassigned and we accept it"
                }
                if (!unassigned && !weAccept) {
                    failures += "method byte $v is registered and we reject it"
                }
            }
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} table mismatches:\n  " + failures.joinToString("\n  "))
        }
        println("registry/tables: kinds, flags, mte types and method bytes all match")
    }

    @Test
    fun `the signature and error header match`() {
        val sigs = registry.getJSONArray("signature")
        for (i in 0 until sigs.length()) {
            val s = sigs.getJSONObject(i)
            val want = SpecVectors.hex(s.getString("hex"))
            val ours = if (s.getString("name") == "legacy") Envelope.LEGACY_SIGNATURE else Envelope.SIGNATURE
            assertEquals(SpecVectors.toHex(want), SpecVectors.toHex(ours), s.getString("name"))
        }
        assertEquals(registry.getJSONObject("header_string").getString("header"), RelayError.HEADER)

        val example = registry.getJSONObject("header_string").getJSONObject("example")
        val parsed = RelayError.parseHeader("${example.getInt("code")} ${example.getString("reason")}")
            ?: fail("could not parse the registry's own example header")
        assertEquals(example.getInt("code"), parsed.code)
        assertEquals(example.getString("reason"), parsed.reason)
        println("registry/signature: signature and ${RelayError.HEADER} match")
    }
}
