package com.ardtt.app.bypass

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ardtt.app.core.AppLog
import java.io.File

/**
 * One call hash per VPN user, stored only on device (never required in server profile).
 *
 * Writes go to EncryptedSharedPreferences **and** a file in [Context.getNoBackupFilesDir]
 * so the code survives APK updates even if the encrypted prefs or the profile name change.
 */
class CallHashStore(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = openPrefs(app)
    private val backupFile = File(app.noBackupFilesDir, CallHashPersistence.BACKUP_FILE_NAME)

    fun getHash(profileName: String?): String? {
        val found = CallHashPersistence.resolve(profileName, prefsMap(), readBackup())
            ?.takeIf { VkUrl.isPlausibleHash(it) }
            ?: return null
        val alreadyDevice = runCatching {
            prefs.getString(CallHashPersistence.DEVICE_KEY, null)
        }.getOrNull() == found
        val alreadyFile = readBackup()?.trim() == found
        if (!alreadyDevice || !alreadyFile) persist(profileName, found)
        return found
    }

    fun setHash(profileName: String?, hash: String) {
        val cleaned = hash.trim()
        if (!VkUrl.isPlausibleHash(cleaned)) return
        persist(profileName, cleaned)
    }

    fun clear(profileName: String? = null) {
        val edit = prefs.edit()
        runCatching {
            prefs.all.keys.filter { it.startsWith("hash:") }.forEach { edit.remove(it) }
        }
        profileName?.let { edit.remove(CallHashPersistence.profileKey(it)) }
        edit.remove(CallHashPersistence.DEVICE_KEY)
        if (!edit.commit()) {
            AppLog.w(TAG, "call hash prefs clear failed")
        }
        runCatching { if (backupFile.exists()) backupFile.delete() }
    }

    fun hasHash(profileName: String?): Boolean = !getHash(profileName).isNullOrBlank()

    private fun persist(profileName: String?, hash: String) {
        val edit = prefs.edit()
        CallHashPersistence.keysToWrite(profileName).forEach { edit.putString(it, hash) }
        if (!edit.commit()) {
            AppLog.w(TAG, "call hash prefs commit failed")
        }
        writeBackup(hash)
    }

    private fun prefsMap(): Map<String, String> =
        runCatching {
            prefs.all.mapNotNull { (key, value) ->
                (value as? String)?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())

    private fun readBackup(): String? =
        runCatching { backupFile.takeIf { it.isFile }?.readText(Charsets.UTF_8) }
            .getOrNull()

    private fun writeBackup(hash: String) {
        runCatching {
            app.noBackupFilesDir.mkdirs()
            val tmp = File(app.noBackupFilesDir, "${CallHashPersistence.BACKUP_FILE_NAME}.tmp")
            tmp.writeText(hash, Charsets.UTF_8)
            if (!tmp.renameTo(backupFile)) {
                backupFile.writeText(hash, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure {
            AppLog.w(TAG, "call hash file backup failed: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "CallHash"

        internal fun openPrefs(context: Context): SharedPreferences {
            val app = context.applicationContext
            return try {
                val master = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app,
                    CallHashPersistence.PREFS_NAME,
                    master,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (t: Throwable) {
                AppLog.w(TAG, "encrypted call-hash prefs unavailable: ${t.message}")
                app.getSharedPreferences(
                    CallHashPersistence.FALLBACK_PREFS_NAME,
                    Context.MODE_PRIVATE,
                )
            }
        }
    }
}
