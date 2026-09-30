package com.eclypses.relay.session

import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.Token
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class RelayControlPlaneContractsTest {

    private val token = Token(ByteArray(40) { (it + 1).toByte() })
    private val pair1 = PairId.parse("AAECAwQFBgcICQoLDA0ODw")
    private val pair2 = PairId.parse("EBESExQVFhcYGRobHB0eHw")


    @Test
    fun `mapPairResponseToMaterials applies cross-wire mapping and uses the nonce verbatim`() {
        val request = RelayPairRequest(
            token = token,
            pairs = listOf(
                RelayPairRequestItem(
                    pairId = pair1,
                    encoderPublicKey = "enc-pk",
                    encoderPersonalizationStr = "enc-pers",
                    decoderPublicKey = "dec-pk",
                    decoderPersonalizationStr = "dec-pers",
                ),
            ),
        )
        val response = listOf(
            RelayPairResponseItem(
                pairId = pair1,
                encoderNonce = "9223372036854775809",
                encoderSecret = Base64.getEncoder().encodeToString("encoder-secret".toByteArray()),
                decoderNonce = "9223372036854775813",
                decoderSecret = Base64.getEncoder().encodeToString("decoder-secret".toByteArray()),
            ),
        )

        val material = RelayControlPlaneContracts.mapPairResponseToMaterials(request, response).single()

        assertEquals(pair1, material.pairId)
        // Verbatim: parseUnsignedLong of these top-bit-set wire values, with no mask.
        // A compliant server never sends them, but if one did we must not silently
        // rewrite the value — that divergence is what the nonce spec exists to prevent.
        assertEquals(java.lang.Long.parseUnsignedLong("9223372036854775813"), material.encoderNonce)
        assertEquals(java.lang.Long.parseUnsignedLong("9223372036854775809"), material.decoderNonce)
        assertContentEquals("decoder-secret".toByteArray(), material.encoderResponderEncryptedSecret)
        assertContentEquals("encoder-secret".toByteArray(), material.decoderResponderEncryptedSecret)
        assertEquals("enc-pers", material.encoderPersonalizationStr)
        assertEquals("dec-pers", material.decoderPersonalizationStr)
    }

    @Test
    fun `mapPairResponseToMaterials passes a compliant nonce through unchanged`() {
        val compliant = "9223372036854775807" // 2^63 - 1, the largest value a compliant server sends
        val request = RelayPairRequest(
            token = token,
            pairs = listOf(
                RelayPairRequestItem(
                    pairId = pair1,
                    encoderPublicKey = "enc-pk",
                    encoderPersonalizationStr = "enc-pers",
                    decoderPublicKey = "dec-pk",
                    decoderPersonalizationStr = "dec-pers",
                ),
            ),
        )
        val response = listOf(
            RelayPairResponseItem(
                pairId = pair1,
                encoderNonce = compliant,
                encoderSecret = Base64.getEncoder().encodeToString("encoder-secret".toByteArray()),
                decoderNonce = compliant,
                decoderSecret = Base64.getEncoder().encodeToString("decoder-secret".toByteArray()),
            ),
        )

        val material = RelayControlPlaneContracts.mapPairResponseToMaterials(request, response).single()

        assertEquals(9223372036854775807L, material.encoderNonce)
        assertEquals(9223372036854775807L, material.decoderNonce)
    }

    @Test
    fun `mapPairResponseToMaterials rejects missing pair responses`() {
        val request = RelayPairRequest(
            token = token,
            pairs = listOf(
                RelayPairRequestItem(pair1, "a", "b", "c", "d"),
                RelayPairRequestItem(pair2, "e", "f", "g", "h"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            RelayControlPlaneContracts.mapPairResponseToMaterials(
                request,
                listOf(
                    RelayPairResponseItem(
                        pairId = pair1,
                        encoderNonce = "1",
                        encoderSecret = Base64.getEncoder().encodeToString("x".toByteArray()),
                        decoderNonce = "1",
                        decoderSecret = Base64.getEncoder().encodeToString("y".toByteArray()),
                    ),
                ),
            )
        }

        assertEquals(true, error.message?.contains("missing pairIds") == true)
    }
}
