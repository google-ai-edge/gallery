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

package com.google.ai.edge.gallery.services.semanticretrieval

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.litertlm.ModelInfo
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.BaseOptions.DelegateOptions.NpuOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.File

private const val TAG = "AGOnDeviceEmbedder"

/** Interface for on-device embedding inference models (e.g. MobileCLIP, EmbeddingGemma, LiteRT). */
interface OnDeviceEmbedder : AutoCloseable {
  /** Checks if the embedding model resources are available on the device. */
  fun isAvailable(): Boolean

  /** Initializes the embedding engine and loads the model. */
  fun initialize() {}

  /** Generates a vector embedding for the given image data. */
  fun generateImageEmbedding(imageData: ByteArray): FloatArray?

  /** Generates a vector embedding for the given list of image data. */
  fun generateImagesEmbedding(imageDataList: List<ByteArray>): FloatArray?

  /** Generates a vector embedding for the given text. */
  fun generateTextEmbedding(text: String): FloatArray?

  /**
   * Whether the underlying model declares support for audio input.
   *
   * Implementations that cannot embed audio keep the default, so callers can gate on real model
   * capability instead of guessing from the model's configuration.
   */
  fun supportsAudio(): Boolean = false

  /**
   * Generates a single vector embedding for [parts], preserving their order.
   *
   * The default implementation embeds only the image parts, so implementations without multimodal
   * support degrade to the frame-only behavior rather than failing.
   */
  fun generateMultimodalEmbedding(parts: List<EmbedPart>): FloatArray? =
    generateImagesEmbedding(parts.filterIsInstance<EmbedPart.Image>().map { it.imageData })

  override fun close() {}
}

/**
 * MediaPipe Universal Embedder on-device embedding model wrapper.
 *
 * Runs multimodal (vision and text) embedding inference via MediaPipe's [UniversalEmbedder].
 *
 * Note: This embedder generates embeddings synchronously and will block the thread it is running
 * on.
 */
