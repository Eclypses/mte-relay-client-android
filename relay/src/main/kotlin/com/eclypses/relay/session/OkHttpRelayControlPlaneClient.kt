package com.eclypses.relay.session

import com.eclypses.mte.wire.Discovery
import com.eclypses.mte.wire.Keepalive
import com.eclypses.mte.wire.Metadata
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.Personalization
import com.eclypses.mte.wire.RelayError
import com.eclypses.mte.wire.Token
import com.eclypses.relay.LogHelper
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** A control-plane call the relay refused, carrying the registry reason and its action. */
class RelayControlPlaneException(
    val error: RelayError,
    val httpStatus: Int,
    message: String,
) : IllegalStateException(message)

/**
 * The frame v2 control plane over OkHttp: auth, pair, keepalive.
 *
 * Every shape here comes from `:wire`, which is measured against the spec vectors, so
 * this class is transport and nothing else. The three things it is careful about:
 *
 *  - **`frameVersions` is mandatory on auth.** Omitting it is how a pre-version-2 SDK
 *    identifies itself, and the relay answers 478 naming the minimum SDK versions.
 *  - **A refreshed token can come back under a different client id.** The relay issues
 *    one silently when the presented token is invalid or aged, so the caller has to
 *    compare rather than assume its pool survived.
 *  - **Errors are keyed on the reason, not the status.** Five codes carry reasons with
 *    opposite actions.
 */
