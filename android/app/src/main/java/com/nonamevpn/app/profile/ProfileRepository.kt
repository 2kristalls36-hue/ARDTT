package com.nonamevpn.app.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore("nvpn_profile")

class ProfileRepository(private val context: Context) {
    private val profileJsonKey = stringPreferencesKey("profile_json")

    val profile: Flow<VpnProfile?> = context.profileStore.data.map { prefs ->
        val raw = prefs[profileJsonKey] ?: return@map null
        runCatching { VpnProfileJson.parse(raw) }.getOrNull()
    }

    suspend fun importJson(raw: String): VpnProfile {
        val parsed = VpnProfileJson.parse(raw)
        context.profileStore.edit { it[profileJsonKey] = VpnProfileJson.encode(parsed) }
        return parsed
    }

    suspend fun importDemo(): VpnProfile = importJson(VpnProfileJson.encode(VpnProfileJson.demo()))

    suspend fun clear() {
        context.profileStore.edit { it.remove(profileJsonKey) }
    }
}
