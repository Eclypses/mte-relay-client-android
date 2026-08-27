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

📚 **[Complete README with Java Examples](https://github.com/Eclypses/mte-relay-client-android)** - Comprehensive documentation with detailed Java and Kotlin examples, troubleshooting, and API reference

💡 This quick-start guide provides Kotlin examples for experienced Android developers. For Java examples, streaming/upload/SSE APIs, and detailed troubleshooting, see the GitHub README.

## Prerequisites

- **Android 9.0 (API 28) or later**
- **Kotlin 1.9+ / Java 11+** - the library is compiled against Java 11 and is fully Java-compatible
- **OkHttp 4.12.0 or later** - You build standard `okhttp3.Request` objects
- **Access to a licensed MTE Relay server instance** - Server URL and licensing credentials required

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
    implementation("com.eclypses:mte-relay-client-android:5.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

**Groovy DSL (`build.gradle`):**

```groovy
dependencies {
    implementation 'com.eclypses:mte-relay-client-android:5.2.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

> **R8 / ProGuard:** the library ships its own `consumer-rules.pro`, so no extra keep
> rules are required in your app.

## Quick Start

### 1. Add Internet Permission

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
```

### 2. Get the Relay Instance

`Relay` is a **singleton**. `Relay.getInstance(context)` validates your MTE license on
first creation (throwing `RelayException` on failure) and initializes logging. Pairing
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

Build an ordinary `okhttp3.Request` pointed at your **MTE Relay server URL** (not your
backend URL — the relay decrypts and forwards). Call `relay.send(...)` and handle the
result in a `RelayOkHttpRequestListener`.

**Building the URL:** keep the path your backend expects and change only the host.
`https://api.yourcompany.com/api/login` becomes
`https://your-relay-server.com/api/login`. The relay forwards the path unchanged and
works out its pairing host from the request URL, so the server URL is never passed to
`Relay.getInstance`.

Your listener has three outcomes: `onResponse` (2xx), `onError` (the server replied
non-2xx), and `onFailure(Request, Throwable)` (no HTTP response at all — offline, DNS,
timeout, pairing). Do not report the third as a server error. All arrive on a
background thread.

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

`Relay.getInstance` needs a `Context`; an application context is fine. The first
`send` performs the key exchange and builds a pair pool before the request leaves, so
it is noticeably slower than later ones.

`headersToEncrypt` lists the header names whose values should be encrypted in transit.
`Content-Type` / `Content-Length` are handled specially; headers not listed are sent
unencrypted.

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
        val bodyText = response.body?.string()
        // Called on a background thread — post UI work to the main thread.
    }
    override fun onError(response: Response) {
        // Non-success / failure is also delivered as a Response you can inspect.
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
- **`headersToEncrypt`** (`String[]`) — header names to encrypt; `null`/empty for none
- **`pathnamePrefix`** (`String`, nullable) — optional relay routing prefix, usually `null`
- **`preventStreaming`** (`boolean`) — pass **`false`** for a standard request/response call;
  set `true` only to force the relay to fully buffer a response it would otherwise stream
- **`listener`** (`RelayOkHttpRequestListener`) — `onResponse(Response)` / `onError(Response)`

### File Upload (Streaming) & Server-Sent Events

- **Upload:** `RelayFileRequestProperties` + a `RelayStreamCallback` write the body to a
  `PipedOutputStream` and close it; the file is encrypted chunk-by-chunk, never fully
  buffered. The request headers **must** include a `Content-Length` equal to the exact
  total unencrypted body size.
- **SSE:** subscribe to a `text/event-stream` endpoint with a `RelaySseListener`.

See the GitHub README for full upload/SSE examples (Kotlin and Java).

## Error Handling

`Relay.getInstance` throws `RelayException` (a `RuntimeException`) on license/init failure.
Per-request failures are delivered to your listener's `onError(Response)` as an
`okhttp3.Response` you can inspect (status code, body):

```kotlin
override fun onError(response: Response) {
    Log.e(TAG, "Request failed: ${response.code} ${response.message}")
    val errorBody = response.body?.string()
    // Inspect / surface the failure; re-issue the request if appropriate.
}
```

## API Reference

### Relay

```kotlin
companion object {
    fun getInstance(context: Context): Relay   // singleton; validates MTE license
}

fun send(
    req: Request,
    headersToEncrypt: Array<String>?,
    pathnamePrefix: String?,
    preventStreaming: Boolean,
    listener: RelayOkHttpRequestListener
)
```

- **`RelayOkHttpRequestListener`** — `onResponse(Response)` / `onError(Response)`
- **`RelayFileRequestProperties`** + **`RelayStreamCallback`** — streaming uploads
- **`RelaySseListener`** — server-sent events
- **`RelayException`** — thrown on license/initialization failure

## Contact Eclypses

**Email:** [info@eclypses.com](mailto:info@eclypses.com)  
**Web:** [www.eclypses.com](https://www.eclypses.com)

---

**All trademarks of Eclypses Inc.** may not be used without Eclypses Inc.'s prior written consent.
