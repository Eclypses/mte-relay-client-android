package com.eclypses.relay

import android.content.Context
import com.eclypses.relay.persistence.RelayStateStore
import com.eclypses.relay.persistence.SharedPreferencesRelayStateStore
import com.eclypses.relay.session.OkHttpRelayControlPlaneClient
import com.eclypses.relay.session.RelaySessionLifecycleManager
import com.eclypses.relay.session.RelaySessionManager
import com.eclypses.relay.transport.OkHttpRelayTransport
import com.eclypses.relay.transport.RelayCookieJar
import com.eclypses.relay.transport.RelayTransport
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/**
 * Composition root for the V5 relay runtime: builds the session manager, lifecycle manager,
 * and transport as one set so every caller (streaming executor, OkHttp
 * interceptor) shares the same clientId and pair pool.
 */
class RelayComponents private constructor(
    val sessionManager: RelaySessionManager,
    val sessionLifecycleManager: RelaySessionLifecycleManager,
    val transport: RelayTransport,
    val useMke: Boolean,
) {

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 30L

        /** Builds the runtime with Android-backed persistence of relay state. */
        @JvmStatic
        @JvmOverloads
        fun create(
            context: Context,
            httpClient: OkHttpClient,
            useMke: Boolean = true,
            cookiesEnabled: Boolean = true,
        ): RelayComponents =
            create(httpClient, useMke, SharedPreferencesRelayStateStore(context), cookiesEnabled)

        /** Builds the runtime without persistence (JVM tests, smoke harnesses). */
        @JvmStatic
        @JvmOverloads
        fun create(
            httpClient: OkHttpClient,
            useMke: Boolean = true,
            cookiesEnabled: Boolean = true,
        ): RelayComponents = create(httpClient, useMke, stateStore = null, cookiesEnabled = cookiesEnabled)

        private fun create(
            httpClient: OkHttpClient,
            useMke: Boolean,
            stateStore: RelayStateStore?,
            cookiesEnabled: Boolean,
        ): RelayComponents {
            val sessionManager = RelaySessionManager()
            // Cookies set against the relay host have to ride every relay call — a load
            // balancer's affinity cookie is set on the auth response and has to be on the
            // pair request that follows, or the pair lands on another replica. OkHttp
            // defaults to NO_COOKIES, so absent a jar the client silently never returns one.
            //
            // Disabling is the cookiesEnabled flag, not passing NO_COOKIES: that value is
            // also OkHttp's default, so the two intents are indistinguishable here and a
            // caller who meant "off" would otherwise be quietly given a jar.
            //
            // Both clients below derive from this one, so they share the one store.
            val cookieAwareClient = when {
                !cookiesEnabled -> httpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
                // A jar the caller configured is theirs; this only fills in the default.
                httpClient.cookieJar === CookieJar.NO_COOKIES ->
                    httpClient.newBuilder().cookieJar(RelayCookieJar()).build()
                else -> httpClient
            }
            // Streaming transfers (large uploads/downloads, long-lived event streams) must not be
            // subject to the default 10-second OkHttp read/write timeouts. Derive a separate client
            // for the transport that disables those while keeping a bounded connect timeout.
            val streamingHttpClient = cookieAwareClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            return RelayComponents(
                sessionManager = sessionManager,
                sessionLifecycleManager = RelaySessionLifecycleManager(
                    sessionManager = sessionManager,
                    controlPlaneClient = OkHttpRelayControlPlaneClient(cookieAwareClient),
                    stateStore = stateStore,
                    useMke = useMke,
                    cookiesEnabled = cookiesEnabled,
                ),
                transport = OkHttpRelayTransport(streamingHttpClient),
                useMke = useMke,
            )
        }
    }
}
