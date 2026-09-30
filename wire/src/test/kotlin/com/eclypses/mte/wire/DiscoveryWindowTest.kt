package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The two pairing inputs, in the cases the vector documents do not cover.
 *
 * `discovery.json` carries both members in every document, "minimal" included, so a
 * conforming relay never omits either. These pin what happens if one does -- and the
 * answer differs per member, which is the whole point.
 */
class DiscoveryWindowTest {

    private fun doc(extra: String): String = """
        {"discoverySchema":1,"buildVersion":"5.0.0","frameVersions":[2],"features":[],
         "mteProfile":"mte/4.2.1","kyberStrength":1024,
         "maxFrameBytes":65536,"maxMessageBytes":1048576,
         "maxMetadataBytes":{"MKE":61439,"MTE":61439},
         "transports":["http"]$extra}
    """.trimIndent()

    /**
     * Minus 63, not zero. The relay ships minus 63 and the browser client falls back to
     * it; a zero here would be read as a window the server does not use, and the pair
     * would fail from its first frame with nothing saying why.
     */
    @Test
    fun anAbsentSequenceWindowFallsBackToWhatTheRelayShips() {
        assertEquals(-63, Discovery.parse(doc("")).sequenceWindow)
        assertEquals(-63, Discovery.SEQUENCE_WINDOW_DEFAULT)
    }

    @Test
    fun anAbsentTimeWindowFallsBackToOneThousand() {
        assertEquals(1000, Discovery.parse(doc("")).timeWindow)
    }

    /** Present and zero is a different case from absent, and is adopted. */
    @Test
    fun aPresentZeroSequenceWindowIsAdopted() {
        assertEquals(0, Discovery.parse(doc(""","sequenceWindow":0""")).sequenceWindow)
    }

    /** Present and zero is not adopted here: zero is also Go's unset value. */
    @Test
    fun aPresentZeroTimeWindowIsNotAdopted() {
        assertEquals(1000, Discovery.parse(doc(""","timeWindow":0""")).timeWindow)
    }

    @Test
    fun aSequenceWindowOutsideTheLibraryRangeIsRefused() {
        assertFailsWith<WireException> { Discovery.parse(doc(""","sequenceWindow":-64""")) }
        assertFailsWith<WireException> { Discovery.parse(doc(""","sequenceWindow":65536""")) }
        assertEquals(-63, Discovery.parse(doc(""","sequenceWindow":-63""")).sequenceWindow)
        assertEquals(65535, Discovery.parse(doc(""","sequenceWindow":65535""")).sequenceWindow)
    }
}
