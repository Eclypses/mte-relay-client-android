package com.eclypses.relay.session

import com.eclypses.mte.MteBase
import com.eclypses.mte.MteKyber
import com.eclypses.mte.MteMkeDec
import com.eclypses.mte.MteMkeEnc
import com.eclypses.mte.MteStatus
import com.eclypses.mte.wire.PairId
import com.eclypses.mte.wire.Personalization
import com.eclypses.relay.KyberException
import com.eclypses.relay.MteException
import java.io.IOException

/**
 * One MTE encoder/decoder pair: the Kyber handshake that creates it, and the codec
 * operations that run over it.
 *
 * This replaces the reflective bridge to the old package-private `Pair`. That bridge
 * existed because the pairing code lived in a different package from the class holding
 * the keys, and the cost of it was real: R8 rewrites a `Class.forName` literal but not a
 * `getDeclaredField("pairId")` one, so every minified release needed a consumer keep rule
 * or it died on the first request with `NoSuchFieldException`. Constructing the pair
 * directly removes the rules along with the bridge.
 *
 * It also removes the old pair's `Math.random()`. That generated the personalization
 * strings and the pair ids -- material a peer uses to seed a DRBG -- from a generator
 * with no cryptographic guarantee at all. Frame v2 mints both from `SecureRandom` by way
 * of [Personalization] and [PairId], which is the grammar's doing rather than a fix
 * anyone went looking for.
 *
 * **State save/restore around every operation is load-bearing.** A codec operation is
 * counted by both ends; one extra or one missing desynchronises the pair permanently.
 * Saving after each operation and restoring before the next means a failed operation
 * leaves the state where the peer still believes it is, so the pair survives a refused
 * message.
 */
internal class MtePair(
    override val pairId: PairId,
    private val encoder: MteMkeEnc,
    private val decoder: MteMkeDec,
) : RelayRuntimePair {

    private var encoderState: ByteArray = encoder.saveState()
    private var decoderState: ByteArray = decoder.saveState()

    override fun encode(payload: ByteArray): ByteArray {
        restoreEncoder()
        val result = encoder.encode(payload)
        check(result.status, "encode")
        encoderState = encoder.saveState()
        return result.arr ?: throw IOException("encode returned no bytes")
    }

    override fun decode(payload: ByteArray): ByteArray {
        restoreDecoder()
        val result = decoder.decode(payload)
        check(result.status, "decode")
        decoderState = decoder.saveState()
        return result.arr ?: throw IOException("decode returned no bytes")
    }

    private fun restoreEncoder() = check(encoder.restoreState(encoderState), "encoder restoreState")

    private fun restoreDecoder() = check(decoder.restoreState(decoderState), "decoder restoreState")

    private fun check(status: MteStatus, what: String) {
        if (status != MteStatus.mte_status_success) {
            throw MteException(status, "$what failed for pair ${pairId.hex}")
        }
    }
}

/**
 * A pair under construction: the half that exists before the relay has answered.
 *
 * The client mints the identifiers and the Kyber key pairs, sends the public halves, and
 * holds the private halves here until the response comes back with the encrypted secrets.
 * Nothing is instantiated until then, because the encoder's entropy *is* the shared
 * secret.
 */
internal class MtePairDraft private constructor(
    val pairId: PairId,
    val encoderPersonalization: String,
    val decoderPersonalization: String,
    private val encoderKyber: MteKyber,
    private val decoderKyber: MteKyber,
    val encoderPublicKey: ByteArray,
    val decoderPublicKey: ByteArray,
) {

    /**
     * Completes the handshake and builds the pair.
     *
     * Both windows come from discovery and neither has a fallback here. Section 11:
     * "A client MUST take both from discovery and MUST NOT carry a default of its own
     * past the point where discovery has answered." The old pair hardcoded
     * `MteMkeDec(10, -63)` -- a time window of 10 against a relay that ships 1000 --
     * which is unpairable and says nothing about why.
     */
    fun materialize(
        material: RelayPairMaterial,
        sequenceWindow: Int,
        timeWindow: Long,
    ): MtePair {
        val encoder = MteMkeEnc()
        val encoderSecret = ByteArray(MteKyber.getSecretSize())
        checkKyber(
            encoderKyber.decryptSecret(material.encoderResponderEncryptedSecret, encoderSecret),
            "encoder Kyber decryptSecret",
        )
        encoder.setEntropy(encoderSecret)
        encoder.setNonce(material.encoderNonce)
        checkMte(encoder.instantiate(encoderPersonalization), "encoder instantiate")

        val decoder = MteMkeDec(timeWindow, sequenceWindow)
        val decoderSecret = ByteArray(MteKyber.getSecretSize())
        checkKyber(
            decoderKyber.decryptSecret(material.decoderResponderEncryptedSecret, decoderSecret),
            "decoder Kyber decryptSecret",
        )
        decoder.setEntropy(decoderSecret)
        decoder.setNonce(material.decoderNonce)
        checkMte(decoder.instantiate(decoderPersonalization), "decoder instantiate")

        return MtePair(pairId, encoder, decoder)
    }

    companion object {
        /**
         * The Kyber strength this client uses. Discovery carries the relay's and the two
         * are compared before any pairing work, because a mismatch produces a secret
         * neither side can decrypt and no error that says so.
         */
        const val KYBER_STRENGTH: Int = 1024

        /**
         * Mints one draft for [transport] against [host].
         *
         * `host` is the relay origin host as this client sees it. It is part of the
         * personalization grammar and is what keeps two relays from ever producing the
         * same string, so it is passed rather than omitted even though the grammar
         * allows omitting it.
         */
        fun create(transport: Personalization.Transport, host: String?): MtePairDraft {
            MteLicense.require()
            MteKyber.init(MteKyber.KyberStrength.K1024)
            val publicKeySize = MteKyber.getPublicKeySize()

            val encoderKyber = MteKyber()
            val encoderPublicKey = ByteArray(publicKeySize)
            checkKyber(encoderKyber.createKeyPair(encoderPublicKey), "encoder Kyber createKeyPair")

            val decoderKyber = MteKyber()
            val decoderPublicKey = ByteArray(publicKeySize)
            checkKyber(decoderKyber.createKeyPair(decoderPublicKey), "decoder Kyber createKeyPair")

            return MtePairDraft(
                pairId = PairId.random(),
                encoderPersonalization = Personalization.mint(transport, host),
                decoderPersonalization = Personalization.mint(transport, host),
                encoderKyber = encoderKyber,
                decoderKyber = decoderKyber,
                encoderPublicKey = encoderPublicKey,
                decoderPublicKey = decoderPublicKey,
            )
        }

        private fun checkKyber(status: Int, what: String) {
            if (status != MteKyber.Success) throw KyberException(status, "$what failed")
        }

        private fun checkMte(status: MteStatus, what: String) {
            if (status != MteStatus.mte_status_success) throw MteException(status, "$what failed")
        }
    }
}

