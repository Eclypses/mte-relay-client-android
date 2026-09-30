package com.eclypses.mte.wire

/**
 * The `path` member of section 8.1: path plus query, **no leading slash**, percent
 * encoded as sent, at most 16384 bytes, absent or empty meaning the root.
 *
 * `vectors/path.json` splits this into four jobs, "because the grammar has four jobs and
 * conflating them is what produced the drift":
 *
 *  - [fromUrl] -- a writer turning a caller's URL into the member.
 *  - [write] -- what a writer may emit. A leading slash is refused here.
 *  - [read] -- what a reader accepts, deliberately wider: a leading slash is tolerated
 *    and never normalised away.
 *  - [toTarget] -- a reader turning the member back into an absolute path reference. This
 *    is where a traversal is refused, which is why [read] can let one through.
 *
 * The split matters. A writer that emits a leading slash gets 482 on every request; a
 * reader that refuses one rejects traffic a lenient peer legitimately sends.
 */
public object Path {

    public const val MAX_BYTES: Int = 16384

    /**
     * Turns a caller's URL into the member: path and query, no leading slash, no fragment.
     *
     * The encoding is carried through byte for byte. `%7E` is not unescaped to `~`, a
     * lowercase escape keeps its case, `%2F` never becomes a separator, and a literal
     * space becomes `%20`. Normalising any of that would change bytes the origin may be
     * signing or matching on.
     */
    public fun fromUrl(url: String): String {
        // The fragment never leaves the client.
        val noFragment = url.substringBefore('#')

        // Find the start of the path: after the scheme and authority when present.
        val schemeEnd = noFragment.indexOf("://")
        val afterAuthority = if (schemeEnd >= 0) {
            val slash = noFragment.indexOf('/', schemeEnd + 3)
            // No path at all is the root, the same as a bare "/".
            if (slash < 0) return "" else noFragment.substring(slash)
        } else {
            noFragment
        }

        val q = afterAuthority.indexOf('?')
        val rawPath = if (q < 0) afterAuthority else afterAuthority.substring(0, q)
        // A bare question mark with no query is dropped; an empty value after one is not.
        val query = if (q < 0 || q == afterAuthority.length - 1) "" else afterAuthority.substring(q)

        return encodePath(rawPath.removePrefix("/")) + query
    }

    /**
     * Percent-encodes only what a URL parser would, and only what is not already encoded.
     *
     * A literal space becomes `%20`; an existing `%20` is left alone rather than becoming
     * `%2520`. The unreserved set, the sub-delimiters, `:` `@` and `/` are all literals in
     * a path and stay as they are.
     */
    private fun encodePath(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // An existing escape passes through with its case intact.
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                out.append(s, i, i + 3)
                i += 3
                continue
            }
            if (c in PATH_LITERAL) {
                out.append(c)
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                    out.append('%').append("%02X".format(b))
                }
            }
            i++
        }
        return out.toString()
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Unreserved, sub-delimiters, and the path characters `:` `@` `/`. */
    private val PATH_LITERAL: Set<Char> =
        (('a'..'z') + ('A'..'Z') + ('0'..'9') + "-._~!$&'()*+,;=:@/".toList()).toSet()

    /** NUL, CR and LF. A tab is not one of the three the text rule bars. */
    private fun requireNoBarredCharacters(path: String) {
        for (c in path) {
            if (c.code == 0 || c == '\r' || c == '\n') {
                throw WireException(WireError.METADATA, "\"path\" contains NUL, CR or LF")
            }
        }
    }

    private fun requireWithinBound(path: String) {
        // The bound is in UTF-8 bytes, never UTF-16 code units: the vectors carry 8193
        // two-byte characters -- 8193 code units, 16386 bytes -- precisely to catch a
        // length check that counts the wrong unit.
        val bytes = path.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_BYTES) {
            throw WireException(WireError.METADATA, "\"path\" is $bytes bytes, over $MAX_BYTES")
        }
    }

    /**
     * Validates a path a writer is about to emit.
     *
     * @throws WireException [WireError.METADATA] on anything section 8.1 refuses.
     */
    public fun write(path: String) {
        requireWithinBound(path)
        // A leading slash is a writer's mistake: the member is relative by definition, and
        // a doubled one would name an authority rather than a path.
        if (path.startsWith("/")) {
            throw WireException(WireError.METADATA, "\"path\" must not begin with a slash")
        }
        requireNoBarredCharacters(path)
    }

    /**
     * Validates a path a reader received.
     *
     * Deliberately wider than [write]: a leading slash is accepted and never normalised
     * away, and a traversal passes through, because [toTarget] is what refuses it. A
     * reader that refused these would reject traffic a lenient peer legitimately sends.
     */
    public fun read(path: String) {
        requireWithinBound(path)
        requireNoBarredCharacters(path)
    }

    /**
     * Turns the member back into an absolute path reference a URL builder accepts.
     *
     * This is where a traversal is refused. The checks are on **literal** dot segments
     * only: `a/..%2Fb` is one encoded segment and not a traversal, `...` is an ordinary
     * segment, and `x?a=../b` has its dots inside the query where they are not a path
     * segment at all. Decoding before checking is how a path filter gets bypassed.
     */
    public fun toTarget(path: String): String {
        read(path)
        // Two leading slashes name an authority, not a path: "//evil.example/x" as a URL
        // reference is a host, so a peer resolving it would reach another origin.
        if (path.startsWith("//")) {
            throw WireException(WireError.METADATA, "\"path\" begins with an authority")
        }

        val target = if (path.startsWith("/")) path else "/$path"
        for (segment in target.substringBefore('?').split('/')) {
            if (segment == "..") {
                throw WireException(WireError.METADATA, "\"path\" contains a traversal segment")
            }
        }
        return target
    }
}
