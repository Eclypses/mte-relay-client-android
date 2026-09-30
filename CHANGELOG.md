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

## [5.3.0] - 2026-09-30

### Added
- **Cookies now ride every relay call, on by default.** The relay keeps one in-memory
  cookie store shared by auth, pair, keepalive and every request frame. Previously it
  used a bare `OkHttpClient`, whose default is `CookieJar.NO_COOKIES`, so no cookie was
  ever stored or returned. This restores load-balancer affinity (`AWSALB` and friends are
  set on the auth response and must come back on the pair request, or pairs scatter
  across replicas and the relay falls back to Redis or fails with 562) and lets a
  cookie-authenticated origin work through the relay untouched by application code.

- `Relay.getInstance(Context, OkHttpClient)` and
  `Relay.getInstance(Context, OkHttpClient, boolean cookiesEnabled)`. Supply a client with
  your own `cookieJar` to use your own store — a persistent one, or one shared with the
  rest of your app — and it is used as-is. Pass `cookiesEnabled = false` to disable
  cookies for all relay traffic. Note that passing a client built with
  `CookieJar.NO_COOKIES` does *not* disable them: that is also OkHttp's default and so
  cannot signal intent, and such a client is treated as unconfigured. Both overloads are
  additive; `getInstance(Context)` is unchanged and now gets cookies.

- **Sticky-session routing token.** The relay mints an opaque token at auth and returns it
  in `X-MTE-Relay-Route`; the library now stores it for the session and echoes it on auth,
  pair and keepalive, and sends the id of the pair in use on every frame POST. A layer 7
  load balancer hashes on that header to keep a client on the replica holding its pair
  state, which lets that replica serve from memory instead of Redis. Nothing to configure
  on the client. Against a relay that mints no token the library sends none and behaves
  exactly as before.

  The token is not a secret and is never validated — authentication is still the signed
  client id. It only helps if the load balancer is configured to hash on the header (for
  an nginx ingress, `upstream-hash-by: $http_x_mte_relay_route`).

- **Relay discovery.** The auth response's `relay` object is now read, so the client can
  adapt to what a domain actually accepts. Parsing never fails and unknown fields are
  ignored: a relay without discovery leaves behaviour exactly as it was.

  Session start fails with a message naming both sides when the relay speaks a newer frame
  protocol, does not accept the session's encode type, or expects a different Kyber
  strength. All three already failed — as a generic pairing error that said nothing about
  the cause. `protocolVersion` matters most because the frame carries no version byte, so
  this is the only place a future wire change can be caught before the first frame
  silently desyncs a pair.

- **Pass-through routes are reachable.** A route the domain lists in `pass_through_routes`
  is now sent as an ordinary HTTP request — the caller's method, headers, body and cookies,
  no frame and no pair reserved. Previously every request became a frame to `POST /`, which
  the relay does not decrypt for such a route, so it could not be reached through the SDK
  at all. Pass-through removes the MTE framing, not the proxying: the origin still has to
  serve the route, so listing one it does not implement yields an unencrypted 404.

- **An oversized request body fails before anything is sent.** When the domain sets
  `maxRequestBodyBytes`, an over-limit request now raises `RelayRequestTooLargeException`
  carrying the size, the limit and which of the two was measured, with no network call — on
  both the buffered path and file upload, where the file is not read off disk. Previously the
  whole body uploaded and the relay answered 413 after the transfer had been paid for.

  The limit applies to the **encrypted frame**, not to the caller's body: measured against a
  live relay, a body sized exactly at the limit is rejected with a 413, because the frame
  carrying it is larger by the metadata section and the MKE overhead. The client therefore
  checks the built frame, with a cheap pre-check on the body so a grossly oversized one is
  never encrypted. A file upload is checked against the exact content length it will put on
  the wire, before a byte is read from disk.

  **This bounds file streaming.** A streamed upload cannot exceed the domain's limit either,
  so with `MAX_REQUEST_BODY_MIB` set to 1 the largest uploadable file is slightly under 1 MiB.
  Streaming keeps the file out of memory; it does not exempt it from the limit.

- **The relay's tuning values are adopted.** The keepalive interval is a third of the
  server's `sessionTimeoutSeconds`, clamped to 60-600s, where the caller set none — 600s
  against the dev relay's 7200s timeout, rather than the 300s default. The decoder's
  sequence window comes from `sequenceWindow` instead of a hardcoded -63 (the two agree
  today; the plumbing is there for a relay that differs). `maxPairsPerClient` clamps the
  pool sizes. A caller who set a keepalive interval keeps it, and is warned once if it is
  longer than the server's timeout allows.

