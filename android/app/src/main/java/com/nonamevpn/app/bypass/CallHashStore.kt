package com.nonamevpn.app.bypass

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * One call hash per VPN user, stored only on device (never required in server profile).
 */
class CallHashStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val master = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            "nvpn_call_hash",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getHash(profileName: String): String? =
        prefs.getString(key(profileName), null)?.takeIf { it.isNotBlank() }

    fun setHash(profileName: String, hash: String) {
        prefs.edit().putString(key(profileName), hash.trim()).apply()
    }

    fun clear(profileName: String) {
        prefs.edit().remove(key(profileName)).apply()
    }

    fun hasHash(profileName: String): Boolean = !getHash(profileName).isNullOrBlank()

    private fun key(profileName: String) = "hash:${profileName.ifBlank { "_" }}"
}
