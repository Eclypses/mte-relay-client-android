package com.eclypses.relay.session

import com.eclypses.mte.wire.Discovery
import com.eclypses.mte.wire.Metadata
import com.eclypses.mte.wire.MteProfile

/** A relay this client cannot talk to. Reported at setup, before any pairing work. */
class RelayIncompatibleException(message: String) : IllegalStateException(message)

/** Something worth telling the caller once, that does not stop the session. */
data class RelayDiscoveryWarning(val key: String, val message: String)

/**
 * The compatibility checks that run between auth and pairing.
 *
 * All of them are cheap string and integer comparisons, and all of them catch a condition
 * whose only other symptom is a decode failure on the first frame -- by which point the
 * cause is several layers away from the error. The order mirrors
 * `mte-relay-browser/src/mte-relay/discovery.ts`, because three implementations reporting
 * the same misconfiguration differently is the thing this refactor exists to end.
 */
object RelayDiscoveryChecks {

    fun assertCompatible(
        origin: String,
        discovery: Discovery,
        clientProfile: MteProfile,
        clientKyberStrength: Int,
    ): List<RelayDiscoveryWarning> {
        val warnings = mutableListOf<RelayDiscoveryWarning>()

        // 1. Frame version. The client picks the highest version both speak and writes it
        //    in every frame for the life of the session.
        if (discovery.selectFrameVersion() == null) {
            throw RelayIncompatibleException(
                "$origin does not speak frame version ${Metadata.FRAME_VERSION}. It " +
                    "advertises ${discovery.frameVersions}; this client speaks " +
                    "[${Metadata.FRAME_VERSION}]. Upgrade the relay.",
            )
        }
        if (Metadata.FRAME_VERSION in discovery.deprecatedFrameVersions) {
            warnings += RelayDiscoveryWarning(
                "frame-version-deprecated",
                "$origin lists frame version ${Metadata.FRAME_VERSION} as deprecated. " +
                    "Plan a client upgrade.",
            )
        }

        // 2. The MTE profile settings, and only the settings.
        //
        //    The probe differs between a client build and a server build by design, so a
        //    whole-string comparison fails every correct deployment -- while reporting
        //    "profile mismatch", which sends the reader to the one place that is fine.
        if (discovery.mteProfile.isNotEmpty()) {
            val comparison = clientProfile.compareTo(MteProfile(discovery.mteProfile))
            if (!comparison.compatible) {
                throw RelayIncompatibleException(
                    "MTE profile mismatch with $origin on ${comparison.mismatched.joinToString(", ")}. " +
                        "The relay reports \"${discovery.mteProfile}\" and this client is " +
                        "\"${clientProfile.text}\"; the two library builds cannot interoperate.",
                )
            }
            if (comparison.probeDiffers) {
                warnings += RelayDiscoveryWarning(
                    "mte-profile-probe",
                    "The relay's MTE probe is ${comparison.serverProbe} and this client's is " +
                        "${comparison.clientProbe}. Every settings field agrees, so pairing " +
                        "proceeds: a mobile client links the MTE client build and the relay " +
                        "links the server build, and the two produce different probe bytes " +
                        "by design.",
                )
            }
        }
        // 3. Kyber strength. A mismatch yields a secret neither side can decrypt, and
        //    nothing in that failure names Kyber.
        if (discovery.kyberStrength != clientKyberStrength) {
            throw RelayIncompatibleException(
                "Kyber strength mismatch with $origin: the relay reports " +
                    "${discovery.kyberStrength} and this client uses $clientKyberStrength.",
            )
        }

        // 4. The encode type has to be one the relay carries metadata bounds for; a type
        //    it does not bound is a type it does not accept. The caller checks that,
        //    because only it knows which type this session uses.
        //
        //    A failed *client* probe is deliberately not checked here. Discovery is a
        //    comparison of two descriptions; a library that never loaded is a local fault,
        //    and it is caught where the profile is actually sent -- at pairing -- so a
        //    caller who never pairs is not stopped by it.
        return warnings
    }

    /** Whether the relay bounds metadata for [type], which is how it says it accepts it. */
    fun accepts(discovery: Discovery, type: com.eclypses.mte.wire.MteType): Boolean =
        discovery.maxMetadataBytes.containsKey(type)
}

/**
 * The discovery members that are strings lists in `raw` rather than typed on [Discovery].
 *
 * `:wire` surfaces what the protocol defines; these three are relay *deployment* policy
 * carried in the same document, so they are read here rather than widening the shared
 * type that SocketX also consumes.
 */
