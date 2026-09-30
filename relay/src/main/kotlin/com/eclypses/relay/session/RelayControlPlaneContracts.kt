package com.eclypses.relay.session

import com.eclypses.mte.wire.Discovery
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.Token
import java.util.Base64

/**
 * What auth returned.
 *
 * The client id is now a [Token]: 40 bytes carrying a 16 byte client id, an issued-at,
 * and a MAC the relay checks. The client never mints one and cannot forge one, but it
 * does read the issued-at to refresh before expiry, and it derives the client id for the
 * `X-MTE-Relay-Route` header.
 *
 * There is no longer a routing token distinct from the identity. The previous generation
 * carried an opaque server-minted value in that header purely for replica stickiness;
 * section 11 makes the header the client id hex, which is stable for the client's life
 * and needs no separate round trip.
 */
data class RelayAuthResponse(
    val token: Token,
    val discovery: Discovery,
)

data class RelayPairRequestItem(
    val pairId: PairId,
    val encoderPublicKey: String,
    val encoderPersonalizationStr: String,
    val decoderPublicKey: String,
    val decoderPersonalizationStr: String,
)

data class RelayPairRequest(
    val token: Token,
    val pairs: List<RelayPairRequestItem>,
)

data class RelayPairResponseItem(
    val pairId: PairId,
    val encoderNonce: String,
    val encoderSecret: String,
    val decoderNonce: String,
    val decoderSecret: String,
)

data class RelayPairMaterial(
    val pairId: PairId,
    val encoderNonce: Long,
    val decoderNonce: Long,
    val encoderResponderEncryptedSecret: ByteArray,
    val decoderResponderEncryptedSecret: ByteArray,
    val encoderPersonalizationStr: String,
    val decoderPersonalizationStr: String,
)

object RelayControlPlaneContracts {

    fun mapPairResponseToMaterials(
        request: RelayPairRequest,
        response: List<RelayPairResponseItem>,
    ): List<RelayPairMaterial> {
        val requestByPairId = request.pairs.associateBy { it.pairId }
        val responseByPairId = response.associateBy { it.pairId }

        val missing = requestByPairId.keys - responseByPairId.keys
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("pair response missing pairIds: ${missing.map { it.text }.sorted()}")
        }

        val unknown = responseByPairId.keys - requestByPairId.keys
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("pair response contains unknown pairIds: ${unknown.map { it.text }.sorted()}")
        }

        return request.pairs.map { requestItem ->
            val responseItem = responseByPairId.getValue(requestItem.pairId)
            // The crossover is the point of the exchange: this client's encoder consumes
            // what the relay's decoder was built from, and the reverse. Straightening it
            // out here produces two pairs that instantiate cleanly and decode nothing.
            RelayPairMaterial(
                pairId = requestItem.pairId,
                encoderNonce = parseNonce(responseItem.decoderNonce),
                decoderNonce = parseNonce(responseItem.encoderNonce),
                encoderResponderEncryptedSecret = decodeBase64(responseItem.decoderSecret),
                decoderResponderEncryptedSecret = decodeBase64(responseItem.encoderSecret),
                encoderPersonalizationStr = requestItem.encoderPersonalizationStr,
                decoderPersonalizationStr = requestItem.decoderPersonalizationStr,
            )
        }
    }

    /**
     * Kyber secrets are standard base64 **with** padding, unlike the pair id and the
     * token, which are base64url without it. Both spellings travel in the same JSON
     * object and neither side notices a mix until a decode fails.
     */
    private fun decodeBase64(value: String): ByteArray {
        return try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64 value in pair response", error)
        }
    }

    /**
     * Parses the pair-response nonce and uses it verbatim.
     *
     * The server clears the most significant bit at generation and transmits that exact
     * value, so the wire value and the value handed to setNonce are the same on both
     * sides. Masking, truncating or otherwise transforming the nonce here is a protocol
     * violation even when it is harmless against a compliant server: the mask used to
     * live here, and a client-side mask is exactly how the same field drifted apart
     * between implementations elsewhere.
     */
    private fun parseNonce(value: String): Long {
        return try {
            java.lang.Long.parseUnsignedLong(value)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("invalid nonce value '$value'", error)
        }
    }
}
