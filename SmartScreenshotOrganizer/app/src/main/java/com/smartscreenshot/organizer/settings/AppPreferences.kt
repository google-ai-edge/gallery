package com.smartscreenshot.organizer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "smart_screenshot_preferences"
)

/**
 * Type-safe preference access via DataStore.
 * All reads are reactive Flows; writes are suspending.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private val KEY_INFERENCE_MODE = stringPreferencesKey("inference_mode")
        private val KEY_HTTP_ENDPOINT = stringPreferencesKey("http_endpoint")
        private val KEY_HTTP_MODEL = stringPreferencesKey("http_model")
        private val KEY_AUTO_ANALYZE = booleanPreferencesKey("auto_analyze")
        private val KEY_DARK_MODE = stringPreferencesKey("dark_mode")

        const val INFERENCE_AUTO = "auto"
        const val INFERENCE_AI_CORE = "aicore"
        const val INFERENCE_HTTP = "http"

        const val DARK_MODE_SYSTEM = "system"
        const val DARK_MODE_ON = "on"
        const val DARK_MODE_OFF = "off"

        const val DEFAULT_HTTP_ENDPOINT = "http://localhost:11434/api/generate"
        const val DEFAULT_HTTP_MODEL = "llama3.2"
    }

    // ── Inference Mode ──────────────────────────────────────────────────

    val inferenceMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_INFERENCE_MODE] ?: INFERENCE_AUTO
    }

    suspend fun setInferenceMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_INFERENCE_MODE] = mode
        }
    }

    // ── HTTP Endpoint ───────────────────────────────────────────────────

    val httpEndpoint: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HTTP_ENDPOINT] ?: DEFAULT_HTTP_ENDPOINT
    }

    suspend fun setHttpEndpoint(endpoint: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HTTP_ENDPOINT] = endpoint.trim()
        }
    }

    // ── HTTP Model Name ─────────────────────────────────────────────────

    val httpModelName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_HTTP_MODEL] ?: DEFAULT_HTTP_MODEL
    }

    suspend fun setHttpModelName(model: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HTTP_MODEL] = model.trim()
        }
    }

    // ── Auto-Analyze ────────────────────────────────────────────────────

    val autoAnalyze: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_ANALYZE] ?: true
    }

    suspend fun setAutoAnalyze(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_ANALYZE] = enabled
        }
    }

    // ── Dark Mode ───────────────────────────────────────────────────────

    val darkMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DARK_MODE] ?: DARK_MODE_SYSTEM
    }

    suspend fun setDarkMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DARK_MODE] = mode
        }
    }
}
