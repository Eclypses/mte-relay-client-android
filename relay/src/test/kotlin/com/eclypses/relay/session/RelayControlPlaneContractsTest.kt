package com.eclypses.relay.session

import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class RelayControlPlaneContractsTest {

    @Test
    fun `mapPairResponseToMaterials applies cross-wire mapping and nonce mask`() {
        val request = RelayPairRequest(
            clientId = "client-1",
            pairs = listOf(
                RelayPairRequestItem(
                    pairId = "pair-1",
                    encoderPublicKey = "enc-pk",
                    encoderPersonalizationStr = "enc-pers",
                    decoderPublicKey = "dec-pk",
                    decoderPersonalizationStr = "dec-pers",
                ),
            ),
        )
        val response = listOf(
            RelayPairResponseItem(
                pairId = "pair-1",
                encoderNonce = "9223372036854775809",
                encoderSecret = Base64.getEncoder().encodeToString("encoder-secret".toByteArray()),
                decoderNonce = "9223372036854775813",
                decoderSecret = Base64.getEncoder().encodeToString("decoder-secret".toByteArray()),
            ),
        )

        val material = RelayControlPlaneContracts.mapPairResponseToMaterials(request, response).single()

        assertEquals("pair-1", material.pairId)
        assertEquals(5L, material.encoderNonce)
        assertEquals(1L, material.decoderNonce)
        assertContentEquals("decoder-secret".toByteArray(), material.encoderResponderEncryptedSecret)
        assertContentEquals("encoder-secret".toByteArray(), material.decoderResponderEncryptedSecret)
        assertEquals("enc-pers", material.encoderPersonalizationStr)
        assertEquals("dec-pers", material.decoderPersonalizationStr)
    }

    @Test
    fun `mapPairResponseToMaterials rejects missing pair responses`() {
        val request = RelayPairRequest(
            clientId = "client-1",
            pairs = listOf(
                RelayPairRequestItem("pair-1", "a", "b", "c", "d"),
                RelayPairRequestItem("pair-2", "e", "f", "g", "h"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            RelayControlPlaneContracts.mapPairResponseToMaterials(
                request,
                listOf(
                    RelayPairResponseItem(
                        pairId = "pair-1",
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
