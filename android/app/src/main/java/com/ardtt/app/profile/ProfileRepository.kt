package com.ardtt.app.profile

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

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore("ardtt_profile")

class ProfileRepository(private val context: Context) {
    private val profileJsonKey = stringPreferencesKey("profile_json")
    private val catalogJsonKey = stringPreferencesKey("profile_catalog_json")

    val catalog: Flow<ProfileCatalog> = context.profileStore.data.map { prefs ->
        prefs[catalogJsonKey]?.let { raw ->
            runCatching { ProfileCatalogJson.parse(raw) }.getOrNull()
        } ?: prefs[profileJsonKey]?.let { raw ->
            runCatching { ProfileCatalogJson.fromLegacyProfile(raw) }.getOrNull()
        } ?: ProfileCatalog()
    }

    val profile: Flow<VpnProfile?> = catalog.map { it.active }

    suspend fun snapshot(): ProfileCatalog = catalog.first()

    suspend fun importJson(
        raw: String,
        folder: String = DEFAULT_PROFILE_FOLDER,
        activate: Boolean = true,
    ): VpnProfile {
        val imported = importMany(raw, folder, activate)
        return imported.last()
    }

    suspend fun importMany(
        raw: String,
        folder: String = DEFAULT_PROFILE_FOLDER,
        activate: Boolean = true,
    ): List<VpnProfile> {
        val parsed = VpnProfileJson.parseMany(raw)
        parsed.forEachIndexed { index, profile ->
            val makeActive = activate && index == parsed.lastIndex
            upsert(profile, folder, activate = makeActive)
        }
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

    suspend fun clear() {
        save(ProfileCatalog())
    }

    private suspend fun mutate(block: (ProfileCatalog) -> ProfileCatalog) {
        save(block(snapshot()))
    }

    private suspend fun save(catalog: ProfileCatalog) {
        val encoded = ProfileCatalogJson.encode(catalog)
        val activeRaw = catalog.active?.let(VpnProfileJson::encode)
        context.profileStore.edit { prefs ->
            prefs[catalogJsonKey] = encoded
            if (activeRaw != null) prefs[profileJsonKey] = activeRaw else prefs.remove(profileJsonKey)
        }
    }

    companion object {
        fun newLocalId(): String = "local-${UUID.randomUUID().toString().take(8)}"
    }
}
