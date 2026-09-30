# MteRelay Mobile Client — Android

Android library providing the Eclypses **MteRelay** Mobile Client. It enables
secure, encrypted HTTP(S) communication between your Android app and your backend
via an MteRelay server. You must have licensed access to an MteRelay server
instance. [More info](https://eclypses.com/mte-technology/amazon-web-services-aws/)

**What MteRelay does for you:**
- Securely relays HTTP requests to your backend through an MteRelay server
- Protects sensitive headers and request/response data with **MTE** encryption
- Streams large files (upload and download) a block at a time, so a file is never
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
  backend. You need the server URL; the client-side licence is built in (see Setup).

**Native code.** The transitive MTE core ships JNI libraries — `libmtejni.so` for
`arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. Your build will run
`mergeDebugNativeLibs`/`stripDebugDebugSymbols` where it previously had nothing to do,
and your APK grows accordingly. If you ship ABI splits or an `abiFilters` list, make
sure the ABIs you keep are among those four.

**Built and tested against:** compileSdk 34, Android Gradle Plugin 8.13.1, Gradle 8.13,
Java 11 bytecode. Newer toolchains are expected to work — compileSdk 35 with Java 17 is
known to — and these are the versions the library itself is built with, not a ceiling.

## Installation (Gradle — Maven Central)

Maven Central is already in Gradle's default repositories.

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.eclypses:mte-relay-client-android:5.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // you build okhttp3.Request objects
}
```

```groovy
// build.gradle (Groovy DSL)
dependencies {
    implementation 'com.eclypses:mte-relay-client-android:5.3.0'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
}
```

This transitively pulls in `com.eclypses:mte-client-android` (the shared MTE core),
so you do not add it yourself.

> **R8 / ProGuard:** the library ships its own `consumer-rules.pro`, which your app's
> R8 pass applies automatically. No keep rules are required on your side. This matters
> for release builds only — Android minifies release by default, not debug.
>
> The shipped rules keep two things. The MTE core (`com.eclypses.mte`), whose native
> code reads its Java fields by name: without the rule a minified build aborts on the
> first request with `JNI FatalError called: MteBase.init() failed to get myEntropyInput`,
> which no exception handler can catch. And logback, which creates its appenders from
> XML: without it the library logs nothing in release, silently disabling
> `enableFileLogging()`/`readLogFile()` and the log lines this document tells you to look
> for. If you see that JNI error, something in your build is overriding consumer rules.

## Setup

### Step 1: Configure your MteRelay server

Before using this client, ensure your MteRelay server is running and configured to
receive encrypted requests from your app. The server acts as a secure intermediary:
it receives the encrypted request, decrypts it, and forwards it to your backend API.

### Step 2: Get the Relay instance

`Relay` is a **singleton**. `Relay.getInstance(context)` validates the MTE licence on
first creation (throwing `RelayException` if it fails) and initializes logging.
Pairing with the server happens **automatically on the first request** — there is no
separate connect/pair step.

**You do not supply a licence.** It is built into the library and there is no API,
manifest entry or config file for one — this step needs nothing from you. If
`getInstance` throws on licence validation, that is a packaging problem on our side,
not something you can configure around: contact
[info@eclypses.com](mailto:info@eclypses.com).

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
class usually does not have. Expect to pass one in. An application context is fine
and avoids leaking an Activity.

> ⚠️ **Adding that parameter changes the call site too, and getting it wrong
> crashes the app before any network call.** If an Activity holds your networking
> class as a field:
>
> ```kotlin
> private val client = ApiClient()              // was fine
> private val client = ApiClient(applicationContext)   // NullPointerException
> ```
>
> `applicationContext` is null while an Activity's fields are being initialized, so
> the app dies instantiating the Activity. The stack trace names
> `getApplicationContext()` and your Activity and says nothing about the relay, so it
> reads as though you broke your own code.
>
> Make it lazy, or move construction into `onCreate`:
>
> ```kotlin
> private val client by lazy { ApiClient(applicationContext) }
> ```

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
representative app the whole change was three imports, a `Context` parameter, the URL,
and swapping `OkHttpClient()` for a builder — nothing removed. Wrapper mode covers the
same HTTP calls but asks you to rewrite each call site, and it is the only mode for
file transfer and SSE.

#### Interceptor mode

Register the interceptor once on your own client and keep everything else:

```kotlin
import com.eclypses.relay.Relay
import okhttp3.*
import java.io.IOException

val client = OkHttpClient.Builder()
    .addInterceptor(Relay.getInstance(applicationContext).getOkHttpInterceptor())
    .build()

val request = Request.Builder()
    .url("https://your-relay-server.com/api/login")   // relay host, your usual path
    .post(body)
    .build()

// Everything below is ordinary OkHttp, unchanged by the relay.
client.newCall(request).enqueue(object : Callback {
    override fun onResponse(call: Call, response: Response) {
        val status = response.code                       // the origin's status
        val text = response.body?.string().orEmpty()     // consumes the body — read once
        // Called on a background thread; post UI work to the main thread.
    }

    override fun onFailure(call: Call, e: IOException) {
        // No HTTP response: offline, DNS, timeout, or pairing failed.
    }
})
```

Responses arrive as ordinary OkHttp responses and transport failures arrive at your
existing `onFailure`, so error handling you already wrote keeps working.

A failure of the relay's own — a refused pairing, an incompatible relay, a malformed
frame, a body over the relay's limit — arrives at that same `onFailure` as a
`RelayCallException` (`com.eclypses.relay.interceptor`), an `IOException` whose `cause`
is the specific exception listed under [API Reference](#api-reference). Inspect the
`cause` if you need to tell them apart:

```kotlin
import com.eclypses.relay.interceptor.RelayCallException
import com.eclypses.relay.session.RelayRequestTooLargeException

override fun onFailure(call: Call, e: IOException) {
    when (val cause = (e as? RelayCallException)?.cause) {
        is RelayRequestTooLargeException -> { /* nothing was sent; see Size limits */ }
        null -> { /* offline, DNS, timeout */ }
        else -> { /* the relay refused or failed: cause.message says why */ }
    }
}
```

> **Every call made through that client is relayed — including calls to other hosts.**
> Interceptor mode is aimed at apps that already use OkHttp, and such apps usually share
> one `OkHttpClient` across several hosts. Adding the interceptor to a shared client
> sends all of that traffic through your relay, which is almost never what you want and
> fails in ways that look unrelated. If your client talks to anything besides the origin
> behind the relay, **build a second `OkHttpClient` for relay traffic** and leave the
> original alone.

**Per-request options.** `unencryptedHeaders` and `pathnamePrefix` are the same options
wrapper mode takes as arguments. Attach them to a request with a typed tag, or omit the
tag for defaults — the default encrypts every header:

```kotlin
import com.eclypses.relay.interceptor.RelayRequestOptions

// Both parameters are optional:
//   RelayRequestOptions(
//       unencryptedHeaders: Array<String>? = null,   // null = encrypt everything
//       pathnamePrefix: String? = null,
//   )
val request = Request.Builder()
    .url("https://your-relay-server.com/api/users/123")
    .header("Authorization", "Bearer abc123")
    .header("traceparent", "00-trace-id-01")
    .tag(RelayRequestOptions::class.java,
         // Expose only what a hop between you and the relay must read. Authorization
         // is not listed, so it stays encrypted.
         RelayRequestOptions(unencryptedHeaders = arrayOf("traceparent")))
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
            //
            // What throws is the `by lazy` block above, which runs on FIRST ACCESS of
            // `client` and happens to be forced here. So the `try` has to enclose the
            // first access, wherever that is. Touch `client` anywhere earlier outside a
            // try and this catch silently never fires -- RelayException is a
            // RuntimeException, so nothing warns you.
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
MTE: Using Relay Version 5.3.0 and Mte Version 4.2.1
MTE: Created 8 pairs for https://your-relay-server.com; total pairs=8
```

If you do not see those, the request is not going through the relay.

**Not covered by interceptor mode:** file upload, file download and SSE. The frame
protocol needs an exact `Content-Length` before it writes an encrypted body, which
OkHttp's interceptor `RequestBody` model cannot promise. Use `startUploadFile`,
`startDownloadFile` and the SSE API for those — they share this same relay instance.

#### Wrapper mode (`relay.send`)

Call `relay.send(...)` and handle the result in a `RelayOkHttpRequestListener`.

`send` returns immediately and does its work on a background thread. Every failure —
including an unusable URL — is delivered to `onFailure`, with one exception: an
`unencryptedHeaders` list that cannot be honoured throws `IllegalArgumentException`
from `send` itself, before any pairing (see [What throws](#what-throws)). With `null`,
as in the examples below, there is nothing to catch. (`Relay.getInstance` also throws,
at setup time, if the MTE licence check fails.)

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
`response.use { }` or `close()`. An existing `use { }` or `close()` in code you are
migrating is a harmless no-op: leave it rather than editing it out.

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

`unencryptedHeaders` lists the header names to leave **unencrypted** on the hop to the
relay. Every other header is encrypted, so `null` or an empty array — the default —
encrypts everything. Set `Content-Type` the way you normally would; it is always
encrypted and the relay applies it to the origin request. See
[Header encryption](#header-encryption) for the reserved names and what throws.

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

// null encrypts every header, which is what you want unless something between your
// app and the relay has to read one of them.
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
- **`unencryptedHeaders`** (`String[]`) — header names to leave unencrypted on the relay hop; `null`/empty encrypts everything, which is the default
- **`pathnamePrefix`** (`String`, nullable) — optional relay routing prefix, usually `null`
- **`listener`** (`RelayOkHttpRequestListener`) — `onResponse(Response)` / `onError(Response)` /
  `onFailure(Request, Throwable)`. All three must be implemented

### Header encryption

**Every header you set is encrypted by default.** There is nothing to configure, and it
is the right choice for almost every request.

`unencryptedHeaders` names the exceptions — headers that must travel as ordinary HTTP
headers on the hop from your app to the relay, so infrastructure between the two can
read them:

```kotlin
relay.send(request, arrayOf("traceparent"), /* pathnamePrefix */ null, listener)
```

Typical reasons: distributed tracing (`traceparent`, `X-Request-Id`), an API gateway in
front of the relay that authenticates before forwarding, or a load balancer routing on a
tenant header. If the only reader is your origin, leave it encrypted — the relay
decrypts and forwards it either way.

**`arrayOf("*")` exposes every eligible header.** It is a debugging tool, not a
configuration. It does not disable encryption: the body, the request path and the
reserved headers below stay encrypted.

#### Reserved names

Always encrypted; listing one throws `IllegalArgumentException`:

| Name | Why |
|---|---|
| `Content-Type` | The relay hop carries `application/octet-stream` describing the frame. Yours describes a different message, so it travels encrypted and the relay applies it to the origin request. |
| `Content-Length` | The origin's body is rebuilt by the relay from the decrypted frame, so its length is that body's length, not yours. |
| `Host` | The relay replaces it for the origin request. A plain one would also aim the relay hop at the wrong vhost. |
| `X-MTE-Relay-Route` | The relay's sticky-session routing token. The SDK owns this header and sets it itself; the server routes on whatever value arrives. |
| `X-MTE-Relay-Client` | Marks a request as coming from a relay acting as a client. A relay that sees it skips the cookie merge for that request, so setting it would silently cost you cookie forwarding. |

#### What throws

`unencryptedHeaders` is a security instruction, so a list that cannot be honoured throws
before any pairing or network call rather than being quietly ignored:

- an empty or whitespace-only name
- `"*"` alongside any other entry
- a name containing characters RFC 7230 does not allow in a header name
- a reserved name from the table above

Naming a header the request does not actually set is **not** an error; it has no effect.

#### Two things to know before relying on this

**Listing a header does not guarantee the origin receives it.** The relay forwards plain
headers only for names its operator has allowed for your domain, and that list is empty
by default. If you do not run the relay yourself, coordinate with whoever does —
otherwise the header travels in the clear and still never arrives.

**An exposed header is readable and writable by anything on the path.** Plain headers
are protected only by TLS on the hop to the relay, so any TLS-terminating proxy or load
balancer in front of it can read or alter them. Encrypted headers cannot be touched, and
on a name collision the server takes the encrypted value. Never expose `Authorization`,
session identifiers, or anything else your origin treats as a secret.

### Cookies

Cookies are **on by default** and you do not have to do anything to get them. The relay
keeps one in-memory cookie store shared by every call it makes — auth, pair, keepalive
and every request frame — and it is discarded when the process ends.

Two things depend on it:

- **Load balancer affinity.** A relay running as several replicas is usually fronted by
  a load balancer that pins each client with a cookie (`AWSALB`,
  `ApplicationGatewayAffinity`). It is set on the auth response and has to come back on
  the pair request that follows. Without that, requests scatter across replicas and the
  relay serves pair state from Redis or fails with a 562.
- **Cookie-authenticated origins.** With `forward_browser_cookies` enabled on your
  domain (the default), the relay copies the cookie from the hop into the upstream
  request, and moves the origin's `Set-Cookie` onto the real HTTP response where the
  cookie store picks it up. A session cookie set by your origin therefore flows through
  the relay without your code touching it.

**To use your own cookie store**, hand `getInstance` an `OkHttpClient` with a `cookieJar`
set — a persistent one, or a jar shared with the rest of your app. Yours is used as-is:

```kotlin
val relay = Relay.getInstance(
    applicationContext,
    OkHttpClient.Builder().cookieJar(myCookieJar).build(),
)
```

**To turn cookies off entirely**, use the flag:

```kotlin
val relay = Relay.getInstance(applicationContext, null, /* cookiesEnabled = */ false)
```

> ⚠️ Turn them off only if you know your relay is single-replica and your origin does not
> authenticate with cookies. Do **not** express "off" by passing a client built with
> `CookieJar.NO_COOKIES` — that is also OkHttp's default, so it is indistinguishable from
> a client you simply never configured, and the relay will install its own jar over it.
> The `cookiesEnabled` flag is the switch.

A `Cookie` header you set yourself on a request is treated like any other caller header:
encrypted into the frame by default, and it takes precedence at the server over the one
on the hop. That is a deliberate choice you are making, not something the library does
for you — let the cookie store handle the hop.

### Sticky sessions

**Nothing to configure — this section is background.** A relay running as several
replicas keeps each client's pair state on the replica that created it. To keep a client
landing there, the relay mints an opaque routing token at auth and returns it in the
`X-MTE-Relay-Route` response header; the library stores it for the session and echoes it
on auth, pair and keepalive. Frame requests send the id of the pair they are using, so a
client's pairs spread across replicas rather than pinning all of them to one.

The token is not a secret and is never validated — authentication is still the signed
client id. It is a hint for a layer 7 load balancer, which hashes on the header to pick
an upstream.

`X-MTE-Relay-Route` is [reserved](#header-encryption): the library owns the header and
setting it yourself as a request header does not put your value on the hop. Against a
relay that does not mint a token, the library sends none and behaves exactly as before.

> **For whoever operates the relay:** the header only helps if the load balancer is
> configured to hash on it — for an nginx ingress, `upstream-hash-by:
> $http_x_mte_relay_route`. Without that the token is inert and clients may see 562s
> under sustained load.

### Pass-through routes

**Nothing to configure — this section is background.** Some routes are not worth
encrypting: a health or readiness probe an infrastructure component polls, for instance.
An operator can list those in the domain's `pass_through_routes`, and the library sends
them as ordinary HTTP requests — your method, headers, body and cookies, no MTE frame, and
no pair taken from the pool.

Two consequences worth knowing:

- **Pass-through removes the encryption, not the proxying.** The request still goes to the
  relay and the relay still forwards it to your origin, so your origin has to serve the
  route. Listing a path nothing implements gets you an unencrypted 404.
- **A pass-through route is not protected.** Its path, headers and body travel under TLS
  alone. That is the trade being made, so list only routes carrying nothing you would mind
  an intermediary reading.

The library discovers the list at session start, so adding or removing a route on the relay
takes effect the next time the client authenticates, with no app change.

### Size limits

The relay advertises a maximum request body, and **it applies to streamed uploads as well as
ordinary ones**. This is the behaviour most likely to surprise you when moving from an earlier
release.

Previously a streamed upload set a flag telling the relay to pipe the body upstream without
buffering, so the cap did not apply and a file of any size went through. There is no such flag
now: the relay measures the whole body and refuses anything past the cap.

The client checks first, so you get an error rather than a hang. It arrives as a
`RelayRequestTooLargeException` — an `IllegalArgumentException` — through
`onFailure(Request, Throwable)` for `send`, as the `cause` of a `RelayCallException` in
interceptor mode's `onFailure(Call, IOException)`, or as the `errorMessage` on your
`RelayStreamResponseListener` for a file upload:

```java
import com.eclypses.relay.session.RelayRequestTooLargeException;

@Override
public void onFailure(okhttp3.Request request, Throwable t) {
    if (t instanceof RelayRequestTooLargeException e) {
        // Nothing was sent. Both numbers come from the relay's own advertisement.
        Log.w(TAG, e.sizeBytes + " bytes exceeds this relay's " + e.limitBytes + " byte cap");
    }
}
```

`measured` (a `String`) says which number `sizeBytes` is; compare it with the constants on
the exception, `RelayRequestTooLargeException.MEASURED_BODY` and `.MEASURED_FRAME`.
`MEASURED_BODY` is your body, or for a streamed upload its declared `Content-Length`.
`MEASURED_FRAME` is the encrypted frame built from it,
which is larger — so a body sitting just under the limit can still be refused, and the
exception message says so rather than leaving you to work it out.

Why the client checks rather than letting the relay answer: past the cap the relay stops
reading while your upload keeps writing, so the request hangs until a timeout instead of
failing.

**This does not make large uploads work.** If your app must send files bigger than the cap,
the relay's `MAX_REQUEST_BODY_MIB` has to be raised — a server-side deployment decision you
cannot make from the client.

To read the cap a given relay enforces, fetch `maxRequestBodyBytes` from its discovery
document:

```
GET https://your-relay-server.com/api/mte-relay?frameVersions=2
```

`frameVersions` is mandatory. Omitting it is how a pre-version-2 SDK identifies itself, and
the relay answers **478** instead of a discovery document.

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
    null,                                 // unencryptedHeaders (encrypt everything)
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
    null                                  // unencryptedHeaders (encrypt everything)
)

val operationId = relay.startDownloadFile(props,
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

> `Content-Type` may not be listed in `unencryptedHeaders` — it is reserved and always
> encrypted, and naming it throws. Pass `null` unless something between your app and the
> relay must read a specific header.

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

val streamId = relay.startEventStream(request, null, null,
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
> `RelaySseParser` (`com.eclypses.relay.streaming.RelaySseParser`) is included if you
> want a ready-made one: feed each `onData` chunk to `append(bytes)`, which returns the
> `data:` payloads of any events it completed as a `List<String>`, and call `finish()`
> when the stream ends for anything still pending. It returns `data:` only — `event:`,
> `id:` and `retry:` lines are ignored.

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
| `keepAliveIntervalSeconds` | 300 | Relay keep-alive ping cadence (seconds). Against a v5 relay this is **derived from the server**, not used as written — see below |
| `acquisitionWaitTime` | 1.0 | Seconds to wait for a free pair when the pool is saturated |

> **`keepAliveIntervalSeconds` is derived from the relay, not taken from this default.**
> On connecting, the client reads the relay's advertised session timeout and sets the
> interval to a third of it — so a pair is touched twice before it can expire — clamped
> to 60–600 seconds. A relay advertising a 7200-second timeout therefore produces a
> 600-second interval, not the 300 above, and the log line says so. The default applies
> only to a relay that advertises no timeout. Set the value yourself and your value is
> kept; the client warns instead of overriding it.

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
startup logs `Using Relay Version 5.3.0 and Mte Version 4.2.1`; a bad license logs
`MTE License Check Failed`.

**Configuration warnings.** When what the relay reports disagrees with how the client is
configured, the library logs a WARNING once per subject per session: a header in
`unencryptedHeaders` the relay will not forward to the origin, a keepalive interval longer
than the server's session timeout, cookies disabled against a relay that forwards them, or
a relay reporting no streaming support. None of them stop a request. They are on by default
because each describes a failure that otherwise surfaces far from its cause. If you cannot
change the server side and want them quiet:

```kotlin
RelayWarnings.enabled = false        // com.eclypses.relay.RelayWarnings
```

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
The client-side licence is built into the library, so there is nothing of yours to fix:
contact [info@eclypses.com](mailto:info@eclypses.com). See [Step 2](#step-2-get-the-relay-instance).

**Pairing fails repeatedly**
Confirm the relay server is running and reachable, and that there's no firewall or VPN
blocking it. Force a re-pair with `rePairWithRelayServer`.

Only the scheme, host and port of a relay URL are used, so a path or trailing slash on a
URL you pass yourself (wrapper mode, `rePairWithRelayServer`) makes no difference. In
interceptor mode the relay host is whatever your `okhttp3.Request` is addressed to, so
check the request URL.

**Decryption / token-mismatch failures after a server restart**
Call `rePairWithRelayServer` to re-establish MTE state, then retry the request.

**Upload is rejected or fails**
Ensure the request headers include a `Content-Length` equal to the exact total
unencrypted body size, and that your `RelayStreamCallback` writes the whole body and
**closes** the `PipedOutputStream`. If the failure is a `RelayRequestTooLargeException`,
see [Size limits](#size-limits) — the body is past what the relay accepts and nothing
was sent.

**Pairing fails with a message containing `490 pair_limit`**
The relay is refusing because this client already holds as many pairs as it allows
(`maxPairsPerClient`, typically 200). That is the relay's count, not your local pool.

The client keeps its client id across launches — that identity is how a multi-replica
relay routes you back to your own pair state — so pairs accumulate under one id rather
than being orphaned under a new one each run. To stop that growing without bound the
client records every pair it finishes with and asks the relay to delete them on the next
keep-alive, and on a 490 `pair_limit` it flushes that list and retries once.

If you still see it: it clears on its own, because the relay reclaims idle pairs. The
usual cause of reaching the cap at all is many short-lived `Relay` instances — call
`getInstance` and hold the result rather than creating one per screen.

**Pairing fails naming a protocol or cryptographic mismatch**
The relay speaks a protocol, or uses cryptographic parameters, this client cannot work
with. The message names both sides, so read it first — it says what has to change and
which end has to change it. Usual causes: the relay does not offer frame version 2 and
needs upgrading; `kyberStrength` differs between relay and client; or the MTE profile
*settings* differ. A differing **probe** is normal and only warns — a mobile client links
the MTE client build and the relay links the server build, so their probes differ by
design, exactly when the deployment is correct. Only the settings decide
interoperability.

**A plain request for the relay's discovery document answers 478**
`GET /api/mte-relay` with no `frameVersions` query parameter is how a pre-version-2 SDK
identifies itself, and the relay answers **478** with an error frame rather than JSON.
This client always sends it; you only meet this reading discovery yourself:

```
GET https://your-relay-server.com/api/mte-relay?frameVersions=2
```

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
| `send(Request, String[], String, RelayOkHttpRequestListener)` | Encrypted HTTP request |
| `uploadFile(RelayFileRequestProperties, RelayStreamResponseListener, RelayStreamCompletionCallback)` | Streaming upload |
| `startUploadFile(...)` | Streaming upload; returns `operationId` |
| `downloadFile(RelayFileRequestProperties, RelayStreamResponseListener)` | Streaming download |
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

In wrapper mode these arrive directly as the `Throwable` in `onFailure(Request, Throwable)`.
In interceptor mode OkHttp only accepts an `IOException`, so each arrives as the `cause` of
a `RelayCallException`.

- `RelayException` — invalid license, unresolvable server URL, and other relay errors
- `RelayRequestTooLargeException` — the request is over the relay's advertised body limit;
  nothing was sent. Carries `sizeBytes`, `limitBytes` and what was measured
- `RelayControlPlaneException` — the relay refused authentication, pairing or keepalive.
  `error` carries the registry reason and `httpStatus` the relay's status
- `RelayIncompatibleException` — the relay speaks a newer frame protocol, does not accept
  this client's encode type, or expects a different Kyber strength
- `RelayFramedErrorException` — the relay answered with an error frame; `error` carries
  its code, reason and message
- `RelayProtocolException` — the relay's response was not a valid frame
- `RelayCallException` — interceptor mode only: the `IOException` wrapping any of the above
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
│       ├── LogHelper.java                 # internal logging
│       └── RelaySettings.java             # internal state; read version via getRelayGlobalSettings()
└── kotlin/com/eclypses/relay/
    ├── RelayWarnings.kt           # Public API: RelayWarnings.enabled
    ├── interceptor/               # Public API
    │   ├── RelayMteInterceptor.kt # What getOkHttpInterceptor() returns; also RelayCallException
    │   └── RelayRequestOptions.kt # Per-request options, attached as a request tag
    ├── session/                   # Implementation detail, except the exceptions below
    ├── protocol/                  # Implementation detail, except the exceptions below
    ├── streaming/                 # Implementation detail, except RelaySseParser
    ├── transport/                 # Implementation detail
    └── persistence/               # Implementation detail
```

Public types that live in an otherwise-internal package — import them from there:

| Type | Package |
|---|---|
| `RelayRequestTooLargeException`, `RelayControlPlaneException`, `RelayIncompatibleException` | `com.eclypses.relay.session` |
| `RelayFramedErrorException`, `RelayProtocolException` | `com.eclypses.relay.protocol` |
| `RelaySseParser` | `com.eclypses.relay.streaming` |

---
**All trademarks of Eclypses Inc.** may not be used without Eclypses Inc.'s prior written consent.
