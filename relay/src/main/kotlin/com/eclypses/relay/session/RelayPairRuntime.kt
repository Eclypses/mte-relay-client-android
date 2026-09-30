package com.eclypses.relay.session

import com.eclypses.mte.wire.PairId

/**
 * One MTE pair's codec, as the transport uses it.
 *
 * Two operations, because frame v2 needs two: every frame that carries a payload is
 * exactly one `Encode` or one `Decode`. The previous generation also exposed an MKE
 * chunk session -- startEncrypt, encryptChunk, finishEncrypt, encryptFinishBytes and
 * their decrypt counterparts -- because a streaming body was one long encryption with
 * the ciphertext appended raw after the frame header. A body is DATA frames now, each
 * one a whole operation, so the chunk session has no caller and is gone.
 */
interface RelayRuntimePair {
    val pairId: PairId
    fun encode(payload: ByteArray): ByteArray
    fun decode(payload: ByteArray): ByteArray
}

data class RelayPairingResult(
    val materials: List<RelayPairMaterial>,
    val runtimePairs: List<RelayRuntimePair> = emptyList(),
)
