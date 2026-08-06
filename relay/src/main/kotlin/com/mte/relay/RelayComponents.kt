package com.mte.relay

import android.content.Context
import com.mte.relay.persistence.RelayStateStore
import com.mte.relay.persistence.SharedPreferencesRelayStateStore
import com.mte.relay.protocol.BinaryRelayProtocolEngine
import com.mte.relay.protocol.RelayProtocolEngine
import com.mte.relay.session.OkHttpRelayControlPlaneClient
import com.mte.relay.session.RelaySessionLifecycleManager
import com.mte.relay.session.RelaySessionManager
import com.mte.relay.transport.OkHttpRelayTransport
import com.mte.relay.transport.RelayTransport
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Composition root for the V5 relay runtime: builds the session manager, lifecycle manager,
 * protocol engine, and transport as one set so every caller (streaming executor, OkHttp
 * interceptor) shares the same clientId and pair pool.
 */
class RelayComponents private constructor(
    val sessionManager: RelaySessionManager,
    val sessionLifecycleManager: RelaySessionLifecycleManager,
    val protocolEngine: RelayProtocolEngine,
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
        ): RelayComponents = create(httpClient, useMke, SharedPreferencesRelayStateStore(context))

        /** Builds the runtime without persistence (JVM tests, smoke harnesses). */
        @JvmStatic
        @JvmOverloads
        fun create(
            httpClient: OkHttpClient,
            useMke: Boolean = true,
        ): RelayComponents = create(httpClient, useMke, stateStore = null)

        private fun create(
            httpClient: OkHttpClient,
            useMke: Boolean,
            stateStore: RelayStateStore?,
        ): RelayComponents {
            val sessionManager = RelaySessionManager()
            // Streaming transfers (large uploads/downloads, long-lived event streams) must not be
            // subject to the default 10-second OkHttp read/write timeouts. Derive a separate client
            // for the transport that disables those while keeping a bounded connect timeout.
            val streamingHttpClient = httpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            return RelayComponents(
                sessionManager = sessionManager,
                sessionLifecycleManager = RelaySessionLifecycleManager(
                    sessionManager = sessionManager,
                    controlPlaneClient = OkHttpRelayControlPlaneClient(httpClient),
                    stateStore = stateStore,
                ),
                protocolEngine = BinaryRelayProtocolEngine(),
                transport = OkHttpRelayTransport(streamingHttpClient),
                useMke = useMke,
            )
        }
    }
}
