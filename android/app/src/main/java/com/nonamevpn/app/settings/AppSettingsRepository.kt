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

    val isAdminUnlocked: Flow<Boolean> = context.dataStore.data.map { it[adminUnlocked] == true }
    val hideIpEnabled: Flow<Boolean> = context.dataStore.data.map { it[hideIp] == true }
    val hasAdminPin: Flow<Boolean> = context.dataStore.data.map { !it[adminPinHash].isNullOrBlank() }
    val currentProfileName: Flow<String> = context.dataStore.data.map { it[profileName] ?: "" }

    suspend fun setHideIp(enabled: Boolean) {
        context.dataStore.edit { it[hideIp] = enabled }
    }

    suspend fun setProfileName(name: String) {
        context.dataStore.edit { it[profileName] = name }
    }

    suspend fun setAdminPin(pin: String) {
        context.dataStore.edit {
            it[adminPinHash] = sha256(pin)
            it[adminUnlocked] = true
        }
    }

    suspend fun unlockAdmin(pin: String): Boolean {
        var ok = false
        context.dataStore.edit { prefs ->
            val stored = prefs[adminPinHash]
            if (stored.isNullOrBlank()) {
                prefs[adminPinHash] = sha256(pin)
                prefs[adminUnlocked] = true
                ok = true
            } else if (stored == sha256(pin)) {
                prefs[adminUnlocked] = true
                ok = true
            }
        }
        return ok
    }

    suspend fun lockAdmin() {
        context.dataStore.edit { it[adminUnlocked] = false }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
