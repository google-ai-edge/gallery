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
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.supportModelBenchmark
import com.google.ai.edge.gallery.proto.LlmConfig
import com.google.ai.edge.gallery.proto.llmConfig
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Configuration options for inference metrics tracking and periodic system resource monitors. */
data class MetricsTrackerConfig(
  // Whether to enable periodic system resource monitors (memory and power).
  val enableSystemSampling: Boolean = true,
  // Sampling interval for the memory monitor.
  val memorySamplingInterval: Duration = PeriodicSampler.DEFAULT_SAMPLING_INTERVAL,
  // Sampling interval for the power monitor.
  val powerSamplingInterval: Duration = PeriodicSampler.DEFAULT_SAMPLING_INTERVAL,
  // Update interval for the live metrics stream during an active turn.
  val liveUpdateInterval: Duration = PeriodicSampler.DEFAULT_LIVE_UPDATE_INTERVAL,
)

/**
 * Tracks, aggregates, and computes inference telemetry and hardware performance metrics for an
 * active model conversation session.
 *
 * ## Lifecycle
 * A [MetricsTracker] instance is created when a [Model] instance is initialized, and destroyed when
 * that model is unloaded. When a conversation is recreated with updated sampler or thinking
 * settings via `resetConversation`, [resetSession] refreshes the active session metadata and resets
 * cumulative context and turn counters.
 */
interface MetricsTracker {
  /**
   * Hot stream of real-time [InferenceMetrics] snapshots for the active model session.
   *
   * Emits `null` before initialization or when metrics tracking is disabled (e.g.
   * [NoOpMetricsTracker]), and emits updated [InferenceMetrics] snapshots on lifecycle milestones
   * and periodically during active inference turns.
   */
  val liveMetrics: StateFlow<InferenceMetrics?>

  /**
   * Records the model's initialization outcome (`Initialized` or `Failed`), duration, and active
   * sampler configuration from [Model], dispatching telemetry to Logcat and Firebase Analytics when
   * metrics tracking is enabled.
   */
  fun onModelInitialized()

  /**
   * Starts tracking an inference turn using a [ConversationSession] abstraction.
   *
   * The tracker assigns each started turn the next 0-based turn index of the current session;
   * [resetSession] restarts the count at 0. No-ops if [session] is not alive or a turn is already
   * active.
   *
   * @param session Active session abstraction for ground-truth telemetry.
   */
  fun startTurn(session: ConversationSession)

  /**
   * Universal streaming token callback.
   *
   * Increments estimated output tokens and records Time To First Token (TTFT) on the first token.
   *
   * @param tokenText The generated text piece for this token callback.
   * @param thinkingText Optional thinking/reasoning text emitted by thinking models.
   */
  fun onNewToken(tokenText: String, thinkingText: String? = null)

  /**
   * Ends the active turn as [InferenceStatus.Code.CANCELLED] (for example, when the user taps Stop)
   * and logs its metrics.
   *
   * @param reason Why the turn was cancelled.
   * @param customMessage Optional message that describes the cancellation.
   * @return The turn's metrics, or null if no turn is active or tracking is disabled.
   */
  fun cancelTurn(
    reason: CancellationReason = CancellationReason.USER_CANCELLED,
    customMessage: String? = null,
  ): InferenceMetrics?

  /**
   * Starts a new session after the conversation is cleared or recreated. Refreshes the session
   * metadata and restarts the turn index and the context token count. Drops an active turn without
   * logging it.
   */
  fun resetSession()

  /**
   * Ends the active turn with [statusCode] and logs its metrics.
   *
   * @param statusCode The turn's result.
   * @param errorMessage Optional error message if the turn failed.
   * @return The turn's metrics, or null if no turn is active or tracking is disabled.
   */
  fun endTurn(
    statusCode: InferenceStatus.Code = InferenceStatus.Code.SUCCESS,
    errorMessage: String? = null,
  ): InferenceMetrics?

  /** Releases background sampling resources when the model instance is unloaded. */
  fun close() {}

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
      timeSource: TimeSource = TimeSource.Monotonic,
    ): MetricsTracker {

      val enableInferenceMetrics = false

      if (!enableInferenceMetrics) {
        // Returns a no-op tracker if benchmark telemetry is disabled or the model does not support
        // benchmark mode.
        return NoOpMetricsTracker()
      }

      return LitertlmMetricsTracker(
        context = context.applicationContext,
        model = model,
        taskId = taskId,
        config = config,
        ioDispatcher = ioDispatcher,
        timeSource = timeSource,
      )
    }
  }
}