class UniversalOnDeviceEmbedder(
  val modelPath: String,
  val accelerator: Accelerator = Accelerator.GPU,
  context: Context? = null,
  val maxInputSequenceLength: Int? = null,
  private val visionTokenBudget: Int? = null,
  val visionAccelerator: Accelerator? = null,
  val audioAccelerator: Accelerator? = null,
) : OnDeviceEmbedder, AutoCloseable {

  private val context: Context? = context?.applicationContext ?: context
  private val lock = Any()

  @Volatile private var universalEmbedder: UniversalEmbedder? = null
  @Volatile private var isCpuFallback = false
  @Volatile private var hasReadModelLimits = false
  @Volatile
  var isClosed: Boolean = false
    private set

  @Volatile private var modelMaxVisionBudget: Int? = null
  @Volatile private var modelMaxContextTokens: Int? = null
  @Volatile private var modelSupportsVision: Boolean = true
  @Volatile private var modelSupportsAudio: Boolean = false

  /**
   * The effective max input sequence length.
   *
   * Clamped to the maximum context tokens supported by the underlying model's [ModelInfo].
   */
  val effectiveMaxInputSequenceLength: Int?
    get() {
      if (!hasReadModelLimits && isAvailable()) {
        readModelLimits()
      }
      val modelMax = modelMaxContextTokens
      return when {
        maxInputSequenceLength != null && modelMax != null && modelMax > 0 ->
          minOf(maxInputSequenceLength, modelMax)
        maxInputSequenceLength != null -> maxInputSequenceLength
        else -> modelMax?.takeIf { it > 0 }
      }
    }

  /**
   * The effective vision token budget.
   *
   * Computed as min(visionTokenBudget, modelMaxVisionBudget) where modelMaxVisionBudget is read
   * from the underlying model's [ModelInfo].
   */
  val effectiveVisionTokenBudget: Int?
    get() {
      if (!hasReadModelLimits && isAvailable()) {
        readModelLimits()
      }
      if (!modelSupportsVision) {
        return null
      }
      val modelBudget = modelMaxVisionBudget
      return when {
        visionTokenBudget != null && modelBudget != null && modelBudget > 0 ->
          minOf(visionTokenBudget, modelBudget)
        visionTokenBudget != null -> visionTokenBudget
        else -> modelBudget?.takeIf { it > 0 }
      }
    }

  init {
    validatePreconditions()
    if (isAvailable()) {
      validateAgainstModelInfo()
    }
  }

  @VisibleForTesting
  fun setModelLimitsForTesting(
    contextTokens: Int,
    visionBudget: Int,
    supportsAudio: Boolean = false,
  ) {
    synchronized(lock) {
      hasReadModelLimits = true
      if (contextTokens > 0) {
        modelMaxContextTokens = contextTokens
      }
      if (visionBudget > 0) {
        modelMaxVisionBudget = visionBudget
        modelSupportsVision = true
      } else {
        modelSupportsVision = false
      }
      modelSupportsAudio = supportsAudio
    }
  }

  private fun validatePreconditions() {
    if (maxInputSequenceLength != null) {
      require(maxInputSequenceLength > 0) {
        "maxInputSequenceLength must be positive, got $maxInputSequenceLength"
      }
    }
    if (visionTokenBudget != null) {
      require(visionTokenBudget > 0) {
        "visionTokenBudget must be positive, got $visionTokenBudget"
      }
    }
  }

  override fun initialize() {
    ensureInitialized()
  }

  /**
   * Reads and caches the model limits (maxContextTokens, maxVisionTokenBudget) and the supported
   * input modalities from [ModelInfo] at [modelPath].
   */
  private fun readModelLimits() {
    synchronized(lock) {
      if (hasReadModelLimits) return
      hasReadModelLimits = true
      if (!File(modelPath).exists()) return
      try {
        ModelInfo.from(modelPath).use { modelInfo ->
          val contextTokens = modelInfo.maxContextTokens()
          val visionBudget = modelInfo.maxVisionTokenBudget()
          if (contextTokens > 0) {
            modelMaxContextTokens = contextTokens
          }
          if (visionBudget > 0) {
            modelMaxVisionBudget = visionBudget
            modelSupportsVision = true
          } else {
            modelSupportsVision = false
          }
          // Read from the bundle rather than inferred from the configured accelerators: audio
          // stripped builds of an otherwise audio capable model ship to some hardware tiers.
          modelSupportsAudio = modelInfo.inputModalities().audio
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to read model info for validation", e)
      }
    }
  }

  /**
   * Validates parameters and caches the model-supported limits from [ModelInfo].
   *
   * If [maxInputSequenceLength] exceeds the model's supported limit, or if the requested vision
   * token budget exceeds the model's vision budget, effective values are capped using minOf without
   * throwing an exception.
   */
  fun validateAgainstModelInfo() {
    validatePreconditions()
    if (isAvailable()) {
      readModelLimits()
    }

    val modelContext = modelMaxContextTokens
    if (
      modelContext != null &&
        modelContext > 0 &&
        maxInputSequenceLength != null &&
        maxInputSequenceLength > modelContext
    ) {
      Log.i(
        TAG,
        "maxInputSequenceLength ($maxInputSequenceLength) exceeds model supported limit ($modelContext); capping to $modelContext",
      )
    }

    val modelVision = modelMaxVisionBudget
    if (
      modelVision != null &&
        modelVision > 0 &&
        visionTokenBudget != null &&
        visionTokenBudget > modelVision
    ) {
      Log.i(
        TAG,
        "visionTokenBudget ($visionTokenBudget) exceeds model supported limit ($modelVision); capping to $modelVision",
      )
    }
  }

  private fun createDelegate(accelerator: Accelerator): Delegate {
    return when (accelerator) {
      Accelerator.NPU,
      Accelerator.TPU -> Delegate.NPU
      Accelerator.GPU -> Delegate.GPU
      Accelerator.CPU -> Delegate.CPU
      else -> Delegate.GPU
    }
  }

  private fun ensureInitialized() {
    if (isClosed) return
    if (universalEmbedder == null && isAvailable()) {
      synchronized(lock) {
        if (!isClosed && universalEmbedder == null) {
          initializeEngine()
        }
      }
    }
  }

  private fun createBaseOptions(delegate: Delegate): BaseOptions {
    val builder = BaseOptions.builder().setModelAssetPath(modelPath).setDelegate(delegate)
    if (delegate == Delegate.NPU) {
      val nativeLibDir = context?.applicationInfo?.nativeLibraryDir ?: ""
      builder.setDelegateOptions(
        NpuOptions.builder()
          .setDispatchLibraryDirectory(nativeLibDir)
          .setCompilerPluginLibraryDirectory(nativeLibDir)
          .build()
      )
    }
    return builder.build()
  }

  @VisibleForTesting
  fun createUniversalEmbedderOptions(delegate: Delegate): UniversalEmbedderOptions {
    val baseOptions = createBaseOptions(delegate)
    val optionsBuilder =
      UniversalEmbedderOptions.builder().setBaseOptions(baseOptions).setL2Normalize(true)
    val effectiveSeqLength = effectiveMaxInputSequenceLength
    if (effectiveSeqLength != null && effectiveSeqLength > 0) {
      optionsBuilder.setMaxInputLength(effectiveSeqLength)
    }
    val effectiveBudget = effectiveVisionTokenBudget
    if (effectiveBudget != null && effectiveBudget > 0) {
      optionsBuilder.setVisionTokensPerImage(effectiveBudget)
    }

    when (delegate) {
      Delegate.NPU -> {
        val effectiveVisionDelegate = visionAccelerator?.let { createDelegate(it) } ?: Delegate.NPU
        optionsBuilder.setTextDelegate(Delegate.NPU)
        optionsBuilder.setVisionDelegate(effectiveVisionDelegate)
        // Force the audio encoder to run on CPU for performance and accuracy purposes for now,
        // ignoring the audioAccelerator setting in the allowlist.
        optionsBuilder.setAudioDelegate(Delegate.CPU)
      }
      Delegate.CPU -> {
        optionsBuilder.setTextDelegate(Delegate.CPU)
        optionsBuilder.setVisionDelegate(Delegate.CPU)
        optionsBuilder.setAudioDelegate(Delegate.CPU)
      }
      else -> {
        // textDelegate is intentionally omitted: when not explicitly set, UniversalEmbedder
        // defaults to baseOptions.delegate (e.g. GPU).
        visionAccelerator?.let { optionsBuilder.setVisionDelegate(createDelegate(it)) }
        // Force the audio encoder to run on CPU for performance and accuracy purposes for now,
        // ignoring the audioAccelerator setting in the allowlist.
        optionsBuilder.setAudioDelegate(Delegate.CPU)
      }
    }

    return optionsBuilder.build()
  }

  /**
   * Returns a [Context] wrapper that overrides [Context.getCacheDir] to isolate LiteRT-LM's model
   * cache directory per signature configuration (vision token budget and max sequence length).
   *
   * This prevents different features (e.g. Smart Album with 70 vision tokens / 256 max tokens vs.
   * Video Moments Finder with 70 vision tokens / 1024 max tokens) from colliding on the same
   * compiled GPU program and weight cache files in the default cache directory.
   */
  @VisibleForTesting
  fun getIsolatedContext(): Context? {
    val ctx = context ?: return null
    val effectiveVisionTokens = effectiveVisionTokenBudget ?: 0
    val effectiveMaxInputLength = effectiveMaxInputSequenceLength ?: 0
    return object : ContextWrapper(ctx) {
      override fun getCacheDir(): File {
        val baseCacheDir = super.getCacheDir() ?: File(ctx.filesDir, "cache")
        val isolatedCacheDir =
          File(baseCacheDir, "cache_v${effectiveVisionTokens}_t${effectiveMaxInputLength}")
        if (!isolatedCacheDir.exists()) {
          isolatedCacheDir.mkdirs()
        }
        return isolatedCacheDir
      }
    }
  }

  private fun initializeEngine() {
    val ctx = getIsolatedContext() ?: return
    val delegate = createDelegate(accelerator)
    try {
      Log.d(TAG, "[UniversalOnDeviceEmbedder] Initializing UniversalEmbedder on $delegate...")
      val options = createUniversalEmbedderOptions(delegate)
      universalEmbedder = UniversalEmbedder.createFromOptions(ctx, options)
      isCpuFallback = (delegate == Delegate.CPU)
    } catch (e: Throwable) {
      if (delegate == Delegate.CPU) {
        Log.e(
          TAG,
          "[UniversalOnDeviceEmbedder] CPU initialization failed; UniversalEmbedder unavailable",
          e,
        )
        universalEmbedder = null
        throw e
      }
      Log.w(
        TAG,
        "[UniversalOnDeviceEmbedder] $delegate initialization failed; falling back to CPU",
        e,
      )
      fallbackToCpu()
    }
  }

  private fun fallbackToCpu() {
    val ctx = getIsolatedContext() ?: return
    try {
      val options = createUniversalEmbedderOptions(Delegate.CPU)
      universalEmbedder = UniversalEmbedder.createFromOptions(ctx, options)
      isCpuFallback = true
      Log.i(TAG, "[UniversalOnDeviceEmbedder] Successfully recovered on CPU")
    } catch (cpuException: Throwable) {
      Log.e(
        TAG,
        "[UniversalOnDeviceEmbedder] CPU fallback failed; UniversalEmbedder unavailable",
        cpuException,
      )
      universalEmbedder = null
      throw cpuException
    }
  }

  private fun recoverFromInferenceFailure() {
    synchronized(lock) {
      if (isCpuFallback) {
        Log.e(
          TAG,
          "[UniversalOnDeviceEmbedder] Inference failed on CPU fallback; cannot recover further",
        )
        return
      }
      Log.w(
        TAG,
        "[UniversalOnDeviceEmbedder] Inference failed on $accelerator; attempting recovery with CPU",
      )
      try {
        universalEmbedder?.close()
      } catch (closeEx: Throwable) {
        Log.w(TAG, "Error closing failing embedder: ${closeEx.message}")
      }
      universalEmbedder = null
      try {
        fallbackToCpu()
      } catch (e: Throwable) {
        // Already logged in fallbackToCpu.
      }
    }
  }

  override fun isAvailable(): Boolean = File(modelPath).exists()

  override fun supportsAudio(): Boolean {
    if (!hasReadModelLimits && isAvailable()) {
      readModelLimits()
    }
    return modelSupportsAudio
  }

  override fun generateImageEmbedding(imageData: ByteArray): FloatArray? {
    return generateImagesEmbedding(listOf(imageData))
  }

  /** Generates a vector embedding for the given list of images using UniversalEmbedder. */
  override fun generateImagesEmbedding(imageDataList: List<ByteArray>): FloatArray? {
    if (isClosed || imageDataList.isEmpty() || !isAvailable()) return null
    return embedContentWithRecovery(imageDataList.map { it as Any }, modality = "vision")
  }

  /**
   * Generates a single vector embedding for [parts] in the order they are listed.
   *
   * The engine inserts the `<SOI>`/`<EOI>` and `<SOA>`/`<EOA>` markers around each part itself, so
   * the caller only has to supply the parts in the order the model should see them.
   */
  override fun generateMultimodalEmbedding(parts: List<EmbedPart>): FloatArray? {
    if (isClosed || parts.isEmpty() || !isAvailable()) return null
    return embedContentWithRecovery(parts.map { it.toEmbedderContent() }, modality = "multimodal")
  }

  /**
   * Converts a part to the type [UniversalEmbedder.embedContent] maps to the matching modality.
   *
   * Audio must be handed over as an [AudioData]: the embedder interprets a raw `ByteArray` as an
   * image regardless of what it actually holds.
   */
  @VisibleForTesting
  internal fun EmbedPart.toEmbedderContent(): Any =
    when (this) {
      is EmbedPart.Text -> text
      is EmbedPart.Image -> imageData
      is EmbedPart.Audio -> toAudioData()
    }

  /**
   * Runs [UniversalEmbedder.embedContent] over [content], retrying once on CPU if the configured
   * accelerator fails. [modality] only labels the logs.
   */
  private fun embedContentWithRecovery(content: List<Any>, modality: String): FloatArray? {
    return synchronized(lock) {
      if (isClosed) return null
      ensureInitialized()
      val embedder = universalEmbedder ?: return null
      Log.d(
        TAG,
        "[UniversalOnDeviceEmbedder] Running $modality embedding inference on $modelPath...",
      )
      try {
        val result = embedder.embedContent(content)
        result.embeddings().firstOrNull()?.floatEmbedding()
      } catch (e: Throwable) {
        Log.e(
          TAG,
          "[UniversalOnDeviceEmbedder] $modality embedding inference failed; attempting recovery",
          e,
        )
        recoverFromInferenceFailure()
        val retryEmbedder = universalEmbedder
        if (retryEmbedder != null) {
          try {
            val retryResult = retryEmbedder.embedContent(content)
            retryResult.embeddings().firstOrNull()?.floatEmbedding()
          } catch (retryEx: Throwable) {
            Log.e(TAG, "[UniversalOnDeviceEmbedder] Recovery $modality inference failed", retryEx)
            null
          }
        } else {
          null
        }
      }
    }
  }

  override fun generateTextEmbedding(text: String): FloatArray? {
    if (isClosed || !isAvailable()) return null
    return synchronized(lock) {
      if (isClosed) return null
      ensureInitialized()
      val embedder = universalEmbedder ?: return null
      Log.d(TAG, "[UniversalOnDeviceEmbedder] Running text embedding inference on $modelPath...")
      try {
        val result = embedder.embedText(text)
        result.embeddings().firstOrNull()?.floatEmbedding()
      } catch (e: Throwable) {
        Log.e(
          TAG,
          "[UniversalOnDeviceEmbedder] Text embedding inference failed; attempting recovery",
          e,
        )
        recoverFromInferenceFailure()
        val retryEmbedder = universalEmbedder
        if (retryEmbedder != null) {
          try {
            val retryResult = retryEmbedder.embedText(text)
            retryResult.embeddings().firstOrNull()?.floatEmbedding()
          } catch (retryEx: Throwable) {
            Log.e(TAG, "[UniversalOnDeviceEmbedder] Recovery text inference failed", retryEx)
            null
          }
        } else {
          null
        }
      }
    }
  }

  override fun close() {
    synchronized(lock) {
      isClosed = true
      try {
        universalEmbedder?.close()
      } catch (e: Throwable) {
        Log.w(TAG, "Error closing UniversalEmbedder: ${e.message}")
      }
      universalEmbedder = null
    }
  }
}

/** Converts an [EmbedPart.Audio] clip into a MediaPipe [AudioData] buffer. */
internal fun EmbedPart.Audio.toAudioData(): AudioData {
  val format =
    AudioData.AudioDataFormat.builder()
      .setNumOfChannels(1)
      .setSampleRate(sampleRateHz.toFloat())
      .build()
  // Sized to exactly the clip length: a larger ring buffer would rotate the samples and
  // pad the clip with leading silence.
  return AudioData.create(format, pcm.size).apply { load(pcm) }
}