- **Warnings when the server's configuration and the client's disagree**, each once per
  subject per session: a header sent unencrypted that the relay will not forward to the
  origin (checked both against discovery's list and against the relay's per-request
  `x-mte-plain-forwarded` signal), cookies disabled against a relay that forwards them, and
  a relay reporting no streaming support. `x-mte-plain-forwarded` is stripped before the
  caller sees the response — it is the relay describing itself, not something the origin
  sent.

- An event stream that aborts having delivered no data now says so in terms of
  `sseWriteDeadlineSeconds`: the relay closes a streamed response idle longer than that, and
  the fix is an upstream heartbeat rather than anything client-side. Previously the caller
  saw a bare connection abort that read like a network fault.

- `RelayWarnings.enabled` silences the advisory warnings the relay emits when the server's
  configuration disagrees with the client's. On by default; each warning is emitted once
  per subject per session either way.

### Changed

- **Breaking wire change: the frame carries path and headers in one metadata section, as JSON.**
  The v5 frame used to have two places headers could live — requests put a binary
  `[pathLen][path][headersLen][headersJSON]` structure inside the encrypted metadata section,
  while responses left that section empty and carried headers in a separate Head Flag section
  that requests never used. There is now one place, in both directions: the metadata section,
  holding a JSON object of `path` and `headers`. The Head Flag byte and its length and blob are
  gone, and every reader requires the metadata section to end exactly at `5 + ProtoLen`, so a
  peer still writing that byte is rejected outright rather than misparsed. Matches the relay
  server from `develop` at `95e90b6`; against such a relay the previous format fails every
  framed request with `400 Invalid MTE request frame`.

- **Streamed uploads now set the frame's Stream Flag, and are no longer size-capped.**
  `maxRequestBodyBytes` caps only the request bodies the relay *buffers*: with the stream flag
  set it decrypts chunk by chunk straight into the origin and never measures the body. This
  client was sending chunked uploads with the flag clear, so the relay buffered every upload in
  memory and applied the cap to it — and this client then enforced that same cap locally, before
  reading a byte off disk. Both checks are gone from the upload path. A large file upload is
  bounded by the origin, not by the relay's buffered-body limit. The cap still applies to
  ordinary buffered requests, where it is measured against the whole frame rather than the
  caller's body.

- Response metadata and the request metadata JSON are now parsed and built with `org.json`
  rather than a hand-rolled parser. The previous encoder escaped only `\` and `"`, so a header
  value containing a control character — a newline in a `User-Agent`, a tab in a filename —
  produced JSON the relay could not parse.

- Metadata size is checked before the encode call, against the relay's own bounds (61439 bytes
  under MKE, 7168 under standard MTE). Encoding first and rejecting after would leave this
  client's encoder one operation ahead of the relay's decoder, desyncing the pair.

- **`headersToEncrypt` is now `unencryptedHeaders`, and its meaning is inverted
  (breaking).** Every caller header is encrypted by default; the list now names the
  headers to leave unencrypted on the hop to the relay, for infrastructure between the
  app and the relay to read. A list ported unchanged would expose exactly the headers it
  used to protect — in most cases the argument can simply be dropped.

  The old parameter did not merely mis-name the intent, it dropped data: headers that
  were not listed were filtered out of the metadata and, because the frame has no
  unencrypted header channel, never reached the origin at all.

- **`RelayFileRequestProperties` no longer defaults to
  `{"Content-Length", "Content-Type"}`.** Under the whitelist that meant "encrypt those
  two"; under the denylist it asks to expose them, and both are reserved. `null` and
  empty now mean "encrypt everything", which is what the old default was reaching for.

- `arrayOf("*")` exposes every eligible header. It does not disable encryption — the
  body, the path and the reserved names stay encrypted.

- `Content-Type`, `Content-Length`, `Host`, `X-MTE-Relay-Route` and `X-MTE-Relay-Client`
  are reserved and always encrypted. Listing one throws `IllegalArgumentException`, as
  does an empty name, an invalid header-name character, or `"*"` alongside other entries.
  Validation runs at `Relay.send`, `Relay.startEventStream` and
  `RelayFileRequestProperties` construction, before any pairing.

  `X-MTE-Relay-Client` was added to the list after the v5 client-guide review: a relay
  that sees it treats the request as coming from another relay and skips the cookie merge,
  so a caller who listed it would have silently lost cookie forwarding for their own
  traffic. `Content-Length` and `Host` are reserved here although the guide does not list
  them — the guide is written against a browser client, where the Fetch standard forbids a
  caller from setting either, and OkHttp sets both. Confirmed with the relay team as an
  intended mobile-platform deviation rather than a conformance gap.

