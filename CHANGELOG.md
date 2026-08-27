# Changelog

All notable changes to this project will be documented in this file.

> **History before 5.0.0 lives in `eclypses-aws-mte-relay-client-android`.**
> This repository was copied from it to change the repository and artifact name
> without disrupting existing 4.x customers, who continue to consume
> `com.eclypses:eclypses-aws-mte-relay-client-android-release`. 5.0.0 was already
> a known breaking change, so it was the natural place to make the split. That
> repository's CHANGELOG runs back to 3.7.0 and remains the record for 4.x and
> for the 5.0.0 release candidates; it is deliberately not duplicated here.
>
> The 5.0.0 and 5.1.0 entries below are backfilled from commit history — this
> file did not exist when they shipped.

## [Unreleased]

### Added
-

### Changed
-

### Fixed
-


## [5.2.0] - 2026-08-27

### Changed
- **`RelayOkHttpRequestListener` gained a third method, `onFailure(Request, Throwable)`
  (breaking).** Implementations must add it. Previously, a request that never
  reached the network — offline device, unresolvable host, timeout, failed
  pairing — was reported through `onError` as a synthesized **HTTP 500**, which
  made an offline client indistinguishable from a failing server and led callers
  to tell users the backend was down. The three outcomes now mirror what OkHttp
  itself reports: `onResponse` (2xx), `onError` (the server replied non-2xx, with
  the origin's real status and body), and `onFailure` (no HTTP response at all).

### Fixed
- `getRelayGlobalSettings()` reported `relayVersion` as `"5.0.0"` from a 5.1.0
  build. The constant was hardcoded in source and had drifted.
- **Version drift fixed at the root.** The version now has one declaration,
  `ext.relayVersion` in `relay/build.gradle`; the `BuildConfig` constant,
  `versionName`, the project version and the Maven publication coordinate all
  derive from it. Four sites previously held the number independently, which is
  how it drifted twice — see the 5.1.0 entry for the first occurrence.

### Documentation
- Documented **how to compose the request URL** (relay origin + the unchanged
  backend path). This is the single fact an integration cannot proceed without,
  and it appeared in neither document.
- Documented that `Relay.getInstance` requires a `Context`, and that the first
  `send` performs the key exchange and pair-pool setup and is therefore much
  slower than subsequent calls.
- Documented that the response body is buffered in memory (no `close()` needed)
  but is **consumed** by `body.string()` — reading it twice yields an empty
  string the second time.
- Corrected the quick-start's "Java 8+" requirement to **Java 11+**, matching what
  the library is actually compiled against and what the README already said.


## [5.1.0] - 2026-08-12

### Changed
- **Package relocated `com.mte.relay` -> `com.eclypses.relay` (breaking).**
  Consumers must update their imports; type names are unchanged. The published
  artifactId `com.eclypses:mte-relay-client-android` did not change. See
  `NAMING.md` for the convention.

### Fixed
- Gradle/Maven `version` was still `5.0.0` when only `versionName` had been
  bumped, so the published coordinate did not match the release.


## [5.0.0] - 2026-08-06

### Added
- `consumer-rules.pro`, keeping `Pair` and `DecodeResult` under R8. Both are
  reached reflectively by name, so a minified consumer build — which Flutter
  produces by default for Android release — stripped them and crashed only in
  release.
- MIT `LICENSE`, a full Android implementation guide in `README.md`, and a CI
  pipeline enforcing a 70% unit-coverage floor.
- Source mirroring to public GitHub on `master`, as a clean orphan commit plus
  tag.

### Changed
- **Volley removed; OkHttp streaming is the only transport (breaking).**
  `addToMteRequestQueue` and `RelayVolleyRequestListener` are gone. Requests now
  go through `send(okhttp3.Request, headersToEncrypt, pathnamePrefix,
  preventStreaming, RelayOkHttpRequestListener)`. Callbacks arrive on a
  background thread — Volley delivered them on the main thread, so callers that
  touch views must post there themselves.
- **Send and interceptor paths converged onto the streaming core**, so an
  ordinary request and a long-lived stream are produced and consumed the same
  way. SSE carries method and body through the streaming frame, and raw SSE
  chunks are surfaced by the transport rather than parsed inside it;
  `preventStreaming` is wired through for callers that need the buffered shape.
- **MTE core consumed from Maven Central** as `com.eclypses:mte-client-android`
  instead of being embedded, so an app linking both Relay and SocketX gets one
  copy of the core rather than duplicate classes and native libraries. The
  composite build is guarded so the build still works without the sibling
  checkout present.
- Logging gained lazy overloads and level gates, and defaults to quieter output.

### Removed
- `autoRetryAfterFailure` from `RelayClientSettings`, along with its getter,
  builder setter and the equals/hashCode/toString/constructor boilerplate. The
  flag was **inert on Android**: client settings are not sent to the server and
  no code path resent a request, so it was never read. It only ever meant
  something on iOS, where the corresponding decode-failure retry was retired in
  the same change. It was removed before 5.0.0 shipped, so no released version
  ever exposed it.


<!-- Release links. Only versions tagged on the public mirror are listed. -->
[5.1.0]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.1.0
[5.0.0]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.0.0
