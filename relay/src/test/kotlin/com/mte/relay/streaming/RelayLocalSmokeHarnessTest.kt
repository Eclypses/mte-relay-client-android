package com.mte.relay.streaming

import com.eclypses.mte.MteBase
import com.mte.relay.RelayComponents
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

class RelayLocalSmokeHarnessTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "MTE_V5_SMOKE_JVM_ENABLED", matches = "(?i)true")
    @EnabledIfEnvironmentVariable(named = "MTE_V5_SMOKE_URL", matches = ".+")
    fun `executes real relay request against configured relay server`() {
        val licenseValid = MteBase.initLicense("Eclypses Inc", "9eHOohOm/GwY01xbvNTL9B+1")
        assertTrue(licenseValid, "MTE license initialization failed")

        val targetUrl = requireNotNull(System.getenv("MTE_V5_SMOKE_URL"))
        val pathnamePrefix = System.getenv("MTE_V5_SMOKE_PATHNAME_PREFIX")
        val routeHeaderName = System.getenv("MTE_V5_SMOKE_ENCRYPT_HEADER")
        val headersToEncrypt = if (routeHeaderName.isNullOrBlank()) emptyArray() else arrayOf(routeHeaderName)

        val requestBuilder = Request.Builder().url(targetUrl)
        val customHeaderName = System.getenv("MTE_V5_SMOKE_CUSTOM_HEADER_NAME")
        val customHeaderValue = System.getenv("MTE_V5_SMOKE_CUSTOM_HEADER_VALUE")
        if (!customHeaderName.isNullOrBlank() && customHeaderValue != null) {
            requestBuilder.addHeader(customHeaderName, customHeaderValue)
        }

        val executor = RelayStreamingExecutor(RelayComponents.create(OkHttpClient(), useMke = true))
        val response = RelayOkHttpAdapter.execute(
            executor = executor,
            request = requestBuilder.get().build(),
            headersToEncrypt = headersToEncrypt,
            pathnamePrefix = pathnamePrefix,
        )

        assertTrue(response.code !in 559..569, "relay repair status returned: ${response.code}")
    }
}
