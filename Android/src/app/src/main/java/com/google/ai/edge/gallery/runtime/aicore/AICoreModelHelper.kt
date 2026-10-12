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
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.data.AICoreModelPreference
import com.google.ai.edge.gallery.data.AICoreModelReleaseStage
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_OUTPUT_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.EXTRA_CONTEXT_MAX_OUTPUT_TOKENS
import com.google.ai.edge.gallery.runtime.EXTRA_CONTEXT_MULTI_TURN_VISION_SESSION
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolProvider
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "AICoreModelHelper"

/** Implemented by `@Generable` schema classes to serialize their decoded result as JSON. */
fun interface AICoreGenerableResponse {
  fun toJsonString(): String
}

const val EXTRA_CONTEXT_AICORE_GENERABLE_CLASS = "aicore_generable_class"
const val EXTRA_CONTEXT_EPHEMERAL_INFERENCE = "aicore_ephemeral_inference"

data class AICoreChatMessage(val isUser: Boolean, val text: String)

data class AICoreModelInstance(
  val generativeModel: GenerativeModel,
  val chatHistory: MutableList<AICoreChatMessage> = CopyOnWriteArrayList(),
  @Volatile var inferenceJob: Job? = null,
  @Volatile var systemInstructionText: String? = null,
  @Volatile var activeSessionImage: Bitmap? = null,
  // Whether the last runInference call opted in with EXTRA_CONTEXT_MULTI_TURN_VISION_SESSION.
  @Volatile var multiTurnVisionSession: Boolean = false,
  val fallbackScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + AICoreModelHelper.defaultDispatcher),
)

object AICoreModelHelper : LlmModelHelper {

  private val cleanUpListeners = ConcurrentHashMap<String, CleanUpListener>()
  private val initJobs = ConcurrentHashMap<String, Job>()
  private val downloadJobs = ConcurrentHashMap<String, Job>()

  internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
  internal var generativeModelProvider: (Model) -> GenerativeModel = ::getGenerativeModel

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
    enableResponseFormat: Boolean,
  ) {
    val generativeModel = generativeModelProvider(model)
    val scope = coroutineScope ?: CoroutineScope(defaultDispatcher + SupervisorJob())
    val sysText = systemInstruction?.toString()?.takeIf { it.isNotBlank() }

    initJobs.remove(model.name)?.cancel()
    val job = scope.launch {
      try {
        val status = generativeModel.checkStatus()
        when (status) {
          FeatureStatus.AVAILABLE -> {
            generativeModel.warmup()
            updateTokenLimit(model, generativeModel)
            model.instance =
              AICoreModelInstance(
                generativeModel = generativeModel,
                systemInstructionText = sysText,
              )
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
                  model.instance =
                    AICoreModelInstance(
                      generativeModel = generativeModel,
                      systemInstructionText = sysText,
                    )
                  onDone("Download completed")
                }
              }
            }
          }
          FeatureStatus.UNAVAILABLE -> {
            logAICoreAccessDetails(context)
            onDone("Feature is unavailable on this device.")
          }
          else -> {
            onDone("Unknown feature status: $status")
          }
        }
      } catch (e: CancellationException) {
        Log.i(TAG, "Initialization was cancelled for model '${model.name}'.")
      } catch (e: Exception) {
        Log.e(TAG, "Initialization failed", e)
        onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      }
    }
    initJobs[model.name] = job
    job.invokeOnCompletion { initJobs.remove(model.name, job) }
  }

  fun downloadModel(
    context: Context,
    coroutineScope: CoroutineScope? = null,
    model: Model,
    onProgress: (Long, Long) -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
  ) {
    val generativeModel = generativeModelProvider(model)
    val scope = coroutineScope ?: CoroutineScope(defaultDispatcher + SupervisorJob())

    downloadJobs.remove(model.name)?.cancel()
    val job = scope.launch {
      try {
        val status = generativeModel.checkStatus()
        when (status) {
          FeatureStatus.AVAILABLE -> {
            onDone()
          }
          FeatureStatus.DOWNLOADABLE,
          FeatureStatus.DOWNLOADING -> {
            var totalBytesToDownload = model.downloadInfo.sizeInBytes
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
            logAICoreAccessDetails(context)
            onError("AICore model is unavailable on this device.")
          }
          else -> {
            onError("Unknown feature status: $status")
          }
        }
      } catch (e: CancellationException) {
        Log.i(TAG, "Download was cancelled for model '${model.name}'.")
      } catch (e: Exception) {
        Log.e(TAG, "Download failed", e)
        onError(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      }
    }
    downloadJobs[model.name] = job
    job.invokeOnCompletion { downloadJobs.remove(model.name, job) }
  }

  suspend fun isModelDownloaded(model: Model): Boolean {
    val generativeModel = generativeModelProvider(model)

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
    initialMessages: List<Message>,
    // AICore does not support response formats, so this is ignored.
    enableResponseFormat: Boolean,
  ) {
    Log.d(TAG, "Resetting conversation for model '${model.name}'")
    val instance = model.instance as? AICoreModelInstance ?: return
    instance.inferenceJob?.cancel()
    instance.inferenceJob = null
    instance.activeSessionImage?.let { bitmap ->
      if (!bitmap.isRecycled) {
        bitmap.recycle()
      }
    }
    instance.activeSessionImage = null
    instance.systemInstructionText = systemInstruction?.toString()?.takeIf { it.isNotBlank() }
    val initialChatMessages = initialMessages.map { msg ->
      AICoreChatMessage(isUser = (msg.role == Role.USER), text = msg.contents.toString())
    }
    instance.chatHistory.clear()
    instance.chatHistory.addAll(initialChatMessages)
    Log.d(TAG, "Resetting done")
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    initJobs.remove(model.name)?.cancel()
    downloadJobs.remove(model.name)?.cancel()

    val instance = model.instance as? AICoreModelInstance
    if (instance != null) {
      try {
        instance.inferenceJob?.cancel()
        instance.inferenceJob = null
        instance.fallbackScope.cancel()
        instance.activeSessionImage?.let { bitmap ->
          if (!bitmap.isRecycled) {
            bitmap.recycle()
          }
        }
        instance.activeSessionImage = null
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
    cleanUpListeners[model.name]?.invoke()
  }

  override suspend fun countTokens(model: Model, text: String): Int? {
    val instance = model.instance as? AICoreModelInstance ?: return null
    return countPromptTokens(instance = instance, prompt = text)
  }

  override suspend fun countInputTokens(model: Model, input: String): Int? {
    val instance = model.instance as? AICoreModelInstance ?: return null
    return countPromptTokens(
      instance = instance,
      prompt =
        formatChatPrompt(
          chatHistory = instance.chatHistory.toList(),
          input = input,
          systemInstructionText = promptSystemInstruction(instance),
        ),
    )
  }

  /** Returns the system instruction to prepend, or null unless the session opted in. */
  private fun promptSystemInstruction(instance: AICoreModelInstance): String? =
    if (instance.multiTurnVisionSession) instance.systemInstructionText else null

  private suspend fun countPromptTokens(instance: AICoreModelInstance, prompt: String): Int? =
    try {
      instance.generativeModel.countTokens(generateContentRequest(TextPart(prompt)) {}).totalTokens
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to count tokens", e)
      null
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
    extraContext: Map<String, String>,
    // AICore does not support response formats, so this is ignored.
    responseJsonSchema: String?,
  ) {
    val instance = model.instance as? AICoreModelInstance
    if (instance == null) {
      onError("AICore model instance is not initialized.")
      return
    }
    val scope = coroutineScope ?: instance.fallbackScope

    cleanUpListeners[model.name] = cleanUpListener

    val isEphemeral = extraContext[EXTRA_CONTEXT_EPHEMERAL_INFERENCE]?.toBoolean() == true
    if (!isEphemeral) {
      instance.multiTurnVisionSession =
        extraContext[EXTRA_CONTEXT_MULTI_TURN_VISION_SESSION]?.toBoolean() == true
    }
    val prompt =
      if (isEphemeral) {
        input
      } else {
        formatChatPrompt(
          chatHistory = instance.chatHistory.toList(),
          input = input,
          systemInstructionText = promptSystemInstruction(instance),
        )
      }

    val temperature =
      // Clamp the temperature to the range of [0.0, 1.0] in accordance with the ML Kit API.
      model
        .getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
        .coerceIn(0.0f, 1.0f)
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val generableClassName = extraContext[EXTRA_CONTEXT_AICORE_GENERABLE_CLASS]
    val rawMaxOutputTokens =
      extraContext[EXTRA_CONTEXT_MAX_OUTPUT_TOKENS]?.toIntOrNull()?.takeIf { it > 0 }
        ?: model.getIntConfigValue(
          key = ConfigKeys.MAX_OUTPUT_TOKENS,
          defaultValue = DEFAULT_MAX_OUTPUT_TOKEN,
        )
    // AICore's native LLM features enforce max_decode_steps = 4096; cap requests using structured
    // output (or default allowlist 8192) when no explicit override was passed so AICore does not
    // reject the request.
    val maxOutputTokens =
      if (
        !generableClassName.isNullOrBlank() || extraContext[EXTRA_CONTEXT_MAX_OUTPUT_TOKENS] == null
      ) {
        rawMaxOutputTokens.coerceAtMost(4096)
      } else {
        rawMaxOutputTokens
      }

    val requestImages =
      if (isEphemeral || !instance.multiTurnVisionSession) images
      else sessionImages(instance, images)

    instance.inferenceJob?.cancel()
    instance.inferenceJob = scope.launch {
      executeRunInference(
        instance = instance,
        prompt = prompt,
        temperature = temperature,
        topK = topK,
        maxOutputTokens = maxOutputTokens,
        images = requestImages,
        input = input,
        generableClassName = generableClassName,
        isEphemeral = isEphemeral,
        resultListener = resultListener,
        cleanUpListener = cleanUpListener,
        onError = onError,
      )
    }
  }

  /**
   * Returns the images to send in a multi-turn vision session. The latest image stays attached to
   * later text-only turns so follow-up questions keep the visual context (the ML Kit API is
   * stateless). A private copy is kept so the caller may recycle its bitmap.
   */
  private fun sessionImages(instance: AICoreModelInstance, images: List<Bitmap>): List<Bitmap> {
    val newImage = images.firstOrNull()
    if (newImage != null) {
      val copy: Bitmap? =
        if (!newImage.isRecycled) newImage.copy(Bitmap.Config.ARGB_8888, false) else null
      val previous = instance.activeSessionImage
      instance.activeSessionImage = copy
      if (previous != null && previous !== copy && previous !== newImage && !previous.isRecycled) {
        previous.recycle()
      }
      return listOfNotNull(copy ?: newImage.takeIf { !it.isRecycled })
    }
    return listOfNotNull(instance.activeSessionImage?.takeIf { !it.isRecycled })
  }

  private suspend fun executeRunInference(
    instance: AICoreModelInstance,
    prompt: String,
    temperature: Float,
    topK: Int,
    maxOutputTokens: Int,
    images: List<Bitmap>,
    input: String,
    generableClassName: String? = null,
    isEphemeral: Boolean = false,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
  ) {
    var hasDeliveredDone = false
    try {
      val request =
        if (images.isNotEmpty()) {
          // ML Kit GenAI API currently only supports a single image input per request.
          generateContentRequest(ImagePart(images.first()), TextPart(prompt)) {
            this.temperature = temperature
            this.topK = topK
            this.maxOutputTokens = maxOutputTokens
          }
        } else {
          generateContentRequest(TextPart(prompt)) {
            this.temperature = temperature
            this.topK = topK
            this.maxOutputTokens = maxOutputTokens
          }
        }

      val flow = instance.generativeModel.generateContentStream(request)

      var fullResponse = ""
      var finalChunk = ""
      var doneEmitted = false
      flow.collect { response ->
        val candidate = response.candidates.firstOrNull()
        val text = candidate?.text ?: ""

        fullResponse += text
        val isDone = candidate?.finishReason != null

        if (isDone) {
          doneEmitted = true
          finalChunk = text
        } else {
          resultListener(text, false, null)
        }
      }
      if (!isEphemeral) {
        instance.chatHistory.add(AICoreChatMessage(isUser = true, text = input))
        instance.chatHistory.add(AICoreChatMessage(isUser = false, text = fullResponse))
      }
      hasDeliveredDone = true
      resultListener(if (doneEmitted) finalChunk else "", true, null)
    } catch (e: CancellationException) {
      Log.i(TAG, "The inference is cancelled.")
      if (!hasDeliveredDone) {
        cleanUpListener()
      }
      throw e
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
  internal fun getGenerativeModel(model: Model) =
    Generation.getClient(generationConfig { modelConfig = model.toAICoreModelConfig() })

  internal fun Model.toAICoreModelConfig() = modelConfig {
    releaseStage =
      if (backendSpec.aicoreReleaseStage == AICoreModelReleaseStage.PREVIEW) {
        ModelReleaseStage.PREVIEW
      } else {
        ModelReleaseStage.STABLE
      }
    preference =
      if (backendSpec.aicorePreference == AICoreModelPreference.FULL) {
        ModelPreference.FULL
      } else {
        ModelPreference.FAST
      }
  }

  private fun logAICoreAccessDetails(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      Log.w(
        TAG,
        "AICore is not accessible: Android version is ${Build.VERSION.SDK_INT}. It requires at least Android T (API 33).",
      )
      return
    }

    val allowedPackages =
      setOf(
        "com.google.ai.edge.gallery",
        "com.google.ai.edge.gallery.internal",
        "com.google.ai.edge.gallery.dev",
      )
    val packageName = context.packageName
    if (packageName !in allowedPackages) {
      Log.w(
        TAG,
        "AICore is not accessible: Package name '$packageName' is not allowlisted in AICore. " +
          "Allowed package names: $allowedPackages",
      )
    }

    val isInstalled =
      try {
        context.packageManager.getPackageInfo("com.google.android.aicore", 0)
        true
      } catch (e: PackageManager.NameNotFoundException) {
        false
      }
    if (!isInstalled) {
      Log.w(
        TAG,
        "AICore is not accessible: com.google.android.aicore is not installed on this device.",
      )
    }
  }

  internal fun formatChatPrompt(
    chatHistory: List<AICoreChatMessage>,
    input: String,
    systemInstructionText: String? = null,
  ): String = buildString {
    if (!systemInstructionText.isNullOrBlank()) {
      append("system: ").append(systemInstructionText).append("\n")
    }
    for (message in chatHistory) {
      val role = if (message.isUser) "user" else "model"
      append(role).append(": ").append(message.text).append("\n")
    }
    append("user: ").append(input).append("\nmodel: ")
  }
}
