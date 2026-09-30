package com.eclypses.relay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase0CharacterizationTest {

    @Test
    fun relayFileRequestProperties_uploadConstructor_preservesStreamingInputs() {
        val callback = RelayStreamCallback { }
        val headers = linkedMapOf("Content-Type" to "multipart/form-data")
        // Under the denylist this names a header to EXPOSE, so the fixture uses a
        // tracing header rather than modelling exposure of a credential.
        val unencryptedHeaders = arrayOf("traceparent")

        val props = RelayFileRequestProperties(
            "https://relay.example.test",
            "/api/files/upload",
            null,
            headers,
            unencryptedHeaders,
            callback
        )

        assertEquals("https://relay.example.test", props.serverPath)
        assertEquals("/api/files/upload", props.route)
        assertNotNull(props.relayStreamCallback)
        assertEquals("multipart/form-data", props.origHeaders["Content-Type"])
        assertTrue(props.unencryptedHeaders.contentEquals(unencryptedHeaders))
    }

    @Test
    fun streamCompletionCallback_recordsMonotonicProgress() {
        val progressEvents = mutableListOf<kotlin.Pair<Int, Int>>()
        val callback = RelayStreamCompletionCallback { bytesCompleted, totalBytes ->
            progressEvents += kotlin.Pair(bytesCompleted, totalBytes)
        }

        callback.onProgressUpdate(0, 100)
        callback.onProgressUpdate(35, 100)
        callback.onProgressUpdate(80, 100)
        callback.onProgressUpdate(100, 100)

        assertEquals(4, progressEvents.size)
        assertTrue(progressEvents.zipWithNext().all { (prev, next) -> next.first >= prev.first })
        assertTrue(progressEvents.all { it.first <= it.second })
    }
}
