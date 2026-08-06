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

package com.mte.relay.interceptor

/**
 * Per-request options for [RelayMteInterceptor], carried via OkHttp's typed tag mechanism:
 *
 * ```kotlin
 * val request = Request.Builder()
 *     .url("https://api.example.com/data")
 *     .tag(RelayRequestOptions::class.java, RelayRequestOptions(
 *         headersToEncrypt = arrayOf("Authorization"),
 *     ))
 *     .build()
 * ```
 *
 * Omit the tag entirely to use library defaults (all headers encrypted per relay defaults,
 * no pathname prefix, streaming not suppressed).
 *
 * @param headersToEncrypt Headers to include in the encrypted relay frame. `null` means use
 *   the library default (encrypt headers as configured at construction time).
 * @param preventStreaming When `true`, instructs the relay server not to stream the upstream
 *   request body. Relay-server protocol flag — not an OkHttp buffering flag.
 * @param pathnamePrefix Optional pathname segment prepended to the request route before
 *   encryption (e.g. `"api/v2"`).
 */
class RelayRequestOptions @JvmOverloads constructor(
    val headersToEncrypt: Array<String>? = null,
    val preventStreaming: Boolean = false,
    val pathnamePrefix: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayRequestOptions) return false
        if (!headersToEncrypt.contentEquals(other.headersToEncrypt)) return false
        if (preventStreaming != other.preventStreaming) return false
        if (pathnamePrefix != other.pathnamePrefix) return false
        return true
    }

    override fun hashCode(): Int {
        var result = headersToEncrypt?.contentHashCode() ?: 0
        result = 31 * result + preventStreaming.hashCode()
        result = 31 * result + (pathnamePrefix?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RelayRequestOptions(headersToEncrypt=${headersToEncrypt?.contentToString()}, " +
            "preventStreaming=$preventStreaming, pathnamePrefix=$pathnamePrefix)"

    private fun Array<String>?.contentEquals(other: Array<String>?): Boolean {
        if (this === other) return true
        if (this == null || other == null) return false
        return this.contentEquals(other)
    }

    private fun Array<String>?.contentHashCode(): Int = this?.contentHashCode() ?: 0
}
