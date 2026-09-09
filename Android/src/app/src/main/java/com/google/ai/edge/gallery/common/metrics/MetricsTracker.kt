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

import android.content.Context
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.supportModelBenchmark
import com.google.ai.edge.gallery.proto.LlmConfig
import com.google.ai.edge.gallery.proto.llmConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Configuration options for inference metrics tracking and periodic system resource monitors. */
data class MetricsTrackerConfig(
  // Whether to enable periodic system resource monitors (memory and power).
  val enableSystemSampling: Boolean = true,
  // Sampling interval for the memory monitor.
  val memorySamplingInterval: Duration = PeriodicSampler.DEFAULT_SAMPLING_INTERVAL,
  // Sampling interval for the power monitor.
  val powerSamplingInterval: Duration = PeriodicSampler.DEFAULT_SAMPLING_INTERVAL,
)

/**
 * Tracks, aggregates, and computes inference telemetry and hardware performance metrics for an
 * active model conversation session.
 *
 * ## Lifecycle
 * A [MetricsTracker] instance is designed to be created when a [Model] instance is initialized, and
 * destroyed when that model is unloaded, reset, or recreated with updated configurations. During
 * the tracker's lifetime, model metadata and LLM sampler configurations remain constant.
 */
interface MetricsTracker {
  /** The active model associated with this conversation tracker instance. */
  val model: Model

  /** The task or feature identifier (e.g. "llm_chat", "agent_chat", "benchmark"). */
  val taskId: String

  /**
   * Starts tracking an inference turn using a [ConversationSession] abstraction.
   *
   * @param session Active session abstraction for ground-truth telemetry.
   * @throws IllegalStateException if a previous turn was not ended, or if [session] is dead.
   */
  fun startTurn(session: ConversationSession)

  /**
   * Universal streaming token callback.
   *
   * Increments estimated output tokens and records Time To First Token (TTFT) on the first token.
   *
   * @param tokenText The generated text piece for this token callback.
   * @param thinkingText Optional thinking/reasoning text emitted by thinking models.
   * @throws IllegalStateException if called outside of an active turn.
   */
  fun onNewToken(tokenText: String, thinkingText: String? = null)

  /**
   * Cancels the active inference turn (e.g. when manually cancelled by the user).
   *
   * Halts hardware monitors, records partial streamed token counts, finalizes the turn status with
   * [InferenceStatus.Code.CANCELLED] and the provided [reason], logs to ADB, and returns the
   * resulting [InferenceMetrics] (or `null` if benchmark tracking is disabled).
   *
   * @param reason The reason describing why the turn was cancelled.
   * @param customMessage Optional descriptive message describing the cancellation.
   * @return The finalized [InferenceMetrics] with CANCELLED status, or null if benchmark disabled.
   */
  fun cancelTurn(
    reason: CancellationReason = CancellationReason.USER_CANCELLED,
    customMessage: String? = null,
  ): InferenceMetrics?

  /**
   * Resets active metrics and counters when a conversation session is cleared.
   *
   * Resets cumulative session context tokens and clears hardware sensor histories (e.g. when the
   * user clears chat history or starts a new conversation).
   */
  fun resetSession()

  /**
   * Finalizes the active inference turn, queries ground-truth engine metrics from the
   * [ConversationSession], samples hardware monitors, logs to ADB, and returns [InferenceMetrics].
   *
   * @param statusCode Result status code for the inference turn.
   * @param errorMessage Optional error message if inference failed.
   * @return The finalized, immutable [InferenceMetrics] protobuf, or null if benchmark disabled.
   * @throws IllegalStateException if called when no turn is in progress.
   */
  fun endTurn(
    statusCode: InferenceStatus.Code = InferenceStatus.Code.SUCCESS,
    errorMessage: String? = null,
  ): InferenceMetrics?

