package com.eclypses.relay.protocol

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eclypses.mte.MteBase
import com.eclypses.mte.MteMkeEnc
import com.eclypses.mte.MteStatus
import com.eclypses.mte.wire.Envelope
import com.eclypses.mte.wire.MteType
import com.eclypses.relay.session.MteLicense
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The MKE chunk margin, checked against the real encoder.
 *
 * [RelayFrameWriter.maxChunkBytes] reserves a fixed 512 bytes for the MKE header and MAC
 * rather than asking MTE. A margin that is too large only wastes a few bytes per frame; one
 * that is too small is a real failure, and a late one: the frame's size is checked after the
 * encode, so an oversized DATA frame is refused with the encoder already one operation ahead
 * of the relay's decoder. These tests are what make the fixed margin safe to keep -- if an
 * MTE release ever grows its overhead past it, they fail here instead of in an upload.
 *
 * Instrumented because MKE needs the native library.
 */
@RunWith(AndroidJUnit4::class)
class MkeChunkMarginTest {

    /** A small relay, the 65536 the live relays advertise, and a large one. */
    private val frameSizes = listOf(4_096, 65_536, 1 shl 20)

    @Before
    fun licence() = MteLicense.require()

    /** An instantiated MKE encoder with entropy and nonce set directly, as offline tests do. */
    private fun makeEncoder(): MteMkeEnc {
        val encoder = MteMkeEnc()
        encoder.setEntropy(ByteArray(MteBase.getDrbgsEntropyMinBytes(encoder.drbg)) { 0xA5.toByte() })
        encoder.setNonce(1)
        assertEquals(MteStatus.mte_status_success, encoder.instantiate("margin-test"))
        return encoder
    }

    /** A full-size chunk, encoded for real, builds a frame the relay would accept. */
    @Test
    fun fullChunkFrameFitsMaxFrameBytes() {
        val encoder = makeEncoder()
        for (maxFrameBytes in frameSizes) {
            var lastPlaintext = 0
            var lastEncoded = 0
            val encode: (ByteArray) -> ByteArray = { plaintext ->
                val r = encoder.encode(plaintext)
                assertEquals(MteStatus.mte_status_success, r.status)
                lastPlaintext = plaintext.size
                lastEncoded = r.arr.size
                r.arr
            }
            val writer = RelayFrameWriter(encode, MteType.MKE, maxFrameBytes)
            val app = ByteArray(writer.maxChunkBytes()) { 0x5A }

            for (end in listOf(false, true)) {
                val out = ByteArrayOutputStream()
                // Throws RelayProtocolException if the encoded frame is over the bound.
                writer.writeData(out, app, end)
                assertTrue("maxFrameBytes $maxFrameBytes, end $end", out.size() <= maxFrameBytes)
                println("MKE overhead: ${lastEncoded - lastPlaintext} bytes " +
                    "(margin 512, maxFrameBytes $maxFrameBytes)")
            }
        }
    }

    /** MTE's own sizing agrees: the buffer it asks for a full chunk still fits the frame. */
    @Test
    fun buffBytesForFullChunkFitsMaxFrameBytes() {
        val encoder = makeEncoder()
        for (maxFrameBytes in frameSizes) {
            val writer = RelayFrameWriter({ it }, MteType.MKE, maxFrameBytes)
            // The plaintext is the chunk plus the bound flags byte.
            val needed = encoder.getBuffBytes(writer.maxChunkBytes() + 1)
            assertTrue("maxFrameBytes $maxFrameBytes", Envelope.SIZE + needed <= maxFrameBytes)
        }
    }
}
