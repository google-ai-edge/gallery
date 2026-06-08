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

package com.google.ai.edge.gallery.runtime.aicore

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.concurrent.futures.await
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolProvider
import com.google.android.apps.aicore.client.api.AiCoreClient
import com.google.android.apps.aicore.client.api.AiCoreClientOptions
import com.google.android.apps.aicore.client.api.AiFeature
import com.google.android.apps.aicore.client.api.legion.AuthKeyType
import com.google.android.apps.aicore.client.api.legion.ConnectionStrategy
import com.google.android.apps.aicore.client.api.legion.LegionServiceOptions
import com.google.android.apps.aicore.client.api.llm.LlmMessage
import com.google.android.apps.aicore.client.api.llm.LlmRequest
import com.google.android.apps.aicore.client.api.llm.LlmService
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TAG = "PrivateInferenceModelHelper"
private const val API_KEY = "AIzaSyA0sJiKVDGTjGp0vl9x1yGr8qKaXWxbGnY" // Prototyping API key

data class PrivateInferenceChatMessage(val isUser: Boolean, val text: String)

data class PrivateInferenceModelInstance(
  val llmService: LlmService,
  val chatHistory: MutableList<PrivateInferenceChatMessage> = mutableListOf(),
  var inferenceJob: Job? = null,
)

object PrivateInferenceModelHelper : LlmModelHelper {

  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    if (coroutineScope == null) {
      Log.e(TAG, "CoroutineScope is required for PrivateInferenceModelHelper")
      onDone("Initialization failed: CoroutineScope is null")
      return
    }

    coroutineScope.launch {
      try {
        Log.d(TAG, "Initializing AiCoreClient...")
        val client = AiCoreClient.create(AiCoreClientOptions.builder(context).build())

        // Use LEGION_LLM for prototyping
        val featureId = AiFeature.Id.LEGION_LLM
        Log.d(TAG, "Fetching feature $featureId...")
        val feature = client.getFeature(featureId).await()

        if (feature == null) {
          Log.e(TAG, "LEGION_LLM feature is unavailable on this device.")
          onDone("LEGION_LLM feature is unavailable on this device.")
          return@launch
        }

        Log.d(TAG, "Creating LlmService with Legion options...")
        val optionsBuilder =
          LegionServiceOptions.builder(client)
            .setFeature(feature)
            .setAuthKey(API_KEY)
            .setAuthKeyType(AuthKeyType.API_KEY)
            .setModelId("models/dev-v3p1-s")
            .setConnectionStrategy(ConnectionStrategy.createCloseOnIdle(Duration.ofSeconds(30)))

        val llmService = LlmService.create(optionsBuilder.build())

        Log.d(TAG, "Preparing inference engine...")
        llmService.prepareInferenceEngine().await()

        model.instance = PrivateInferenceModelInstance(llmService)
        Log.d(TAG, "Initialization completed successfully")
        onDone("Feature is available (Private Inference)")
      } catch (e: Exception) {
        Log.e(TAG, "Initialization failed", e)
        onDone("Initialization failed: ${e.message}")
      }
    }
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    Log.d(TAG, "Resetting conversation")
    val instance = model.instance as? PrivateInferenceModelInstance ?: return
    instance.chatHistory.clear()
    for (msg in initialMessages) {
      instance.chatHistory.add(
        PrivateInferenceChatMessage(
          isUser = (msg.role == Role.USER),
          text = msg.contents.toString(),
        )
      )
    }
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    Log.d(TAG, "Cleaning up resources")
    val instance = model.instance as? PrivateInferenceModelInstance
    if (instance != null) {
      instance.inferenceJob?.cancel()
      // LlmService doesn't have a close/release, it relies on connection strategy
    }
    val onCleanUp = cleanUpListeners.remove(model.name)
    onCleanUp?.invoke()
    model.instance = null
    onDone()
  }

  override fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response generation")
    val instance = model.instance as? PrivateInferenceModelInstance ?: return
    instance.inferenceJob?.cancel()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? PrivateInferenceModelInstance
    if (instance == null) {
      onError("Private Inference model instance is not initialized.")
      return
    }
    if (coroutineScope == null) {
      Log.e(TAG, "CoroutineScope is required for inference")
      onError("Inference failed: CoroutineScope is null")
      return
    }

    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    instance.inferenceJob?.cancel()
    instance.inferenceJob = coroutineScope.launch {
      try {
        Log.d(TAG, "Preparing LlmRequest...")
        val requestBuilder = LlmRequest.builder()

        val messages = mutableListOf<LlmMessage>()
        // Reconstruct history
        for (msg in instance.chatHistory) {
          val role = if (msg.isUser) LlmMessage.Role.USER else LlmMessage.Role.LLM
          messages.add(LlmMessage.create(role, msg.text))
        }
        // Add current input
        messages.add(LlmMessage.create(LlmMessage.Role.USER, input))

        requestBuilder.setMessages(messages)
        val llmRequest = requestBuilder.build()

        Log.d(TAG, "Running Private Inference...")
        val llmResult = instance.llmService.runInference(llmRequest).await()

        val reply = llmResult.results.firstOrNull()
        val text = reply?.text ?: ""

        Log.d(TAG, "Inference successful, updating history")
        instance.chatHistory.add(PrivateInferenceChatMessage(isUser = true, text = input))
        instance.chatHistory.add(PrivateInferenceChatMessage(isUser = false, text = text))

        resultListener(text, true, null)
      } catch (e: CancellationException) {
        Log.i(TAG, "Inference cancelled")
      } catch (e: Exception) {
        Log.e(TAG, "Inference failed", e)
        onError("Error: ${e.message}")
      }
    }
  }
}
