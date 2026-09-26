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

package com.google.ai.edge.gallery.common.metrics

import android.os.Bundle
import android.util.Log
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.InferenceMetricsParam
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.proto.LlmConfig
import java.util.Locale
import kotlin.time.Duration

private const val TAG = "AGMetricsLogger"

/**
 * Inference telemetry logger that provides:
 *
 * 1. Logging to device log for debugging.
 * 2. Logging to Firebase Analytics for analysis.
 */
internal class MetricsLogger(internal val model: Model, internal val taskId: String) {

  internal fun formatSamplerConfig(llmConfig: LlmConfig): String {
    return buildString {
        if (llmConfig.defaultTopk > 0) append("top_k=${llmConfig.defaultTopk}, ")
        if (llmConfig.defaultTopp > 0f) {
          append("top_p=%.2f, ".format(Locale.US, llmConfig.defaultTopp))
        }
        if (llmConfig.defaultTemperature > 0f) {
          append("temp=%.2f, ".format(Locale.US, llmConfig.defaultTemperature))
        }
        if (llmConfig.defaultMaxTokens > 0) {
          append("max_tokens=${llmConfig.defaultMaxTokens}, ")
        }
        append("thinking=${llmConfig.supportThinking}, ")
        append("speculative_decoding=${llmConfig.supportSpeculativeDecoding}, ")
        if (llmConfig.supportImage) append("image=true, ")
        if (llmConfig.supportAudio) append("audio=true, ")
      }
      .trimEnd(',', ' ')
  }

  private fun formatStatus(status: InferenceStatus): String {
    var message: String = status.code.name
    val hasCancellation =
      status.cancellationReason != CancellationReason.CANCELLATION_REASON_UNSPECIFIED
    val hasError = status.errorMessage.isNotEmpty()
    when {
      hasCancellation && hasError ->
        message += " (${status.cancellationReason.name}, ${status.errorMessage})"
      hasCancellation -> message += " (${status.cancellationReason.name})"
      hasError -> message += " (${status.errorMessage})"
    }
    return message
  }

  private fun formatLatency(latency: LatencyMetrics): String {
    return buildString {
      append(
        "total=${if (latency.hasTotalLatencyMs()) "${latency.totalLatencyMs} ms" else "N/A"}, "
      )
      append("ttft=${if (latency.hasTtftMs()) "${latency.ttftMs} ms" else "N/A"}, ")
      append(
        "decode=${if (latency.hasDecodeDurationMs()) "${latency.decodeDurationMs} ms" else "N/A"}, "
      )
      if (latency.hasInitDurationMs()) {
        append("init=${latency.initDurationMs} ms, ")
      }
      append(
        "prefill=${if (latency.hasPrefillSpeedTps()) "%.2f tps".format(Locale.US, latency.prefillSpeedTps) else "N/A"}, "
      )
      append(
        "decode_speed=${if (latency.hasDecodeSpeedTps()) "%.2f tps".format(Locale.US, latency.decodeSpeedTps) else "N/A"}"
      )
    }
  }

  private fun formatTokens(tokens: TurnTokenMetrics): String {
    return buildString {
      append("prompt=${if (tokens.hasPromptTokens()) tokens.promptTokens.toString() else "N/A"}, ")
      append("output=${if (tokens.hasOutputTokens()) tokens.outputTokens.toString() else "N/A"}, ")
      append("turn_total=${if (tokens.hasTotalTokens()) tokens.totalTokens.toString() else "N/A"}")
    }
  }

  private fun formatContext(tokenContext: ContextMetrics): String {
    return buildString {
      append(
        "consumed=${if (tokenContext.hasConsumedContextTokens()) tokenContext.consumedContextTokens.toString() else "N/A"}, "
      )
      append(
        "max=${if (tokenContext.hasMaxContextTokens()) tokenContext.maxContextTokens.toString() else "N/A"}"
      )
    }
  }

  private fun formatMemory(memory: MemoryMetrics): String {
    return buildString {
      append(
        "peak=${if (memory.hasPeakMemoryMb()) "%.2f MB".format(Locale.US, memory.peakMemoryMb) else "N/A"}, "
      )
      append(
        "avg=${if (memory.hasAverageMemoryMb()) "%.2f MB".format(Locale.US, memory.averageMemoryMb) else "N/A"}"
      )
    }
  }

  private fun Bundle.putCommonMetadata() {
    putString(InferenceMetricsParam.MODEL_NAME.key, model.name)
    val modelVersion = model.downloadInfo.version
    if (modelVersion.isNotEmpty()) {
      putString(InferenceMetricsParam.MODEL_VERSION.key, modelVersion)
    }
    putString(InferenceMetricsParam.TASK_ID.key, taskId)
  }

