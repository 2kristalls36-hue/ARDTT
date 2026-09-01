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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore("nvpn_profile")

class ProfileRepository(private val context: Context) {
    private val profileJsonKey = stringPreferencesKey("profile_json")
    private val profilesJsonKey = stringPreferencesKey("profiles_json")
    private val activeIdKey = stringPreferencesKey("active_profile_id")

    val profiles: Flow<List<VpnProfile>> = context.profileStore.data.map { prefs ->
        loadList(prefs)
    }

    val activeId: Flow<String?> = context.profileStore.data.map { prefs ->
        val list = loadList(prefs)
        resolveActiveId(prefs[activeIdKey], list)
    }

    /** Active profile — drives ConnectionManager via TunnelScreen. */
    val profile: Flow<VpnProfile?> = context.profileStore.data.map { prefs ->
        val list = loadList(prefs)
        val id = resolveActiveId(prefs[activeIdKey], list) ?: return@map null
        list.find { profileKey(it) == id }
    }

    suspend fun importJson(raw: String): VpnProfile {
        val parsed = VpnProfileJson.parse(raw)
        upsertAndActivate(parsed)
        return parsed
    }

    /** Read profile JSON via SAF / Downloads / Files app. */
    suspend fun importUri(
        uri: Uri,
        folder: String = DEFAULT_PROFILE_FOLDER,
        activate: Boolean = true,
    ): VpnProfile =
        withContext(Dispatchers.IO) {
            val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            } ?: throw IllegalStateException("Не удалось открыть файл")
            if (raw.isBlank()) throw IllegalStateException("Файл пустой")
            importJson(raw, folder, activate)
        }

    suspend fun upsert(
        profile: VpnProfile,
        folder: String = DEFAULT_PROFILE_FOLDER,
        activate: Boolean = true,
    ): VpnProfile {
        mutate { current ->
            val id = VpnProfileJson.identityOf(profile)
            val existing = current.items.firstOrNull { it.id == id }
            val stored = StoredProfile(
                id = id,
                folder = folder.ifBlank { existing?.folder ?: DEFAULT_PROFILE_FOLDER },
                addedAtMs = existing?.addedAtMs ?: System.currentTimeMillis(),
                profile = profile,
            )
            val items = current.items.filterNot { it.id == id } + stored
            current.copy(
                activeId = when {
                    activate -> stored.id
                    current.activeId != null -> current.activeId
                    else -> stored.id
                },
                folders = (current.folders + stored.folder).distinct(),
                items = items,
            )
        }
        return profile
    }

    suspend fun setActive(id: String) {
        mutate { current ->
            if (current.items.none { it.id == id }) current
            else current.copy(activeId = id)
        }
    }

    suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        mutate { current ->
            current.copy(
                items = current.items.map { item ->
                    if (item.id == id) item.copy(profile = item.profile.copy(name = trimmed)) else item
                },
            )
        }
    }

    suspend fun moveToFolder(id: String, folder: String) {
        val dest = folder.trim().ifBlank { DEFAULT_PROFILE_FOLDER }
        mutate { current ->
            current.copy(
                folders = (current.folders + dest).distinct(),
                items = current.items.map { item ->
                    if (item.id == id) item.copy(folder = dest) else item
                },
            )
        }
    }

    suspend fun addFolder(name: String) {
        val folder = name.trim().ifBlank { return }
        mutate { current ->
            current.copy(folders = (current.folders + folder).distinct())
        }
    }

    suspend fun deleteFolder(name: String) {
        val folder = name.trim()
        if (folder.isBlank() || folder == DEFAULT_PROFILE_FOLDER) return
        mutate { current ->
            current.copy(
                folders = current.folders.filterNot { it == folder }.ifEmpty { listOf(DEFAULT_PROFILE_FOLDER) },
                items = current.items.map { item ->
                    if (item.folder == folder) item.copy(folder = DEFAULT_PROFILE_FOLDER) else item
                },
            )
        }
    }

    suspend fun delete(id: String) {
        mutate { current ->
            val items = current.items.filterNot { it.id == id }
            current.copy(
                items = items,
                activeId = when {
                    current.activeId == id -> items.firstOrNull()?.id
                    else -> current.activeId
                },
            )
        }
    }

    suspend fun setActive(id: String) {
        context.profileStore.edit { prefs ->
            val list = loadList(prefs)
            if (list.any { profileKey(it) == id }) {
                prefs[profilesJsonKey] = encodeList(list)
                prefs[activeIdKey] = id
                prefs.remove(profileJsonKey)
            }
        }
    }

    suspend fun delete(id: String) {
        context.profileStore.edit { prefs ->
            val list = loadList(prefs).filterNot { profileKey(it) == id }
            prefs[profilesJsonKey] = encodeList(list)
            prefs.remove(profileJsonKey)
            val active = prefs[activeIdKey]
            if (active == id || list.none { profileKey(it) == active }) {
                if (list.isEmpty()) {
                    prefs.remove(activeIdKey)
                } else {
                    prefs[activeIdKey] = profileKey(list.first())
                }
            }
        }
    }

    /** Clears all profiles (legacy single-profile clear). */
    suspend fun clear() {
        context.profileStore.edit {
            it.remove(profileJsonKey)
            it.remove(profilesJsonKey)
            it.remove(activeIdKey)
        }
    }

    private suspend fun upsertAndActivate(parsed: VpnProfile) {
        context.profileStore.edit { prefs ->
            val key = profileKey(parsed)
            val list = loadList(prefs).toMutableList()
            val idx = list.indexOfFirst { profileKey(it) == key }
            if (idx >= 0) list[idx] = parsed else list.add(parsed)
            prefs[profilesJsonKey] = encodeList(list)
            prefs[activeIdKey] = key
            prefs.remove(profileJsonKey)
        }
    }

    private fun loadList(prefs: Preferences): List<VpnProfile> {
        val multi = prefs[profilesJsonKey]
        if (!multi.isNullOrBlank()) {
            return decodeList(multi)
        }
        // Migrate legacy single profile_json into the list.
        val legacy = prefs[profileJsonKey] ?: return emptyList()
        val one = runCatching { VpnProfileJson.parse(legacy) }.getOrNull() ?: return emptyList()
        return listOf(one)
    }

    private fun resolveActiveId(stored: String?, list: List<VpnProfile>): String? {
        if (list.isEmpty()) return null
        if (!stored.isNullOrBlank() && list.any { profileKey(it) == stored }) return stored
        return profileKey(list.first())
    }

    companion object {
        fun profileKey(p: VpnProfile): String =
            p.deviceId.takeIf { it.isNotBlank() } ?: p.name

        fun encodeList(list: List<VpnProfile>): String {
            val out = JSONArray()
            list.forEach { out.put(org.json.JSONObject(VpnProfileJson.encode(it))) }
            return out.toString()
        }

        fun decodeList(raw: String): List<VpnProfile> = runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    val json = when (item) {
                        is String -> item
                        is org.json.JSONObject -> item.toString()
                        else -> continue
                    }
                    runCatching { VpnProfileJson.parse(json) }.getOrNull()?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }
}
