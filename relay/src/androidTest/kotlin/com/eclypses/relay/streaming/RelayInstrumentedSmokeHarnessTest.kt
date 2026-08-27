package com.eclypses.relay.streaming

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eclypses.mte.MteBase
import com.eclypses.relay.RelayComponents
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelayInstrumentedSmokeHarnessTest {

    @Test
    fun executesRealV5RequestAgainstConfiguredRelayServer() {
        val args = InstrumentationRegistry.getArguments()
        val targetUrl = args.getString("MTE_V5_SMOKE_URL")
        assumeTrue("Set MTE_V5_SMOKE_URL instrumentation argument", !targetUrl.isNullOrBlank())

        val licenseValid = MteBase.initLicense("Eclypses Inc", "9eHOohOm/GwY01xbvNTL9B+1")
        assertTrue("MTE license initialization failed", licenseValid)

        val pathnamePrefix = args.getString("MTE_V5_SMOKE_PATHNAME_PREFIX")
        val routeHeaderName = args.getString("MTE_V5_SMOKE_ENCRYPT_HEADER")
        val headersToEncrypt = if (routeHeaderName.isNullOrBlank()) emptyArray() else arrayOf(routeHeaderName)

        val requestBuilder = Request.Builder().url(targetUrl!!)
        val customHeaderName = args.getString("MTE_V5_SMOKE_CUSTOM_HEADER_NAME")
        val customHeaderValue = args.getString("MTE_V5_SMOKE_CUSTOM_HEADER_VALUE")
        if (!customHeaderName.isNullOrBlank() && customHeaderValue != null) {
            requestBuilder.addHeader(customHeaderName, customHeaderValue)
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = RelayStreamingExecutor(RelayComponents.create(context, OkHttpClient(), useMke = true))
        val response = RelayOkHttpAdapter.execute(
            executor = executor,
            request = requestBuilder.get().build(),
            headersToEncrypt = headersToEncrypt,
            pathnamePrefix = pathnamePrefix,
        )

        assertTrue("relay repair status returned: ${response.code}", response.code !in 559..569)
    }
}