- The instrumented and local smoke harnesses take `MTE_V5_SMOKE_PLAIN_HEADER` in place
  of `MTE_V5_SMOKE_ENCRYPT_HEADER`, matching the inverted meaning.

- `Set-Cookie` arriving inside a decrypted response frame is dropped rather than surfaced
  to the caller. A v5 relay with the cookie feature puts it on the real HTTP response,
  where the cookie store handles it; one inside the frame comes from a relay that does
  not, and handing it up would present a cookie that nothing will ever send back.

- **An invalid HTTP header name is now rejected when the frame is built**, naming the header,
  rather than being encrypted and sent. These names are set on the relay's *upstream* request,
  so an invalid one produced a clean-looking frame and a bare `502 Upstream request failed` with
  nothing to say which header caused it. A demo app shipped `"Content-Transfer-Encoding:"` — one
  trailing colon — and every file upload failed at every size, which read as a relay outage for
  long enough to be worth preventing. RFC 7230 tokens are accepted in full, including the
  punctuation that looks wrong but is legal.

- **`downloadFile` and `startDownloadFile` no longer take a `pathnamePrefix` argument.**
  They read `reqProperties.pathnamePrefix`, the same field `uploadFile` reads. The prefix used
  to be accepted in both places and the argument won, so a caller who set the field and passed
  `null` — which the documented example did — got no prefix and no warning. The Flutter plugin
  had started setting the field *and* passing the argument, with a comment saying it was unsure
  which mattered; that is the wart leaving a mark on a consumer. Breaking: drop the second
  argument.

### Removed

- **`preventStreaming`**, from `Relay.send`, `RelayRequestOptions`, `RelayOkHttpAdapter.execute`,
  `RelayStreamingExecutor` and `RelayRequestFrame`. It does not exist in the relay server — no
  occurrence anywhere in the `mte-relay` repository at `95e90b6` — and had not since v5 replaced
  the V4 `x-mte-relay` CSV header, where it was a genuine field. In V5 it was carried on the API
  and read by nobody; in July it was wired to the frame's trailing byte on the strength of that
  byte being named `STREAM_FLAG_RESERVED` in this client. It was never reserved: it is the
  Stream Flag, and setting it means "the body is an MKE chunk stream". Sending it with a GET now
  earns a 566, and with a POST makes the relay read a single encoded blob as a chunk stream.
  Nothing called it — no call site in this repository, the demo app, or the Flutter plugin ever
  passed `true`.

### Fixed
- **A relay failure in interceptor mode no longer crashes the app.** A refused pairing, an
  incompatible relay, a malformed frame or a body over the relay's limit left the
  interceptor as an unchecked exception. OkHttp passes that to an `enqueue`d call's
  `onFailure` and then rethrows it on its dispatcher thread, where nothing catches it and
  Android kills the process. It now arrives as a `RelayCallException` — an `IOException`
  whose `cause` is the specific relay exception — and only at `onFailure`. A synchronous
  `execute()` throws the same. Wrapper mode (`relay.send`) was never affected. Present
  since 5.0.0; 5.3.0 adds more relay failures that took this path.

- **Repeated request headers no longer lose every value but the last.** OkHttp keeps
  repeated headers as separate values and the frame's header section is a JSON object
  holding one value per name; the adapter flattened this with `Headers.get()`, which
  returns only the final value. Repeats are now joined with `", "` per RFC 7230 §3.2.2.
  A request sending `Accept` twice previously delivered one of them to the origin.

- Unlisted headers reach the origin again. `uploadFile` and `streamRequest` had separate
  filter sites; both now share one partition, so the buffered, upload, download and SSE
  paths cannot disagree about which headers travel where.

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
[5.3.0]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.3.0
[5.2.3]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.2.3
[5.2.2]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.2.2
[5.2.1]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.2.1
[5.2.0]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.2.0
[5.1.0]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.1.0
[5.0.0]: https://github.com/Eclypses/mte-relay-client-android/releases/tag/v5.0.0
