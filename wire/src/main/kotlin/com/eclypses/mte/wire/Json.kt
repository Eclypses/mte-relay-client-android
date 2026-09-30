package com.eclypses.mte.wire

/**
 * A JSON model, parser and canonical writer for frame version 2 metadata.
 *
 * Hand written rather than delegated to a JSON library, for three reasons the
 * metadata vectors make concrete:
 *
 *  - **Duplicate keys must be rejected at any level.** Every mainstream parser
 *    accepts them and keeps the last, so a library turns `{"v":2,"v":2}` into
 *    valid input. The vectors call that a trap and require a refusal.
 *  - **Numbers must survive exactly.** `{"id":18446744073709551615}` is 2^64-1;
 *    parsing it into a signed `Long` mangles it and into a `Double` loses it.
 *    Numbers are kept as the raw text they were written with, so canonical output
 *    is the input text and "integers in plain decimal" is a check on that text.
 *  - **Escaping is specified exactly.** Section 8.3: the quote, the backslash and
 *    control characters only, using `\b \f \n \r \t` where they exist and
 *    otherwise `\u00xx` with *lowercase* hex; no HTML escaping, no slash escaping.
 *    Libraries differ on all three and none of them let you choose.
 */
public sealed interface JsonValue

public data class JsonString(public val value: String) : JsonValue

/** The number as written. Never converted, so nothing is rounded or truncated. */
public data class JsonNumber(public val raw: String) : JsonValue {
    /** Section 8.1 calls several members integers; this is that check, on the text. */
    public val isInteger: Boolean
        get() = raw.isNotEmpty() && raw.none { it == '.' || it == 'e' || it == 'E' }
}

public data class JsonBool(public val value: Boolean) : JsonValue

public object JsonNull : JsonValue

public data class JsonArray(public val items: List<JsonValue>) : JsonValue

/** Insertion ordered; the canonical writer sorts, so order here is only the input's. */
public data class JsonObject(public val members: Map<String, JsonValue>) : JsonValue {
    public operator fun get(key: String): JsonValue? = members[key]
}

/**
 * A strict JSON reader.
 *
 * Rejects what section 8.3 requires rejected: a duplicate key at any level, a top
 * level that is not an object, and anything after the value. Leading and trailing
 * whitespace around the whole document is allowed, which the vectors confirm.
 */
public object Json {

    public fun parseObject(text: String): JsonObject {
        val p = Parser(text)
        p.skipWhitespace()
        val v = p.value()
        p.skipWhitespace()
        if (!p.atEnd) throw WireException(WireError.METADATA, "trailing content after the object")
        if (v !is JsonObject) throw WireException(WireError.METADATA, "top level is not an object")
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        val atEnd: Boolean get() = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(): JsonValue {
            if (atEnd) throw WireException(WireError.METADATA, "unexpected end of input")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonString(string())
                't' -> literal("true").let { JsonBool(true) }
                'f' -> literal("false").let { JsonBool(false) }
                'n' -> literal("null").let { JsonNull }
                else -> number()
            }
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) throw WireException(WireError.METADATA, "bad literal")
            i += word.length
        }

        private fun obj(): JsonObject {
            expect('{')
            val out = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { i++; return JsonObject(out) }
            while (true) {
                skipWhitespace()
                val key = string()
                // Section 8.3: duplicates are rejected "at any level". Keeping the
                // last, as every library does, lets an on path party append a second
                // copy of a member and change what the peer reads.
                if (out.containsKey(key)) {
                    throw WireException(WireError.METADATA, "duplicate key \"$key\"")
                }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                out[key] = value()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return JsonObject(out)
                    else -> throw WireException(WireError.METADATA, "expected , or }")
                }
            }
        }

        private fun arr(): JsonArray {
            expect('[')
            val out = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { i++; return JsonArray(out) }
            while (true) {
                skipWhitespace()
                out += value()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return JsonArray(out)
                    else -> throw WireException(WireError.METADATA, "expected , or ]")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd) throw WireException(WireError.METADATA, "unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(escape())
                    c.code < 0x20 ->
                        throw WireException(WireError.METADATA, "raw control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun escape(): Char {
            if (atEnd) throw WireException(WireError.METADATA, "unterminated escape")
            return when (val c = s[i++]) {
                '"' -> '"'
                '\\' -> '\\'
                // Section 8.3 says a writer does not escape the slash; a reader still
                // accepts one, and the vectors require it be unescaped on the way out.
                '/' -> '/'
                'b' -> '\b'
                'f' -> ''
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (i + 4 > s.length) throw WireException(WireError.METADATA, "short \\u escape")
                    val hex = s.substring(i, i + 4)
                    i += 4
                    val code = hex.toIntOrNull(16)
                        ?: throw WireException(WireError.METADATA, "bad \\u escape")
                    code.toChar()
                }
                else -> throw WireException(WireError.METADATA, "bad escape \\$c")
            }
        }

        private fun number(): JsonNumber {
            val start = i
            if (peek() == '-') i++
            while (!atEnd && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' ||
                    s[i] == 'E' || s[i] == '+' || s[i] == '-')
            ) i++
            val raw = s.substring(start, i)
            if (raw.isEmpty()) throw WireException(WireError.METADATA, "expected a value")
            if (!NUMBER.matches(raw)) throw WireException(WireError.METADATA, "bad number $raw")
            return JsonNumber(raw)
        }

        private fun peek(): Char? = if (atEnd) null else s[i]
        private fun next(): Char? = if (atEnd) null else s[i++]
        private fun expect(c: Char) {
            if (next() != c) throw WireException(WireError.METADATA, "expected $c")
        }

        companion object {
            private val NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
        }
    }

    /**
     * Writes canonical form: keys sorted in byte order at every level, no
     * insignificant whitespace, and the escaping of section 8.3 exactly.
     */
    public fun canonical(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(v: JsonValue, out: StringBuilder) {
        when (v) {
            is JsonObject -> {
                out.append('{')
                // "Byte order", not the platform's string collation: sort the UTF-8
                // bytes. For the ASCII member names in this protocol the two agree,
                // but the rule is about bytes and a locale aware sort is a bug that
                // only appears on someone else's device.
                var first = true
                for (k in v.members.keys.sortedWith(BYTE_ORDER)) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k, out)
                    out.append(':')
                    write(v.members.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                v.items.forEachIndexed { idx, item ->
                    if (idx > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonString -> writeString(v.value, out)
            is JsonNumber -> out.append(v.raw)
            is JsonBool -> out.append(if (v.value) "true" else "false")
            JsonNull -> out.append("null")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                // Lowercase hex, and only for control characters. Not the slash, not
                // < > &, and not non ASCII text, which goes out as UTF-8.
                c.code < 0x20 -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private val BYTE_ORDER = Comparator<String> { a, b ->
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < x.size && i < y.size) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return@Comparator d
            i++
        }
        x.size - y.size
    }
}
