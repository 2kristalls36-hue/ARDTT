package com.ardtt.lab

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class LabTarget(
    val host: String,
    val sshPort: Int,
    val user: String,
    val password: String,
    val remotePort: Int,
)

class LabSettings(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ardtt_lab",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences("ardtt_lab_plain", Context.MODE_PRIVATE)
    }

    fun load(): LabTarget = LabTarget(
        host = prefs.getString(KEY_HOST, LabProtocol.DEFAULT_SSH_HOST) ?: LabProtocol.DEFAULT_SSH_HOST,
        sshPort = prefs.getInt(KEY_PORT, LabProtocol.DEFAULT_SSH_PORT),
        user = prefs.getString(KEY_USER, LabProtocol.DEFAULT_SSH_USER) ?: LabProtocol.DEFAULT_SSH_USER,
        password = prefs.getString(KEY_PASSWORD, "") ?: "",
        remotePort = prefs.getInt(KEY_REMOTE, LabProtocol.DEFAULT_REMOTE_PORT),
    )

    fun save(target: LabTarget) {
        prefs.edit()
            .putString(KEY_HOST, target.host.trim())
            .putInt(KEY_PORT, target.sshPort.coerceIn(1, 65535))
            .putString(KEY_USER, target.user.trim())
            .putString(KEY_PASSWORD, target.password)
            .putInt(KEY_REMOTE, target.remotePort.coerceIn(1, 65535))
            .apply()
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "ssh_port"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMOTE = "remote_port"
    }
}
