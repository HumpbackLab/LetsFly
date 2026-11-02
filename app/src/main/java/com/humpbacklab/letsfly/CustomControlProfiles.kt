package com.humpbacklab.letsfly

import android.content.SharedPreferences

internal data class CustomControlProfile(val id: Int, val name: String,
    val config: CustomControlConfig)

/** Keeps the existing configuration key as the active layout for compatibility. */
internal class CustomControlProfiles(private val preferences: SharedPreferences,
    initialName: String) {
    companion object {
        const val MAX_NAME_LENGTH = 30
        private const val IDS_KEY = "custom_control_profile_ids"
        private const val ACTIVE_KEY = "custom_control_active_profile"
        private const val NEXT_ID_KEY = "custom_control_profile_next_id"
        private fun configKey(id: Int) = "custom_control_profile_${id}_config"
        private fun nameKey(id: Int) = "custom_control_profile_${id}_name"
    }

    init {
        if (ids().isEmpty()) {
            val config = CustomControlConfig.load(preferences).encode()
            preferences.edit().putString(IDS_KEY, "1").putInt(ACTIVE_KEY, 1)
                .putInt(NEXT_ID_KEY, 2)
                .putString(nameKey(1), initialName).putString(configKey(1), config)
                .putString(CustomControlConfig.PREF_KEY, config).apply()
        } else {
            // Older app versions edit only the canonical single-layout key.
            val active = get(activeId())
            val legacy = preferences.getString(CustomControlConfig.PREF_KEY, null)
                ?.let { CustomControlConfig.decode(it) }
            if (active != null && legacy != null && legacy != active.config) {
                preferences.edit().putString(configKey(active.id), legacy.encode()).apply()
            }
        }
    }

    private fun ids(): List<Int> = preferences.getString(IDS_KEY, null)
        ?.split(',')?.mapNotNull { it.toIntOrNull()?.takeIf { id -> id > 0 } }
        ?.distinct() ?: emptyList()

    fun all(): List<CustomControlProfile> = ids().mapNotNull(::get)

    fun get(id: Int): CustomControlProfile? {
        if (id !in ids()) return null
        val config = preferences.getString(configKey(id), null)
            ?.let { CustomControlConfig.decode(it) } ?: return null
        val name = preferences.getString(nameKey(id), null) ?: return null
        return CustomControlProfile(id, name, config)
    }

    fun activeId(): Int = preferences.getInt(ACTIVE_KEY, 1)

    fun nameAvailable(name: String, exceptId: Int? = null): Boolean =
        name.trim().isNotEmpty() && name.trim().length <= MAX_NAME_LENGTH &&
            all().none { it.id != exceptId && it.name == name.trim() }

    fun select(id: Int): Boolean {
        val profile = get(id) ?: return false
        preferences.edit().putInt(ACTIVE_KEY, id)
            .putString(CustomControlConfig.PREF_KEY, profile.config.encode()).apply()
        return true
    }

    fun create(name: String, config: CustomControlConfig): Int? {
        if (!nameAvailable(name) || config.error() != null) return null
        val ids = ids()
        val id = maxOf(preferences.getInt(NEXT_ID_KEY, 2), (ids.maxOrNull() ?: 0) + 1)
        val encoded = config.encode()
        preferences.edit().putString(IDS_KEY, (ids + id).joinToString(","))
            .putInt(ACTIVE_KEY, id).putInt(NEXT_ID_KEY, id + 1)
            .putString(nameKey(id), name.trim())
            .putString(configKey(id), encoded)
            .putString(CustomControlConfig.PREF_KEY, encoded).apply()
        return id
    }

    fun rename(id: Int, name: String): Boolean {
        if (get(id) == null || !nameAvailable(name, id)) return false
        preferences.edit().putString(nameKey(id), name.trim()).apply()
        return true
    }

    fun save(id: Int, config: CustomControlConfig): Boolean {
        if (get(id) == null || config.error() != null) return false
        val encoded = config.encode()
        val editor = preferences.edit().putString(configKey(id), encoded)
        if (id == activeId()) editor.putString(CustomControlConfig.PREF_KEY, encoded)
        editor.apply()
        return true
    }

    fun delete(id: Int): Boolean {
        val remaining = ids().filter { it != id }
        if (get(id) == null || remaining.isEmpty()) return false
        val next = if (id == activeId()) get(remaining.first()) ?: return false else null
        val editor = preferences.edit().putString(IDS_KEY, remaining.joinToString(","))
            .remove(nameKey(id)).remove(configKey(id))
        if (next != null) editor.putInt(ACTIVE_KEY, next.id)
            .putString(CustomControlConfig.PREF_KEY, next.config.encode())
        editor.apply()
        return true
    }
}
