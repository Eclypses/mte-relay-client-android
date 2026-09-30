package com.eclypses.mte.wire

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads the vendored conformance vectors.
 *
 * `spec/frame-v2.md` opens by saying the fixtures under `vectors/` are part of the
 * specification and "an implementation that disagrees with a vector is wrong". So
 * these tests assert against the files rather than against expectations restated
 * here: a restatement is a second source of truth and the place a protocol drifts.
 *
 * The directory comes from the `spec.vectors.dir` system property, set by the wire
 * module's build so the relay module's vendored copy is the only copy.
 */
internal object SpecVectors {

    private val dir: File by lazy {
        val configured = System.getProperty("spec.vectors.dir")
            ?: error(
                "spec.vectors.dir is not set. The wire tests read the vectors " +
                    "vendored under relay/src/test/resources/spec-vectors; run them " +
                    "through Gradle, which points the property at that directory.",
            )
        File(configured).also {
            check(it.isDirectory) {
                "no vectors at $it -- run scripts/sync_spec_vectors.py"
            }
        }
    }

    fun array(name: String): JSONArray = JSONArray(read(name))

    fun obj(name: String): JSONObject = JSONObject(read(name))

    private fun read(name: String): String {
        val f = File(dir, name)
        check(f.isFile) { "missing vector file $name in $dir" }
        return f.readText()
    }

    /** Hex as the vectors write it: lowercase, no separators, possibly empty. */
    fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex: $s" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((digit(s[i * 2]) shl 4) or digit(s[i * 2 + 1])).toByte()
        }
        return out
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not hex: $c")
    }
}
