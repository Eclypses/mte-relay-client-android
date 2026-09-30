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

/**
 * Per-request options for [RelayMteInterceptor], carried via OkHttp's typed tag mechanism:
 *
 * ```kotlin
 * val request = Request.Builder()
 *     .url("https://api.example.com/data")
 *     .tag(RelayRequestOptions::class.java, RelayRequestOptions(
 *         unencryptedHeaders = arrayOf("Authorization"),
 *     ))
 *     .build()
 * ```
 *
 * Omit the tag entirely to use library defaults (every header encrypted, no pathname prefix).
 *
 * @param unencryptedHeaders Names of headers to leave UNENCRYPTED on the hop to the relay,
 *   so infrastructure between the app and the relay can read them. Everything else is
 *   encrypted into the frame. `null` or empty encrypts every header, which is the default.
 *   `arrayOf("*")` exposes every eligible header; the reserved names in
 *   [com.eclypses.relay.streaming.RelayHeaderPolicy.RESERVED] stay encrypted even then, and
 *   listing one throws. An exposed header is readable by anything that terminates TLS in
 *   front of the relay — do not list `Authorization` or anything your origin treats as a
 *   secret.
 * @param pathnamePrefix Optional pathname segment prepended to the request route before
 *   encryption (e.g. `"api/v2"`).
 */
class RelayRequestOptions @JvmOverloads constructor(
    val unencryptedHeaders: Array<String>? = null,
    val pathnamePrefix: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RelayRequestOptions) return false
        if (!unencryptedHeaders.contentEquals(other.unencryptedHeaders)) return false
        if (pathnamePrefix != other.pathnamePrefix) return false
        return true
    }

    override fun hashCode(): Int {
        var result = unencryptedHeaders?.contentHashCode() ?: 0
        result = 31 * result + (pathnamePrefix?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "RelayRequestOptions(unencryptedHeaders=${unencryptedHeaders?.contentToString()}, " +
            "pathnamePrefix=$pathnamePrefix)"

    private fun Array<String>?.contentEquals(other: Array<String>?): Boolean {
        if (this === other) return true
        if (this == null || other == null) return false
        return this.contentEquals(other)
    }

    private fun Array<String>?.contentHashCode(): Int = this?.contentHashCode() ?: 0
}
