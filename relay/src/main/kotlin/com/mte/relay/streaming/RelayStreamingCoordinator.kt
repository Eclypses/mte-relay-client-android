package com.mte.relay.streaming

data class RelayStreamingRequest(
    val operationId: String,
    val totalBytes: Long? = null,
)

interface RelayStreamingCoordinator {
    fun start(request: RelayStreamingRequest)
    fun cancel(operationId: String)
}
