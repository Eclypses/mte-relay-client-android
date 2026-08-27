// The MIT License (MIT)
//
// Copyright (c) Eclypses, Inc.
//
// All rights reserved.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.eclypses.relay.interceptor

import com.eclypses.relay.protocol.RelayProtocolEngine
import com.eclypses.relay.session.RelaySessionLifecycleManager
import com.eclypses.relay.session.RelaySessionManager
import com.eclypses.relay.streaming.RelayOkHttpAdapter
import com.eclypses.relay.streaming.RelayStreamingExecutor
import com.eclypses.relay.transport.RelayTransport
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp application interceptor that transparently encrypts outbound requests and decrypts
 * inbound responses using the relay binary protocol.
 *
 * Register once with your [okhttp3.OkHttpClient]:
 * ```kotlin
 * val relay = Relay.getInstance(context)
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(relay.getOkHttpInterceptor())
 *     .build()
 * ```
 *
 * All calls made through that client are automatically relayed. Per-request options
 * (headers to encrypt, pathname prefix, preventStreaming) are carried via OkHttp's typed
 * tag: `request.tag(RelayRequestOptions::class.java)`. Omit the tag to use defaults.
 *
 * **File uploads and downloads must still use the wrapper mode** (`relay.startUploadFile(...)`,
 * `relay.startDownloadFile(...)`). Streaming is intentionally excluded from interceptor mode —
 * the relay frame protocol requires an exact `Content-Length` commitment before writing the
 * encrypted body, which is incompatible with OkHttp's interceptor `RequestBody` model.
 */
class RelayMteInterceptor internal constructor(
    private val executor: RelayStreamingExecutor,
) : Interceptor {

    /**
     * Primary constructor — wires into an existing relay session. Use
     * [com.eclypses.relay.Relay.getOkHttpInterceptor] to obtain an instance that shares the
     * session with wrapper-mode calls.
     */
    constructor(
        sessionManager: RelaySessionManager,
        sessionLifecycleManager: RelaySessionLifecycleManager,
        protocolEngine: RelayProtocolEngine,
        transport: RelayTransport,
    ) : this(
        RelayStreamingExecutor(
            sessionManager = sessionManager,
            sessionLifecycleManager = sessionLifecycleManager,
            protocolEngine = protocolEngine,
            transport = transport,
        ),
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val options = request.tag(RelayRequestOptions::class.java)
        return RelayOkHttpAdapter.execute(
            executor = executor,
            request = request,
            headersToEncrypt = options?.headersToEncrypt,
            pathnamePrefix = options?.pathnamePrefix,
            preventStreaming = options?.preventStreaming ?: false,
        )
    }
}
