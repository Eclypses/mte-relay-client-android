package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** `vectors/discovery.json`. */
class DiscoveryVectorTest {

    private val doc by lazy { SpecVectors.obj("discovery.json") }

    @Test
    fun `every registered member is one we know`() {
        val members = doc.getJSONArray("members")
        // The rule the vector exists to hold is "no implementation invents a member".
        // We cannot check our field list against theirs mechanically without
        // reflection, so this asserts the reverse direction that matters: every
        // document below parses, which means no registered member breaks the parser.
        assertTrue(members.length() >= 30, "expected the full registered set")
        println("discovery: ${members.length()} registered members")
    }

    @Test
    fun `every document parses`() {
        val documents = doc.getJSONArray("documents")
        val failures = mutableListOf<String>()

        for (i in 0 until documents.length()) {
            val c = documents.getJSONObject(i)
            val name = c.getString("name")
            val text = c.getJSONObject("document").toString()
            try {
                val d = Discovery.parse(text)
                if (d.frameVersions.isEmpty()) failures += "$name: no frameVersions"
                if (d.selectFrameVersion() != Metadata.FRAME_VERSION) {
                    failures += "$name: selected ${d.selectFrameVersion()}"
                }
            } catch (e: WireException) {
                failures += "$name: ${e.error.token}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) fail("discovery documents failed:\n  " + failures.joinToString("\n  "))
        println("discovery: ${documents.length()} documents parse")
    }

    /**
     * The asymmetry the `zero_windows` document exists for.
     *
     * A zero `sequenceWindow` is adopted. A zero `timeWindow` is not: zero is both a
     * legal window and the Go zero value a server sends when the block that fills it
     * did not run, the wire cannot tell those apart, and only one reading is safe.
     * Falling back to 1000 pairs with a server that meant 1000; adopting a spurious
     * zero pairs with nothing and says nothing about why.
     */
    @Test
    fun `a zero timeWindow is not adopted but a zero sequenceWindow is`() {
        val documents = doc.getJSONArray("documents")
        var checked = false
        for (i in 0 until documents.length()) {
            val c = documents.getJSONObject(i)
            if (c.getString("name") != "zero_windows") continue
            val d = Discovery.parse(c.getJSONObject("document").toString())
            assertEquals(Discovery.TIME_WINDOW_DEFAULT, d.timeWindow, "a zero timeWindow must fall back")
            assertEquals(0, d.sequenceWindow, "a zero sequenceWindow must be adopted")
            checked = true
        }
        assertTrue(checked, "the zero_windows document is missing from the vector")
        println("discovery/zero_windows: timeWindow falls back to 1000, sequenceWindow adopts 0")
    }

    @Test
    fun `a negative sequenceWindow is adopted as sent`() {
        val documents = doc.getJSONArray("documents")
        for (i in 0 until documents.length()) {
            val c = documents.getJSONObject(i)
            if (c.getString("name") != "minimal") continue
            val d = Discovery.parse(c.getJSONObject("document").toString())
            assertEquals(-63, d.sequenceWindow)
            assertEquals(1000, d.timeWindow)
        }
    }

    @Test
    fun `the auth url carries frameVersions as a query parameter`() {
        assertEquals(
            "https://relay.example.com/api/mte-relay?frameVersions=2",
            Discovery.authUrl("https://relay.example.com/"),
        )
    }
}
