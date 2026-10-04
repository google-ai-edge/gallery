/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.data

import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.gson.Gson
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AGModelFeedbackRepo"

/**
 * Repository for packaging and submitting user feedback on model responses to the Oneplatform API.
 */
@Singleton
class ModelFeedbackRepository
@Inject
constructor(
  private val authTokenProvider: AuthTokenProvider,
  @FeedbackApiKey private val apiKey: String,
) {

  /**
   * Submits user rating and conversational metadata to the Feedback Oneplatform service.
   *
   * @param isPositive True if Thumbs Up ( SCORE5 ), false if Thumbs Down ( SCORE0 ).
   * @param description Free text user comment entered in the dialog.
   * @param selectedChips Categorical taxonomical chips chosen by the user.
   * @param userPrompt Prompt that triggered the response.
   * @param modelResponse Agent answer being rated.
   * @param modelId Unique name of the model.
   * @param modelVersion Active version identifier of the model.
   * @param temperature Generative temperature model parameter.
   * @param topK Top K model parameter.
   * @param topP Top P model parameter.
   * @param extraPsd Map of any additional key-value pairs specific to the feature (e.g.
   *   feature_card).
   * @param conversationHistory Full formatted conversation logs up to the rated agent answer.
   */
  @Suppress("AndroidLintDispatcherUsage")
  suspend fun submitFeedback(
    isPositive: Boolean,
    description: String,
    selectedChips: List<String>,
    userPrompt: String,
    modelResponse: String,
    modelId: String,
    modelVersion: String,
    temperature: String,
    topK: String,
    topP: String,
    extraPsd: Map<String, String> = emptyMap(),
    conversationHistory: String,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      try {
        // Retrieve the OAuth Bearer Token with the supportcontent scope
        val scope = "oauth2:https://www.googleapis.com/auth/supportcontent"
        val token = authTokenProvider.getAuthToken(scope)
        Log.d(TAG, "Fetched OAuth token present: ${token != null} (scope: $scope)")

        // TODO: Remove this short-circuit block once we configure an active FeedbackApiKey in
        // AppModule.kt
        if (token == null && apiKey.isEmpty()) {
          Log.w(
            TAG,
            "No OAuth token or API Key provided. Short-circuiting to simulate successful sandbox submission for local developer testing.",
          )
          return@withContext Result.success(Unit)
        }

        val score = if (isPositive) MicrofeedbackScore.SCORE5 else MicrofeedbackScore.SCORE0

        // Construct tabular metadata key-value pairs
        val psdList =
          mutableListOf(
            ModelFeedbackPsdData("model_id", modelId),
            ModelFeedbackPsdData("model_version", modelVersion),
            ModelFeedbackPsdData("temperature", temperature),
            ModelFeedbackPsdData("top_k", topK),
            ModelFeedbackPsdData("top_p", topP),
            ModelFeedbackPsdData("selected_chips", selectedChips.joinToString(",")),
            ModelFeedbackPsdData("app_version", BuildConfig.VERSION_NAME),
            ModelFeedbackPsdData("user_prompt", userPrompt),
            ModelFeedbackPsdData("model_response", modelResponse),
            ModelFeedbackPsdData("conversation_history", conversationHistory),
          )

        // Merge extra PSD fields
        for ((key, value) in extraPsd) {
          psdList.add(ModelFeedbackPsdData(key, value))
        }

        val productInfo =
          ModelFeedbackProductInfo(
            uiLanguage = "en-US",
            productVersion = BuildConfig.VERSION_NAME,
            productSpecificData = psdList,
          )

        val payload =
          ModelFeedbackDataPayload(description = description, microfeedbackScore = score)

        val request =
          ModelFeedbackRequest(
            productId = 5372309,
            bucketId = "android-agent-chat-feedback",
            productInfo = productInfo,
            feedbackData = payload,
          )

        // Staging public network REST submission endpoint
        var urlString =
          "https://stagingqual-feedback-pa-googleapis.sandbox.google.com/v1/feedback/products/5372309:submit"
        if (token == null && apiKey.isNotEmpty()) {
          urlString += "?key=$apiKey"
        }
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (token != null) {
          connection.setRequestProperty("Authorization", "Bearer $token")
        }

        val json = Gson().toJson(request)
        Log.d(TAG, "Feedback JSON Request Payload: $json")
        OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
          writer.write(json)
          writer.flush()
        }

        val responseCode = connection.responseCode
        Log.d(TAG, "Feedback submission HTTP Response Code: $responseCode")
        if (responseCode in 200..299) {
          Result.success(Unit)
        } else {
          val errorMsg = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
          Result.failure(
            Exception("Feedback submission failed with response code: $responseCode - $errorMsg")
          )
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error occurred during feedback submission", e)
        Result.failure(e)
      }
    }
}
