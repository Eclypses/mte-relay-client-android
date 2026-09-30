package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairIdTest {

    @Test
    fun theTextFormIsBase64UrlWithoutPadding() {
        val id = PairId(SpecVectors.hex("000102030405060708090a0b0c0d0e0f"))
        assertEquals("AAECAwQFBgcICQoLDA0ODw", id.text)
        assertEquals("000102030405060708090a0b0c0d0e0f", id.hex)
        // 16 bytes is 22 base64 characters plus two pad characters that must not be sent.
        assertEquals(22, id.text.length)
        assertTrue('=' !in id.text)
    }

    @Test
    fun theTextFormUsesTheUrlAlphabet() {
        // Bytes chosen so standard base64 would emit '+' and '/' where url emits '-' and '_'.
        val id = PairId(SpecVectors.hex("fbff00fbff00fbff00fbff00fbff0000"))
        assertTrue('+' !in id.text && '/' !in id.text, "got ${id.text}")
        assertEquals(id, PairId.parse(id.text))
    }

    @Test
    fun roundTripsThroughTheTextForm() {
        repeat(50) {
            val id = PairId.random()
            assertEquals(id, PairId.parse(id.text))
            assertEquals(32, id.hex.length)
        }
    }

    @Test
    fun paddingIsToleratedOnTheWayIn() {
        val id = PairId.random()
        assertEquals(id, PairId.parse(id.text + "=="))
    }

    @Test
    fun rejectsTheWrongLength() {
        assertFailsWith<WireException> { PairId(ByteArray(15)) }
        assertFailsWith<WireException> { PairId(ByteArray(32)) }
        // The previous generation's 32 random characters decode to 24 bytes, not 16.
        assertFailsWith<WireException> { PairId.parse("abcdefghijklmnopqrstuvwxyz234567") }
    }

    /** All-zero is the OPEN frame's "no pair" sentinel, so it cannot also be an id. */
    @Test
    fun rejectsAllZero() {
        assertFailsWith<WireException> { PairId(ByteArray(16)) }
        assertTrue(RequestFrame.PAIR_ID_SIZE == 16)
    }

    @Test
    fun randomIdsDiffer() {
        assertNotEquals(PairId.random(), PairId.random())
    }
}
