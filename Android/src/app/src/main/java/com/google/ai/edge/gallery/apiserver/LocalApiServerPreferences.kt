package com.google.ai.edge.gallery.apiserver

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val ENABLED_KEY = booleanPreferencesKey("local_api_server_enabled")
private val PORT_KEY = intPreferencesKey("local_api_server_port")
private val TOKEN_KEY = stringPreferencesKey("local_api_server_token")

const val DEFAULT_LOCAL_API_SERVER_PORT = 8080

/** Reads and writes the on/off toggle, port, and bearer token for the local API server. */
@Singleton
class LocalApiServerPreferences @Inject constructor(private val dataStore: DataStore<Preferences>) {
  suspend fun readEnabled(): Boolean = dataStore.data.first()[ENABLED_KEY] ?: false

  suspend fun saveEnabled(enabled: Boolean) {
    dataStore.edit { it[ENABLED_KEY] = enabled }
  }

  suspend fun readPort(): Int = dataStore.data.first()[PORT_KEY] ?: DEFAULT_LOCAL_API_SERVER_PORT

  suspend fun savePort(port: Int) {
    dataStore.edit { it[PORT_KEY] = port }
  }

  /** Returns the existing token, or generates and persists a new one if none exists yet. */
  suspend fun readOrCreateToken(): String {
    val existing = dataStore.data.first()[TOKEN_KEY]
    if (existing != null) return existing
    return regenerateToken()
  }

  /** Generates a new random token, persists it, and returns it. */
  suspend fun regenerateToken(): String {
    val token = UUID.randomUUID().toString()
    dataStore.edit { it[TOKEN_KEY] = token }
    return token
  }
}
