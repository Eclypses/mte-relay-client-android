package com.mte.relay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase0CharacterizationTest {

    @Test
    fun relayFileRequestProperties_uploadConstructor_preservesStreamingInputs() {
        val callback = RelayStreamCallback { }
        val headers = linkedMapOf("Content-Type" to "multipart/form-data")
        val headersToEncrypt = arrayOf("Authorization")

        val props = RelayFileRequestProperties(
            "https://relay.example.test",
            "/api/files/upload",
            null,
            headers,
            headersToEncrypt,
            callback
        )

        assertEquals("https://relay.example.test", props.serverPath)
        assertEquals("/api/files/upload", props.route)
        assertNotNull(props.relayStreamCallback)
        assertEquals("multipart/form-data", props.origHeaders["Content-Type"])
        assertTrue(props.headersToEncrypt.contentEquals(headersToEncrypt))
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