  /**
   * Dispatches model initialization telemetry to Logcat and Firebase Analytics based on the current
   * [Model.initStatusFlow] state (`Initialized` or `Failed`) and active [Model] configuration.
   */
  fun logModelInitialization() {
    val (status, initDuration, errorMessage) =
      when (val initStatus = model.initStatusFlow.value) {
        is Model.InitializationStatus.Initialized ->
          Triple(InferenceStatus.Code.SUCCESS, initStatus.duration, null)
        is Model.InitializationStatus.Failed ->
          Triple(InferenceStatus.Code.ERROR, initStatus.duration, initStatus.error.message)
        else -> return
      }
    val llmConfig = model.toLlmConfig()
    logModelInitToLogcat(initDuration, status, llmConfig, errorMessage)
    logModelInitToFirebase(initDuration, status, llmConfig)
  }

  /** Logs model initialization telemetry to Logcat for debugging. */
  internal fun logModelInitToLogcat(
    initDuration: Duration,
    status: InferenceStatus.Code,
    llmConfig: LlmConfig,
    errorMessage: String? = null,
  ) {
    val accelerator = model.currentAccelerator?.name ?: "N/A"
    Log.d(
      TAG,
      "[Model Init Telemetry]\n" +
        "  • Model:         ${model.name}\n" +
        "  • Accelerator:   $accelerator\n" +
        "  • Task:          ${taskId}\n" +
        "  • Status:        ${status.name}${if (!errorMessage.isNullOrEmpty()) " ($errorMessage)" else ""}\n" +
        "  • Duration:      ${initDuration.inWholeMilliseconds} ms\n" +
        "  • LlmConfig:     ${formatSamplerConfig(llmConfig)}",
    )
  }

  /** Uploads privacy-compliant model initialization telemetry to Firebase Analytics. */
  internal fun logModelInitToFirebase(
    initDuration: Duration,
    status: InferenceStatus.Code,
    llmConfig: LlmConfig,
  ) {
    val analytics = firebaseAnalytics ?: return
    val bundle = buildModelInitBundle(initDuration, status, llmConfig)
    try {
      analytics.logEvent(GalleryEvent.MODEL_INITIALIZE.id, bundle)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to log model initialization to Firebase Analytics", e)
    }
  }

  /**
   * Constructs a strongly typed, privacy-preserving [Bundle] containing model initialization
   * duration and sampler configuration for Firebase Analytics.
   */
  internal fun buildModelInitBundle(
    initDuration: Duration,
    status: InferenceStatus.Code,
    llmConfig: LlmConfig,
  ): Bundle {
    return Bundle().apply {
      putCommonMetadata()
      val accelerator = model.currentAccelerator?.name ?: ""
      if (accelerator.isNotEmpty()) {
        putString(InferenceMetricsParam.ACCELERATOR.key, accelerator)
      }
      putString(InferenceMetricsParam.STATUS.key, status.name)
      putLong(InferenceMetricsParam.INIT_DURATION_MS.key, initDuration.inWholeMilliseconds)
      val samplerConfigStr = formatSamplerConfig(llmConfig)
      if (samplerConfigStr.isNotEmpty()) {
        putString(InferenceMetricsParam.SAMPLER_CONFIG.key, samplerConfigStr)
      }
    }
  }

  /** Dispatches turn-end inference metrics to Logcat and Firebase Analytics. */
  fun logMetrics(metrics: InferenceMetrics) {
    logToLogcat(metrics)
    logToFirebase(metrics)
  }

  /** Logs the turn-end inference metrics to Logcat for debugging. */
  internal fun logToLogcat(metrics: InferenceMetrics) {
    Log.d(
      TAG,
      "[Inference Telemetry]\n" +
        "  • Task:          ${taskId} (Model: ${model.name}, Accelerator: ${metrics.metadata.accelerator})\n" +
        "  • Session:       ${if (metrics.metadata.hasSessionId()) metrics.metadata.sessionId else "N/A"}\n" +
        "  • Turn:          ${if (metrics.metadata.hasTurnIndex()) metrics.metadata.turnIndex.toString() else "N/A"}\n" +
        "  • Status:        ${if (metrics.metadata.hasStatus()) formatStatus(metrics.metadata.status) else "N/A"}\n" +
        "  • Latency:       ${if (metrics.inference.hasLatency()) formatLatency(metrics.inference.latency) else "N/A"}\n" +
        "  • Tokens:        ${if (metrics.inference.hasTokens()) formatTokens(metrics.inference.tokens) else "N/A"}\n" +
        "  • Context:       ${if (metrics.inference.hasContext()) formatContext(metrics.inference.context) else "N/A"}\n" +
        "  • Memory:        ${if (metrics.hasMemory()) formatMemory(metrics.memory) else "N/A"}",
    )
  }

