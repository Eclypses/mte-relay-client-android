package com.eclypses.mte.wire

/**
 * The OPEN and OPEN_ACK bodies of section 6.
 *
 * These open a connection -- a WebSocket or a TCP tunnel -- so nothing on the HTTP
 * path sends one. They live here because `vectors/control.json` is normative for all
 * three implementations and SocketX consumes this same codec: a body shape that only
 * SocketX exercises still belongs with the rest of the wire, tested against the same
 * file.
 *
 * Added after the Swift port read the two sections this module had been ignoring.
 * Both were vendored and unread, which is the failure mode the vectors exist to
 * prevent and the reason a second implementation is worth doing carefully.
 *
 * Two things the vectors pin that a plain JSON round trip would miss:
 *
 *  - **Required members are structural, not advisory.** An OPEN without `pair`,
 *    `host` or `clientId` is refused rather than sent and rejected by the relay.
 *  - **The failure token is `payload`, not `metadata`.** These are frame payloads,
 *    and the vectors name every payload failure that way -- including a duplicate
 *    key, which the JSON reader reports as a metadata fault and which is re-labelled
 *    here.
 */
public object OpenBody {

    /** The members an OPEN must carry. */
    public val OPEN_REQUIRED: List<String> = listOf("clientId", "host", "pair")

    /** The members an OPEN_ACK must carry. */
    public val OPEN_ACK_REQUIRED: List<String> = listOf("pair")

    public fun canonicalizeOpen(text: String): String =
        canonicalize(text, OPEN_REQUIRED, "OPEN")

    public fun canonicalizeOpenAck(text: String): String =
        canonicalize(text, OPEN_ACK_REQUIRED, "OPEN_ACK")

    private fun canonicalize(text: String, required: List<String>, what: String): String {
        val obj = try {
            Json.parseObject(text)
        } catch (e: WireException) {
            // A duplicate key is a metadata fault to the JSON reader and a payload fault
            // on a frame. The vectors name the frame's view, so that is what travels.
            throw WireException(WireError.PAYLOAD, "$what body: ${e.message}")
        }

        for (member in required) {
            if (obj[member] == null) {
                throw WireException(WireError.PAYLOAD, "$what body has no \"$member\"")
            }
        }

        // Canonical at every level, including the nested `pair` object: the bytes are
        // part of one codec operation, so a nested object that kept its input order
        // would make the same body encode two ways.
        return Json.canonical(obj)
    }
}
