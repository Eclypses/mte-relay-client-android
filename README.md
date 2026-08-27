# MteRelay Mobile Client — Android

Android library providing the Eclypses **MteRelay** Mobile Client. It enables
secure, encrypted HTTP(S) communication between your Android app and your backend
via an MteRelay server. You must have licensed access to an MteRelay server
instance. [More info](https://eclypses.com/mte-technology/amazon-web-services-aws/)

**What MteRelay does for you:**
- Securely relays HTTP requests to your backend through an MteRelay server
- Protects sensitive headers and request/response data with **MTE** encryption
- Streams large files (upload and download) chunk-by-chunk, so a file is never
  held entirely in memory
- Supports relay-backed Server-Sent Events (SSE)

## Quick Links

📚 **[Official Getting Started Guide](https://public-docs.eclypses.com/docs/mte-relay-server/client-libraries/Android)** — concise guide for experienced developers.

🚀 **[Quick-Start Guide (in this repo)](quick-start/android.md)** - Fast setup for experienced developers

💡 This README is the comprehensive reference, with detailed examples suitable for
developers at all experience levels. The API is **callback-based**: you pass a
listener to each call and handle the result there.

## Prerequisites

- **Android `minSdk` 28 or higher** — required by the bundled MTE client library
- **Java 11+** — the library is compiled against Java 11
- **OkHttp** — the request API takes `okhttp3.Request`, so your app must have OkHttp
  on its classpath (the library depends on it internally as `implementation`, which
  is not exposed transitively — declare it yourself; see Installation)
- **MTE core 4.2.1** (via `com.eclypses:mte-client-android`) — resolved automatically
  as a transitive Maven dependency; no separate download required
- **Access to a licensed MteRelay server instance** — this client talks to an
  MteRelay server that performs the encryption/decryption and forwards to your real
  backend. You need the server URL and valid MTE licensing.

## Installation (Gradle — Maven Central)

Maven Central is already in Gradle's default repositories.

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.eclypses:mte-relay-client-android:5.2.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // you build okhttp3.Request objects
}
```

```groovy
// build.gradle (Groovy DSL)
dependencies {
    implementation 'com.eclypses:mte-relay-client-android:5.2.3'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

This transitively pulls in `com.eclypses:mte-client-android` (the shared MTE core),
so you do not add it yourself.

> **R8 / ProGuard:** the library ships its own `consumer-rules.pro`, which your app's
> R8 pass applies automatically. No keep rules are required on your side. This matters
> for release builds only — Android minifies release by default, not debug.

## Setup

### Step 1: Configure your MteRelay server

Before using this client, ensure your MteRelay server is running and configured to
receive encrypted requests from your app. The server acts as a secure intermediary:
it receives the encrypted request, decrypts it, and forwards it to your backend API.

### Step 2: Get the Relay instance

`Relay` is a **singleton**. `Relay.getInstance(context)` validates your MTE license on
first creation (throwing `RelayException` if it fails) and initializes logging.
Pairing with the server happens **automatically on the first request** — there is no
separate connect/pair step.

```kotlin
import com.eclypses.relay.Relay
import com.eclypses.relay.RelayException

val relay: Relay = try {
    Relay.getInstance(applicationContext)
} catch (e: RelayException) {
    // MTE license check failed, or fatal init error
    Log.e("Relay", "Relay init failed: ${e.message}")
    throw e
}
```

```java
// Java
Relay relay;
try {
    relay = Relay.getInstance(getApplicationContext());
} catch (RelayException e) {
    Log.e("Relay", "Relay init failed: " + e.getMessage());
    throw e;
}
```

> ⚠️ **Threading:** the request methods below do their network work on background
> threads and deliver results through listener callbacks that also fire on a
> background thread. Marshal any UI updates back to the main thread, e.g.
> `Handler(Looper.getMainLooper()).post { ... }` or `Activity.runOnUiThread { ... }`.

## Usage

### Where to create the Relay

`Relay.getInstance(context)` needs an Android `Context`, which a plain networking
class usually does not have. Expect to pass one in — that constructor change is
normally the only structural edit the integration asks for. An application context
is fine and avoids leaking an Activity.

It is a singleton and cheap to fetch repeatedly, so calling it per request is fine.
The **first request** is the expensive one, in either mode: it runs the key exchange
and establishes a pool of pairs with the host before your request leaves. Subsequent
requests reuse the pool. Show a spinner on that first call, and see the timeout note
under Interceptor mode for which of your timeouts actually apply.

### Making Secure HTTP Requests

Every request is an ordinary `okhttp3.Request` pointed at your **MteRelay server URL**,
not your backend URL — the relay decrypts and forwards.

**How to build the URL.** Keep the path your backend already expects and change only
the host: the relay forwards the path through unchanged.

| Your app calls today | Through the relay |
|---|---|
| `https://api.yourcompany.com/api/login` | `https://your-relay-server.com/api/login` |

So the URL is *relay origin + the same backend path*. The relay works out which host
it is paired with from the request URL — you never pass the server URL to
`Relay.getInstance`.

#### Two ways to send it

Both share one pairing session, so you can mix them in the same app.

| | Interceptor mode | Wrapper mode (`relay.send`) |
|---|---|---|
| Your `OkHttpClient` | kept | not used |
| Your `Callback` and response handling | unchanged | rewritten as a `RelayOkHttpRequestListener` |
| Failures | your existing `Callback.onFailure` | `onFailure(Request, Throwable)` |
| Per-request options | optional request tag | explicit arguments |
| Upload, download, SSE | not supported | required |

**If you already use OkHttp, start with interceptor mode.** It leaves your calling
code alone: same client, same `enqueue`, same callbacks, same response handling. In a
representative app the whole change was two imports, a `Context` parameter, the URL,
and swapping `OkHttpClient()` for a builder — nothing removed. Wrapper mode covers the
same HTTP calls but asks you to rewrite each call site, and it is the only mode for
file transfer and SSE.

#### Interceptor mode

Register the interceptor once on your own client and keep everything else:

```kotlin
import com.eclypses.relay.Relay

val relay = Relay.getInstance(applicationContext)

val client = OkHttpClient.Builder()
    .addInterceptor(relay.getOkHttpInterceptor())
    .build()

// Unchanged from here on — your existing request, call and callbacks.
client.newCall(request).enqueue(object : Callback {
    override fun onResponse(call: Call, response: Response) { /* as before */ }
    override fun onFailure(call: Call, e: IOException) { /* as before */ }
})
```

Every call made through that client is relayed. Responses arrive as ordinary OkHttp
responses and transport failures arrive at your existing `onFailure`, so error
handling you already wrote keeps working.

**Per-request options.** `headersToEncrypt`, `pathnamePrefix` and `preventStreaming`
are the same options wrapper mode takes as arguments. Attach them to a request with a
typed tag, or omit the tag for defaults:

```kotlin
import com.eclypses.relay.interceptor.RelayRequestOptions

// All three parameters are optional:
//   RelayRequestOptions(
//       headersToEncrypt: Array<String>? = null,
//       preventStreaming: Boolean = false,
//       pathnamePrefix: String? = null,
//   )
val request = Request.Builder()
    .url("https://your-relay-server.com/api/users/123")
    .header("Authorization", "Bearer abc123")
    .tag(RelayRequestOptions::class.java,
         RelayRequestOptions(headersToEncrypt = arrayOf("Authorization")))
    .get()
    .build()
```

**In a real class.** Apps usually keep the `OkHttpClient` as a field, and
`Relay.getInstance` throws — so building it in a property initializer crashes the
class at construction and bypasses whatever error contract your callers rely on.
Build it lazily and catch at the call site:

```kotlin
class ApiClient(private val context: Context) {

    // Lazy, so a licence failure surfaces on first use rather than at construction.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Relay.getInstance(context).getOkHttpInterceptor())
            .build()
    }

    fun login(listener: Listener) {
        val call = try {
            client.newCall(request)
        } catch (e: RelayException) {
            // Licence/init failure. Not a network condition — it will fail the same
            // way on every launch — so report it through your own error path.
            listener.onFailure("Relay unavailable: ${e.message}")
            return
        }
        call.enqueue(/* your existing callback */)
    }
}
```

If you would rather fail fast, call `Relay.getInstance` once at application start
and let it throw there; a licence failure is a configuration error, not something a
retry fixes.

**Timeouts.** The relay performs its own network I/O through an internal
`OkHttpClient`, so the `connectTimeout`/`readTimeout`/`writeTimeout` you set on
*your* client do not apply to relay traffic. `callTimeout` does, because an
application interceptor runs inside the call — and the first call includes the key
exchange and pair-pool setup. If you set `callTimeout`, make sure it leaves room for
that first request.

**The one-shot body applies here too.** Both modes build the response the same way,
so `response.body?.string()` consumes it in interceptor mode exactly as it does in
wrapper mode.

**Confirming it is actually relayed.** The library logs to logcat under the tag
`MTE`. A working integration prints the version line and then the pair count:

```
MTE: Using Relay Version 5.2.3 and Mte Version 4.2.1
MTE: Created 8 pairs for https://your-relay-server.com; total pairs=8
```

If you do not see those, the request is not going through the relay.

**Not covered by interceptor mode:** file upload, file download and SSE. The frame
protocol needs an exact `Content-Length` before it writes an encrypted body, which
OkHttp's interceptor `RequestBody` model cannot promise. Use `startUploadFile`,
`startDownloadFile` and the SSE API for those — they share this same relay instance.

#### Wrapper mode (`relay.send`)

Call `relay.send(...)` and handle the result in a `RelayOkHttpRequestListener`.

`send` returns immediately and never throws: it does its work on a background thread,
and every failure — including an unusable URL — is delivered to `onFailure`. You do
not need to wrap the call in a `try`. (`Relay.getInstance` is the one that throws, at
setup time, if the MTE licence check fails.)

Your listener has three outcomes, mirroring what OkHttp itself reports:

- `onResponse(Response)` — the server replied 2xx.
- `onError(Response)` — the server replied, but not 2xx. Status and body are the
  origin's.
- `onFailure(Request, Throwable)` — **no HTTP response was obtained**: the device was
  offline, the host would not resolve, the connection timed out, or pairing failed.

That third one matters. A request that never reached the network is not a server
error, so do not treat it as one — reporting it as a 5xx tells users your backend is
down when they are in airplane mode.

All three are delivered on a background thread. Post to the main thread before
touching views.

**Reading the body.** The relay hands you a `Response` whose body is already decrypted
and buffered in memory, so there is no socket to release — you do not need
`response.use { }` or `close()`.

It is still a one-shot read. `response.body?.string()` **consumes** the body, and a
second call returns an empty string rather than the payload again. Read it once and
keep the result:

```kotlin
val bodyText = response.body?.string().orEmpty()   // read once
parse(bodyText)                                     // reuse the String, not the body
```

This is the single most common mistake against this API: logging the body and then
parsing it produces an empty parse, and the request looks like it silently returned
nothing.

`headersToEncrypt` lists the header names whose values should be encrypted in transit.
Name only headers your request actually sets — listing one you did not set has no
effect. If you have no headers to encrypt, pass an empty array; `null` behaves the
same way.
Headers you do not list are still sent — just unencrypted. Set `Content-Type` the way
you normally would; the relay forwards your request headers as they are and manages the
encrypted framing itself, so you do not need to remove or adjust anything.

#### GET request (Kotlin)

```kotlin
import com.eclypses.relay.RelayOkHttpRequestListener
import okhttp3.Request
import okhttp3.Response

val request = Request.Builder()
    .url("https://your-relay-server.com/api/users/123")
    .header("Accept", "application/json")
    .header("Authorization", "Bearer abc123")
    .get()
    .build()

val headersToEncrypt = arrayOf("Authorization")

relay.send(request, headersToEncrypt, /* pathnamePrefix */ null,
           /* preventStreaming */ false,
           object : RelayOkHttpRequestListener {
    override fun onResponse(response: Response) {
        val status = response.code
        val bodyText = response.body?.string()   // consumes the body — read it once
        // Called on a background thread — post UI work to the main thread.
    }
    override fun onError(response: Response) {
        // The server replied, but not 2xx. Status and body are the origin's.
    }
    override fun onFailure(request: okhttp3.Request, cause: Throwable) {
        // No HTTP response at all — offline, DNS, timeout, or pairing failed.
        // Not a server error; do not report it as one.
    }
})
```

#### POST request with a JSON body (Kotlin)

```kotlin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

val json = """{"name":"John Doe","email":"john@example.com"}"""
val body = json.toRequestBody("application/json".toMediaType())

val request = Request.Builder()
    .url("https://your-relay-server.com/users")
    .header("Authorization", "Bearer abc123")
    .post(body)
    .build()

relay.send(request, arrayOf("Authorization"), null, false, listener)
```

**Parameters of `send`:**

- **`req`** (`okhttp3.Request`) — configured with the relay server URL, method, headers, body
- **`headersToEncrypt`** (`String[]`) — header names to encrypt; pass `null`/empty for none
- **`pathnamePrefix`** (`String`, nullable) — optional relay routing prefix, usually `null`
- **`preventStreaming`** (`boolean`) — pass **`false`** unless your origin needs
  otherwise. It is a directive to the relay server about how it talks to *your origin*:
  `true` asks it to disable its normal HTTP streaming of the upstream request and hand
  it off for non-standard processing. It does not change how you receive the response —
  this client always streams the response and hands you a complete body
- **`listener`** (`RelayOkHttpRequestListener`) — `onResponse(Response)` / `onError(Response)` /
  `onFailure(Request, Throwable)`. All three must be implemented

### File Upload (Streaming)

For files — especially large ones — use the streaming upload API. Your body bytes are
sourced from a `RelayStreamCallback` and encrypted chunk-by-chunk; the file is never
loaded entirely into memory.

**Requirements:**
- Provide a `RelayStreamCallback` that writes the full body to the given
  `PipedOutputStream` and then **closes it**
- The request headers **must** include a `Content-Length` equal to the exact total
  unencrypted body size (the upload is rejected without it)

```kotlin
import com.eclypses.relay.*
import java.io.PipedOutputStream

val headers = mapOf(
    "Content-Type"   to "multipart/form-data; boundary=$boundary",
    "Content-Length" to contentLength.toString()   // exact total unencrypted size
)

val props = RelayFileRequestProperties(
    "https://your-relay-server.com",   // serverPath — the relay origin
    "/upload",                          // route — path on your backend
    null,                               // pathnamePrefix
    headers,                            // origHeaders
    arrayOf("Authorization"),           // headersToEncrypt
    object : RelayStreamCallback {
        override fun getRequestBodyStream(outputStream: PipedOutputStream) {
            // Write the complete request body, then close the stream.
            outputStream.write(multipartPrefixBytes)
            file.inputStream().use { it.copyTo(outputStream) }
            outputStream.write(multipartPostfixBytes)
            outputStream.close()
        }
    }
)

val operationId = relay.startUploadFile(props,
    object : RelayStreamResponseListener {
        override fun relayStreamResponse(
            statusCode: Int, success: Boolean,
            responseStr: String?, errorMessage: String?,
            responseHeaders: Map<String, List<String>>?
        ) {
            if (success) { /* upload complete */ } else { /* errorMessage */ }
        }
    },
    object : RelayStreamCompletionCallback {
        override fun onProgressUpdate(bytesCompleted: Int, totalBytes: Int) {
            val pct = if (totalBytes > 0) bytesCompleted * 100 / totalBytes else 0
        }
    }
)
// operationId can be passed to relay.cancelStreamingOperation(operationId) to cancel.
```

`uploadFile(...)` is the same call without a return value; `startUploadFile(...)`
returns the `operationId` string for cancellation.

### File Download (Streaming)

Decrypted bytes are written to a local file incrementally. Use the download
constructor of `RelayFileRequestProperties` (the one that takes a `downloadPath`).

```kotlin
val destination = java.io.File(context.filesDir, "video.mp4")
destination.parentFile?.mkdirs()   // ensure the parent directory exists

val props = RelayFileRequestProperties(
    "https://your-relay-server.com",     // serverPath — the relay origin
    "/api/files/video.mp4",               // route
    destination.absolutePath,             // downloadPath — where to write the file
    null,                                 // pathnamePrefix
    emptyMap(),                           // origHeaders
    emptyArray()                          // headersToEncrypt (only encrypt headers you set)
)

val operationId = relay.startDownloadFile(props, /* pathnamePrefix */ null,
    object : RelayStreamResponseListener {
        override fun relayStreamResponse(
            statusCode: Int, success: Boolean,
            responseStr: String?, errorMessage: String?,
            responseHeaders: Map<String, List<String>>?
        ) {
            if (success) { /* file written to destination */ }
        }
    }
)
```

> Don't list headers you didn't set in `headersToEncrypt` — e.g. encrypting
> `Content-Type` on a bodyless GET download will fail. Pass an empty array (or only
> headers you actually set, like `Authorization`).

### Server-Sent Events (SSE)

Open a relay-backed SSE stream and receive **decrypted bytes** through a
`RelaySseListener`. `startEventStream` returns a `streamId` you use to cancel. Any
HTTP method is permitted (a stream may carry a body, e.g. an AI-prompt POST).

```kotlin
import com.eclypses.relay.RelaySseListener

val request = Request.Builder()
    .url("https://your-relay-server.com/api/events")
    .get()
    .build()

val streamId = relay.startEventStream(request, arrayOf("Authorization"), null,
    object : RelaySseListener {
        override fun onOpened(streamId: String, statusCode: Int,
                              responseHeaders: Map<String, List<String>>) { }
        override fun onData(streamId: String, data: ByteArray) {
            val chunk = String(data, Charsets.UTF_8)
            // See the note below — this is raw decrypted bytes, not parsed events.
        }
        override fun onCompleted(streamId: String) { }
        override fun onCancelled(streamId: String) { }
        override fun onError(streamId: String, statusCode: Int,
                             errorMessage: String?,
                             responseHeaders: Map<String, List<String>>?) { }
    })

// Later: relay.cancelEventStream(streamId)
```

> ⚠️ **`onData` delivers raw decrypted bytes — the transport does not parse
> `text/event-stream`.** Reassemble and parse SSE fields (`data:`, `event:`, `id:`)
> yourself, exactly as you would for a plain HTTP SSE response. An opt-in
> `RelaySseParser` helper is included if you want a ready-made line parser.

### Re-Pairing with the Server

Pairing is automatic, but you can force a fresh MTE re-pair — e.g. after a server
restart or persistent decryption failures.

```kotlin
import com.eclypses.relay.RelayResponseListener

relay.rePairWithRelayServer("https://your-relay-server.com", null,
    object : RelayResponseListener {
        override fun onCompletion(success: Boolean, message: String) { }
    })
```

### Adjusting Relay Settings

Per-host behavior is controlled by `RelayClientSettings`. Calling `adjustRelaySettings`
triggers an automatic re-pair **only if a value actually changed**.

| Setting | Default | Meaning |
|---------|---------|---------|
| `minPairs` | 5 | Repair the pool up to `basePairs` when the available count drops below this |
| `basePairs` | 8 | Target pool size after a repair |
| `maxPairs` | 15 | Hard upper limit on concurrent pairs |
| `keepAliveIntervalSeconds` | 300 | Relay keep-alive ping cadence (seconds) |
| `acquisitionWaitTime` | 1.0 | Seconds to wait for a free pair when the pool is saturated |

> Note: unlike the iOS client, the Android `RelayClientSettings` does **not** include a
> `streamChunkSize` field.

```kotlin
import com.eclypses.relay.RelayClientSettings

// Constructor order: minPairs, basePairs, maxPairs, keepAliveIntervalSeconds, acquisitionWaitTime
val settings = RelayClientSettings(5, 8, 15, 300, 1.0)

relay.adjustRelaySettings("https://your-relay-server.com", null, settings,
    object : RelayResponseListener {
        override fun onCompletion(success: Boolean, message: String) { }
    })

// Read the current settings for a host:
val current = relay.getRelaySettings("https://your-relay-server.com")
println("maxPairs = ${current.maxPairs}")   // getters: getMinPairs(), getMaxPairs(), ...
```

`RelayClientSettings()` (no-arg) yields the defaults, and `settings.buildUpon()`
returns a `Builder` for changing individual fields.

### Logging

File logging is off by default. It writes to the app's files directory.

```kotlin
Relay.enableFileLogging(true)      // typically once at app start
val log: String? = Relay.readLogFile()
Relay.clearLogFile()
```

Log lines are level-tagged (INFO / WARNING / ERROR, plus TRACE diagnostics). A healthy
startup logs `Using Relay Version 5.2.3 and Mte Version 4.2.1`; a bad license logs
`MTE License Check Failed`.

### OkHttp Interceptor integration

See [Interceptor mode](#interceptor-mode) under Making Secure HTTP Requests. It is
the lowest-friction way to adopt the relay in an app that already uses OkHttp, and
is documented there alongside the wrapper-mode alternative.

## Listeners / Callbacks Reference

| Interface | Used by | Key methods |
|-----------|---------|-------------|
| `RelayOkHttpRequestListener` | `send` | `onResponse(Response)`, `onError(Response)`, `onFailure(Request, Throwable)` |
| `RelayStreamCallback` | upload | `getRequestBodyStream(PipedOutputStream)` — write body, then close |
| `RelayStreamResponseListener` | upload / download | `relayStreamResponse(statusCode, success, responseStr, errorMessage, headers)` |
| `RelayStreamCompletionCallback` | upload / download | `onProgressUpdate(bytesCompleted, totalBytes)` |
| `RelaySseListener` | SSE | `onOpened` / `onData` / `onCompleted` / `onCancelled` / `onError` |
| `RelayResponseListener` | re-pair / adjust settings | `onCompletion(success, message)` |

All callbacks fire on **background threads** — marshal UI work to the main thread.

## Cancellation

- `relay.cancelStreamingOperation(operationId)` — cancel an in-progress upload/download
  (the id returned by `startUploadFile` / `startDownloadFile`)
- `relay.cancelEventStream(streamId)` — cancel an SSE stream (the id returned by
  `startEventStream`)

## Troubleshooting

**"MTE License Check Failed" (`RelayException` from `getInstance`)**
Verify your MTE license is valid/active and matches your application id. Contact
[info@eclypses.com](mailto:info@eclypses.com) to confirm licensing.

**Pairing fails repeatedly**
Confirm the relay server is running and reachable; the server URL uses `https` with no
trailing slash; and there's no firewall/VPN blocking it. Force a re-pair with
`rePairWithRelayServer`.

**Decryption / token-mismatch failures after a server restart**
Call `rePairWithRelayServer` to re-establish MTE state, then retry the request.

**Upload is rejected or fails**
Ensure the request headers include a `Content-Length` equal to the exact total
unencrypted body size, and that your `RelayStreamCallback` writes the whole body and
**closes** the `PipedOutputStream`.

**Download produces a corrupt/empty file**
Ensure the parent directory of `downloadPath` exists, check available disk space, and
confirm `success == true` / `statusCode` is 2xx in `relayStreamResponse`.

**High memory use during file transfers**
Always use the streaming upload/download APIs for multi-MB transfers — never send large
bodies through `send`.

**UI doesn't update from a callback**
Callbacks run on background threads. Post UI updates to the main thread.

## API Reference

**`Relay` (singleton — obtain with `Relay.getInstance(Context)`)**

| Method | Purpose |
|--------|---------|
| `send(Request, String[], String, boolean, RelayOkHttpRequestListener)` | Encrypted HTTP request |
| `uploadFile(RelayFileRequestProperties, RelayStreamResponseListener, RelayStreamCompletionCallback)` | Streaming upload |
| `startUploadFile(...)` | Streaming upload; returns `operationId` |
| `downloadFile(RelayFileRequestProperties, String, RelayStreamResponseListener)` | Streaming download |
| `startDownloadFile(...)` | Streaming download; returns `operationId` |
| `cancelStreamingOperation(String)` | Cancel an upload/download |
| `startEventStream(Request, String[], String, RelaySseListener)` | Open an SSE stream; returns `streamId` |
| `cancelEventStream(String)` | Cancel an SSE stream |
| `getOkHttpInterceptor()` | OkHttp `Interceptor` for transparent integration |
| `rePairWithRelayServer(String, String, RelayResponseListener)` | Force a full re-pair |
| `getRelaySettings(String)` | Current `RelayClientSettings` for a host |
| `adjustRelaySettings(String, String, RelayClientSettings, RelayResponseListener)` | Update per-host settings (re-pairs if changed) |
| `getRelayGlobalSettings()` | Version + license info (`RelayGlobalSettings`) |
| `getKeepAliveDiagnostics(String)` | Keep-alive diagnostic string for a host |
| `enableFileLogging(Boolean)` / `readLogFile()` / `clearLogFile()` | Static logging controls |

**Exceptions**
- `RelayException` — invalid license, unresolvable server URL, and other relay errors
- `MteException`, `KyberException` — surfaced from the underlying MTE core

## Support

**Email:** [info@eclypses.com](mailto:info@eclypses.com)  ·  **Web:** [www.eclypses.com](https://www.eclypses.com)  ·  **Developer Portal:** [developers.eclypses.com/dashboard](https://developers.eclypses.com/dashboard)

## Source Layout (for contributors)

Everything documented above is public API. A few Java types carry the `public`
modifier without being part of it — Java has no `internal` — so treat this listing,
not the modifier, as the contract.

```
relay/src/main/
├── java/com/eclypses/relay/            # Public API
│   ├── Relay.java                 # Main entry point (singleton)
│   ├── RelayFileRequestProperties.java
│   ├── RelayClientSettings.java   # Per-host settings (+ Builder)
│   ├── RelayGlobalSettings.java
│   ├── RelayOkHttpRequestListener.java
│   ├── RelayStreamCallback.java / RelayStreamResponseListener.java / RelayStreamCompletionCallback.java
│   ├── RelaySseListener.java
│   ├── RelayResponseListener.java
│   ├── RelayException.java / MteException.java / KyberException.java
│   │
│   └── (public modifier, NOT public API — do not depend on these)
│       ├── Pair.java / DecodeResult.java  # reached by reflection; kept by consumer-rules.pro
│       ├── LogHelper.java                 # internal logging
│       └── RelaySettings.java             # internal state; read version via getRelayGlobalSettings()
├── kotlin/com/eclypses/relay/interceptor/  # Public API
│   ├── RelayMteInterceptor.kt     # What getOkHttpInterceptor() returns
│   └── RelayRequestOptions.kt     # Per-request options, attached as a request tag
└── kotlin/com/eclypses/relay/          # Implementation detail (not public API)
    ├── session/                   # Pairing, session + lifecycle management
    ├── protocol/                  # Relay protocol engine
    ├── streaming/                 # Streaming executor, SSE parser/adapter
    ├── transport/                 # OkHttp transport
    └── persistence/               # Relay state store
```

---
**All trademarks of Eclypses Inc.** may not be used without Eclypses Inc.'s prior written consent.
