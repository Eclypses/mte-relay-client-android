package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** `vectors/request.json`, `vectors/response.json` and `vectors/data.json`. */
class FrameVectorTest {

    @Test
    fun `every request vector`() {
        val cases = SpecVectors.array("request.json")
        val failures = mutableListOf<String>()

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val bytes = SpecVectors.hex(c.getString("hex"))

            if (c.has("error")) {
                val want = c.getString("error")
                try {
                    RequestFrame.read(bytes)
                    failures += "$name: expected '$want', parsed"
                } catch (e: WireException) {
                    if (e.error.token != want) failures += "$name: got '${e.error.token}', want '$want'"
                }
                continue
            }

            val want = c.getJSONObject("expect")
            try {
                val got = RequestFrame.read(bytes)
                when {
                    got.mteType.value != want.getInt("mteType") ->
                        failures += "$name: mteType ${got.mteType.value}"
                    got.methodByte != want.getInt("method") ->
                        failures += "$name: method ${got.methodByte}"
                    SpecVectors.toHex(got.pairId) != want.getString("pairId") ->
                        failures += "$name: pairId ${SpecVectors.toHex(got.pairId)}"
                    SpecVectors.toHex(got.token) != want.getString("token") ->
                        failures += "$name: token ${SpecVectors.toHex(got.token)}"
                    SpecVectors.toHex(got.metadata) != want.getString("metadata") ->
                        failures += "$name: metadata ${SpecVectors.toHex(got.metadata)}"
                    got.isConnection != want.getBoolean("connection") ->
                        failures += "$name: connection ${got.isConnection}"
                    // The writer is not covered by the file; round-trip closes that.
                    SpecVectors.toHex(got.toByteArray()) != c.getString("hex") ->
                        failures += "$name: does not round-trip"
                }
            } catch (e: WireException) {
                failures += "$name: expected a parse, got '${e.error.token}'"
            }
        }
        report("request", failures, cases.length())
    }

    @Test
    fun `every response vector`() {
        val cases = SpecVectors.array("response.json")
        val failures = mutableListOf<String>()

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val bytes = SpecVectors.hex(c.getString("hex"))

            if (c.has("error")) {
                val want = c.getString("error")
                try {
                    ResponseFrame.read(bytes)
                    failures += "$name: expected '$want', parsed"
                } catch (e: WireException) {
                    if (e.error.token != want) failures += "$name: got '${e.error.token}', want '$want'"
                }
                continue
            }

            val want = c.getJSONObject("expect")
            try {
                val got = ResponseFrame.read(bytes)
                when {
                    got.status != want.getInt("status") -> failures += "$name: status ${got.status}"
                    SpecVectors.toHex(got.metadata) != want.getString("metadata") ->
                        failures += "$name: metadata ${SpecVectors.toHex(got.metadata)}"
                    SpecVectors.toHex(got.toByteArray()) != c.getString("hex") ->
                        failures += "$name: does not round-trip"
                }
            } catch (e: WireException) {
                failures += "$name: expected a parse, got '${e.error.token}'"
            }
        }
        report("response", failures, cases.length())
    }

    @Test
    fun `DATA plaintext carries the flags byte`() {
        val doc = SpecVectors.obj("data.json")
        val build = doc.getJSONArray("plaintext")
        val failures = mutableListOf<String>()

        for (i in 0 until build.length()) {
            val c = build.getJSONObject(i)
            val got = DataPlaintext.build(c.getInt("flags"), SpecVectors.hex(c.getString("app")))
            if (SpecVectors.toHex(got) != c.getString("plaintext")) {
                failures += "${c.getString("name")}: built ${SpecVectors.toHex(got)}"
            }
        }
        report("data/plaintext", failures, build.length())
    }

    @Test
    fun `DATA plaintext verifies the flags byte against the envelope`() {
        val doc = SpecVectors.obj("data.json")
        val split = doc.getJSONArray("split")
        val failures = mutableListOf<String>()

        for (i in 0 until split.length()) {
            val c = split.getJSONObject(i)
            val name = c.getString("name")
            val plaintext = SpecVectors.hex(c.getString("plaintext"))
            val want = c.getInt("want")

            if (c.has("error")) {
                val wantErr = c.getString("error")
                try {
                    DataPlaintext.split(plaintext, want)
                    failures += "$name: expected '$wantErr', accepted"
                } catch (e: WireException) {
                    if (e.error.token != wantErr) {
                        failures += "$name: got '${e.error.token}', want '$wantErr'"
                    }
                }
                continue
            }

            try {
                val app = DataPlaintext.split(plaintext, want)
                if (SpecVectors.toHex(app) != c.getString("app")) {
                    failures += "$name: app ${SpecVectors.toHex(app)}"
                }
            } catch (e: WireException) {
                failures += "$name: expected a split, got '${e.error.token}'"
            }
        }
        report("data/split", failures, split.length())
    }

    private fun report(label: String, failures: List<String>, total: Int) {
        if (failures.isNotEmpty()) {
            fail("${failures.size} of $total $label vectors failed:\n  " + failures.joinToString("\n  "))
        }
        assertEquals(0, failures.size)
        println("$label: $total vectors pass")
    }
}