  companion object {
    /**
     * Factory function for creating a [MetricsTracker].
     *
     * Instantiates [LitertlmMetricsTracker] if [model] supports benchmark telemetry, or a
     * lightweight [NoOpMetricsTracker] otherwise.
     */
    fun create(
      context: Context,
      model: Model,
      taskId: String,
      ioDispatcher: CoroutineDispatcher,
      config: MetricsTrackerConfig = MetricsTrackerConfig(),
      scope: CoroutineScope = CoroutineScope(ioDispatcher + SupervisorJob()),
      timeSource: TimeSource = TimeSource.Monotonic,
    ): MetricsTracker {

      val enableInferenceMetrics = false

      if (!enableInferenceMetrics) {
        // Returns a no-op tracker if benchmark telemetry is disabled or the model does not support
        // benchmark mode.
        return NoOpMetricsTracker(model = model, taskId = taskId)
      }

      return LitertlmMetricsTracker(
        context = context,
        model = model,
        taskId = taskId,
        config = config,
        scope = scope,
        ioDispatcher = ioDispatcher,
        timeSource = timeSource,
      )
    }
  }
}

/**
 * No-op implementation of [MetricsTracker] used when benchmark telemetry is disabled or unsupported
 * for [model].
 */
class NoOpMetricsTracker(override val model: Model, override val taskId: String) : MetricsTracker {

  override fun startTurn(session: ConversationSession) {}

  override fun onNewToken(tokenText: String, thinkingText: String?) {}

  override fun cancelTurn(reason: CancellationReason, customMessage: String?): InferenceMetrics? =
    null

  override fun resetSession() {}

  override fun endTurn(statusCode: InferenceStatus.Code, errorMessage: String?): InferenceMetrics? =
    null
}

/**
 * LiteRT-LM implementation of [MetricsTracker], coordinating latency tracking, periodic hardware
 * sensor monitoring, and turn-end metrics aggregation for models running on the LiteRT-LM runtime
 * with benchmark telemetry enabled.
 *
 * @throws IllegalArgumentException if [model] does not support benchmark mode.
 */
