package com.eclypses.mte.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import org.json.JSONObject

/**
 * `vectors/path.json`, all four sections.
 *
 * The file says every `from_url` row "was run through Go's url.EscapedPath plus RawQuery
 * and the browser's URL.pathname plus URL.search before it was written down", so these
 * are three implementations agreeing rather than one asserting.
 */
class PathVectorTest {

    private val doc = SpecVectors.obj("path.json")

    /** A row carries either a path or a pad, never both. */
    private fun pathOf(row: JSONObject): String {
        row.optJSONObject("pad")?.let { pad ->
            return pad.getString("char").repeat(pad.getInt("count"))
        }
        return row.getString("path")
    }

    private fun each(section: String, body: (JSONObject, String) -> Unit) {
        val rows = doc.getJSONArray(section)
        check(rows.length() > 0) { "$section is empty" }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            body(row, row.getString("name"))
        }
    }

    /** A writer turning a caller's URL into the member. */
    @Test
    fun fromUrl() {
        each("from_url") { row, name ->
            assertEquals(row.getString("path"), Path.fromUrl(row.getString("url")), name)
        }
    }

    /**
     * What a writer may emit. A leading slash is refused here and nowhere else -- emitting
     * one is 482 on every request, which is a whole client that never works.
     */
    @Test
    fun write() {
        each("write") { row, name ->
            val path = pathOf(row)
            if (row.has("error")) {
                try {
                    Path.write(path)
                    fail("$name: expected ${row.getString("error")}, got none")
                } catch (e: WireException) {
                    assertEquals(row.getString("error"), e.error.token, name)
                }
            } else {
                Path.write(path)
            }
        }
    }

    /** What a reader accepts: deliberately wider than what a writer may emit. */
    @Test
    fun read() {
        each("read") { row, name ->
            val path = pathOf(row)
            if (row.has("error")) {
                try {
                    Path.read(path)
                    fail("$name: expected ${row.getString("error")}, got none")
                } catch (e: WireException) {
                    assertEquals(row.getString("error"), e.error.token, name)
                }
            } else {
                Path.read(path)
            }
        }
    }

    /** A reader turning the member back into a target. This is where a traversal dies. */
    @Test
    fun toTarget() {
        each("to_target") { row, name ->
            val path = pathOf(row)
            if (row.has("error")) {
                try {
                    Path.toTarget(path)
                    fail("$name: expected ${row.getString("error")}, got none")
                } catch (e: WireException) {
                    assertEquals(row.getString("error"), e.error.token, name)
                }
            } else {
                assertEquals(row.getString("target"), Path.toTarget(path), name)
            }
        }
    }

    /**
     * The four jobs are not the same job. A reader that used the writer's rule would
     * refuse a leading slash a lenient peer legitimately sends; a writer that used the
     * reader's rule would emit one and be refused on every request. The vectors carry the
     * same input under both sections with different verdicts, so this states it directly.
     */
    @Test
    fun theWriterAndTheReaderDisagreeOnPurpose() {
        Path.read("/api/users")
        try {
            Path.write("/api/users")
            fail("a writer must not emit a leading slash")
        } catch (e: WireException) {
            assertEquals(WireError.METADATA, e.error)
        }

        // And a traversal reaches the reader intact, because toTarget is what refuses it.
        Path.read("a/../b")
        try {
            Path.toTarget("a/../b")
            fail("a traversal must not become a target")
        } catch (e: WireException) {
            assertEquals(WireError.METADATA, e.error)
        }
    }

    @Test
    fun theBoundIsMeasuredInBytes() {
        assertEquals(16384, doc.getInt("max_bytes"))
        assertEquals(Path.MAX_BYTES, doc.getInt("max_bytes"))
    }
}
