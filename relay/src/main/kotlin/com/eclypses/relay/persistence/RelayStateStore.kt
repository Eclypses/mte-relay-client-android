package com.eclypses.relay.persistence

interface RelayStateStore {
    fun load(origin: String): ByteArray?
    fun save(origin: String, state: ByteArray)
    fun remove(origin: String)
}
