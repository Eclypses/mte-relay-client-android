package com.eclypses.relay.session

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that [OkHttpRelayControlPlaneClient]'s private LegacyRuntimePair correctly
 * delegates streaming encrypt methods (startEncrypt / encryptChunk / finishEncrypt /
 * encryptFinishBytes) to the underlying legacy pair object via reflection.
 *
 * Uses a JVM-only FakeLegacyPair that mimics the Pair.java method signatures expected
 * by the reflection bridge — no native MTE library is required.
 */
class LegacyRuntimePairStreamingTest {

    // ---- Fake legacy pair mimicking Pair.java's method shapes -----------------

    /**
     * Fake result object returned by [FakeLegacyPair.finishEncrypt].
     * Must expose a field named "arr" of type ByteArray — this is what
     * LegacyRuntimePair reads via getDeclaredField("arr").
     */
    @Suppress("unused")
    class FakeArrStatus(@JvmField val arr: ByteArray)

    /** Minimal stand-in for Pair.java with only the streaming-relevant methods. */
    @Suppress("unused")
    class FakeLegacyPair(private val trailing: ByteArray = byteArrayOf(0xAB.toByte(), 0xCD.toByte())) {
        var startEncryptCount = 0
        val encryptChunkCalls = mutableListOf<Pair<ByteArray, Int>>()
        var finishEncryptCount = 0
        var startDecryptCount = 0
        val decryptChunkCalls = mutableListOf<ByteArray>()
        var finishDecryptCount = 0

        fun startEncrypt() {
            startEncryptCount++
        }

        fun encryptChunk(bytes: ByteArray, len: Int) {
            encryptChunkCalls += bytes.copyOf() to len
        }

        fun finishEncrypt(): FakeArrStatus {
            finishEncryptCount++
            return FakeArrStatus(trailing)
        }

        fun getFinishEncryptBytes(): Int = trailing.size

        fun startDecrypt() {
            startDecryptCount++
        }

        fun decryptChunk(encoded: ByteArray): ByteArray {
            decryptChunkCalls += encoded.copyOf()
            return encoded // passthrough: decoded == encoded in the fake
        }

        fun finishDecrypt(): FakeArrStatus {
            finishDecryptCount++
            return FakeArrStatus(trailing)
        }
    }

    // ---- Helpers to access private LegacyRuntimePair --------------------------

