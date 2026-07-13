package com.gagmate.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gagmate_settings")

/**
 * Manages local app preferences via DataStore.
 * Stores ggboard connection details and user preferences.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val HOST_KEY = stringPreferencesKey("ggboard_host")
        private val PORT_KEY = stringPreferencesKey("ggboard_port")
        private const val DEFAULT_HOST = "192.168.0.186"
        private const val DEFAULT_PORT = "80"
    }

    val host: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[HOST_KEY] ?: DEFAULT_HOST
    }

    val port: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[PORT_KEY] ?: DEFAULT_PORT
    }

    suspend fun saveHost(host: String) {
        context.dataStore.edit { prefs ->
            prefs[HOST_KEY] = host
        }
    }

    suspend fun savePort(port: String) {
        context.dataStore.edit { prefs ->
            prefs[PORT_KEY] = port
        }
    }

    suspend fun saveSettings(host: String, port: String) {
        context.dataStore.edit { prefs ->
            prefs[HOST_KEY] = host
            prefs[PORT_KEY] = port
        }
    }

    fun getConnectionUrl(): String {
        return "http://$DEFAULT_HOST:$DEFAULT_PORT/"
    }
}
