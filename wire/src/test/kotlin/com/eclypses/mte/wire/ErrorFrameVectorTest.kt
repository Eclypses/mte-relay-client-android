package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.json.JSONObject

/** `vectors/error.json`, read as the specification rather than restated. */
class ErrorFrameVectorTest {

    private fun payload(code: Int, json: String): ByteArray =
        byteArrayOf((code ushr 8 and 0xFF).toByte(), (code and 0xFF).toByte()) +
            json.toByteArray(Charsets.UTF_8)

    @Test
    fun everyVectorCase() {
        val cases = SpecVectors.array("error.json")
        var accepted = 0
        var rejected = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val bytes = if (c.has("hex")) {
                SpecVectors.hex(c.getString("hex"))
            } else {
                payload(c.getInt("code"), c.getString("json"))
            }

            if (c.has("error")) {
                rejected++
                try {
                    ErrorFrame.read(bytes)
                    fail("$name: expected a ${c.getString("error")} failure, got none")
                } catch (e: WireException) {
                    assertEquals(
                        c.getString("error"), e.error.token,
                        "$name: wrong failure token",
                    )
                }
                continue
            }

            accepted++
            val frame = ErrorFrame.read(bytes)
            val expect: JSONObject = c.getJSONObject("expect")
            assertEquals(c.getInt("code"), frame.code, "$name: code")
            assertEquals(expect.getString("reason"), frame.reason, "$name: reason")
            assertEquals(expect.optString("message", ""), frame.message, "$name: message")

            val expectedCause = expect.optJSONObject("cause")
            if (expectedCause == null) {
                assertEquals(null, frame.cause, "$name: unexpected cause")
            } else {
                val cause = assertNotNull(frame.cause, "$name: missing cause")
                assertEquals(expectedCause.getInt("code"), cause.code, "$name: cause code")
                assertEquals(expectedCause.getInt("hop"), cause.hop, "$name: cause hop")
                assertEquals(expectedCause.getString("reason"), cause.reason, "$name: cause reason")
            }

            // A case naming a canonical form pins what a re-serialisation drops: the
            // unknown member is ignored on the way in and absent on the way out.
            if (c.has("canonical")) {
                assertEquals(
                    c.getString("canonical"),
                    String(frame.toByteArray(), 2, frame.toByteArray().size - 2, Charsets.UTF_8),
                    "$name: canonical round trip",
                )
            }
        }
        // The file is the specification; an empty read would pass every assertion above.
        check(accepted >= 6 && rejected >= 5) { "only $accepted accepted / $rejected rejected cases ran" }
    }

    /** A reason newer than this build's registry is accepted and takes the code's default. */
    @Test
    fun anUnknownReasonTakesTheCodeDefaultAction() {
        val frame = ErrorFrame.read(payload(477, """{"reason":"frame_shape_v3"}"""))
        assertEquals(RelayAction.SURFACE, frame.action)
        assertEquals(RelayRegistry.defaultAction(477), frame.action)
    }

    /** Same code, opposite actions. This is why behaviour keys on the reason. */
    @Test
    fun oneCodeCarriesTwoActions() {
        assertEquals(
            RelayAction.SURFACE,
            ErrorFrame.read(payload(477, """{"reason":"invalid_frame"}""")).action,
        )
        assertEquals(
            RelayAction.REPLACE_PAIR,
            ErrorFrame.read(payload(477, """{"reason":"eof_before_end"}""")).action,
        )
    }

    @Test
    fun roundTripsThroughTheWireForm() {
        val frame = ErrorFrame(
            code = 502,
            reason = "upstream_response_failed",
            message = "downstream relay rejected the pair",
            cause = ErrorFrame.Cause(470, 2, "pair_not_found"),
        )
        assertEquals(frame, ErrorFrame.read(frame.toByteArray()))
    }
}
