package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MteProfileTest {

    private val serverBuild = MteProfile.build(
        version = "4.2.1", tokBytes = 8, drbg = "CTR-AES256-DF", cipher = "AES-256-CTR",
        hash = "SHA-256", verifiers = "CRC32", kyberStrength = 1024, probe = "0011223344556677",
    )

    private val clientBuild = MteProfile.build(
        version = "4.2.1", tokBytes = 8, drbg = "CTR-AES256-DF", cipher = "AES-256-CTR",
        hash = "SHA-256", verifiers = "CRC32", kyberStrength = 1024, probe = "8899aabbccddeeff",
    )

    /** The probe inputs are pinned in the vector file; drifting from it is the bug. */
    @Test
    fun probeInputsMatchTheVector() {
        val v = SpecVectors.obj("probe.json")
        assertEquals(v.getString("entropy"), SpecVectors.toHex(MteProfile.PROBE_ENTROPY))
        assertEquals(v.getLong("nonce"), MteProfile.PROBE_NONCE)
        assertEquals(v.getString("personalization"), MteProfile.PROBE_PERSONALIZATION)
        assertEquals(v.getInt("operations"), MteProfile.PROBE_OPERATIONS)
        assertEquals(
            v.getString("plaintext"),
            SpecVectors.toHex(MteProfile.PROBE_PLAINTEXT),
        )
    }

    @Test
    fun parsesTheLeadingVersionMemberWithoutAnEquals() {
        assertEquals("mte/4.2.1", serverBuild.members["mte"])
        assertEquals("8", serverBuild.members["tok"])
        assertEquals("1024", serverBuild.members["kyber"])
        assertEquals("0011223344556677", serverBuild.probe)
    }

    /**
     * The one that decides whether this client can pair at all. A client build and a
     * server build produce different probe bytes by design, so comparing whole strings
     * fails every deployment that is correct.
     */
    @Test
    fun differingProbeAloneIsCompatible() {
        val c = clientBuild.compareTo(serverBuild)
        assertTrue(c.compatible, "probe difference must not block pairing")
        assertTrue(c.probeDiffers)
        assertEquals("0011223344556677", c.serverProbe)
        assertEquals("8899aabbccddeeff", c.clientProbe)
        assertEquals(serverBuild.settings, clientBuild.settings)
        assertFalse(serverBuild.settings.contains("probe"))
    }

    @Test
    fun differingSettingsAreNotCompatible() {
        val otherCipher = MteProfile.build(
            version = "4.2.1", tokBytes = 8, drbg = "CTR-AES256-DF", cipher = "AES-128-CTR",
            hash = "SHA-256", verifiers = "CRC32", kyberStrength = 1024, probe = "8899aabbccddeeff",
        )
        val c = otherCipher.compareTo(serverBuild)
        assertFalse(c.compatible)
        assertEquals(listOf("cipher"), c.mismatched)
    }

    @Test
    fun aKyberDifferenceIsASettingsMismatch() {
        val k768 = MteProfile.build(
            version = "4.2.1", tokBytes = 8, drbg = "CTR-AES256-DF", cipher = "AES-256-CTR",
            hash = "SHA-256", verifiers = "CRC32", kyberStrength = 768, probe = "8899aabbccddeeff",
        )
        assertEquals(listOf("kyber"), k768.compareTo(serverBuild).mismatched)
    }

    /** A relay may advertise fewer members than we carry; that is not a disagreement. */
    @Test
    fun aMemberTheRelayDoesNotAdvertiseIsNotAMismatch() {
        val sparse = MteProfile("mte/4.2.1;kyber=1024")
        assertTrue(clientBuild.compareTo(sparse).compatible)
    }

    @Test
    fun aFailedProbeIsRecognised() {
        val broken = MteProfile.build(
            version = "4.2.1", tokBytes = 8, drbg = "CTR-AES256-DF", cipher = "AES-256-CTR",
            hash = "SHA-256", verifiers = "CRC32", kyberStrength = 1024,
            probe = MteProfile.PROBE_ERROR,
        )
        assertTrue(broken.probeFailed)
        assertFalse(clientBuild.probeFailed)
    }

    /** The settings are compared as one joined string server-side, so order is load-bearing. */
    @Test
    fun theMemberOrderIsFixed() {
        assertEquals(
            "mte/4.2.1;tok=8;drbg=CTR-AES256-DF;cipher=AES-256-CTR;hash=SHA-256;" +
                "verifiers=CRC32;kyber=1024",
            serverBuild.settings,
        )
    }
}
