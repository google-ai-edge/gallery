package com.smartscreenshot.organizer.ai

import android.util.Log
import com.smartscreenshot.organizer.settings.AppPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the best available inference provider based on device capabilities
 * and user preferences.
 *
 * Selection order:
 * 1. User-forced mode (if set in preferences)
 * 2. AI Core (on-device, zero latency, full privacy)
 * 3. HTTP server (local network LLM)
 * 4. null (no provider available)
 */
@Singleton
class InferenceProviderFactory @Inject constructor(
    private val aiCoreProvider: AICoreInferenceProvider,
    private val httpProvider: HttpInferenceProvider,
    private val preferences: AppPreferences
) {

    companion object {
        private const val TAG = "InferenceFactory"
    }

    /**
     * Returns the best available provider, or null if none is available.
     * Checks user preference first, then auto-selects.
     */
    suspend fun getProvider(): InferenceProvider? {
        val mode = preferences.inferenceMode.first()

        return when (mode) {
            AppPreferences.INFERENCE_AI_CORE -> {
                if (aiCoreProvider.isAvailable()) {
                    Log.d(TAG, "Using forced AI Core provider")
                    aiCoreProvider
                } else {
                    Log.w(TAG, "AI Core forced but not available")
                    null
                }
            }

            AppPreferences.INFERENCE_HTTP -> {
                if (httpProvider.isAvailable()) {
                    Log.d(TAG, "Using forced HTTP provider")
                    httpProvider
                } else {
                    Log.w(TAG, "HTTP provider forced but not reachable")
                    null
                }
            }

            else -> autoSelect()
        }
    }

    /**
     * Auto-selects the best provider by availability.
     * AI Core is preferred over HTTP because it's fully on-device.
     */
    private suspend fun autoSelect(): InferenceProvider? {
        if (aiCoreProvider.isAvailable()) {
            Log.d(TAG, "Auto-selected: AI Core")
            return aiCoreProvider
        }

        if (httpProvider.isAvailable()) {
            Log.d(TAG, "Auto-selected: HTTP (AI Core not available)")
            return httpProvider
        }

        Log.w(TAG, "No inference provider available")
        return null
    }

    /** Returns all registered providers with their availability status. */
    suspend fun getAvailableProviders(): List<Pair<InferenceProvider, Boolean>> {
        return listOf(
            aiCoreProvider to aiCoreProvider.isAvailable(),
            httpProvider to httpProvider.isAvailable()
        )
    }
}
