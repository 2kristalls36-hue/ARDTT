package com.nonamevpn.app.profile

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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

    /** Read profile JSON via SAF / Downloads / Files app. */
    suspend fun importUri(uri: Uri): VpnProfile = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        } ?: throw IllegalStateException("Не удалось открыть файл")
        if (raw.isBlank()) throw IllegalStateException("Файл пустой")
        importJson(raw)
    }

    suspend fun importDemo(): VpnProfile = importJson(VpnProfileJson.encode(VpnProfileJson.demo()))

    suspend fun clear() {
        context.profileStore.edit { it.remove(profileJsonKey) }
    }
}
