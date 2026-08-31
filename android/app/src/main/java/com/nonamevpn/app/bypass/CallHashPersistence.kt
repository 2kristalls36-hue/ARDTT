package com.nonamevpn.app.bypass

/**
 * One call hash per device (not per profile name). Profile-scoped keys are
 * kept so older installs still resolve after an APK update or a re-import
 * that changes [VpnProfile.name].
 */
internal object CallHashPersistence {
    const val DEVICE_KEY = "hash:__device__"
    const val PREFS_NAME = "nvpn_call_hash"
    const val FALLBACK_PREFS_NAME = "nvpn_call_hash_plain"
    const val BACKUP_FILE_NAME = "call_hash.txt"

    fun profileKey(profileName: String?): String =
        "hash:${profileName?.trim().orEmpty().ifBlank { "_" }}"

    fun keysToWrite(profileName: String?): List<String> {
        val keys = linkedSetOf(DEVICE_KEY)
        val name = profileName?.trim().orEmpty()
        if (name.isNotBlank()) keys += profileKey(name)
        return keys.toList()
    }

    fun resolve(
        profileName: String?,
        stored: Map<String, String>,
        fileBackup: String? = null,
    ): String? {
        val named = stored[profileKey(profileName)]?.trim()?.takeIf { it.isNotBlank() }
        if (named != null) return named
        stored[DEVICE_KEY]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        stored.entries
            .asSequence()
            .filter { it.key.startsWith("hash:") && it.value.isNotBlank() }
            .sortedBy { it.key }
            .firstOrNull()
            ?.value
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return fileBackup?.trim()?.takeIf { it.isNotBlank() }
    }
}
