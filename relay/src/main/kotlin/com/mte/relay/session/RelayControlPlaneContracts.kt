package com.mte.relay.session

import java.util.Base64

data class RelayAuthResponse(
    val clientId: String,
)

data class RelayPairRequest(
    val clientId: String,
    val pairs: List<RelayPairRequestItem>,
)

data class RelayPairRequestItem(
    val pairId: String,
    val encoderPublicKey: String,
    val encoderPersonalizationStr: String,
    val decoderPublicKey: String,
    val decoderPersonalizationStr: String,
)

data class RelayPairResponseItem(
    val pairId: String,
    val encoderNonce: String,
    val encoderSecret: String,
    val decoderNonce: String,
    val decoderSecret: String,
)

data class RelayPairMaterial(
    val pairId: String,
    val encoderNonce: Long,
    val decoderNonce: Long,
    val encoderResponderEncryptedSecret: ByteArray,
    val decoderResponderEncryptedSecret: ByteArray,
    val encoderPersonalizationStr: String,
    val decoderPersonalizationStr: String,
)

object RelayControlPlaneContracts {
    private const val NONCE_MASK = 0x7FFF_FFFF_FFFF_FFFFL

    fun mapPairResponseToMaterials(
        request: RelayPairRequest,
        response: List<RelayPairResponseItem>,
    ): List<RelayPairMaterial> {
        val requestByPairId = request.pairs.associateBy { it.pairId }
        val responseByPairId = response.associateBy { it.pairId }

        val missing = requestByPairId.keys - responseByPairId.keys
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("pair response missing pairIds: ${missing.sorted()}")
        }

        val unknown = responseByPairId.keys - requestByPairId.keys
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("pair response contains unknown pairIds: ${unknown.sorted()}")
        }

        return request.pairs.map { requestItem ->
            val responseItem = responseByPairId.getValue(requestItem.pairId)
            RelayPairMaterial(
                pairId = requestItem.pairId,
                encoderNonce = parseAndMaskNonce(responseItem.decoderNonce),
                decoderNonce = parseAndMaskNonce(responseItem.encoderNonce),
                encoderResponderEncryptedSecret = decodeBase64(responseItem.decoderSecret),
                decoderResponderEncryptedSecret = decodeBase64(responseItem.encoderSecret),
                encoderPersonalizationStr = requestItem.encoderPersonalizationStr,
                decoderPersonalizationStr = requestItem.decoderPersonalizationStr,
            )
        }
    }

    private fun decodeBase64(value: String): ByteArray {
        return try {
            Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64 value in pair response", error)
        }
    }

    private fun parseAndMaskNonce(value: String): Long {
        val unsigned = try {
            java.lang.Long.parseUnsignedLong(value)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("invalid nonce value '$value'", error)
        }
        return unsigned and NONCE_MASK
    }
}
