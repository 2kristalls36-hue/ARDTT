package com.nonamevpn.app.deploy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServersRepository(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "nvpn_servers",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _servers = MutableStateFlow(load())
    val servers: Flow<List<DeployTarget>> = _servers.asStateFlow()

    fun snapshot(): List<DeployTarget> = _servers.value

    fun upsert(target: DeployTarget) {
        val list = _servers.value.toMutableList()
        val idx = list.indexOfFirst { it.id == target.id }
        if (idx >= 0) list[idx] = target else list.add(target)
        persist(list)
    }

    fun delete(id: String) {
        persist(_servers.value.filterNot { it.id == id })
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(list: List<DeployTarget>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("host", t.host)
                    .put("sshPort", t.sshPort)
                    .put("sshUser", t.sshUser)
                    .put("password", t.password)
                    .put("privateKeyPem", t.privateKeyPem)
                    .put("keyPassphrase", t.keyPassphrase)
                    .put("sudoPassword", t.sudoPassword)
                    .put("publicHost", t.publicHost.ifBlank { t.host })
                    .put("directPort", t.directPort)
                    .put("bypassPort", t.bypassPort)
                    .put("lastDeployedAtMs", t.lastDeployedAtMs),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
        _servers.value = list
    }

    private fun load(): List<DeployTarget> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        DeployTarget(
                            id = o.getString("id"),
                            name = o.optString("name", o.optString("host")),
                            host = o.getString("host"),
                            sshPort = o.optInt("sshPort", 22),
                            sshUser = o.optString("sshUser", "root"),
                            password = o.optString("password", ""),
                            privateKeyPem = o.optString("privateKeyPem", ""),
                            keyPassphrase = o.optString("keyPassphrase", ""),
                            sudoPassword = o.optString("sudoPassword", ""),
                            publicHost = o.optString("publicHost", ""),
                            directPort = o.optInt("directPort", 51820),
                            bypassPort = o.optInt("bypassPort", 56003),
                            lastDeployedAtMs = o.optLong("lastDeployedAtMs", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "servers_json"
    }
}
