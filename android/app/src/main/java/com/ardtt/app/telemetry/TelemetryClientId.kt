package com.ardtt.app.telemetry

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.telemetryDataStore: DataStore<Preferences> by preferencesDataStore("ardtt_telemetry")

object TelemetryClientId {
    private val clientIdKey = stringPreferencesKey("client_id")

    fun get(context: Context): String = runBlocking {
        context.telemetryDataStore.data.map { prefs ->
            prefs[clientIdKey]
        }.first() ?: run {
            val id = "client_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            context.telemetryDataStore.edit { it[clientIdKey] = id }
            id
        }
    }

    suspend fun getAsync(context: Context): String {
        val existing = context.telemetryDataStore.data.map { it[clientIdKey] }.first()
        if (!existing.isNullOrBlank()) return existing
        val id = "client_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        context.telemetryDataStore.edit { it[clientIdKey] = id }
        return id
    }
}
