package com.mte.relay

import okhttp3.Request

class RelaySseStreamHandle internal constructor(
    private val relay: Relay,
    val streamId: String,
) {
    fun cancel(): Boolean = relay.cancelEventStream(streamId)
}

@JvmOverloads
fun Relay.openEventStream(
    request: Request,
    headersToEncrypt: Array<String>? = null,
    pathnamePrefix: String? = null,
    onOpened: (streamId: String, statusCode: Int, responseHeaders: Map<String, List<String>>) -> Unit = { _, _, _ -> },
    onData: (streamId: String, data: ByteArray) -> Unit,
    onCompleted: (streamId: String) -> Unit = {},
    onCancelled: (streamId: String) -> Unit = {},
    onError: (streamId: String, statusCode: Int, errorMessage: String, responseHeaders: Map<String, List<String>>?) -> Unit = { _, _, _, _ -> },
): RelaySseStreamHandle {
    val streamId = startEventStream(
        request,
        headersToEncrypt,
        pathnamePrefix,
        object : RelaySseListener {
            override fun onOpened(
                streamId: String,
                statusCode: Int,
                responseHeaders: Map<String, List<String>>,
            ) {
                onOpened(streamId, statusCode, responseHeaders)
            }

            override fun onData(streamId: String, data: ByteArray) {
                onData(streamId, data)
            }

            override fun onCompleted(streamId: String) {
                onCompleted(streamId)
            }

            override fun onCancelled(streamId: String) {
                onCancelled(streamId)
            }

            override fun onError(
                streamId: String,
                statusCode: Int,
                errorMessage: String,
                responseHeaders: Map<String, List<String>>?,
            ) {
                onError(streamId, statusCode, errorMessage, responseHeaders)
            }
        },
    )
    return RelaySseStreamHandle(this, streamId)
}