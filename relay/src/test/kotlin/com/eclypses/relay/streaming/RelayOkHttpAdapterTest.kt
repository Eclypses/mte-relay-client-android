package com.eclypses.relay.streaming

import okhttp3.Request
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * The adapter is where an OkHttp request is flattened into the shape the frame carries.
 * OkHttp keeps headers as a multimap and the frame's header section is a JSON object, so
 * the flattening is lossy unless it is done deliberately.
 */
class RelayOkHttpAdapterTest {

    @Test
    fun `repeated header values are joined rather than dropped`() {
        val request = Request.Builder()
            .url("https://api.example.com/data")
            .addHeader("Accept", "text/plain")
            .addHeader("Accept", "application/json")
            .build()

        val headers = RelayOkHttpAdapter.headers(request)

        // Headers.get() returns only the last value, which would silently lose text/plain.
        assertEquals("text/plain, application/json", headers["Accept"])
    }

    @Test
    fun `a single valued header is unchanged`() {
        val request = Request.Builder()
            .url("https://api.example.com/data")
            .addHeader("X-Tenant-Id", "acme")
            .build()

        assertEquals("acme", RelayOkHttpAdapter.headers(request)["X-Tenant-Id"])
    }

    @Test
    fun `route carries the query string when there is one`() {
        val withQuery = Request.Builder().url("https://api.example.com/a/b?x=1&y=2").build()
        val without = Request.Builder().url("https://api.example.com/a/b").build()

        assertEquals("/a/b?x=1&y=2", RelayOkHttpAdapter.route(withQuery))
        assertEquals("/a/b", RelayOkHttpAdapter.route(without))
    }
}
