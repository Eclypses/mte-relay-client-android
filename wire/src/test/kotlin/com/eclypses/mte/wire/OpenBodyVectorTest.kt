package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The `open` and `open_ack` sections of `vectors/control.json`.
 *
 * These were vendored and unread until the Swift port read them. Six rows, two of
 * which require structural validation rather than a JSON round trip.
 */
class OpenBodyVectorTest {

    private val doc = SpecVectors.obj("control.json")

    private fun runSection(section: String, canonicalize: (String) -> String) {
        val rows = doc.getJSONArray(section)
        check(rows.length() > 0) { "$section is empty" }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val name = "$section: ${row.getString("name")}"
            val input = row.getString("input")

            if (row.has("error")) {
                try {
                    canonicalize(input)
                    fail("$name: expected ${row.getString("error")}, got none")
                } catch (e: WireException) {
                    assertEquals(row.getString("error"), e.error.token, name)
                }
                continue
            }
            assertEquals(row.getString("canonical"), canonicalize(input), name)
        }
    }

    @Test
    fun openVectors() = runSection("open", OpenBody::canonicalizeOpen)

    @Test
    fun openAckVectors() = runSection("open_ack", OpenBody::canonicalizeOpenAck)

    /**
     * A duplicate key is a metadata fault to the JSON reader and a payload fault on a
     * frame. The vectors name the frame's view.
     */
    @Test
    fun aDuplicateKeyIsAPayloadFault() {
        try {
            OpenBody.canonicalizeOpen("""{"pair":{},"host":"a","host":"b","clientId":"t"}""")
            fail("expected a payload failure")
        } catch (e: WireException) {
            assertEquals(WireError.PAYLOAD, e.error)
        }
    }

    /** Required members are structural: an OPEN without them is refused, not sent. */
    @Test
    fun requiredMembersAreRefusedWhenAbsent() {
        for (missing in OpenBody.OPEN_REQUIRED) {
            val members = OpenBody.OPEN_REQUIRED.filter { it != missing }
                .joinToString(",") { "\"$it\":{}" }
            try {
                OpenBody.canonicalizeOpen("{$members}")
                fail("an OPEN without \"$missing\" must be refused")
            } catch (e: WireException) {
                assertEquals(WireError.PAYLOAD, e.error)
            }
        }
    }
}
