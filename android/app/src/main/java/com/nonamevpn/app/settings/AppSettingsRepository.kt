package com.nonamevpn.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("nvpn_settings")

class AppSettingsRepository(private val context: Context) {
    private val adminUnlocked = booleanPreferencesKey("admin_unlocked")
    private val adminPinHash = stringPreferencesKey("admin_pin_hash")
    private val hideIp = booleanPreferencesKey("hide_ip")
    private val profileName = stringPreferencesKey("profile_name")
    private val silentRecreate = booleanPreferencesKey("silent_recreate")
    private val economyWorkers = booleanPreferencesKey("economy_workers")
    private val dialPath = stringPreferencesKey("dial_path")
    private val pathMode = stringPreferencesKey("conn_path_mode")

    val isAdminUnlocked: Flow<Boolean> = context.dataStore.data.map { it[adminUnlocked] == true }
    val hideIpEnabled: Flow<Boolean> = context.dataStore.data.map { it[hideIp] == true }
    val hasAdminPin: Flow<Boolean> = context.dataStore.data.map { !it[adminPinHash].isNullOrBlank() }
    val currentProfileName: Flow<String> = context.dataStore.data.map { it[profileName] ?: "" }
    val silentRecreateEnabled: Flow<Boolean> = context.dataStore.data.map { it[silentRecreate] == true }
    val economyWorkersEnabled: Flow<Boolean> = context.dataStore.data.map { it[economyWorkers] == true }
    /** `auto` | `vkcalls` | `legacy` — Path B TURN dial. */
    val dialPathName: Flow<String> = context.dataStore.data.map {
        normalizeDialPath(it[dialPath])
    }
    /** `auto` | `direct` | `bypass` — tunnel path override. */
    val pathModeName: Flow<String> = context.dataStore.data.map {
        normalizePathMode(it[pathMode])
    }

    suspend fun setHideIp(enabled: Boolean) {
        context.dataStore.edit { it[hideIp] = enabled }
    }

    suspend fun setProfileName(name: String) {
        context.dataStore.edit { it[profileName] = name }
    }

    suspend fun setSilentRecreate(enabled: Boolean) {
        context.dataStore.edit { it[silentRecreate] = enabled }
    }

    suspend fun setEconomyWorkers(enabled: Boolean) {
        context.dataStore.edit { it[economyWorkers] = enabled }
    }

    suspend fun setDialPath(name: String) {
        context.dataStore.edit { it[dialPath] = normalizeDialPath(name) }
    }

    suspend fun setPathMode(name: String) {
        context.dataStore.edit { it[pathMode] = normalizePathMode(name) }
    }

    suspend fun unlockAdmin() {
        context.dataStore.edit { it[adminUnlocked] = true }
    }

    /** @deprecated PIN removed — use [unlockAdmin]. Kept for binary compat of older calls. */
    suspend fun unlockAdmin(pin: String): Boolean {
        unlockAdmin()
        return true
    }

    suspend fun setAdminPin(pin: String) {
        // No-op: PIN flow removed in favour of long-press unlock.
        unlockAdmin()
    }

    suspend fun lockAdmin() {
        context.dataStore.edit { it[adminUnlocked] = false }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun normalizeDialPath(raw: String?): String = when (raw?.lowercase()?.trim()) {
            "vkcalls" -> "vkcalls"
            "legacy" -> "legacy"
            else -> "auto"
        }

        fun normalizePathMode(raw: String?): String = when (raw?.lowercase()?.trim()) {
            "direct", "awg" -> "direct"
            "bypass", "wdtt" -> "bypass"
            else -> "auto"
        }
    }
}
