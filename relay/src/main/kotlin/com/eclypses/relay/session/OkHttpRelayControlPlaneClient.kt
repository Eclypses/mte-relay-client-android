package com.eclypses.relay.session

import com.eclypses.relay.LogHelper
import java.io.IOException
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Concrete V5 control-plane client for auth + pair calls using OkHttp.
 */
class OkHttpRelayControlPlaneClient(
    private val httpClient: OkHttpClient,
) : RelayControlPlaneClient {

    override fun authenticate(origin: String, existingClientId: String?): RelayAuthResponse {
        val urlBuilder = "$origin/api/mte-relay".toHttpUrl().newBuilder()
        if (!existingClientId.isNullOrBlank()) {
            urlBuilder.addQueryParameter("clientId", existingClientId)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("auth request failed with status ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = if (body.isBlank()) JSONObject() else JSONObject(body)
            val clientId = json.optString("clientId", "")
            if (clientId.isBlank()) {
                throw IllegalStateException("auth response missing clientId")
            }
            return RelayAuthResponse(clientId)
        }
    }

    override fun pair(origin: String, clientId: String, pairPoolSize: Int): RelayPairingResult {
        val drafts = (1..pairPoolSize).map { LegacyPairDraft.create() }
        val pairRequest = RelayPairRequest(
            clientId = clientId,
            pairs = drafts.map { it.requestItem },
        )

        val requestJson = JSONObject().apply {
            put("clientId", pairRequest.clientId)
            put("pairs", JSONArray().apply {
                pairRequest.pairs.forEach { pair ->
                    put(
                        JSONObject().apply {
                            put("pairId", pair.pairId)
                            put("encoderPublicKey", pair.encoderPublicKey)
                            put("encoderPersonalizationStr", pair.encoderPersonalizationStr)
                            put("decoderPublicKey", pair.decoderPublicKey)
                            put("decoderPersonalizationStr", pair.decoderPersonalizationStr)
                        },
                    )
                }
            })
        }

        val request = Request.Builder()
            .url("$origin/api/mte-pair")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("pair request failed with status ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val array = JSONArray(body)
            val responseItems = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                RelayPairResponseItem(
                    pairId = item.getString("pairId"),
                    encoderNonce = item.getString("encoderNonce"),
                    encoderSecret = item.getString("encoderSecret"),
                    decoderNonce = item.getString("decoderNonce"),
                    decoderSecret = item.getString("decoderSecret"),
                )
            }
            val materials = RelayControlPlaneContracts.mapPairResponseToMaterials(pairRequest, responseItems)
            val draftsById = drafts.associateBy { it.requestItem.pairId }
            val runtimePairs = materials.map { material ->
                val draft = draftsById[material.pairId]
                    ?: throw IllegalStateException("missing pair draft for ${material.pairId}")
                draft.materialize(material)
            }
            return RelayPairingResult(materials = materials, runtimePairs = runtimePairs)
        }
    }

    override fun keepAlive(origin: String, clientId: String, pairIds: List<String>) {
        val requestJson = JSONObject().apply {
            put("clientId", clientId)
            put("pairIds", JSONArray().apply {
                pairIds.forEach { put(it) }
            })
        }

        val request = Request.Builder()
            .url("$origin/api/mte-keepalive")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 200 || response.code == 204) {
                val body = response.body?.string().orEmpty()
                val touched = if (body.isBlank()) {
                    null
                } else {
                    JSONObject(body).optInt("touched")
                }
                LogHelper.info(
                    "OkHttpRelayControlPlaneClient",
                    "Keep-alive success for $origin: status=${response.code}, touched=${touched ?: "n/a"}, pairIds=$pairIds",
                )
                return
            }
            throw IllegalStateException("keepalive request failed with status ${response.code}")
        }
    }

    private class LegacyPairDraft private constructor(
        private val legacyPair: Any,
        val requestItem: RelayPairRequestItem,
    ) {
        fun materialize(material: RelayPairMaterial): RelayRuntimePair {
            setField("encResponderEncryptedSecret", material.encoderResponderEncryptedSecret)
            setField("encNonce", material.encoderNonce)
            setField("decResponderEncryptedSecret", material.decoderResponderEncryptedSecret)
            setField("decNonce", material.decoderNonce)
            invoke("createEncoderAndDecoder")
            return LegacyRuntimePair(legacyPair, material.pairId)
        }

        private fun setField(name: String, value: Any) {
            val field = legacyPair.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(legacyPair, value)
        }

        private fun invoke(name: String) {
            val method = legacyPair.javaClass.getDeclaredMethod(name)
            method.isAccessible = true
            method.invoke(legacyPair)
        }

        companion object {
            fun create(): LegacyPairDraft {
                val pairClass = Class.forName("com.eclypses.relay.Pair")
                val ctor = pairClass.getDeclaredConstructor()
                ctor.isAccessible = true
                val pair = ctor.newInstance()

                fun readField(name: String): Any {
                    val field = pairClass.getDeclaredField(name)
                    field.isAccessible = true
                    return field.get(pair)
                        ?: throw IllegalStateException("legacy pair field '$name' was null")
                }

                val pairId = readField("pairId") as String
                val encoderPublicKey = Base64.getEncoder().encodeToString(readField("encMyPublicKey") as ByteArray)
                val decoderPublicKey = Base64.getEncoder().encodeToString(readField("decMyPublicKey") as ByteArray)
                val encoderPersonalizationStr = readField("encPersStr") as String
                val decoderPersonalizationStr = readField("decPersStr") as String

                return LegacyPairDraft(
                    legacyPair = pair,
                    requestItem = RelayPairRequestItem(
                        pairId = pairId,
                        encoderPublicKey = encoderPublicKey,
                        encoderPersonalizationStr = encoderPersonalizationStr,
                        decoderPublicKey = decoderPublicKey,
                        decoderPersonalizationStr = decoderPersonalizationStr,
                    ),
                )
            }
        }
    }

    private class LegacyRuntimePair(
        private val legacyPair: Any,
        override val pairId: String,
    ) : RelayRuntimePair {

        override fun encode(payload: ByteArray): ByteArray {
            val method = legacyPair.javaClass.getDeclaredMethod("encode", ByteArray::class.java)
            method.isAccessible = true
            return method.invoke(legacyPair, payload) as ByteArray
        }

        override fun decode(payload: ByteArray): ByteArray {
            val method = legacyPair.javaClass.getDeclaredMethod("decode", ByteArray::class.java)
            method.isAccessible = true
            val decodeResult = method.invoke(legacyPair, payload)
            val resultClass = decodeResult.javaClass
            val bytesField = resultClass.getDeclaredField("decodedBytes")
            bytesField.isAccessible = true
            val bytes = bytesField.get(decodeResult) as? ByteArray
            return bytes ?: throw IOException("decode returned null bytes")
        }

        override fun startEncrypt() {
            val method = legacyPair.javaClass.getDeclaredMethod("startEncrypt")
            method.isAccessible = true
            method.invoke(legacyPair)
        }

        override fun encryptChunk(buffer: ByteArray, length: Int) {
            val method = legacyPair.javaClass.getDeclaredMethod(
                "encryptChunk", ByteArray::class.java, Int::class.java
            )
            method.isAccessible = true
            method.invoke(legacyPair, buffer, length)
        }

        override fun finishEncrypt(): ByteArray {
            val method = legacyPair.javaClass.getDeclaredMethod("finishEncrypt")
            method.isAccessible = true
            val result = method.invoke(legacyPair)
            val arrField = result.javaClass.getDeclaredField("arr")
            arrField.isAccessible = true
            return arrField.get(result) as? ByteArray
                ?: throw IOException("finishEncrypt returned null arr")
        }

        override fun encryptFinishBytes(): Int {
            val method = legacyPair.javaClass.getDeclaredMethod("getFinishEncryptBytes")
            method.isAccessible = true
            return method.invoke(legacyPair) as Int
        }

        override fun startDecrypt() {
            val method = legacyPair.javaClass.getDeclaredMethod("startDecrypt")
            method.isAccessible = true
            method.invoke(legacyPair)
        }

        override fun decryptChunk(buffer: ByteArray): ByteArray {
            val method = legacyPair.javaClass.getDeclaredMethod("decryptChunk", ByteArray::class.java)
            method.isAccessible = true
            return method.invoke(legacyPair, buffer) as ByteArray
        }

        override fun finishDecrypt(): ByteArray {
            val method = legacyPair.javaClass.getDeclaredMethod("finishDecrypt")
            method.isAccessible = true
            val result = method.invoke(legacyPair)
            val arrField = result.javaClass.getDeclaredField("arr")
            arrField.isAccessible = true
            return arrField.get(result) as? ByteArray ?: ByteArray(0)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
