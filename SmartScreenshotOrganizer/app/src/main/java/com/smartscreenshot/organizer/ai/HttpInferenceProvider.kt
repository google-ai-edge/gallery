package com.smartscreenshot.organizer.ai

import android.util.Log
import com.smartscreenshot.organizer.data.model.AnalysisResult
import com.smartscreenshot.organizer.settings.AppPreferences
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP-based inference provider for local LLM servers.
 *
 * Supports the Ollama API format by default (POST /api/generate).
 * The endpoint is configurable in app settings.
 *
 * Communication is asynchronous with:
 * - 30s connect timeout
 * - 120s read timeout (LLM generation can be slow on CPU)
 * - Automatic retry not implemented here; WorkManager handles retries
 */
@Singleton
class HttpInferenceProvider @Inject constructor(
    private val httpClient: OkHttpClient,
    private val preferences: AppPreferences,
    private val moshi: Moshi
) : InferenceProvider {

    companion object {
        private const val TAG = "HttpInference"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val HEALTH_CHECK_TIMEOUT_MS = 5000L
    }

    private val analysisAdapter = moshi.adapter(AnalysisResult::class.java)

    override suspend fun analyze(context: ScreenshotContext): AnalysisResult? {
        return withContext(Dispatchers.IO) {
            try {
                val endpoint = preferences.httpEndpoint.first()
                val modelName = preferences.httpModelName.first()
                val prompt = PromptTemplates.buildAnalysisPrompt(context)
                val response = sendRequest(endpoint, prompt, modelName)
                parseResponse(response)
            } catch (e: Exception) {
                Log.e(TAG, "HTTP inference failed", e)
                null
            }
        }
    }

    override suspend fun isAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val endpoint = preferences.httpEndpoint.first()
                val baseUrl = endpoint.substringBeforeLast("/")

                val request = Request.Builder()
                    .url(baseUrl)
                    .head()
                    .build()

                val result = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) {
                    val response = httpClient.newCall(request).execute()
                    response.use { it.isSuccessful || it.code == 404 }
                }

                result ?: false
            } catch (e: Exception) {
                Log.d(TAG, "HTTP endpoint not reachable: ${e.message}")
                false
            }
        }
    }

    override fun providerName(): String = "HTTP Server (Local LLM)"

    private fun sendRequest(endpoint: String, prompt: String, modelName: String): String {
        // Build Ollama-compatible request body
        val requestJson = JSONObject().apply {
            put("model", modelName)
            put("prompt", prompt)
            put("stream", false)
            put("format", "json")
            put("options", JSONObject().apply {
                put("temperature", 0.3)
                put("num_predict", 1024)
            })
        }

        val requestBody = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()

        return response.use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP inference returned ${resp.code}: ${resp.message}")
            }

            val body = resp.body?.string()
                ?: throw RuntimeException("Empty response body from inference server")

            // Ollama wraps the response in a JSON envelope
            try {
                val envelope = JSONObject(body)
                envelope.optString("response", body)
            } catch (e: Exception) {
                body
            }
        }
    }

    private fun parseResponse(response: String): AnalysisResult? {
        if (response.isBlank()) return null

        return try {
            val cleaned = response
                .replace("```json", "")
                .replace("```", "")
                .trim()

            analysisAdapter.fromJson(cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse HTTP inference response", e)
            null
        }
    }
}