private fun Discovery.stringList(name: String): List<String>? =
    (raw[name] as? com.eclypses.mte.wire.JsonArray)
        ?.items?.mapNotNull { (it as? com.eclypses.mte.wire.JsonString)?.value }

/**
 * Whether [routeOrPath] names a route the relay forwards upstream without MTE framing.
 *
 * Pass-through removes the encryption, not the proxying: the origin still has to serve the
 * route. Listing one the origin does not implement gets an unencrypted 404 rather than a
 * working endpoint.
 *
 * Matching follows the server's own rule: an exact match, or a prefix match when the entry
 * ends in a star, comparing against the entry with the star removed. An entry of `/public/`
 * plus a star therefore matches `/public/x` and not `/publicx` -- the prefix keeps its
 * trailing slash.
 *
 * (Spelled out rather than shown literally: Kotlin block comments nest, so an example
 * containing a slash-star would open a nested comment and swallow this one's terminator.)
 *
 * Any query string is dropped first: the relay's routes are paths, and callers hand routes
 * around here with the query attached.
 */
fun Discovery.isPassThrough(routeOrPath: String): Boolean {
    val routes = stringList("passThroughRoutes") ?: return false
    val path = routeOrPath.substringBefore('?').let { if (it.startsWith("/")) it else "/$it" }
    return routes.any { entry ->
        when {
            entry.isEmpty() -> false
            entry.endsWith("*") -> path.startsWith(entry.dropLast(1))
            else -> path == entry
        }
    }
}

/**
 * Names no `forwardPlainHeaders` entry can opt into the upstream request, including under
 * the wildcard. The server excludes them outright, so a caller who sends one in the clear
 * gets the exposure without the delivery and should be told.
 *
 * `Content-*` is handled by prefix rather than listed: they all describe the frame POST
 * rather than the inner request, whose real ones are inside the frame.
 */
private val NEVER_FORWARDED = setOf(
    "host",
    "accept-encoding", // a compressing origin would return bytes the client cannot decode
    "cookie", // governed by forwardBrowserCookies, never by the allowlist
    "expect", // 100-continue is per hop; forwarding it stalls the upstream send
    "x-mte-relay-route",
    "x-mte-relay-client",
    // RFC 7230 hop-by-hop
    "connection", "proxy-connection", "keep-alive", "proxy-authenticate",
    "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
)

/**
 * Whether the relay will forward a header sent in the clear on to the origin.
 *
 * Returns null when the domain states no list at all, which is not the same as an empty one:
 * a relay saying nothing about forwarding is not a promise that nothing is forwarded, and
 * warning on it would be guessing.
 */
fun Discovery.forwardsPlainHeader(name: String): Boolean? {
    val list = stringList("forwardPlainHeaders")?.map { it.lowercase() } ?: return null
    val lowered = name.lowercase()
    if (lowered in NEVER_FORWARDED || lowered.startsWith("content-")) {
        return false
    }
    return list.any { entry ->
        when {
            entry == "*" -> true
            entry.contains('*') -> lowered.matches(globToRegex(entry))
            else -> entry == lowered
        }
    }
}

/**
 * The server's glob rule: split on stars, escape the literal pieces, join with "match
 * anything", anchor both ends. So `x-abc-*` matches `x-abc-trace` and not `y-abc-trace`.
 */
private fun globToRegex(entry: String): Regex =
    entry.split("*")
        .joinToString(".*") { Regex.escape(it) }
        .toRegex(RegexOption.IGNORE_CASE)

/**
 * Raised when a request body is larger than the relay will accept, before anything is sent.
 *
 * Carries both numbers because the caller cannot otherwise tell a body they can shrink from
 * a server limit they need raised. Without this the whole body uploads and the relay answers
 * 481 after the cost has already been paid -- on mobile, over metered data.
 */
class RelayRequestTooLargeException(
    val sizeBytes: Long,
    val limitBytes: Long,
    /** What [sizeBytes] measured -- the caller's body, or the encrypted frame built from it. */
    val measured: String = MEASURED_BODY,
) : IllegalArgumentException(
    buildString {
        append("The $measured is $sizeBytes bytes; the relay accepts at most $limitBytes. ")
        append("Nothing was sent.")
        if (measured == MEASURED_FRAME) {
            append(" Encryption adds overhead, so a body at the limit does not fit inside it.")
        }
    },
) {
    companion object {
        const val MEASURED_BODY = "request body"
        const val MEASURED_FRAME = "encrypted request"
    }
}
