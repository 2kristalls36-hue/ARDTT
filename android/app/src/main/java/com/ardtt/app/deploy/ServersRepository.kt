package com.ardtt.app.deploy

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServersRepository(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "ardtt_servers",
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

    fun upsertAll(targets: List<DeployTarget>) {
        if (targets.isEmpty()) return
        val list = _servers.value.toMutableList()
        targets.forEach { target ->
            val idx = list.indexOfFirst { it.id == target.id }
            if (idx >= 0) list[idx] = target else list.add(target)
        }
        persist(list)
    }

    fun delete(id: String) {
        persist(_servers.value.filterNot { it.id == id })
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun persist(list: List<DeployTarget>) {
        val arr = JSONArray()
        list.forEach { arr.put(DeployTargetJson.encode(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
        _servers.value = list
    }

    private fun load(): List<DeployTarget> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { DeployTargetJson.parseList(raw) }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "servers_json"

        @Volatile
        private var instance: ServersRepository? = null

        fun get(context: Context): ServersRepository {
            return instance ?: synchronized(this) {
                instance ?: ServersRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
