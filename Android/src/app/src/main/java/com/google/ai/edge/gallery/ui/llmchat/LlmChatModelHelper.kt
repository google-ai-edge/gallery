/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.common.cleanUpMediapipeTaskErrorMessage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession

private const val TAG = "AGLlmChatModelHelper"

typealias ResultListener = (partialResult: String, done: Boolean) -> Unit
typealias CleanUpListener = () -> Unit

data class LlmModelInstance(val engine: LlmInference, var session: LlmInferenceSession)

object LlmChatModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  fun primeSessionWithSystemPrompt(model: Model, systemPrompt: String) {
      val instance = model.instance as? LlmModelInstance ?: return
      try {
          instance.session.addQueryChunk(systemPrompt)
          Log.d(TAG, "Session primed with system prompt.")
      } catch (e: Exception) {
          Log.e(TAG, "Error priming session with system prompt: ", e)
          // Consider how to handle this error, maybe throw or callback
      }
  }

  fun initialize(
    context: Context, model: Model, onDone: (String) -> Unit
  ) {
    // Prepare options.
    val maxTokens =
      model.getIntConfigValue(key = ConfigKey.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val topK = model.getIntConfigValue(key = ConfigKey.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKey.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKey.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKey.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    Log.d(TAG, "Initializing...")
    val preferredBackend = when (accelerator) {
      Accelerator.CPU.label -> LlmInference.Backend.CPU
      Accelerator.GPU.label -> LlmInference.Backend.GPU
      else -> LlmInference.Backend.GPU
    }
    val options =
      LlmInference.LlmInferenceOptions.builder().setModelPath(model.getPath(context = context))
        .setMaxTokens(maxTokens).setPreferredBackend(preferredBackend)
        .setMaxNumImages(if (model.llmSupportImage) 1 else 0)
        .build()

    // Create an instance of the LLM Inference task and session.
    try {
      val llmInference = LlmInference.createFromOptions(context, options)

      val session = LlmInferenceSession.createFromOptions(
        llmInference,
        LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(topK).setTopP(topP)
          .setTemperature(temperature)
          .setGraphOptions(
            GraphOptions.builder().setEnableVisionModality(model.llmSupportImage).build()
          ).build()
      )
      model.instance = LlmModelInstance(engine = llmInference, session = session)
    } catch (e: Exception) {
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
    onDone("")
  }

  fun resetSession(model: Model) {
    try {
      Log.d(TAG, "Resetting session for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      val session = instance.session
      session.close()

      val inference = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKey.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKey.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKey.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val newSession = LlmInferenceSession.createFromOptions(
        inference,
        LlmInferenceSession.LlmInferenceSessionOptions.builder().setTopK(topK).setTopP(topP)
          .setTemperature(temperature)
          .setGraphOptions(
            GraphOptions.builder().setEnableVisionModality(model.llmSupportImage).build()
          ).build()
      )
      instance.session = newSession
      Log.d(TAG, "Resetting done")
    } catch (e: Exception) {
      Log.d(TAG, "Failed to reset session", e)
    }
  }

  fun cleanUp(model: Model) {
    if (model.instance == null) {
      return
    }

    val instance = model.instance as LlmModelInstance
    try {
      // This will also close the session. Do not call session.close manually.
      instance.engine.close()
    } catch (e: Exception) {
      // ignore
    }
    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.instance = null
    Log.d(TAG, "Clean up done.")
  }

  fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    image: Bitmap? = null,
  ) {
    val instance = model.instance as LlmModelInstance

    // Set listener.
    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    // Start async inference.
    //
    // For a model that supports image modality, we need to add the text query chunk before adding
    // image.
    val session = instance.session
    session.addQueryChunk(input)
    if (image != null) {
      session.addImage(BitmapImageBuilder(image).build())
    }
    session.generateResponseAsync(resultListener)
  }
}
