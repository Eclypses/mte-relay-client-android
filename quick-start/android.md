---
title: MTE Relay Client for Android
sidebar_label: Kotlin
description: An MTE Relay client for Android applications.
---

![Latest Release](https://img.shields.io/github/v/release/Eclypses/mte-relay-client-android?style=flat-square)

## Introduction

This Android library provides the Eclypses MTE Relay Client for Android. It enables secure, encrypted HTTP(S) communication between your Android app and your backend via an MTE Relay server. Build an ordinary `okhttp3.Request`, send it through the relay, and the library transparently encrypts it (with Kyber-512 post-quantum key exchange) and relays it to the server, which decrypts and forwards it to your real backend. [More Info](https://eclypses.com/mte-technology/amazon-web-services-aws/)

**Purpose of the Relay Client:**

- Securely relay ordinary HTTP requests to your backend
- Protect sensitive headers and bodies with MTE encryption
- Stream large files efficiently without loading them into memory

## Documentation

📚 **[Complete README](https://github.com/Eclypses/mte-relay-client-android)** - every API, file transfer and SSE, troubleshooting, and the API reference

💡 This quick-start guide provides Kotlin examples for experienced Android developers. For Java examples, streaming/upload/SSE APIs, and detailed troubleshooting, see the GitHub README.

## Prerequisites

- **Android 9.0 (API 28) or later**
- **Kotlin 1.9+ / Java 11+** - the library is compiled against Java 11 and is fully Java-compatible
- **OkHttp 4.12.0 or later** - You build standard `okhttp3.Request` objects
- **Access to a licensed MTE Relay server instance** - you need its URL. The client-side licence is built into the library

## How the Relay Works

```
[Your Android App] ←→ Encrypted request ←→ [MTE Relay Server] ←→ [Your Backend Service]
     ↑                                              ↑
  Encrypts here                          Decrypts & forwards here
```

1. **Pairing:** Automatic MTE pairing handshake (Kyber-512) on the first request — no separate connect step
2. **Encode:** The request is encrypted before it leaves the device
3. **Relay:** The encrypted payload travels to the MTE Relay server
4. **Decode & forward:** The server decrypts and forwards to your backend
5. **Response:** The response returns along the same path, decrypted on-device

## Installation

### Gradle (Recommended)

The library is published to Maven Central. The artifact id keeps the descriptive
`mte-relay-client-android` name; the Kotlin/Java package is `com.eclypses.relay`.

**Kotlin DSL (`build.gradle.kts`):**

```kotlin
dependencies {
    implementation("com.eclypses:mte-relay-client-android:5.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

**Groovy DSL (`build.gradle`):**

```groovy
dependencies {
    implementation 'com.eclypses:mte-relay-client-android:5.3.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

> **R8 / ProGuard:** the library ships its own `consumer-rules.pro`, so no extra keep
> rules are required in your app. It keeps the MTE core, whose native code reads its
> fields by name; without that, a minified build aborts on the first request with
> `JNI FatalError called: MteBase.init() failed to get myEntropyInput`.

## Quick Start

### 1. Add Internet Permission

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
```

### 2. Get the Relay Instance

`Relay` is a **singleton**. `Relay.getInstance(context)` validates the MTE licence built
into the library (there is none of yours to supply) on first creation (throwing `RelayException` on failure) and initializes logging. Pairing
with the server happens **automatically on the first request**.

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

> ⚠️ **Threading:** the request methods do their network work on background threads and
> deliver results through listener callbacks that also fire on a background thread.
> Marshal UI updates back to the main thread (e.g. `Activity.runOnUiThread { ... }`).

## Usage

### Making Secure HTTP Requests

**Building the URL:** keep the path your backend expects and change only the host.
`https://api.yourcompany.com/api/login` becomes
`https://your-relay-server.com/api/login`. The relay forwards the path unchanged and
works out its pairing host from the request URL, so the server URL is never passed to
`Relay.getInstance`.

**Reading the body — applies to both modes below.** The relay hands you a `Response`
whose body is already decrypted and buffered in memory, so there is no socket to
release; you do not need `response.use { }` or `close()`.

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

`Relay.getInstance` needs a `Context`; an application context is fine. If that means
adding a `Context` parameter to a networking class an Activity holds as a field, make the
field lazy: `applicationContext` is null while an Activity's fields are initialized, so
`private val client = ApiClient(applicationContext)` crashes the Activity at launch with a
`NullPointerException` that never mentions the relay. Use
`private val client by lazy { ApiClient(applicationContext) }` instead.

The first request performs the key exchange and builds a pair pool before it leaves, so
it is noticeably slower than later ones.

### Two ways to send a request

**Interceptor mode — start here if you already use OkHttp.** Register the relay's
interceptor on your own client and change nothing else: same `enqueue`, same
callbacks, same response handling, and transport failures still arrive at your
existing `Callback.onFailure`.

```kotlin
import android.content.Context
import com.eclypses.relay.Relay
import okhttp3.OkHttpClient

class ApiClient(private val context: Context) {
    // getInstance throws on licence failure, so build the client lazily rather than in
    // a property initializer — otherwise the failure crashes your class at construction.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(Relay.getInstance(context).getOkHttpInterceptor())
            .build()
    }
}
```

Catch `RelayException` where you first use the client, and report it through your own
error path — a licence failure is a configuration error, not a network condition.

Your own `connectTimeout`/`readTimeout` do not apply to relay traffic (the relay uses
its own internal client), but `callTimeout` does, and the first call includes pairing.
Leave room for it if you set one.

Per-request options ride an optional tag; omit it for defaults:

```kotlin
import com.eclypses.relay.interceptor.RelayRequestOptions
import okhttp3.Request

val request = Request.Builder()
    .url("https://your-relay-server.com/api/users/123")
    .tag(RelayRequestOptions::class.java,
         // Expose only what a hop must read; Authorization stays encrypted.
         RelayRequestOptions(unencryptedHeaders = arrayOf("traceparent")))
    .get()
    .build()
```

Interceptor mode does not cover file upload, file download or SSE — use the wrapper
API below for those.

**Wrapper mode** — call `relay.send(...)` and handle the result in a
`RelayOkHttpRequestListener`. Required for upload, download and SSE; for plain HTTP
it does the same job as the interceptor but asks you to rewrite each call site.

Its listener has three outcomes: `onResponse` (2xx), `onError` (the server replied
non-2xx), and `onFailure(Request, Throwable)` (no HTTP response at all — offline, DNS,
timeout, pairing). Do not report the third as a server error. All arrive on a
background thread. In interceptor mode you do not implement this interface at all —
your existing OkHttp `Callback` carries the same distinction.

`unencryptedHeaders` lists the header names to leave **unencrypted** on the hop to the
relay; every other header is encrypted, so `null` or an empty array encrypts everything.
Headers you do not list still reach your origin — they travel inside the encrypted
frame rather than on the hop, so nothing is dropped by leaving them off.

Listing one is not free, and not a guarantee of delivery. The name travels in the clear
to the relay, and the relay forwards it upstream only if its operator allowed that name —
so a listed header can lose its protection **and** still never arrive. List a header only
when something between your app and the relay has to read it. Set `Content-Type` the way
you normally would: it is always encrypted, and the relay applies it to the origin request
while managing the framing itself, so you do not need to remove or adjust anything.

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

// null encrypts every header — the right default.
relay.send(request, /* unencryptedHeaders */ null, /* pathnamePrefix */ null,
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

relay.send(request, null, null, listener)   // null: every header encrypted, Authorization included
```

**Parameters of `send`:**

- **`request`** (`okhttp3.Request`) — configured with the relay server URL, method, headers, body
- **`unencryptedHeaders`** (`String[]`) — header names to leave unencrypted on the relay hop; `null`/empty encrypts everything. `arrayOf("*")` exposes all eligible headers. `Content-Type`, `Content-Length`, `Host`, `X-MTE-Relay-Route` and `X-MTE-Relay-Client` are reserved and always encrypted — listing one throws. An exposed header is readable by anything between your app and the relay, and reaches your origin only if the relay operator allowed that name
- **`pathnamePrefix`** (`String`, nullable) — optional relay routing prefix, usually `null`
- **`listener`** (`RelayOkHttpRequestListener`) — `onResponse(Response)` / `onError(Response)` /
  `onFailure(Request, Throwable)`. All three must be implemented

### File Upload (Streaming) & Server-Sent Events

- **Upload:** `RelayFileRequestProperties` + a `RelayStreamCallback` write the body to a
  `PipedOutputStream` and close it; the file is encrypted a block at a time and never fully
  buffered. The request headers **must** include a `Content-Length` equal to the exact
  total unencrypted body size — the size check below reads it.
- **SSE:** subscribe to a `text/event-stream` endpoint with a `RelaySseListener`.

See the GitHub README for full upload/SSE examples (Kotlin and Java).

## Error Handling

`Relay.getInstance` throws `RelayException` (a `RuntimeException`) on license/init failure.
`send` returns immediately and reports everything through the listener, with one
exception: an `unencryptedHeaders` list it cannot honour (a reserved name, an invalid name,
`"*"` beside other entries) throws `IllegalArgumentException` from `send` itself, before
any pairing. Pass `null` and there is nothing to catch.

Per-request outcomes split in two, and the split matters:

```kotlin
// The server replied, but not 2xx. A real origin response you can inspect.
override fun onError(response: Response) {
    Log.e(TAG, "Server returned ${response.code} ${response.message}")
    val errorBody = response.body?.string()
}

// No HTTP response at all — offline, DNS, timeout, or pairing failed.
override fun onFailure(request: okhttp3.Request, cause: Throwable) {
    Log.e(TAG, "Request never completed: ${cause.message}")
    // Do not surface this as a server error; nothing reached the server.
}
```

In interceptor mode you get the same distinction through OkHttp's own `Callback`:
non-2xx arrives in `onResponse` with the status set, and transport failures arrive in
`onFailure(Call, IOException)`. A failure of the relay's own arrives there too, as a
`RelayCallException` whose `cause` is the specific relay exception.

### Size limits

The relay advertises a maximum request body and **applies it to streamed uploads as well as
buffered ones**. Earlier releases exempted streams; there is no such exemption now.

```kotlin
import com.eclypses.relay.session.RelayRequestTooLargeException

override fun onFailure(request: okhttp3.Request, cause: Throwable) {
    if (cause is RelayRequestTooLargeException) {
        // Nothing was sent. Both numbers come from the relay's own advertisement.
        Log.w(TAG, "${cause.sizeBytes} bytes exceeds the relay's ${cause.limitBytes} byte cap")
    }
}
```

`measured` distinguishes the two checks, compared with the constants on the exception
(`RelayRequestTooLargeException.MEASURED_BODY` / `.MEASURED_FRAME`): `MEASURED_BODY` is your body (or a streamed upload's
declared `Content-Length`), `MEASURED_FRAME` the encrypted frame built from it — which is
larger, so a body just under the cap can still be refused.

The client refuses before sending because the alternative is worse: past the cap the relay
stops reading while the upload keeps writing, so the request hangs until a timeout rather than
failing. It does **not** make large uploads work — that needs `MAX_REQUEST_BODY_MIB` raised on
the relay, which is a server-side decision.

Read a relay's own limit from `maxRequestBodyBytes` in its discovery document:

```
GET https://your-relay-server.com/api/mte-relay?frameVersions=2
```

`frameVersions` is mandatory; omitting it identifies you as a pre-version-2 SDK and the relay
answers **478**.

## API Reference

### Relay

```kotlin
companion object {
    fun getInstance(context: Context): Relay   // singleton; validates MTE license
    // Cookies are on by default. Supply a client with your own cookieJar to use your own
    // store; pass cookiesEnabled = false to send none. A client built with
    // CookieJar.NO_COOKIES does not disable them — that is also OkHttp's default, so it
    // reads as unconfigured and gets the relay's jar. Use the flag.
    fun getInstance(context: Context, httpClient: OkHttpClient?): Relay
    fun getInstance(context: Context, httpClient: OkHttpClient?, cookiesEnabled: Boolean): Relay
}

fun send(
    request: Request,
    unencryptedHeaders: Array<String>?,
    pathnamePrefix: String?,
    listener: RelayOkHttpRequestListener
)
```

- **`RelayOkHttpRequestListener`** — `onResponse(Response)` / `onError(Response)` / `onFailure(Request, Throwable)`
- **`RelayFileRequestProperties`** + **`RelayStreamCallback`** — streaming uploads
- **`RelaySseListener`** — server-sent events
- **`RelayException`** — thrown on license/initialization failure

## Contact Eclypses

**Email:** [info@eclypses.com](mailto:info@eclypses.com)  
**Web:** [www.eclypses.com](https://www.eclypses.com)

---

**All trademarks of Eclypses Inc.** may not be used without Eclypses Inc.'s prior written consent.
