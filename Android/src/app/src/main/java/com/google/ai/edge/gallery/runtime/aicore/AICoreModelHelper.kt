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
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.data.AICoreModelPreference
import com.google.ai.edge.gallery.data.AICoreModelReleaseStage
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "AICoreModelHelper"

data class AICoreChatMessage(val isUser: Boolean, val text: String)

data class AICoreModelInstance(
  val generativeModel: GenerativeModel,
  val chatHistory: MutableList<AICoreChatMessage> = mutableListOf(),
  var inferenceJob: kotlinx.coroutines.Job? = null,
)

object AICoreModelHelper : LlmModelHelper {

  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  override fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    // AICore model helper requires a coroutine scope
    if (coroutineScope == null) {
      Log.e(TAG, "CoroutineScope is required for AICoreModelHelper")
      onDone("Initialization failed: CoroutineScope is null")
      return
    }
    val generativeModel = getGenerativeModel(model)

    coroutineScope.launch {
      try {
        val status = generativeModel.checkStatus()
        when (status) {
          FeatureStatus.AVAILABLE -> {
            generativeModel.warmup()
            updateTokenLimit(model, generativeModel)
            model.instance = AICoreModelInstance(generativeModel)
            onDone("Feature is available")
          }
          FeatureStatus.DOWNLOADABLE,
          FeatureStatus.DOWNLOADING -> {
            generativeModel.download().collect { downloadStatus ->
              when (downloadStatus) {
                is DownloadStatus.DownloadStarted -> {
                  onDone("Downloading (${downloadStatus.bytesToDownload} bytes)")
                }
                is DownloadStatus.DownloadProgress -> {
                  onDone("Downloading (${downloadStatus.totalBytesDownloaded} bytes)")
                }
                is DownloadStatus.DownloadFailed -> {
                  onDone("Download failed: ${downloadStatus.e.message}")
                }
                is DownloadStatus.DownloadCompleted -> {
                  generativeModel.warmup()
                  updateTokenLimit(model, generativeModel)
                  model.instance = AICoreModelInstance(generativeModel)
                  onDone("Download completed")
                }
              }
            }
          }
          FeatureStatus.UNAVAILABLE -> {
            onDone("Feature is unavailable on this device.")
          }
          else -> {
            onDone("Unknown feature status: $status")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Initialization failed", e)
        onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      }
    }
  }

  fun downloadModel(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onProgress: (Long, Long) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
  ) {
    val generativeModel = getGenerativeModel(model)

    coroutineScope.launch {
      try {
        val status = generativeModel.checkStatus()
        when (status) {
          FeatureStatus.AVAILABLE -> {
            onDone()
          }
          FeatureStatus.DOWNLOADABLE,
          FeatureStatus.DOWNLOADING -> {
            var totalBytesToDownload = model.sizeInBytes
            generativeModel.download().collect { downloadStatus ->
              when (downloadStatus) {
                is DownloadStatus.DownloadStarted -> {
                  totalBytesToDownload = downloadStatus.bytesToDownload
                  onProgress(0L, totalBytesToDownload)
                }
                is DownloadStatus.DownloadProgress -> {
                  onProgress(downloadStatus.totalBytesDownloaded, totalBytesToDownload)
                }
                is DownloadStatus.DownloadFailed -> {
                  onError(downloadStatus.e.message ?: "Unknown download error")
                }
                is DownloadStatus.DownloadCompleted -> {
                  onDone()
                }
              }
            }
          }
          FeatureStatus.UNAVAILABLE -> {
            onError("AICore model is unavailable on this device.")
          }
          else -> {
            onError("Unknown feature status: $status")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Download failed", e)
        onError(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      }
    }
  }

  suspend fun isModelDownloaded(model: Model): Boolean {
    val generativeModel = getGenerativeModel(model)

    return try {
      generativeModel.checkStatus() == FeatureStatus.AVAILABLE
    } catch (e: Exception) {
      Log.e(TAG, "Failed to check AICore model status", e)
      false
    }
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
  ) {
    Log.d(TAG, "Resetting conversation for model '${model.name}'")
    val instance = model.instance as? AICoreModelInstance ?: return
    instance.chatHistory.clear()
    Log.d(TAG, "Resetting done")
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    val instance = model.instance as? AICoreModelInstance

    if (instance != null) {
      try {
        instance.generativeModel.close()
      } catch (e: Exception) {
        Log.e(TAG, "Failed to close the engine: ${e.message}")
      }
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) {
      onCleanUp()
    }
    model.instance = null

    // Mitigation of OOM relies on the JVM GC now that references are cleared.
    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? AICoreModelInstance ?: return
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
    val instance = model.instance as? AICoreModelInstance
    if (instance == null) {
      onError("AICore model instance is not initialized.")
      return
    }
    if (coroutineScope == null) {
      Log.e(TAG, "CoroutineScope is required for AICoreModelHelper inference")
      onError("Inference failed: CoroutineScope is null")
      return
    }

    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val prompt = formatChatPrompt(instance.chatHistory, input)

    val temperature =
      // Clamp the temperature to the range of [0.0, 1.0] in accordance with the ML Kit API.
      model
        .getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
        .coerceIn(0.0f, 1.0f)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)

    instance.inferenceJob?.cancel()

    instance.inferenceJob = coroutineScope.launch {
      executeRunInference(
        instance = instance,
        prompt = prompt,
        temperature = temperature,
        topK = topK,
        images = images,
        input = input,
        resultListener = resultListener,
        onError = onError,
      )
    }
  }

  private suspend fun executeRunInference(
    instance: AICoreModelInstance,
    prompt: String,
    temperature: Float,
    topK: Int,
    images: List<Bitmap>,
    input: String,
    resultListener: ResultListener,
    onError: (message: String) -> Unit,
  ) {
    try {
      val request =
        if (images.isNotEmpty()) {
          // ML Kit GenAI API currently only supports a single image input per request.
          generateContentRequest(
            com.google.mlkit.genai.prompt.ImagePart(images.first()),
            TextPart(prompt),
          ) {
            this.temperature = temperature
            this.topK = topK
          }
        } else {
          generateContentRequest(TextPart(prompt)) {
            this.temperature = temperature
            this.topK = topK
          }
        }
      val flow = instance.generativeModel.generateContentStream(request)

      var fullResponse = ""
      flow.collect { response ->
        val candidate = response.candidates.firstOrNull()
        val text = candidate?.text ?: ""

        fullResponse += text
        val isDone = candidate?.finishReason != null

        if (isDone) {
          instance.chatHistory.add(AICoreChatMessage(isUser = true, text = input))
          instance.chatHistory.add(AICoreChatMessage(isUser = false, text = fullResponse))
          resultListener(text, true, null)
        } else {
          resultListener(text, false, null)
        }
      }
    } catch (e: CancellationException) {
      Log.i(TAG, "The inference is cancelled.")
      // Skip invoking resultListener to avoid ambiguous cancellation state
    } catch (e: Exception) {
      Log.e(TAG, "onError", e)
      onError("Error: ${e.message}")
    }
  }

  // Update the token limit of the model based on the value from AICore.
  private suspend fun updateTokenLimit(model: Model, generativeModel: GenerativeModel) {
    val tokenLimit =
      try {
        generativeModel.getTokenLimit()
      } catch (e: Exception) {
        -1
      }
    if (tokenLimit > 0) {
      val configMap = model.configValues.toMutableMap()
      configMap[ConfigKeys.MAX_TOKENS.label] = tokenLimit.toString()
      model.configValues = configMap
    }
  }

  // Get the generative model from AICore based on the model config.
  private fun getGenerativeModel(model: Model) =
    Generation.getClient(generationConfig { modelConfig = model.toAICoreModelConfig() })

  private fun Model.toAICoreModelConfig() = modelConfig {
    releaseStage =
      if (aicoreReleaseStage == AICoreModelReleaseStage.PREVIEW) {
        ModelReleaseStage.PREVIEW
      } else {
        ModelReleaseStage.STABLE
      }
    preference =
      if (aicorePreference == AICoreModelPreference.FULL) {
        ModelPreference.FULL
      } else {
        ModelPreference.FAST
      }
  }

  private fun formatChatPrompt(chatHistory: List<AICoreChatMessage>, input: String): String =
    buildString {
      for (message in chatHistory) {
        val role = if (message.isUser) "user" else "model"
        append(role).append(": ").append(message.text).append("\n")
      }
      append("user: ").append(input).append("\nmodel: ")
    }
}