  /** Uploads privacy-compliant, non-PII inference performance metrics to Firebase Analytics. */
  internal fun logToFirebase(metrics: InferenceMetrics) {
    val analytics = firebaseAnalytics ?: return
    val bundle = buildFirebaseBundle(metrics)
    try {
      analytics.logEvent(GalleryEvent.INFERENCE_METRICS.id, bundle)
    } catch (e: Exception) {
      Log.w(TAG, "Failed to log inference metrics to Firebase Analytics", e)
    }
  }

  /**
   * Constructs a strongly typed, privacy-preserving [Bundle] containing numerical telemetry and
   * non-PII diagnostic metadata for Firebase Analytics.
   */
  internal fun buildFirebaseBundle(metrics: InferenceMetrics): Bundle {
    return Bundle().apply {
      putCommonMetadata()
      if (metrics.metadata.accelerator.isNotEmpty()) {
        putString(InferenceMetricsParam.ACCELERATOR.key, metrics.metadata.accelerator)
      }
      putString(InferenceMetricsParam.STATUS.key, metrics.metadata.status.code.name)

      // Log sanitized non-PII enum error or cancellation code if turn did not succeed.
      if (metrics.metadata.status.code != InferenceStatus.Code.SUCCESS) {
        val errorCode =
          if (
            metrics.metadata.status.cancellationReason !=
              CancellationReason.CANCELLATION_REASON_UNSPECIFIED
          ) {
            metrics.metadata.status.cancellationReason.name
          } else {
            metrics.metadata.status.code.name
          }
        putString(InferenceMetricsParam.ERROR_CODE.key, errorCode)
      }

      if (metrics.metadata.hasTurnIndex() && metrics.metadata.turnIndex >= 0) {
        putInt(InferenceMetricsParam.TURN_INDEX.key, metrics.metadata.turnIndex)
      }

      // Latency & Speed metrics.
      val latency = metrics.inference.latency
      if (latency.hasTotalLatencyMs()) {
        putLong(InferenceMetricsParam.TOTAL_LATENCY_MS.key, latency.totalLatencyMs)
      }
      if (latency.hasTtftMs()) {
        putLong(InferenceMetricsParam.TTFT_MS.key, latency.ttftMs)
      }
      if (latency.hasDecodeDurationMs()) {
        putLong(InferenceMetricsParam.DECODE_DURATION_MS.key, latency.decodeDurationMs)
      }
      if (latency.hasPrefillSpeedTps()) {
        putDouble(InferenceMetricsParam.PREFILL_SPEED_TPS.key, latency.prefillSpeedTps.toDouble())
      }
      if (latency.hasDecodeSpeedTps()) {
        putDouble(InferenceMetricsParam.DECODE_SPEED_TPS.key, latency.decodeSpeedTps.toDouble())
      }

      // Token counts.
      val tokens = metrics.inference.tokens
      if (tokens.hasPromptTokens()) {
        putInt(InferenceMetricsParam.PROMPT_TOKENS.key, tokens.promptTokens)
      }
      if (tokens.hasOutputTokens()) {
        putInt(InferenceMetricsParam.OUTPUT_TOKENS.key, tokens.outputTokens)
      }
      if (tokens.hasTotalTokens()) {
        putInt(InferenceMetricsParam.TURN_TOTAL_TOKENS.key, tokens.totalTokens)
      }

      // Context KV-cache token metrics.
      val tokenContext = metrics.inference.context
      if (tokenContext.hasConsumedContextTokens()) {
        putInt(
          InferenceMetricsParam.CONSUMED_CONTEXT_TOKENS.key,
          tokenContext.consumedContextTokens,
        )
      }
      if (tokenContext.hasMaxContextTokens()) {
        putInt(InferenceMetricsParam.MAX_CONTEXT_TOKENS.key, tokenContext.maxContextTokens)
      }

      // Hardware sensor metrics (memory).
      val memory = metrics.memory
      if (memory.hasPeakMemoryMb()) {
        putDouble(InferenceMetricsParam.PEAK_MEMORY_MB.key, memory.peakMemoryMb.toDouble())
      }
      if (memory.hasAverageMemoryMb()) {
        putDouble(InferenceMetricsParam.AVG_MEMORY_MB.key, memory.averageMemoryMb.toDouble())
      }
    }
  }
}