class OkHttpRelayControlPlaneClient(
    // See OkHttpRelayTransport.httpClient: internal so the cookie-store sharing is testable.
    internal val httpClient: OkHttpClient,
) : RelayControlPlaneClient {

    override fun authenticate(origin: String, existingToken: Token?): RelayAuthResponse {
        val urlBuilder = Discovery.authUrl(origin).toHttpUrl().newBuilder()
        // The refresh path. The relay reads the issued-at and the MAC off it; a token it
        // refuses is not an error, it is a new client id in the response.
        if (existingToken != null) {
            urlBuilder.addQueryParameter("clientId", existingToken.text)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .withRoute(existingToken)
            .build()

        httpClient.newCall(request).execute().use { response ->
            failOnRelayError(response, "auth")
            val json = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
            val tokenText = json.optString("clientId", "")
            if (tokenText.isBlank()) {
                throw IllegalStateException("auth response carried no clientId")
            }
            val token = Token.parse(tokenText)

            val relay = json.optJSONObject("relay")
                ?: throw IllegalStateException(
                    "auth response carried no relay object. Frame version 2 requires " +
                        "discovery: the sequence and time windows are pairing inputs and " +
                        "there is nothing safe to assume in their absence.",
                )
            val discovery = Discovery.parse(com.eclypses.mte.wire.Json.parseObject(relay.toString()))

            if (discovery.selectFrameVersion() == null) {
                throw IllegalStateException(
                    "the relay speaks frame versions ${discovery.frameVersions} and this " +
                        "client speaks ${Metadata.FRAME_VERSION}",
                )
            }
            return RelayAuthResponse(token, discovery)
        }
    }

    override fun pair(
        origin: String,
        token: Token,
        pairPoolSize: Int,
        sequenceWindow: Int,
        timeWindow: Long,
    ): RelayPairingResult {
        val host = origin.toHttpUrl().host
        val drafts = (1..pairPoolSize).map {
            MtePairDraft.create(Personalization.Transport.HTTP, host)
        }
        val pairRequest = RelayPairRequest(
            token = token,
            pairs = drafts.map {
                RelayPairRequestItem(
                    pairId = it.pairId,
                    // Kyber public keys are standard base64 WITH padding; the pair id and
                    // the token in the same object are base64url without it. Mixing the
                    // two is silent on both sides until a decode fails.
                    encoderPublicKey = Base64.getEncoder().encodeToString(it.encoderPublicKey),
                    encoderPersonalizationStr = it.encoderPersonalization,
                    decoderPublicKey = Base64.getEncoder().encodeToString(it.decoderPublicKey),
                    decoderPersonalizationStr = it.decoderPersonalization,
                )
            },
        )

        val profile = MteProfileSource.profile
        if (profile.probeFailed) {
            // The probe runs two MKE encodes; failing it means the MTE library did not
            // load or was never licensed. Such a profile matches no relay, so pairing
            // would answer 490 profile_mismatch -- a message that sends the reader to the
            // relay's configuration rather than to the library that is actually missing.
            throw IllegalStateException(
                "This client's MTE probe did not run, so it cannot pair with any relay. " +
                    "The usual causes are a library that was never licensed and an ABI " +
                    "the app did not bundle. Profile: \"${profile.text}\".",
            )
        }
        val requestJson = JSONObject().apply {
            put("clientId", token.text)
            put("pairs", JSONArray().apply {
                pairRequest.pairs.forEach { pair ->
                    put(
                        JSONObject().apply {
                            put("pairId", pair.pairId.text)
                            put("encoderPublicKey", pair.encoderPublicKey)
                            put("encoderPersonalizationStr", pair.encoderPersonalizationStr)
                            put("decoderPublicKey", pair.decoderPublicKey)
                            put("decoderPersonalizationStr", pair.decoderPersonalizationStr)
                            // New in frame v2, and all three are refused rather than
                            // ignored: the relay checks the frame version per item, the
                            // profile settings, and the Kyber strength before it does any
                            // Kyber work.
                            put("mteProfile", profile.text)
                            put("kyberStrength", MtePairDraft.KYBER_STRENGTH)
                            put("frameVersion", Metadata.FRAME_VERSION)
                        },
                    )
                }
            })
        }

        val request = Request.Builder()
            .url("${origin.trimEnd('/')}${Discovery.PAIR_ENDPOINT}")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .withRoute(token)
            .build()

        httpClient.newCall(request).execute().use { response ->
            failOnRelayError(response, "pair")
            val array = JSONArray(response.body?.string().orEmpty())
            val responseItems = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                RelayPairResponseItem(
                    pairId = PairId.parse(item.getString("pairId")),
                    encoderNonce = item.getString("encoderNonce"),
                    encoderSecret = item.getString("encoderSecret"),
                    decoderNonce = item.getString("decoderNonce"),
                    decoderSecret = item.getString("decoderSecret"),
                )
            }
            val materials = RelayControlPlaneContracts.mapPairResponseToMaterials(pairRequest, responseItems)
            val draftsById = drafts.associateBy { it.pairId }
            val runtimePairs = materials.map { material ->
                val draft = draftsById[material.pairId]
                    ?: throw IllegalStateException("missing pair draft for ${material.pairId.text}")
                draft.materialize(material, sequenceWindow, timeWindow)
            }
            return RelayPairingResult(materials = materials, runtimePairs = runtimePairs)
        }
    }

    /**
     * One call refreshes every pair the client holds, and deletes the ones in [drop].
     *
     * That is the whole change from the previous generation, which listed every pair id
     * to keep and cost the relay two store calls per pair. A client that still sends
     * `pairIds` gets 200 and no effect, silently, which is why the body is built by
     * [Keepalive] rather than here.
     */
    override fun keepAlive(origin: String, token: Token, drop: List<PairId>) {
        val request = Request.Builder()
            .url("${origin.trimEnd('/')}${Discovery.KEEPALIVE_ENDPOINT}")
            .post(Keepalive.body(token, drop.map { it.text }).toRequestBody(JSON_MEDIA_TYPE))
            .withRoute(token)
            .build()

        httpClient.newCall(request).execute().use { response ->
            failOnRelayError(response, "keepalive")
            if (!response.isSuccessful) {
                throw IllegalStateException("keepalive failed with status ${response.code}")
            }
            LogHelper.info(
                TAG,
                "Keep-alive succeeded for $origin: status=${response.code}, dropped=${drop.size}",
            )
        }
    }

    /**
     * Turns a refused control-plane call into an exception carrying the registry reason.
     *
     * The reason is what behaviour keys on. `475 invalid_token` means re-authenticate
     * and `475 unknown_key` means stop and page someone; both are 475, and a client that
     * reads only the status retries the second one until its rate limit is gone.
     */
    private fun failOnRelayError(response: Response, what: String) {
        if (response.isSuccessful) return
        val header = response.header(RelayError.HEADER)
        val error = header?.let { RelayError.parseHeader(it) }
            ?: RelayError(response.code, "", "")
        throw RelayControlPlaneException(
            error = error,
            httpStatus = response.code,
            message = buildString {
                append("$what failed with ${response.code}")
                if (error.reason.isNotEmpty()) append(" ${error.reason} (${error.action.token})")
                response.header("Retry-After")?.let { append(", Retry-After: $it") }
            },
        )
    }

    companion object {
        private const val TAG = "OkHttpRelayControlPlaneClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * `X-MTE-Relay-Route: <client id hex>`, the same value for the client's life.
         *
         * A consistent hash in front of a multi-replica relay keeps one client landing on
         * the replica holding its pair state. It is derived from the token rather than
         * minted by the relay, so it needs no round trip and there is nothing to carry
         * between calls beyond the token itself.
         */
        private fun Request.Builder.withRoute(token: Token?): Request.Builder =
            if (token == null) this else header(Discovery.ROUTE_HEADER, token.clientIdHex)
    }
}
