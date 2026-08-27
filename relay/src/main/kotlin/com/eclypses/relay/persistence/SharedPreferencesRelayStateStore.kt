package com.eclypses.relay.persistence

import android.content.Context
import java.util.Base64

/**
 * Android-backed RelayStateStore implementation persisted via SharedPreferences.
 */
class SharedPreferencesRelayStateStore(
    context: Context,
) : RelayStateStore {

    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(origin: String): ByteArray? {
        val encoded = preferences.getString(keyFor(origin), null) ?: return null
        return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
    }

    override fun save(origin: String, state: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(state)
        preferences.edit().putString(keyFor(origin), encoded).apply()
    }

    override fun remove(origin: String) {
        preferences.edit().remove(keyFor(origin)).apply()
    }

    private fun keyFor(origin: String): String = "origin:$origin"

    private companion object {
        private const val PREFS_NAME = "mte_relay_state_store"
    }
}