    /**
     * Creates a [RelayRuntimePair] backed by the given [fakeLegacyPair] by instantiating
     * the private [OkHttpRelayControlPlaneClient.LegacyRuntimePair] class via reflection.
     */
    private fun makeLegacyRuntimePair(fakeLegacyPair: Any, pairId: String): RelayRuntimePair {
        val clazz = OkHttpRelayControlPlaneClient::class.java.declaredClasses
            .first { it.simpleName == "LegacyRuntimePair" }
        // LegacyRuntimePair is a static nested class — constructor: (Any legacyPair, String pairId)
        val ctor = clazz.getDeclaredConstructor(Any::class.java, String::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(fakeLegacyPair, pairId) as RelayRuntimePair
    }

    // ---- Tests ----------------------------------------------------------------

    @Test
    fun `startEncrypt delegates to underlying legacy pair`() {
        val fake = FakeLegacyPair()
        val pair = makeLegacyRuntimePair(fake, "test-pair")

        pair.startEncrypt()
        pair.startEncrypt()

        assertEquals(2, fake.startEncryptCount)
    }

    @Test
    fun `encryptChunk delegates buffer and length to underlying legacy pair`() {
        val fake = FakeLegacyPair()
        val pair = makeLegacyRuntimePair(fake, "test-pair")

        val buf1 = byteArrayOf(1, 2, 3)
        val buf2 = byteArrayOf(4, 5, 6, 7)
        pair.encryptChunk(buf1, buf1.size)
        pair.encryptChunk(buf2, buf2.size)

        assertEquals(2, fake.encryptChunkCalls.size)
        assertContentEquals(buf1, fake.encryptChunkCalls[0].first)
        assertEquals(buf1.size, fake.encryptChunkCalls[0].second)
        assertContentEquals(buf2, fake.encryptChunkCalls[1].first)
        assertEquals(buf2.size, fake.encryptChunkCalls[1].second)
    }

    @Test
    fun `finishEncrypt delegates and returns arr field bytes from legacy pair result`() {
        val trailingBytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val fake = FakeLegacyPair(trailing = trailingBytes)
        val pair = makeLegacyRuntimePair(fake, "test-pair")

        val result = pair.finishEncrypt()

        assertEquals(1, fake.finishEncryptCount)
        assertContentEquals(trailingBytes, result)
    }

    @Test
    fun `encryptFinishBytes delegates to getFinishEncryptBytes on underlying legacy pair`() {
        val trailing = ByteArray(7)
        val fake = FakeLegacyPair(trailing = trailing)
        val pair = makeLegacyRuntimePair(fake, "test-pair")

        assertEquals(7, pair.encryptFinishBytes())
    }

    @Test
    fun `pairId is set correctly`() {
        val fake = FakeLegacyPair()
        val pair = makeLegacyRuntimePair(fake, "my-pair-id")

        assertEquals("my-pair-id", pair.pairId)
    }

    @Test
    fun `full streaming sequence delegates in order`() {
        val trailingBytes = byteArrayOf(0xFF.toByte(), 0x00.toByte())
        val fake = FakeLegacyPair(trailing = trailingBytes)
        val pair = makeLegacyRuntimePair(fake, "seq-pair")

        val finishSize = pair.encryptFinishBytes()
        assertEquals(trailingBytes.size, finishSize)

        pair.startEncrypt()
        val chunkA = byteArrayOf(10, 20, 30)
        pair.encryptChunk(chunkA, chunkA.size)
        val trailing = pair.finishEncrypt()

        assertEquals(1, fake.startEncryptCount)
        assertTrue(fake.encryptChunkCalls.isNotEmpty())
        assertContentEquals(trailingBytes, trailing)
    }

    // ---- Streaming decrypt tests -----------------------------------------------

    @Test
    fun `startDecrypt delegates to underlying legacy pair`() {
        val fake = FakeLegacyPair()
        val pair = makeLegacyRuntimePair(fake, "test-pair")

        pair.startDecrypt()
        pair.startDecrypt()

        assertEquals(2, fake.startDecryptCount)
    }

    @Test
    fun `decryptChunk delegates buffer to underlying legacy pair and returns result`() {
        val fake = FakeLegacyPair()
        val pair = makeLegacyRuntimePair(fake, "test-pair")

        val chunk1 = byteArrayOf(0x01, 0x02, 0x03)
        val chunk2 = byteArrayOf(0x04, 0x05)
        val result1 = pair.decryptChunk(chunk1)
        val result2 = pair.decryptChunk(chunk2)

        assertEquals(2, fake.decryptChunkCalls.size)
        assertContentEquals(chunk1, fake.decryptChunkCalls[0])
        assertContentEquals(chunk2, fake.decryptChunkCalls[1])
        // Fake returns input unchanged
        assertContentEquals(chunk1, result1)
        assertContentEquals(chunk2, result2)
    }

    @Test
    fun `finishDecrypt delegates and returns arr field bytes from legacy pair result`() {
        val trailingBytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
        val fake = FakeLegacyPair(trailing = trailingBytes)
        val pair = makeLegacyRuntimePair(fake, "test-pair")

        val result = pair.finishDecrypt()

        assertEquals(1, fake.finishDecryptCount)
        assertContentEquals(trailingBytes, result)
    }

    @Test
    fun `full streaming decrypt sequence delegates in order`() {
        val trailingBytes = byteArrayOf(0xBE.toByte(), 0xEF.toByte())
        val fake = FakeLegacyPair(trailing = trailingBytes)
        val pair = makeLegacyRuntimePair(fake, "decrypt-seq-pair")

        pair.startDecrypt()
        val chunkA = byteArrayOf(10, 20, 30)
        val decoded = pair.decryptChunk(chunkA)
        val trailing = pair.finishDecrypt()

        assertEquals(1, fake.startDecryptCount)
        assertEquals(1, fake.decryptChunkCalls.size)
        assertContentEquals(chunkA, decoded)
        assertContentEquals(trailingBytes, trailing)
    }
}