/**
 * No-op implementation of [MetricsTracker] used when benchmark telemetry is disabled or unsupported
 * for [Model].
 */
class NoOpMetricsTracker : MetricsTracker {
  override val liveMetrics: StateFlow<InferenceMetrics?> =
    MutableStateFlow<InferenceMetrics?>(null).asStateFlow()

  override fun onModelInitialized() {}

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
  context: Context,
  private val model: Model,
  private val taskId: String,
  ioDispatcher: CoroutineDispatcher,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  config: MetricsTrackerConfig = MetricsTrackerConfig(),
  private val metricsLogger: MetricsLogger = MetricsLogger(model = model, taskId = taskId),
  private val memoryMonitor: PeriodicSensorMonitor<MemoryMetrics>? =
    if (config.enableSystemSampling) {
      MemoryMonitor(
        context = context,
        samplingInterval = config.memorySamplingInterval,
        dispatcher = ioDispatcher,
      )
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
) : MetricsTracker {

  init {
    require(model.supportModelBenchmark) {
      "Cannot instantiate LitertlmMetricsTracker for '${model.name}': benchmark mode is not supported or enabled."
    }
  }

  private val _liveMetrics = MutableStateFlow<InferenceMetrics?>(null)
  override val liveMetrics: StateFlow<InferenceMetrics?> = _liveMetrics.asStateFlow()

  /**
   * Active session metadata (model name, accelerator, task ID, initialization duration, and LLM
   * sampler configuration). Initialized in [onModelInitialized] and refreshed in [resetSession]
   * when a conversation is recreated with updated sampler or thinking settings.
   */
  @Volatile private var metadata: InferenceMetadata = buildMetadata()

  /**
   * Per-session inference tracker, created in [onModelInitialized] and recreated in [resetSession]
   * when a new conversation session starts.
   */
  @Volatile private var inferenceTracker: LitertlmInferenceMetricsTracker? = null

  /**
   * Refreshes the internal state when a new conversation session is started: session metadata and
   * [LitertlmInferenceMetricsTracker].
   *
   * Aborts any active turn in the previous session and creates a new
   * [LitertlmInferenceMetricsTracker] with the updated metadata.
   */
  private fun refreshSessionTracker() {
    val newMetadata = buildMetadata()
    metadata = newMetadata
    inferenceTracker?.abortActiveTurn()
    inferenceTracker =
      LitertlmInferenceMetricsTracker(metadata = newMetadata, timeSource = timeSource)
  }

  /**
   * Scope the periodic sensor samplers run on, kept off the main thread by `ioDispatcher`. A
   * [SupervisorJob] keeps one failing sampler from tearing down the others. Sampling jobs launched
   * on this scope are stopped at turn end, turn cancellation, or session reset.
   */
  private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

  /**
   * Whether live metrics streaming is enabled for this tracker instance. Captured once at
   * construction time so the live stream behavior remains consistent across the session.
   */
  private val enableLiveMetrics: Boolean = false

  private val liveUpdateSampler: PeriodicSampler? =
    if (enableLiveMetrics) {
      PeriodicSampler(
        samplingInterval = config.liveUpdateInterval,
        dispatcher = ioDispatcher,
        onSample = ::emitLiveSnapshotIfTurnActive,
      )
    } else {
      null
    }

  @Synchronized
  override fun onModelInitialized() {
    refreshSessionTracker()
    metricsLogger.logModelInitialization()
    if (model.initStatusFlow.value !is Model.InitializationStatus.Failed) {
      emitLiveSnapshot()
    }
  }

  @Synchronized
  override fun startTurn(session: ConversationSession) {
    if (inferenceTracker?.startTurn(session = session) != true) {
      return
    }
    memoryMonitor?.start(scope)
    powerMonitor?.start(scope)
    liveUpdateSampler?.start(scope)
    emitLiveSnapshot()
  }

  override fun onNewToken(tokenText: String, thinkingText: String?) {
    inferenceTracker?.onNewToken(
      tokenText = tokenText,
      thinkingText = thinkingText,
      onFirstToken = ::emitLiveSnapshot,
    )
  }

  override fun cancelTurn(reason: CancellationReason, customMessage: String?): InferenceMetrics? =
    endTurnInternal(
      status =
        inferenceStatus {
          this.code = InferenceStatus.Code.CANCELLED
          if (reason != CancellationReason.CANCELLATION_REASON_UNSPECIFIED) {
            this.cancellationReason = reason
          }
          if (!customMessage.isNullOrEmpty()) {
            this.errorMessage = customMessage
          }
        }
    )

  @Synchronized
  override fun resetSession() {
    liveUpdateSampler?.stop()
    refreshSessionTracker()
    memoryMonitor?.reset()
    powerMonitor?.reset()
    emitLiveSnapshot()
  }

  override fun endTurn(statusCode: InferenceStatus.Code, errorMessage: String?): InferenceMetrics? =
    endTurnInternal(
      status =
        inferenceStatus {
          this.code = statusCode
          if (!errorMessage.isNullOrEmpty()) {
            this.errorMessage = errorMessage
          }
        }
    )

  @Synchronized
  override fun close() {
    liveUpdateSampler?.stop()
    memoryMonitor?.reset()
    powerMonitor?.reset()
    scope.cancel()
  }

  @Synchronized
  private fun emitLiveSnapshot() {
    if (!enableLiveMetrics) return
    val turnMetrics = inferenceTracker?.buildLiveMetrics() ?: return
    val isTurnActive = turnMetrics.status.code != InferenceStatus.Code.IDLE
    _liveMetrics.value = inferenceMetrics {
      this.metadata = this@LitertlmMetricsTracker.metadata
      this.inference = turnMetrics
      this.memory =
        if (isTurnActive) {
          memoryMonitor?.buildMetrics() ?: MemoryMetrics.getDefaultInstance()
        } else {
          MemoryMetrics.getDefaultInstance()
        }
      this.battery =
        if (isTurnActive) {
          powerMonitor?.buildMetrics() ?: BatteryMetrics.getDefaultInstance()
        } else {
          BatteryMetrics.getDefaultInstance()
        }
    }
  }

  private fun emitLiveSnapshotIfTurnActive() {
    if (inferenceTracker?.hasActiveTurn != true) return
    synchronized(this) {
      if (inferenceTracker?.hasActiveTurn != true) return
      emitLiveSnapshot()
    }
  }

  private fun endTurnInternal(status: InferenceStatus): InferenceMetrics? {
    val finalMetrics =
      synchronized(this) {
        liveUpdateSampler?.stop()
        val turnMetrics = inferenceTracker?.endTurn(status = status) ?: return null
        val memoryMetrics = memoryMonitor?.stop() ?: MemoryMetrics.getDefaultInstance()
        val batteryMetrics = powerMonitor?.stop() ?: BatteryMetrics.getDefaultInstance()
        inferenceMetrics {
            this.metadata = this@LitertlmMetricsTracker.metadata
            this.inference = turnMetrics
            this.memory = memoryMetrics
            this.battery = batteryMetrics
          }
          .also {
            if (enableLiveMetrics) {
              _liveMetrics.value = it
            }
          }
      }
    metricsLogger.logMetrics(finalMetrics)
    return finalMetrics
  }

  private fun buildMetadata(): InferenceMetadata {
    return inferenceMetadata {
      this.modelName = model.name
      this.accelerator = model.currentAccelerator?.name ?: ""
      this.taskId = this@LitertlmMetricsTracker.taskId
      this.llmConfig = model.toLlmConfig()
      model.initDuration?.let { this.initDurationMs = it.inWholeMilliseconds }
    }
  }
}

/**
 * Extension function converting the active runtime [Model] sampler and capability configuration
 * into a [LlmConfig] protobuf (populating the shared `settings.proto` `default_*` and `support_*`
 * fields with the actual values configured for the active session).
 */
internal fun Model.toLlmConfig(): LlmConfig = llmConfig {
  this.defaultTopk = getIntConfigValue(ConfigKeys.TOPK, 0)
  this.defaultTopp = getFloatConfigValue(ConfigKeys.TOPP, 0.0f)
  this.defaultTemperature = getFloatConfigValue(ConfigKeys.TEMPERATURE, 0.0f)
  this.defaultMaxTokens =
    getIntConfigValue(
      key = ConfigKeys.MAX_TOKENS,
      defaultValue = llmProfile?.maxTokens ?: DEFAULT_MAX_TOKEN,
    )
  this.supportThinking = getBooleanConfigValue(ConfigKeys.ENABLE_THINKING, false)
  this.supportSpeculativeDecoding =
    getBooleanConfigValue(ConfigKeys.ENABLE_SPECULATIVE_DECODING, false)
  this.supportImage = this@toLlmConfig.supportImage
  this.supportAudio = this@toLlmConfig.supportAudio
}
