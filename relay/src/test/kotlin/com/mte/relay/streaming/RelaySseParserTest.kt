package com.mte.relay.streaming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelaySseParserTest {

    @Test
    fun append_handlesPartialChunksAcrossReads() {
        val parser = RelaySseParser()

        val first = parser.append("data: Hel".toByteArray())
        val second = parser.append("lo\n\n".toByteArray())

        assertEquals(emptyList(), first)
        assertEquals(listOf("Hello"), second)
    }

    @Test
    fun append_emitsMultipleCanonicalEventsFromOneRead() {
        val parser = RelaySseParser()

        val events = parser.append("data: first\n\ndata: second\n\n".toByteArray())

        assertEquals(listOf("first", "second"), events)
    }

    @Test
    fun append_supportsBlankLineDelimitedCanonicalFraming() {
        val parser = RelaySseParser()

        val first = parser.append(": keep-alive\n".toByteArray())
        val second = parser.append("data: payload\n\n".toByteArray())

        assertEquals(emptyList(), first)
        assertEquals(listOf("payload"), second)
    }

    @Test
    fun append_supportsDataOnlyCompatibilityMode() {
        val parser = RelaySseParser()

        val events = parser.append("data: one\ndata: two\ndata: three\n".toByteArray())

        assertEquals(listOf("one", "two"), events)
        assertEquals(listOf("three"), parser.finish())
    }

    @Test
    fun finish_flushesTrailingDataAndCommentLinesAtEof() {
        val parser = RelaySseParser()

        parser.append(": comment\n".toByteArray())
        parser.append("data: final-fragment".toByteArray())

        assertEquals(listOf("final-fragment"), parser.finish())
    }

    @Test
    fun append_rejectsPendingBufferOverflow() {
        val parser = RelaySseParser(maxPendingCharacters = 8)

        val error = assertFailsWith<IllegalStateException> {
            parser.append("data: toolong".toByteArray())
        }

        assertEquals("SSE parser buffer exceeded limit of 8 characters", error.message)
    }

    @Test
    fun append_decodesSplitUtf8CharactersIncrementally() {
        val parser = RelaySseParser()
        val source = "data: €\n\n".toByteArray(Charsets.UTF_8)

        val first = parser.append(source.copyOfRange(0, 7))
        val second = parser.append(source.copyOfRange(7, source.size))

        assertEquals(emptyList(), first)
        assertEquals(listOf("€"), second)
    }
}