/**
 * The MTE licence, initialised once for the process.
 *
 * It used to be initialised only in `Relay`'s constructor, which made a working codec a
 * property of one entry point rather than of the library. Anything that reached MTE
 * another way -- the control plane on its own, a test, a future transport -- got an
 * uninitialised library, and the only symptom was `probe=error` in a profile that matched
 * no relay. The message then blamed the relay's configuration for a missing licence.
 *
 * Every path that touches MTE goes through here now, and it is idempotent: the binding's
 * own call is, and the lazy makes it once per process regardless.
 */
internal object MteLicense {

    val initialized: Boolean by lazy {
        com.eclypses.mte.MteBase.initLicense(
            com.eclypses.relay.RelaySettings.licenseCompanyName,
            com.eclypses.relay.RelaySettings.licenseKey,
        )
    }

    /** @throws com.eclypses.relay.RelayException when the licence is refused. */
    fun require() {
        if (!initialized) {
            throw com.eclypses.relay.RelayException("MteLicense", "MTE License Check Failed")
        }
    }
}

/**
 * This client's MTE profile string, computed once.
 *
 * The probe costs two codec operations, so it runs on first read and is kept. A library
 * that cannot run it yields `error`, which matches nothing -- the setup path treats that
 * as fatal and says so, rather than letting it surface later as a 490 profile_mismatch
 * nobody can explain.
 */
internal object MteProfileSource {

    val profile: com.eclypses.mte.wire.MteProfile by lazy {
        try {
            com.eclypses.mte.wire.MteProfile.build(
                version = MteBase.getVersion(),
                tokBytes = MteBase.getDefaultTokBytes(),
                drbg = MteBase.getDrbgsName(MteBase.getDefaultDrbg()),
                cipher = MteBase.getCiphersName(MteBase.getDefaultCipher()),
                hash = MteBase.getHashesName(MteBase.getDefaultHash()),
                verifiers = MteBase.getVerifiersName(MteBase.getDefaultVerifiers()),
                kyberStrength = MtePairDraft.KYBER_STRENGTH,
                probe = probeDigest(),
            )
        } catch (e: Throwable) {
            // Every accessor above is a native method, so a library that did not load --
            // an unbundled ABI, a JVM unit test, a licence that never initialised --
            // throws here rather than returning anything. Yielding an unmatchable profile
            // instead of propagating means the failure surfaces once, at the compatibility
            // check, saying the probe did not run; propagating would instead take out
            // whatever happened to touch this object first, several layers from the cause.
            com.eclypses.mte.wire.MteProfile("mte/unavailable;probe=${com.eclypses.mte.wire.MteProfile.PROBE_ERROR}")
        }
    }

    /**
     * Two MKE encodes over the inputs pinned in `vectors/probe.json`, hashed.
     *
     * Failures yield `error` rather than throwing, matching the Go and TypeScript
     * implementations, so a library that is not licensed yet produces an unmatchable
     * profile instead of taking the process down at class-init time.
     */
    private fun probeDigest(): String = try {
        MteLicense.require()
        val enc = MteMkeEnc()
        // setEntropy zeroizes its argument; hand it a copy so the constant survives.
        enc.setEntropy(com.eclypses.mte.wire.MteProfile.PROBE_ENTROPY.copyOf())
        enc.setNonce(com.eclypses.mte.wire.MteProfile.PROBE_NONCE)
        if (enc.instantiate(com.eclypses.mte.wire.MteProfile.PROBE_PERSONALIZATION) !=
            MteStatus.mte_status_success
        ) {
            com.eclypses.mte.wire.MteProfile.PROBE_ERROR
        } else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var failed = false
            repeat(com.eclypses.mte.wire.MteProfile.PROBE_OPERATIONS) {
                val r = enc.encode(com.eclypses.mte.wire.MteProfile.PROBE_PLAINTEXT)
                if (r.status != MteStatus.mte_status_success || r.arr == null) failed = true
                else digest.update(r.arr)
            }
            if (failed) {
                com.eclypses.mte.wire.MteProfile.PROBE_ERROR
            } else {
                digest.digest().copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
            }
        }
    } catch (e: Throwable) {
        com.eclypses.mte.wire.MteProfile.PROBE_ERROR
    }
}