class LitertlmMetricsTracker
internal constructor(
  private val context: Context,
  override val model: Model,
  override val taskId: String,
  private val scope: CoroutineScope,
  ioDispatcher: CoroutineDispatcher,
  timeSource: TimeSource = TimeSource.Monotonic,
  private val config: MetricsTrackerConfig = MetricsTrackerConfig(),
  private val metricsLogger: MetricsLogger = MetricsLogger(model = model, taskId = taskId),
  private val memoryMonitor: PeriodicSensorMonitor<MemoryMetrics>? =
    if (config.enableSystemSampling) {
      MemoryMonitor(samplingInterval = config.memorySamplingInterval, dispatcher = ioDispatcher)
    } else {
      null
    },
  private val powerMonitor: PeriodicSensorMonitor<BatteryMetrics>? =
    if (config.enableSystemSampling) {
      PowerMonitor(
        context = context,
        samplingInterval = config.powerSamplingInterval,
        dispatcher = ioDispatcher,
      )
    } else {
      null
    },
  private val inferenceTracker: LitertlmInferenceMetricsTracker =
    LitertlmInferenceMetricsTracker(model = model, timeSource = timeSource),
) : MetricsTracker {

  init {
    require(model.supportModelBenchmark) {
      "Cannot instantiate LitertlmMetricsTracker for '${model.name}': benchmark mode is not supported or enabled."
    }
  }

  // Immutable metadata captured at construction time.
  private val baseMetadata: InferenceMetadata = buildBaseMetadata()

  private val isTurnActive = AtomicBoolean(false)

  override fun startTurn(session: ConversationSession) {
    // Step 1: Validate session & conversation preconditions.
    check(session.isAlive) {
      "Cannot start turn for '${model.name}': conversation is not alive (already closed or uninitialized)."
    }
    check(isTurnActive.compareAndSet(false, true)) {
      "Cannot start turn: previous turn for model '${model.name}' is still in progress."
    }

    // Step 2: Start inference turn tracker and hardware sensor monitors if enabled.
    inferenceTracker.startTurn(session = session)
    if (memoryMonitor != null) memoryMonitor.start(scope)
    if (powerMonitor != null) powerMonitor.start(scope)
  }

  override fun onNewToken(tokenText: String, thinkingText: String?) {
    inferenceTracker.onNewToken(tokenText = tokenText, thinkingText = thinkingText)
  }

  override fun cancelTurn(reason: CancellationReason, customMessage: String?): InferenceMetrics =
    endTurnInternal(
      statusCode = InferenceStatus.Code.CANCELLED,
      cancellationReason = reason,
      errorMessage = customMessage,
    )

  override fun resetSession() {
    isTurnActive.set(false)
    inferenceTracker.resetSession()
    if (memoryMonitor != null) memoryMonitor.reset()
    if (powerMonitor != null) powerMonitor.reset()
  }

  override fun endTurn(statusCode: InferenceStatus.Code, errorMessage: String?): InferenceMetrics =
    endTurnInternal(
      statusCode = statusCode,
      cancellationReason = CancellationReason.CANCELLATION_REASON_UNSPECIFIED,
      errorMessage = errorMessage,
    )

  private fun endTurnInternal(
    statusCode: InferenceStatus.Code,
    cancellationReason: CancellationReason = CancellationReason.CANCELLATION_REASON_UNSPECIFIED,
    errorMessage: String? = null,
  ): InferenceMetrics {
    // Step 1: Validate turn state.
    check(isTurnActive.compareAndSet(true, false)) {
      "Cannot end turn: No active turn in progress for model '${model.name}'."
    }

    // Step 2: Finalize sensor measurements (process memory and battery power) if enabled.
    val memoryMetrics =
      if (memoryMonitor != null) memoryMonitor.stop() else MemoryMetrics.getDefaultInstance()
    val batteryMetrics =
      if (powerMonitor != null) powerMonitor.stop() else BatteryMetrics.getDefaultInstance()

    // Step 3: Reconcile latency, token counts, and KV-cache context metrics from engine.
    val turnMetrics = inferenceTracker.endTurn(status = statusCode)

    // Step 4: Construct final immutable InferenceMetrics protobuf.
    val finalMetrics = inferenceMetrics {
      this.metadata = buildMetadata(statusCode, cancellationReason, errorMessage)
      this.inference = turnMetrics
      this.memory = memoryMetrics
      this.battery = batteryMetrics
    }

    // Step 5: Dispatch logs to ADB and Firebase Analytics.
    metricsLogger.logMetrics(finalMetrics)
    return finalMetrics
  }

  private fun buildBaseMetadata(): InferenceMetadata {
    return inferenceMetadata {
      this.modelName = model.name
      this.accelerator =
        model.getStringConfigValue(
          ConfigKeys.ACCELERATOR,
          model.accelerators.firstOrNull()?.name ?: "",
        )
      this.taskId = this@LitertlmMetricsTracker.taskId
      this.llmConfig = model.toLlmConfig()
    }
  }

  private fun buildMetadata(
    statusCode: InferenceStatus.Code,
    cancellationReason: CancellationReason = CancellationReason.CANCELLATION_REASON_UNSPECIFIED,
    errorMessage: String? = null,
  ): InferenceMetadata = baseMetadata.copy {
    this.status = inferenceStatus {
      this.code = statusCode
      if (cancellationReason != CancellationReason.CANCELLATION_REASON_UNSPECIFIED) {
        this.cancellationReason = cancellationReason
      }
      if (!errorMessage.isNullOrEmpty()) {
        this.errorMessage = errorMessage
      }
    }
  }
}

/** Extension function converting [Model] sampler configuration into a [LlmConfig] protobuf. */
internal fun Model.toLlmConfig(): LlmConfig = llmConfig {
  this.defaultTopk = getIntConfigValue(ConfigKeys.TOPK, 0)
  this.defaultTopp = getFloatConfigValue(ConfigKeys.TOPP, 0.0f)
  this.defaultTemperature = getFloatConfigValue(ConfigKeys.TEMPERATURE, 0.0f)
  this.defaultMaxTokens = getIntConfigValue(ConfigKeys.MAX_TOKENS, 0)
  this.supportThinking = getBooleanConfigValue(ConfigKeys.ENABLE_THINKING, false)
  this.supportSpeculativeDecoding =
    getBooleanConfigValue(ConfigKeys.ENABLE_SPECULATIVE_DECODING, false)
}
