package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `vectors/ws.json`: the relay-code to WebSocket close-code mapping.
 *
 * Only the close-code tables are read here, because the mapping is the only part of this
 * file the codec implements. The rest -- per-transport flag rules, one frame per message,
 * fragment runs and close classes -- describes the WebSocket transport and is SocketX's to
 * adopt with the codec. Its HTTP flag rows included: nothing here yet refuses MORE, TEXT or
 * HALF on an HTTP DATA, which the spec makes a `payload` error. Deferred past 5.3.0.
 */
class WsVectorTest {

    /** Every relay code in the mapped window, registered or not, lands at `4000 + code - 400`. */
    @Test
    fun `every close code`() = checkSection("close_codes")

    /**
     * A code outside the window has no application close code of its own and closes as
     * 1011, internal error -- never as a number outside the RFC 6455 application range.
     */
    @Test
    fun `every out of domain close code`() = checkSection("close_codes_out_of_domain")

    private fun checkSection(section: String) {
        val rows = SpecVectors.obj("ws.json").getJSONArray(section)
        assertTrue(rows.length() > 0, "ws.json has no $section section")
        val failures = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val got = Control.closeCodeFor(row.getInt("code"))
            if (got != row.getInt("close_code")) {
                failures += "${row.getString("name")}: got $got, want ${row.getInt("close_code")}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
