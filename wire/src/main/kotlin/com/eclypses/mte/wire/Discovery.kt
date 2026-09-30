package com.eclypses.mte.wire

/**
 * The discovery document from `GET /api/mte-relay?frameVersions=2`.
 *
 * `vectors/discovery.json`: this "is the one protocol surface that had no vector, no
 * shared type, and an independent spelling in every implementation". Hence the
 * registered member list and a parser measured against it.
 *
 * Two rules shape the whole type:
 *
 *  - **Unknown members are ignored, and no member is required beyond the minimal
 *    set.** A document from a later relay must still parse, or every future addition
 *    breaks every shipped client.
 *  - **`features` is the only thing behaviour keys on.** Never `buildVersion`.
 */
public data class Discovery(
    public val discoverySchema: Int,
    public val buildVersion: String,
    public val frameVersions: List<Int>,
    public val features: Set<String>,
    public val mteProfile: String,
    public val kyberStrength: Int,
    /** Pairing input. See [SEQUENCE_WINDOW_DEFAULT]; zero is adopted. */
    public val sequenceWindow: Int,
    /** Pairing input. See [TIME_WINDOW_DEFAULT]; zero is NOT adopted. */
    public val timeWindow: Int,
    public val maxFrameBytes: Long,
    public val maxMessageBytes: Long,
    public val maxMetadataBytes: Map<MteType, Long>,
    public val transports: List<String>,
    public val deprecatedFrameVersions: List<Int> = emptyList(),
    public val wsPath: String? = null,
    public val tokenMaxAgeSeconds: Long? = null,
    public val maxPairsPerClient: Int? = null,
    public val maxPairsPerBatch: Int? = null,
    public val maxPairBytes: Long? = null,
    public val maxPairSeconds: Long? = null,
    public val pingIntervalSeconds: Long? = null,
    public val idleTimeoutSeconds: Long? = null,
    public val maxRequestBodyBytes: Long? = null,
    public val ownerSeconds: Long? = null,
    /** Everything registered that this type does not surface individually. */
    public val raw: JsonObject,
) {
    public fun hasFeature(name: String): Boolean = name in features

    /**
     * The frame version to speak, or null when the sets do not intersect.
     *
     * Section 14: the client picks the highest version in both lists and writes it in
     * every frame for the life of the session. The choice is the client's alone --
     * auth answers with the whole set and never names one. `deprecatedFrameVersions`
     * does not narrow the choice: the announcement opens a migration window, it does
     * not close one.
     */
    public fun selectFrameVersion(weSpeak: Set<Int> = setOf(Metadata.FRAME_VERSION)): Int? =
        frameVersions.filter { it in weSpeak }.maxOrNull()

    public companion object {
        public const val ENDPOINT: String = "/api/mte-relay"
        public const val KEEPALIVE_ENDPOINT: String = "/api/mte-keepalive"
        public const val PAIR_ENDPOINT: String = "/api/mte-pair"
        public const val ROUTE_HEADER: String = "X-MTE-Relay-Route"

        /** The schema this build understands. A different value is refused, not guessed at. */
        public const val SCHEMA: Int = 1

        /**
         * Section 11: "An implementer that does not set `timeWindow` gets 1000."
         *
         * `sequenceWindow` and `timeWindow` are pairing inputs, not hints. Both ends
         * construct the decoder with them, so a disagreement is not a degraded pair
         * but no pair at all: every decode fails from the first frame and nothing in
         * the error says why.
         */
        public const val TIME_WINDOW_DEFAULT: Int = 1000

        /**
         * The fallback when discovery carries no `sequenceWindow` at all, which is
         * distinct from carrying a zero.
         *
         * Minus 63, because that is what the relay ships (`config.DefaultSequenceWindow`)
         * and what the browser client falls back to. A fallback of 0 here would read a
         * silent omission as a window the server does not use, and the result is the
         * failure this whole member exists to prevent: no pair at all, from the first
         * frame, with nothing in the error saying why. No conforming server omits it --
         * every vector document carries it, "minimal" included -- so this path should
         * never run; it exists so that if it does, it lands on the value both peers mean.
         */
        public const val SEQUENCE_WINDOW_DEFAULT: Int = -63

        /** The range the MTE library accepts. Outside it, the decoder cannot be built. */
        public const val SEQUENCE_WINDOW_MIN: Int = -63
        public const val SEQUENCE_WINDOW_MAX: Int = 65535

        /** The query parameter is mandatory, and a parameter rather than a header so
         *  a cross origin fetch adds no preflight. Absent means a pre-version-2 SDK
         *  and gets 478. */
        public fun authUrl(base: String, versions: Set<Int> = setOf(Metadata.FRAME_VERSION)): String =
            "${base.trimEnd('/')}$ENDPOINT?frameVersions=${versions.sorted().joinToString(",")}"

        public fun parse(text: String): Discovery = parse(Json.parseObject(text))

        public fun parse(obj: JsonObject): Discovery {
            val schema = int(obj, "discoverySchema")
                ?: throw WireException(WireError.DISCOVERY, "discoverySchema is required")
            if (schema != SCHEMA) {
                // "A client that does not know the value refuses rather than guessing
                // which members mean what."
                throw WireException(WireError.DISCOVERY, "discoverySchema $schema is not $SCHEMA")
            }

            return Discovery(
                discoverySchema = schema,
                buildVersion = string(obj, "buildVersion")
                    ?: throw WireException(WireError.DISCOVERY, "buildVersion is required"),
                frameVersions = intList(obj, "frameVersions")
                    ?: throw WireException(WireError.DISCOVERY, "frameVersions is required"),
                features = stringList(obj, "features")?.toSet() ?: emptySet(),
                mteProfile = string(obj, "mteProfile")
                    ?: throw WireException(WireError.DISCOVERY, "mteProfile is required"),
                kyberStrength = int(obj, "kyberStrength")
                    ?: throw WireException(WireError.DISCOVERY, "kyberStrength is required"),

                // The two pairing inputs, and the one place the rules differ.
                //
                // A zero sequenceWindow is adopted: every client already adopts any
                // integer there and a zero is meaningful. An *absent* one is a
                // different case and falls back to minus 63; see the constant.
                //
                // A zero timeWindow is NOT adopted. Zero is both a legal window and
                // the Go zero value a server sends when the block that fills it did
                // not run; the wire cannot tell those apart, and only one reading is
                // safe. Falling back to 1000 pairs with a server that meant 1000.
                // Adopting a spurious zero pairs with nothing and says nothing about
                // why -- every decode fails from the first frame.
                sequenceWindow = (int(obj, "sequenceWindow") ?: SEQUENCE_WINDOW_DEFAULT)
                    .also(::checkSequenceWindow),
                timeWindow = int(obj, "timeWindow")?.takeIf { it != 0 } ?: TIME_WINDOW_DEFAULT,

                maxFrameBytes = long(obj, "maxFrameBytes")
                    ?: throw WireException(WireError.DISCOVERY, "maxFrameBytes is required"),
                maxMessageBytes = long(obj, "maxMessageBytes")
                    ?: throw WireException(WireError.DISCOVERY, "maxMessageBytes is required"),
                maxMetadataBytes = metadataBytes(obj),
                transports = stringList(obj, "transports")
                    ?: throw WireException(WireError.DISCOVERY, "transports is required"),

                deprecatedFrameVersions = intList(obj, "deprecatedFrameVersions") ?: emptyList(),
                wsPath = string(obj, "wsPath"),
                tokenMaxAgeSeconds = long(obj, "tokenMaxAgeSeconds"),
                maxPairsPerClient = int(obj, "maxPairsPerClient"),
                maxPairsPerBatch = int(obj, "maxPairsPerBatch"),
                maxPairBytes = long(obj, "maxPairBytes"),
                maxPairSeconds = long(obj, "maxPairSeconds"),
                pingIntervalSeconds = long(obj, "pingIntervalSeconds"),
                idleTimeoutSeconds = long(obj, "idleTimeoutSeconds"),
                maxRequestBodyBytes = long(obj, "maxRequestBodyBytes"),
                ownerSeconds = long(obj, "ownerSeconds"),
                raw = obj,
            )
        }

        private fun checkSequenceWindow(w: Int) {
            if (w < SEQUENCE_WINDOW_MIN || w > SEQUENCE_WINDOW_MAX) {
                throw WireException(
                    WireError.DISCOVERY,
                    "sequenceWindow $w is outside the range the MTE library accepts " +
                        "($SEQUENCE_WINDOW_MIN to $SEQUENCE_WINDOW_MAX)",
                )
            }
        }

        private fun metadataBytes(obj: JsonObject): Map<MteType, Long> {
            val m = obj["maxMetadataBytes"] as? JsonObject
                ?: throw WireException(WireError.DISCOVERY, "maxMetadataBytes is required")
            val out = mutableMapOf<MteType, Long>()
            (m["MKE"] as? JsonNumber)?.raw?.toLongOrNull()?.let { out[MteType.MKE] = it }
            (m["MTE"] as? JsonNumber)?.raw?.toLongOrNull()?.let { out[MteType.MTE] = it }
            if (out.isEmpty()) throw WireException(WireError.DISCOVERY, "maxMetadataBytes is empty")
            return out
        }

        private fun string(o: JsonObject, k: String): String? = (o[k] as? JsonString)?.value
        private fun int(o: JsonObject, k: String): Int? = (o[k] as? JsonNumber)?.raw?.toIntOrNull()
        private fun long(o: JsonObject, k: String): Long? = (o[k] as? JsonNumber)?.raw?.toLongOrNull()

        private fun intList(o: JsonObject, k: String): List<Int>? =
            (o[k] as? JsonArray)?.items?.mapNotNull { (it as? JsonNumber)?.raw?.toIntOrNull() }

        private fun stringList(o: JsonObject, k: String): List<String>? =
            (o[k] as? JsonArray)?.items?.mapNotNull { (it as? JsonString)?.value }
    }
}
