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
import com.google.ai.edge.gallery.data.markInitializationFailed
import com.google.ai.edge.gallery.data.markInitializationStarted
import com.google.ai.edge.gallery.data.markInitialized
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
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateTypedContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
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
  // The task's system prompt. It is sent with every non-ephemeral request because the ML Kit API is
  // stateless, so nothing persists between turns unless the app re-sends it.
  @Volatile var systemInstructionText: String? = null,
  @Volatile var activeSessionImage: Bitmap? = null,
  // Whether the last runInference call opted in with EXTRA_CONTEXT_MULTI_TURN_VISION_SESSION,
  // which keeps the latest image attached to follow-up text-only turns.
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
    initialMessages: List<Message>,
    coroutineScope: CoroutineScope?,
    enableResponseFormat: Boolean,
  ) {
    val scope = coroutineScope ?: CoroutineScope(defaultDispatcher + SupervisorJob())
    val systemInstructionText = systemInstruction?.toString()?.takeIf { it.isNotBlank() }
    initJobs.remove(model.name)?.cancel()
    model.markInitializationStarted()
    val job = scope.launch {
      try {
        val result =
          initializeModelInstance(
            context = context,
            model = model,
            initialMessages = initialMessages,
            systemInstructionText = systemInstructionText,
            onProgress = onDone,
          )
        when (result) {
          is InitResult.Success -> onDone(result.message)
          is InitResult.Failure -> onDone(result.error)
        }
      } catch (e: CancellationException) {
        Log.i(TAG, "Initialization was cancelled for model '${model.name}'.")
        throw e
      }
    }
    trackInitJob(model, job)
  }

  override suspend fun loadModel(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onProgress: (String) -> Unit,
  ): String? {
    if (model.instance is AICoreModelInstance) {
      Log.d(TAG, "Model '${model.name}' already loaded in AICoreModelHelper. Skipping.")
      model.markInitialized()
      return null
    }
    initJobs.remove(model.name)?.cancel()
    model.markInitializationStarted()
    // Run as a tracked child so that cleanUp() can cancel an in-flight load. Cancellation then
    // propagates to the caller as a CancellationException.
    val result = coroutineScope {
      val job =
        async(defaultDispatcher) {
          initializeModelInstance(
            context = context,
            model = model,
            initialMessages = emptyList(),
            systemInstructionText = null,
            onProgress = onProgress,
          )
        }
      trackInitJob(model, job)
      job.await()
    }
    return when (result) {
      is InitResult.Success -> null
      is InitResult.Failure -> result.error
    }
  }

  /** Final result of [initializeModelInstance]. */
  private sealed interface InitResult {
    /** [Model.instance] was set. [message] describes how it became available. */
    data class Success(val message: String) : InitResult

    data class Failure(val error: String) : InitResult
  }

  /** Registers [job] as the in-flight initialization of [model] so that [cleanUp] can cancel it. */
  private fun trackInitJob(model: Model, job: Job) {
    initJobs[model.name] = job
    job.invokeOnCompletion { initJobs.remove(model.name, job) }
  }

  /**
   * Checks AICore availability for [model], downloading it if needed, and sets [Model.instance] on
   * success. The generative model is closed unless it ends up owned by [Model.instance], including
   * when cancelled.
   *
   * @param onProgress invoked with download progress updates.
   * @throws CancellationException if cancelled.
   */
  private suspend fun initializeModelInstance(
    context: Context,
    model: Model,
    initialMessages: List<Message>,
    systemInstructionText: String?,
    onProgress: (String) -> Unit,
  ): InitResult {
    val generativeModel = generativeModelProvider(model)
    var assignedToModelInstance = false
    suspend fun assignModelInstance() {
      generativeModel.warmup()
      updateTokenLimit(model, generativeModel)
      assignedToModelInstance = true
      model.markInitialized(
        createModelInstance(
          generativeModel = generativeModel,
          initialMessages = initialMessages,
          systemInstructionText = systemInstructionText,
        )
      )
    }

    val result =
      try {
        val status = generativeModel.checkStatus()
        when (status) {
          FeatureStatus.AVAILABLE -> {
            assignModelInstance()
            InitResult.Success("Feature is available")
          }
          FeatureStatus.DOWNLOADABLE,
          FeatureStatus.DOWNLOADING -> {
            val finalStatus =
              generativeModel
                .download()
                .onEach { downloadStatus ->
                  when (downloadStatus) {
                    is DownloadStatus.DownloadStarted ->
                      onProgress("Downloading (${downloadStatus.bytesToDownload} bytes)")
                    is DownloadStatus.DownloadProgress ->
                      onProgress("Downloading (${downloadStatus.totalBytesDownloaded} bytes)")
                    else -> {}
                  }
                }
                .firstOrNull {
                  it is DownloadStatus.DownloadFailed || it is DownloadStatus.DownloadCompleted
                }
            when (finalStatus) {
              is DownloadStatus.DownloadCompleted -> {
                assignModelInstance()
                InitResult.Success("Download completed")
              }
              is DownloadStatus.DownloadFailed ->
                InitResult.Failure("Download failed: ${finalStatus.e.message}")
              else -> InitResult.Failure("Download of '${model.name}' ended without completing.")
            }
          }
          FeatureStatus.UNAVAILABLE -> {
            logAICoreAccessDetails(context)
            InitResult.Failure("Feature is unavailable on this device.")
          }
          else -> InitResult.Failure("Unknown feature status: $status")
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Initialization failed", e)
        InitResult.Failure(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      } finally {
        if (!assignedToModelInstance) {
          generativeModel.close()
        }
      }
    if (result is InitResult.Failure) {
      model.markInitializationFailed(result.error)
    }
    return result
  }

  private fun createModelInstance(
    generativeModel: GenerativeModel,
    initialMessages: List<Message>,
    systemInstructionText: String?,
  ): AICoreModelInstance =
    AICoreModelInstance(
      generativeModel = generativeModel,
      chatHistory =
        CopyOnWriteArrayList(
          initialMessages.map { msg ->
            AICoreChatMessage(isUser = (msg.role == Role.USER), text = msg.contents.toString())
          }
        ),
      systemInstructionText = systemInstructionText,
    )

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
    val closed = AtomicBoolean(false)
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
      } finally {
        if (closed.compareAndSet(false, true)) {
          generativeModel.close()
        }
      }
    }
    downloadJobs[model.name] = job
    job.invokeOnCompletion {
      downloadJobs.remove(model.name, job)
      if (closed.compareAndSet(false, true)) {
        generativeModel.close()
      }
    }
  }

  suspend fun isModelDownloaded(model: Model): Boolean {
    val generativeModel = generativeModelProvider(model)

    return try {
      generativeModel.checkStatus() == FeatureStatus.AVAILABLE
    } catch (e: Exception) {
      Log.e(TAG, "Failed to check AICore model status", e)
      false
    } finally {
      generativeModel.close()
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
    if (model.instance !is AICoreModelInstance) {
      return
    }
    try {
      resetConversationOrThrow(
        model = model,
        supportImage = supportImage,
        supportAudio = supportAudio,
        systemInstruction = systemInstruction,
        tools = tools,
        enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
        initialMessages = initialMessages,
        enableResponseFormat = enableResponseFormat,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to reset conversation", e)
    }
  }

  override fun resetConversationOrThrow(
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
    val instance =
      checkNotNull(model.instance as? AICoreModelInstance) {
        "Model '${model.name}' is not loaded."
      }
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
    return countPromptTokens(instance = instance, prompt = text, systemInstructionText = null)
  }

  override suspend fun countInputTokens(model: Model, input: String): Int? {
    val instance = model.instance as? AICoreModelInstance ?: return null
    // Count exactly what runInference would send: the system prompt plus the formatted history.
    return countPromptTokens(
      instance = instance,
      prompt = formatChatPrompt(chatHistory = instance.chatHistory.toList(), input = input),
      systemInstructionText = instance.systemInstructionText,
    )
  }

  private suspend fun countPromptTokens(
    instance: AICoreModelInstance,
    prompt: String,
    systemInstructionText: String?,
  ): Int? =
    try {
      val request =
        buildGenerateContentRequest(
          prompt = prompt,
          systemInstructionText = systemInstructionText,
          images = emptyList(),
        )
      instance.generativeModel.countTokens(request).totalTokens
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
    // Ephemeral requests are self-contained side queries (e.g. a one-off classification), so they
    // are sent verbatim without the conversation history or the task's system prompt. Every
    // regular chat turn carries both, since the ML Kit API keeps no state between calls.
    val prompt =
      if (isEphemeral) {
        input
      } else {
        formatChatPrompt(chatHistory = instance.chatHistory.toList(), input = input)
      }
    val systemInstructionText = if (isEphemeral) null else instance.systemInstructionText

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
        systemInstructionText = systemInstructionText,
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
    systemInstructionText: String?,
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
        buildGenerateContentRequest(
          prompt = prompt,
          systemInstructionText = systemInstructionText,
          images = images,
        ) {
          this.temperature = temperature
          this.topK = topK
          this.maxOutputTokens = maxOutputTokens
        }

      if (!generableClassName.isNullOrBlank()) {
        val generableKClass =
          Class.forName(generableClassName, true, Thread.currentThread().contextClassLoader).kotlin
        val typedResponse = generateTypedContent(instance.generativeModel, request, generableKClass)
        val jsonOutput =
          (typedResponse as? AICoreGenerableResponse)?.toJsonString()
            ?: typedResponse?.toString().orEmpty()
        if (!isEphemeral) {
          instance.chatHistory.add(AICoreChatMessage(isUser = true, text = input))
          instance.chatHistory.add(AICoreChatMessage(isUser = false, text = jsonOutput))
        }
        hasDeliveredDone = true
        resultListener(jsonOutput, true, null)
        return
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

  private suspend fun <T : Any> generateTypedContent(
    generativeModel: GenerativeModel,
    request: GenerateContentRequest,
    generableKClass: KClass<T>,
  ): T? =
    generativeModel
      .generateContent(generateTypedContentRequest(request, generableKClass))
      .candidates
      .firstOrNull()
      ?.response

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

  /**
   * Builds the ML Kit request for a single call.
   *
   * The system prompt travels in the request's dedicated [SystemInstruction] field rather than
   * being inlined into the prompt text. ML Kit turns that field into a real `SYSTEM` role turn in
   * the model's chat template, which Gemma follows far more reliably than a "system:" line buried
   * inside the user message. The ML Kit API is stateless, so the caller must pass the system prompt
   * on every turn that should honor it.
   */
  private fun buildGenerateContentRequest(
    prompt: String,
    systemInstructionText: String?,
    images: List<Bitmap>,
    configure: GenerateContentRequest.Builder.() -> Unit = {},
  ): GenerateContentRequest {
    val builder =
      if (images.isNotEmpty()) {
        // ML Kit GenAI API currently only supports a single image input per request.
        GenerateContentRequest.builder(ImagePart(images.first()), TextPart(prompt))
      } else {
        GenerateContentRequest.builder(TextPart(prompt))
      }
    if (!systemInstructionText.isNullOrBlank()) {
      builder.systemInstruction = SystemInstruction(systemInstructionText)
    }
    builder.configure()
    return builder.build()
  }

  /**
   * Flattens the conversation history and the new [input] into a single role-tagged transcript,
   * ending with an open `model:` turn for the model to complete. The system prompt is deliberately
   * not part of this text; see [buildGenerateContentRequest].
   */
  internal fun formatChatPrompt(chatHistory: List<AICoreChatMessage>, input: String): String =
    buildString {
      for (message in chatHistory) {
        val role = if (message.isUser) "user" else "model"
        append(role).append(": ").append(message.text).append("\n")
      }
      append("user: ").append(input).append("\nmodel: ")
    }
}
