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


## [5.2.3] - 2026-08-27

Documentation only. No library code changed. Supersedes 5.2.2, which was still
publishing when this was cut; the two are cumulative and 5.2.3 contains both.

### Fixed
- **Source Layout listed the whole Java tree as "Public API".** Four types carry
  the `public` modifier without being part of the contract — Java has no
  `internal`. `Pair` and `DecodeResult` are reached by `Class.forName` and are
  public only so reflection can find them, which is why `consumer-rules.pro` keeps
  them by name; `LogHelper` and `RelaySettings` are internal. A reader treating
  that tree as a menu could depend on any of them. Every public type is now either
  documented as API or listed as not, and the listing — not the modifier — is
  stated to be the contract.

  This is the same defect fixed on the Kotlin side in 5.2.2, where `interceptor/`
  was marked implementation detail while the docs instructed customers to import
  from it. One half was fixed and the other was not checked.


## [5.2.2] - 2026-08-27

Documentation only. No library code changed. A third blind run, the first against
interceptor-first docs, cut integration cost to +24/-4 with nothing removed — and
found what promoting the interceptor had left inconsistent.

### Fixed
- **`preventStreaming` was corrected in the README and not in the quick-start**, so
  5.2.1 shipped the two documents flatly contradicting each other: one said the flag
  concerns the upstream request and explicitly not the response, the other said it
  buffers the response. Both now state the upstream-request meaning.
- **The interceptor package was still listed as "not public API"** in Source Layout
  while the docs instructed customers to import `RelayRequestOptions` from it.
  `RelayMteInterceptor` and `RelayRequestOptions` are now listed as public.
- **The quick-start's interceptor snippet modelled unsafe usage** — it inlined the
  throwing `Relay.getInstance` inside a builder chain, which in a property
  initializer crashes the class at construction and bypasses the caller's error
  contract. Both docs now show the lazy pattern with the catch at the call site.

### Added
- A worked example of the relay inside a class that owns its `OkHttpClient` as a
  field, which is how apps are actually structured and which no example showed.
- **Which timeouts apply.** The relay does its network I/O through its own internal
  client, so the caller's `connectTimeout`/`readTimeout`/`writeTimeout` do not govern
  relay traffic; `callTimeout` does, and must accommodate first-request pairing.
- How to confirm traffic is actually relayed: the `MTE` logcat tag prints the version
  line and the pair count. This was previously documented only for file logging.
- `RelayRequestOptions`' full parameter list, with defaults. Only `headersToEncrypt`
  had ever been shown.
- A note that the one-shot body read applies in interceptor mode too — both modes
  build the response through the same adapter.


## [5.2.1] - 2026-08-27

Documentation only. No library code changed.

### Fixed
- **The listener code samples did not compile.** 5.2.0 added a third method to
  `RelayOkHttpRequestListener` but updated only the prose describing it. Both
  worked examples and four reference entries still showed the two-method
  interface, so copying the README's first example produced
  `Class '<anonymous>' is not abstract and does not implement abstract member
  'onFailure'`. All six sites now show three methods, with the `okhttp3.Request`
  parameter type spelled out.
- **`preventStreaming` was described wrongly, twice.** Both halves of the
  description talked about response buffering. It is a directive to the relay
  server about how it talks to *your origin*, and does not affect how the client
  receives the response — which is always streamed and handed over complete.
- **`Content-Type`/`Content-Length` are not "handled specially".** Request headers
  are forwarded exactly as set. The old wording implied callers had to do
  something about it, and left them unsure whether setting `Content-Type`
  alongside a `RequestBody` media type was a mistake. It is not.
- Documented that `send` never throws — it reports every failure, including an
  unusable URL, through the listener. Only `Relay.getInstance` throws.
- The sample startup log showed version 5.0.0.
- The R8/ProGuard note existed only in the quick-start, though the README is the
  comprehensive reference and release builds are where it matters.

### Changed
- **Interceptor mode is now documented as the primary integration path.**
  `getOkHttpInterceptor()` had three lines under "Advanced" in the README and was
  absent from the quick-start, while `send` — which requires rewriting every call
  site — was presented as the way in. Measured against the same baseline app,
  interceptor mode cost **+8/−3** lines and wrapper mode **+38/−28**; the caller's
  `OkHttpClient`, callbacks and response handling survive untouched, and transport
  failures still arrive at their existing `Callback.onFailure`. Both modes share
  one pairing session. Wrapper mode remains required for upload, download and SSE,
  which the interceptor cannot serve because the frame protocol needs an exact
  `Content-Length` before writing the body.
- Documented `RelayRequestOptions`, the request tag that carries `headersToEncrypt`,
  `pathnamePrefix` and `preventStreaming` in interceptor mode.


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
