package com.eclypses.relay.session

interface RelayRuntimePair {
    val pairId: String
    fun encode(payload: ByteArray): ByteArray
    fun decode(payload: ByteArray): ByteArray
    fun startEncrypt()
    fun encryptChunk(buffer: ByteArray, length: Int) // encrypts in-place; output length == input length (MKE guarantee)
    fun finishEncrypt(): ByteArray                  // returns trailing bytes
    fun encryptFinishBytes(): Int                    // pre-computable for Content-Length calculation
    fun startDecrypt()
    fun decryptChunk(buffer: ByteArray): ByteArray   // returns decoded bytes (same length as input for MKE body chunks)
    fun finishDecrypt(): ByteArray                   // returns any trailing plaintext bytes from the MKE MAC block
}

data class RelayPairingResult(
    val materials: List<RelayPairMaterial>,
    val runtimePairs: List<RelayRuntimePair> = emptyList(),
)
