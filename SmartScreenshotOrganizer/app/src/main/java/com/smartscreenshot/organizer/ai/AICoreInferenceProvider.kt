package com.smartscreenshot.organizer.ai

import android.content.Context
import android.util.Log
import com.smartscreenshot.organizer.data.model.AnalysisResult
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device inference via Android AI Core (Gemini Nano).
 *
 * AI Core availability depends on:
 * - Device hardware (Tensor G3+ or equivalent)
 * - Android version (14+ with AI Core module)
 * - Google Play Services AI Core component installed
 *
 * When AI Core is unavailable, isAvailable() returns false and the
 * InferenceProviderFactory falls back to HttpInferenceProvider.
 *
 * NOTE: The AI Core API surface is still evolving. This implementation
 * uses reflection to check availability at runtime, avoiding hard compile
 * dependencies on the AI Core SDK which may not be in all build environments.
 */
@Singleton
class AICoreInferenceProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi
) : InferenceProvider {

    companion object {
        private const val TAG = "AICoreInference"
        private const val AI_CORE_CLASS = "com.google.android.gms.ai.generativemodel.GenerativeModel"
    }

    private val analysisAdapter = moshi.adapter(AnalysisResult::class.java)

    // Cache availability check result to avoid repeated reflection
    @Volatile
    private var availabilityChecked = false

    @Volatile
    private var isAvailableCached = false

    override suspend fun analyze(context: ScreenshotContext): AnalysisResult? {
        if (!isAvailable()) return null

        return try {
            val prompt = PromptTemplates.buildAnalysisPrompt(context)
            val response = invokeAiCore(prompt)
            parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "AI Core inference failed", e)
            null
        }
    }

    override suspend fun isAvailable(): Boolean {
        if (availabilityChecked) return isAvailableCached

        isAvailableCached = try {
            // Check if AI Core classes are available via reflection
            Class.forName(AI_CORE_CLASS)
            // If class exists, check if the service is actually ready
            checkAiCoreServiceReady()
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "AI Core SDK not found on this device")
            false
        } catch (e: Exception) {
            Log.w(TAG, "AI Core availability check failed", e)
            false
        }

        availabilityChecked = true
        return isAvailableCached
    }

    override fun providerName(): String = "AI Core (Gemini Nano)"

    /**
     * Invokes AI Core for text generation.
     *
     * This is a placeholder for the actual AI Core API call which requires
     * the Google AI Edge SDK. The real implementation would look like:
     *
     * ```
     * val model = GenerativeModel.Builder(context)
     *     .setModelName("gemini-nano")
     *     .build()
     * val response = model.generateContent(prompt)
     * return response.text ?: ""
     * ```
     */
    private suspend fun invokeAiCore(prompt: String): String {
        // In production, this calls the actual AI Core GenerativeModel API.
        // The implementation depends on the specific AI Core SDK version available.
        // For now, we return empty to trigger fallback to HTTP provider.
        Log.d(TAG, "AI Core invocation requested (prompt length: ${prompt.length})")

        try {
            val modelClass = Class.forName(AI_CORE_CLASS)
            val builderClass = Class.forName("$AI_CORE_CLASS\$Builder")
            val builder = builderClass.getConstructor(android.content.Context::class.java)
                .newInstance(this.context)

            // Set model name
            val setModelMethod = builderClass.getMethod("setModelName", String::class.java)
            setModelMethod.invoke(builder, "gemini-nano")

            // Build the model
            val buildMethod = builderClass.getMethod("build")
            val model = buildMethod.invoke(builder)

            // Generate content
            val generateMethod = modelClass.getMethod("generateContent", String::class.java)
            val response = generateMethod.invoke(model, prompt)

            // Extract text from response
            val getTextMethod = response!!.javaClass.getMethod("getText")
            return getTextMethod.invoke(response) as? String ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "AI Core reflection call failed, device may not support Gemini Nano", e)
            return ""
        }
    }

    private fun checkAiCoreServiceReady(): Boolean {
        return try {
            val pmInfo = this.context.packageManager
                .getPackageInfo("com.google.android.aicore", 0)
            pmInfo != null
        } catch (e: Exception) {
            false
        }
    }

    private fun parseResponse(response: String): AnalysisResult? {
        if (response.isBlank()) return null

        return try {
            // Strip markdown code fences if present
            val cleaned = response
                .replace("```json", "")
                .replace("```", "")
                .trim()

            analysisAdapter.fromJson(cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse AI Core response as JSON", e)
            null
        }
    }
}
