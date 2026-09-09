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

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import java.util.Locale

/**
 * Formats and outputs on-device inference telemetry diagnostics for a specific task and model
 * session.
 *
 * @param model The active model for this conversation session.
 * @param taskId The task/capability identifier (e.g. "llm_chat", "agent_chat", "benchmark").
 */
class MetricsLogger(val model: Model, val taskId: String) {

  /**
   * Outputs structured inference telemetry metrics.
   *
   * @param metrics The complete [InferenceMetrics] protobuf container, or null if telemetry is
   *   disabled.
   */
  fun logMetrics(metrics: InferenceMetrics?) {
    if (metrics == null) return
    logToAdb(metrics)
  }

  /** Formats and outputs structured telemetry metrics to Logcat/ADB. */
  private fun logToAdb(metrics: InferenceMetrics) {
    val latency = metrics.inference.latency
    val tokens = metrics.inference.tokens
    val context = metrics.inference.context
    val memory = metrics.memory
    val battery = metrics.battery
    val llmConfig = if (metrics.metadata.hasLlmConfig()) metrics.metadata.llmConfig else null

    val samplerInfo =
      if (llmConfig != null) {
        buildString {
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
            append("spec_dec=${llmConfig.supportSpeculativeDecoding}, ")
          }
          .trimEnd(',', ' ')
      } else ""

    Log.i(
      TAG,
      "[Inference Telemetry]\n" +
        "  • Task:          $taskId (Model: ${model.name}, Accelerator: ${metrics.metadata.accelerator})\n" +
        (if (samplerInfo.isNotEmpty()) "  • Sampler:       $samplerInfo\n" else "") +
        "  • Status:        ${metrics.metadata.status.code}" +
        (if (metrics.metadata.status.errorMessage.isNotEmpty())
          " (${metrics.metadata.status.errorMessage})"
        else "") +
        "\n" +
        "  • Latency:       total=${if (latency.hasTotalLatencyMs()) "${latency.totalLatencyMs} ms" else "N/A"}, " +
        "ttft=${if (latency.hasTtftMs()) "${latency.ttftMs} ms" else "N/A"}, " +
        "decode=${if (latency.hasDecodeDurationMs()) "${latency.decodeDurationMs} ms" else "N/A"}" +
        (if (latency.hasInitDurationMs()) ", init=${latency.initDurationMs} ms" else "") +
        "\n" +
        "  • Speed:         prefill=${if (latency.hasPrefillSpeedTps()) "%.2f tps".format(Locale.US, latency.prefillSpeedTps) else "N/A"}, " +
        "decode=${if (latency.hasDecodeSpeedTps()) "%.2f tok/s".format(Locale.US, latency.decodeSpeedTps) else "N/A"}\n" +
        "  • Tokens:        prompt=${if (tokens.hasPromptTokens()) tokens.promptTokens.toString() else "N/A"}, " +
        "output=${if (tokens.hasOutputTokens()) tokens.outputTokens.toString() else "N/A"}, " +
        "turn_total=${if (tokens.hasTotalTokens()) tokens.totalTokens.toString() else "N/A"}\n" +
        "  • Context:       ${if (context.hasConsumedContextTokens()) context.consumedContextTokens.toString() else "N/A"} / " +
        "${if (context.hasMaxContextTokens()) context.maxContextTokens.toString() else "N/A"} tokens\n" +
        "  • Memory:        peak=${if (memory.hasPeakMemoryMb()) "%.2f MB".format(Locale.US, memory.peakMemoryMb) else "N/A"}, " +
        "avg=${if (memory.hasAverageMemoryMb()) "%.2f MB".format(Locale.US, memory.averageMemoryMb) else "N/A"}\n" +
        "  • Power Draw:    peak=${if (battery.hasPeakPowerMw()) "%.2f mW".format(Locale.US, battery.peakPowerMw) else "N/A"}, " +
        "avg=${if (battery.hasAveragePowerMw()) "%.2f mW".format(Locale.US, battery.averagePowerMw) else "N/A"}",
    )
  }

  companion object {
    private const val TAG = "AGMetricsLogger"
  }
}
