package com.eclypses.mte.wire

/**
 * The `mteProfile` string of section 12.
 *
 * ```
 * mte/<version>;tok=<n>;drbg=<name>;cipher=<name>;hash=<name>;verifiers=<name>;kyber=<n>;probe=<hex>
 * ```
 *
 * It identifies the MTE library build a peer runs, so two builds that cannot
 * interoperate are refused at discovery or at pair rather than surfacing as a decode
 * failure on the first request.
 *
 * **The settings decide interoperability; the probe is advisory.** That distinction is
 * the whole reason this type exists rather than a string comparison at the call site. A
 * mobile client links the MTE *client* build and a relay links the *server* build, and
 * those two produce different encoder output by design -- so the probe is guaranteed to
 * differ exactly when the deployment is correct. A client that compares whole strings
 * never pairs with anything, and the failure says "profile mismatch", which is the one
 * message guaranteed to send someone looking in the wrong place.
 *
 * The server agrees: `relayserver/pair.go` compares [settings] and logs a [probe]
 * difference at debug.
 *
 * This type holds no MTE dependency. [build] takes the library's own answers as
 * arguments, because `:wire` links nothing and the codec lives a module up.
 */
public data class MteProfile(public val text: String) {

    /** The members in order. The leading `mte/<version>` has no `=` and is keyed `mte`. */
    public val members: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        for (part in text.split(';')) {
            val eq = part.indexOf('=')
            if (eq < 0) out["mte"] = part else out[part.substring(0, eq)] = part.substring(eq + 1)
        }
        out
    }

    /** The profile with its probe removed: the part two peers must agree on. */
    public val settings: String
        get() = text.split(';').filterNot { it.startsWith("probe=") }.joinToString(";")

    /** The probe digest, or the empty string when the profile carries none. */
    public val probe: String get() = members["probe"] ?: ""

    /** True when the probe could not be computed. Such a profile matches nothing. */
    public val probeFailed: Boolean get() = probe == PROBE_ERROR

    /**
     * Compares this client profile against a relay's advertised one.
     *
     * A field the relay does not advertise is not a mismatch: older relays and future
     * ones may carry a different set, and treating an absent field as a disagreement
     * would make every addition a breaking change.
     */
    public fun compareTo(server: MteProfile): Comparison {
        val mine = members
        val theirs = server.members
        val mismatched = SETTINGS_FIELDS.filter { field ->
            val a = theirs[field]
            val b = mine[field]
            a != null && b != null && a != b
        }
        return Comparison(
            mismatched = mismatched,
            serverProbe = server.probe,
            clientProbe = probe,
        )
    }

    public data class Comparison(
        /** Settings that disagree. Non-empty is a fatal setup error, not a warning. */
        public val mismatched: List<String>,
        public val serverProbe: String,
        public val clientProbe: String,
    ) {
        /** Expected on a correct deployment. Warn once; never fail. */
        public val probeDiffers: Boolean get() = serverProbe != clientProbe
        public val compatible: Boolean get() = mismatched.isEmpty()
    }

    override fun toString(): String = text

    public companion object {
        /** What the probe reports when the library could not run it. */
        public const val PROBE_ERROR: String = "error"

        /** The fields that decide interoperability. `probe` is deliberately absent. */
        public val SETTINGS_FIELDS: List<String> =
            listOf("mte", "tok", "drbg", "cipher", "hash", "verifiers", "kyber")

        /** The probe inputs, pinned in `vectors/probe.json`. */
        public val PROBE_ENTROPY: ByteArray = ByteArray(32) { it.toByte() }
        public const val PROBE_NONCE: Long = 1
        public const val PROBE_PERSONALIZATION: String = "mte-relay/2/probe"
        public const val PROBE_OPERATIONS: Int = 2
        public val PROBE_PLAINTEXT: ByteArray = PROBE_PERSONALIZATION.toByteArray(Charsets.UTF_8)

        /**
         * Assembles the profile from the library's own answers.
         *
         * The member order is fixed and significant: the server compares the settings as
         * one joined string, so a reordering is a mismatch even when every value agrees.
         */
        public fun build(
            version: String,
            tokBytes: Int,
            drbg: String,
            cipher: String,
            hash: String,
            verifiers: String,
            kyberStrength: Int,
            probe: String,
        ): MteProfile = MteProfile(
            "mte/$version;tok=$tokBytes;drbg=$drbg;cipher=$cipher;hash=$hash;" +
                "verifiers=$verifiers;kyber=$kyberStrength;probe=$probe",
        )
    }
}
