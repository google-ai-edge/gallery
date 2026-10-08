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
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val TAG = "AGLitertlmInferenceMetricsTracker"

/**
 * Tracks model inference latency, token throughput, and KV-cache context utilization for a single
 * LiteRT-LM conversation session with benchmark mode enabled.
 *
 * A new [LitertlmInferenceMetricsTracker] instance is created when the session tracker is
 * initialized or when the conversation session is reset.
 */
class LitertlmInferenceMetricsTracker(
  metadata: InferenceMetadata,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {

  private val maxContextTokens: Int =
    metadata.llmConfig.defaultMaxTokens.takeIf { it > 0 } ?: DEFAULT_MAX_TOKEN

  private val calculator = TurnMetricsCalculator(maxContextTokens = maxContextTokens)

  /** Lifecycle phase of the tracker and active/completed inference turn. */
  private enum class TurnPhase {
    IDLE,
    PREFILLING,
    DECODING,
    SUCCESS,
    USER_CANCELLED,
    ERROR,
  }

  /**
   * Lifecycle state of the current turn across all phases, held in a single [AtomicReference] so
   * that every phase transition and its associated data are published atomically.
   */
  private sealed interface TurnState {
    val phase: TurnPhase
    val turnIndex: Int

    val isActive: Boolean
      get() = this is Active

    sealed interface Active : TurnState {
      val session: ConversationSession
      val startContextTokens: Int?
      val startTimeMark: TimeMark
      val ttftDuration: Duration?
    }

    data object Idle : TurnState {
      override val phase: TurnPhase = TurnPhase.IDLE
      override val turnIndex: Int = 0
    }

    data class Prefilling(
      override val turnIndex: Int,
      override val session: ConversationSession,
      override val startContextTokens: Int?,
      override val startTimeMark: TimeMark,
    ) : Active {
      override val phase: TurnPhase = TurnPhase.PREFILLING
      override val ttftDuration: Duration? = null
    }

    data class Decoding(
      override val turnIndex: Int,
      override val session: ConversationSession,
      override val startContextTokens: Int?,
      override val startTimeMark: TimeMark,
      override val ttftDuration: Duration,
      val prefillBenchmark: InferenceBenchmark?,
      val outputTokenCount: AtomicInteger = AtomicInteger(1),
    ) : Active {
      override val phase: TurnPhase = TurnPhase.DECODING

      val streamedOutputTokens: Int
        get() = outputTokenCount.get()
    }

    data class Completed(
      override val turnIndex: Int,
      override val phase: TurnPhase,
      val finalMetrics: TurnInferenceMetrics,
    ) : TurnState
  }

  private val turnState = AtomicReference<TurnState>(TurnState.Idle)

  internal val hasActiveTurn: Boolean
    get() = turnState.get().isActive

  /**
   * Starts tracking a new inference turn with a [ConversationSession] abstraction, assigning it the
   * next 0-based turn index of this session.
   *
   * @return `true` if the turn was started, or `false` if [session] is not alive or a turn is
   *   already active.
   */
  fun startTurn(session: ConversationSession): Boolean {
    if (!session.isAlive) {
      Log.w(TAG, "Cannot start turn: Conversation is not alive.")
      return false
    }
    val current = turnState.get()
    val nextTurnIndex =
      when (current) {
        is TurnState.Idle -> current.turnIndex
        is TurnState.Completed -> current.turnIndex + 1
        is TurnState.Active -> {
          Log.w(TAG, "Cannot start turn: previous turn is still in progress.")
          return false
        }
      }
    // Known issue (KI): When a Conversation is created or reset with `initialMessages` (e.g.
    // restored session, context compaction, or post-stop recovery), LiteRT-LM sets
    // `prefill_preface_on_init = false` and defers prefilling those initial messages until the
    // first turn's prefill pass. Consequently, `session.getTokenCount()` returns 0 here before the
    // first turn runs, and the actual context token count is unavailable for live metrics until
    // the first output token arrives (`DECODING`), when `prefillTokenCount` includes the prefaced
    // initial messages. On subsequent turns (`turnIndex >= 1`), `session.getTokenCount()` returns
    // the KV-cache token count from the end of the previous turn.
    val startContextTokens = queryTokenCount(session)
    val started =
      TurnState.Prefilling(
        turnIndex = nextTurnIndex,
        session = session,
        startContextTokens = startContextTokens,
        startTimeMark = timeSource.markNow(),
      )
    // Fails if another call started a turn or aborted the session meanwhile.
    if (!turnState.compareAndSet(current, started)) {
      Log.w(TAG, "Cannot start turn: tracker state changed concurrently.")
      return false
    }
    return true
  }

  /** Aborts any active turn when this session is closed or replaced by a session reset. */
  fun abortActiveTurn() {
    turnState.set(TurnState.Idle)
  }

  /**
   * Processes streaming token callbacks, recording the initial token arrival time (TTFT) and
   * incrementing output token counts. Safely no-ops if no turn is active.
   *
   * @param tokenText Generated token text piece.
   * @param thinkingText Optional thinking text emitted by reasoning models.
   * @param onFirstToken Optional callback invoked only on the first non-empty token when TTFT is
   *   locked in.
   */
  fun onNewToken(
    tokenText: String,
    thinkingText: String? = null,
    onFirstToken: (() -> Unit)? = null,
  ) {
    val hasContent = tokenText.isNotEmpty() || !thinkingText.isNullOrEmpty()
    if (!hasContent) return
    when (val current = turnState.get()) {
      is TurnState.Prefilling -> {
        val ttftDuration = current.startTimeMark.elapsedNow()
        val prefillBenchmark = queryPrefillBenchmark(current.session)
        val decoding =
          TurnState.Decoding(
            turnIndex = current.turnIndex,
            session = current.session,
            startContextTokens = current.startContextTokens,
            startTimeMark = current.startTimeMark,
            ttftDuration = ttftDuration,
            prefillBenchmark = prefillBenchmark,
            outputTokenCount = AtomicInteger(1),
          )
        if (turnState.compareAndSet(current, decoding)) {
          onFirstToken?.invoke()
        } else {
          (turnState.get() as? TurnState.Decoding)?.outputTokenCount?.incrementAndGet()
        }
      }
      is TurnState.Decoding -> {
        current.outputTokenCount.incrementAndGet()
      }
      is TurnState.Idle,
      is TurnState.Completed -> Unit
    }
  }

  /**
   * Queries [ConversationSession.getBenchmark] once when prefill finishes (upon first token
   * arrival), extracting only the current turn's prefill metrics (`prefillTokenCount` and
   * `prefillSpeedTps`). Decode fields (`decodeTokenCount` and `decodeSpeedTps`) are intentionally
   * omitted (`null`) because the native engine only finalizes decode benchmark stats at turn end.
   */
  private fun queryPrefillBenchmark(session: ConversationSession): InferenceBenchmark? {
    if (!session.isAlive) return null
    return try {
      session.getBenchmark()?.let { benchmark ->
        InferenceBenchmark(
          prefillTokenCount = benchmark.prefillTokenCount,
          prefillSpeedTps = benchmark.prefillSpeedTps,
          decodeTokenCount = null,
          decodeSpeedTps = null,
        )
      }
    } catch (e: Exception) {
      Log.d(TAG, "Failed to query prefill InferenceBenchmark: ${e.message}")
      null
    }
  }

  private data class SessionDiagnostics(
    val benchmark: InferenceBenchmark? = null,
    val tokenCount: Int? = null,
  )

  private data class RawTurnSnapshot(
    val turnIndex: Int,
    val status: InferenceStatus,
    val diagnostics: SessionDiagnostics,
    val turnDuration: Duration,
    val streamedTtft: Duration?,
    val maxContextTokens: Int,
  )

  /**
   * Isolated calculator for phases where engine or streaming inference statistics can be computed:
   * - [calculateStatsDuringDecoding]: During token streaming after the first output token arrives.
   * - [calculateStatsOnTurnEnd]: When a turn ends (`endTurn`).
   */
  private class TurnMetricsCalculator(private val maxContextTokens: Int) {
    /**
     * Computes live metrics during the decoding phase ([InferenceStatus.Code.DECODING]), after the
     * first output token has arrived.
     *
     * During decoding, the native engine is actively executing `Tasks::Decode`, so engine benchmark
     * info only contains prefill-related stats (`prefillTokenCount`, `prefillSpeedTps`), and
     * `Conversation.getTokenCount()` is not yet available for the active turn. Therefore, output
     * token count, decode speed, and context utilization are estimated from app-counted streaming
     * output tokens (`turn.streamedOutputTokens`) and app-measured timings alongside the prefill
     * benchmark snapshot captured when the first token arrived.
     */
    fun calculateStatsDuringDecoding(
      turn: TurnState.Decoding,
      turnDuration: Duration,
    ): TurnInferenceMetrics {
      val ttftDuration = turn.ttftDuration
      val prefillBenchmark = turn.prefillBenchmark
      val promptTokens = prefillBenchmark?.prefillTokenCount?.takeIf { it > 0 }
      val outputTokens = turn.streamedOutputTokens
      val totalTokens = if (promptTokens != null) promptTokens + outputTokens else null
      val decodeDuration = (turnDuration - ttftDuration).coerceAtLeast(Duration.ZERO)
      val prefillSpeed = prefillBenchmark?.prefillSpeedTps?.takeIf { it > 0f }
      val decodeSpeed =
        calculateDecodeSpeedTps(outputTokens = outputTokens, decodeDuration = decodeDuration)
      val consumedContextTokens =
        if (totalTokens != null) {
          (turn.startContextTokens ?: 0) + totalTokens
        } else {
          null
        }

      return buildTurnMetrics(
        turnIndex = turn.turnIndex,
        status = inferenceStatus { this.code = InferenceStatus.Code.DECODING },
        promptTokens = promptTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        ttftDuration = ttftDuration,
        turnDuration = turnDuration,
        decodeDuration = decodeDuration,
        prefillSpeed = prefillSpeed,
        decodeSpeed = decodeSpeed,
        consumedContextTokens = consumedContextTokens,
        maxContextTokens = maxContextTokens,
      )
    }

    /**
     * Computes finalized turn metrics at turn end (`endTurn`).
     *
     * At turn end, engine benchmark info and KV-cache token counts are available and used as the
     * source of truth for token counts and prefill throughput, while app-measured timings are
     * preferred for latency milestones and decode throughput.
     *
     * Engine diagnostics are queried only after C++ `Tasks::Decode` finishes (`SUCCESS`, or
     * `CANCELLED` after at least one token was emitted) and releases
     * `ResourceManager::executor_mutex_`. If a turn ends before any token is emitted (e.g.
     * cancelled during prefill or failed with `ERROR`), engine diagnostics are not queried and
     * unavailable fields remain unset.
     */
    fun calculateStatsOnTurnEnd(snapshot: RawTurnSnapshot): TurnInferenceMetrics {
      val benchmark = snapshot.diagnostics.benchmark
      val promptTokens = benchmark?.prefillTokenCount?.takeIf { it > 0 }
      val outputTokens = benchmark?.decodeTokenCount?.takeIf { it > 0 }
      val totalTokens =
        if (promptTokens != null && outputTokens != null) promptTokens + outputTokens else null

      // Latency milestones come from app-measured monotonic TimeSource.
      val ttftDuration = snapshot.streamedTtft
      val turnDuration = snapshot.turnDuration
      val decodeDuration =
        if (ttftDuration != null && turnDuration >= ttftDuration) {
          turnDuration - ttftDuration
        } else {
          null
        }
      val prefillSpeed = benchmark?.prefillSpeedTps?.takeIf { it > 0f }
      // Decode speed is derived from engine decodeTokenCount and app-measured decode duration:
      // (outputTokens - 1) / decodeSeconds.
      val decodeSpeed =
        calculateDecodeSpeedTps(outputTokens = outputTokens, decodeDuration = decodeDuration)
      // Context utilization comes from Conversation.getTokenCount().
      val consumedContextTokens = snapshot.diagnostics.tokenCount?.takeIf { it > 0 }

      return buildTurnMetrics(
        turnIndex = snapshot.turnIndex,
        status = snapshot.status,
        promptTokens = promptTokens,
        outputTokens = outputTokens,
        totalTokens = totalTokens,
        ttftDuration = ttftDuration,
        turnDuration = turnDuration,
        decodeDuration = decodeDuration,
        prefillSpeed = prefillSpeed,
        decodeSpeed = decodeSpeed,
        consumedContextTokens = consumedContextTokens,
        maxContextTokens = snapshot.maxContextTokens,
      )
    }

    private fun buildTurnMetrics(
      turnIndex: Int,
      status: InferenceStatus,
      promptTokens: Int?,
      outputTokens: Int?,
      totalTokens: Int?,
      ttftDuration: Duration?,
      turnDuration: Duration,
      decodeDuration: Duration?,
      prefillSpeed: Float?,
      decodeSpeed: Float?,
      consumedContextTokens: Int?,
      maxContextTokens: Int,
    ): TurnInferenceMetrics = turnInferenceMetrics {
      this.turnIndex = turnIndex
      this.status = status
      this.tokens = turnTokenMetrics {
        promptTokens?.let { this.promptTokens = it }
        outputTokens?.let { this.outputTokens = it }
        totalTokens?.let { this.totalTokens = it }
      }
      this.latency = latencyMetrics {
        ttftDuration?.let { this.ttftMs = it.inWholeMilliseconds }
        this.totalLatencyMs = turnDuration.inWholeMilliseconds
        decodeDuration?.let { this.decodeDurationMs = it.inWholeMilliseconds }
        prefillSpeed?.let { this.prefillSpeedTps = it }
        decodeSpeed?.takeIf { it > 0f }?.let { this.decodeSpeedTps = it }
      }
      this.context = contextMetrics {
        consumedContextTokens?.takeIf { it > 0 }?.let { this.consumedContextTokens = it }
        this.maxContextTokens = maxContextTokens
      }
    }

    /**
     * Computes the token decode throughput in tokens per second (tok/s) from [outputTokens] and
     * app-measured [decodeDuration].
     *
     * ## Calculation & Single-Token Boundary
     * TTFT measures the time elapsed until the *first* generated token arrives. The remaining
     * decode duration ($T_{total} - \text{TTFT}$) corresponds strictly to the generation of
     * subsequent tokens ($N - 1$).
     *
     * If $N \le 1$, zero additional tokens were decoded after TTFT, making decode speed undefined.
     */
    private fun calculateDecodeSpeedTps(outputTokens: Int?, decodeDuration: Duration?): Float? {
      if (
        outputTokens == null ||
          outputTokens <= 1 ||
          decodeDuration == null ||
          decodeDuration <= Duration.ZERO
      ) {
        return null
      }
      val decodeSeconds = decodeDuration.toDouble(DurationUnit.SECONDS)
      if (decodeSeconds <= 0.0) return null
      val effectiveTokens = outputTokens - 1
      return (effectiveTokens / decodeSeconds).toFloat()
    }
  }

  /**
   * Builds a non-mutating snapshot of the current live turn state based on [TurnState]:
   * - [TurnState.Idle]: When no turn is active (e.g. after model initialization or session reset).
   * - [TurnState.Prefilling]: When a turn is active and waiting for the first token; no
   *   inference-level benchmark or token metrics are available yet, while `consumedContextTokens`
   *   reflects any existing session KV-cache tokens captured at turn start (`startContextTokens >
   *   0`).
   * - [TurnState.Decoding]: When a turn is active and streaming tokens, computed via
   *   [TurnMetricsCalculator.calculateStatsDuringDecoding].
   * - [TurnState.Completed]: Returns the finalized metrics computed on turn end (`SUCCESS`,
   *   `USER_CANCELLED`, or `ERROR`).
   */
  fun buildLiveMetrics(): TurnInferenceMetrics {
    return when (val current = turnState.get()) {
      is TurnState.Idle -> buildIdleMetrics(turnIndex = current.turnIndex)
      is TurnState.Prefilling ->
        turnInferenceMetrics {
          this.turnIndex = current.turnIndex
          this.status = inferenceStatus { this.code = InferenceStatus.Code.PREFILLING }
          this.latency = LatencyMetrics.getDefaultInstance()
          this.context = contextMetrics {
            current.startContextTokens?.takeIf { it > 0 }?.let { this.consumedContextTokens = it }
            this.maxContextTokens = this@LitertlmInferenceMetricsTracker.maxContextTokens
          }
        }
      is TurnState.Decoding ->
        calculator.calculateStatsDuringDecoding(
          turn = current,
          turnDuration = current.startTimeMark.elapsedNow(),
        )
      is TurnState.Completed -> current.finalMetrics
    }
  }

  private fun buildIdleMetrics(turnIndex: Int): TurnInferenceMetrics = turnInferenceMetrics {
    this.turnIndex = turnIndex
    this.status = inferenceStatus { this.code = InferenceStatus.Code.IDLE }
    this.latency = LatencyMetrics.getDefaultInstance()
    this.context = contextMetrics {
      this.maxContextTokens = this@LitertlmInferenceMetricsTracker.maxContextTokens
    }
  }

  /**
   * Finalizes the active turn's latency milestones, token counts, and context utilization.
   *
   * @param status Final execution status of the turn.
   * @return The finalized [TurnInferenceMetrics], or `null` if no turn is active.
   */
  fun endTurn(status: InferenceStatus): TurnInferenceMetrics? {
    val current = turnState.get() as? TurnState.Active ?: return null
    val turnDuration = current.startTimeMark.elapsedNow()
    val terminalPhase =
      when (status.code) {
        InferenceStatus.Code.SUCCESS -> TurnPhase.SUCCESS
        InferenceStatus.Code.CANCELLED -> TurnPhase.USER_CANCELLED
        InferenceStatus.Code.ERROR -> TurnPhase.ERROR
        else -> TurnPhase.IDLE
      }
    val sessionDiagnostics =
      querySessionDiagnostics(
        session = current.session,
        preEndPhase = current.phase,
        terminalPhase = terminalPhase,
      )

    val snapshot =
      RawTurnSnapshot(
        turnIndex = current.turnIndex,
        status = status,
        diagnostics = sessionDiagnostics,
        turnDuration = turnDuration,
        streamedTtft = current.ttftDuration,
        maxContextTokens = maxContextTokens,
      )
    val finalMetrics = calculator.calculateStatsOnTurnEnd(snapshot = snapshot)
    val completed =
      TurnState.Completed(
        turnIndex = current.turnIndex,
        phase = terminalPhase,
        finalMetrics = finalMetrics,
      )
    // Fails if another call ended the turn or aborted the session meanwhile.
    if (!turnState.compareAndSet(current, completed)) return null
    return finalMetrics
  }

  private fun queryTokenCount(session: ConversationSession): Int? {
    if (!session.isAlive) return null
    return try {
      session.getTokenCount()?.takeIf { it >= 0 }
    } catch (e: Exception) {
      Log.d(TAG, "Failed to query token count: ${e.message}")
      null
    }
  }

  /**
   * Queries engine-level benchmark and KV-cache token count after the C++ worker has finished the
   * turn ([TurnPhase.SUCCESS], or [TurnPhase.USER_CANCELLED] after entering [TurnPhase.DECODING])
   * and released `ResourceManager::executor_mutex_`.
   */
  private fun querySessionDiagnostics(
    session: ConversationSession,
    preEndPhase: TurnPhase,
    terminalPhase: TurnPhase,
  ): SessionDiagnostics {
    val shouldQueryDiagnostics =
      terminalPhase == TurnPhase.SUCCESS ||
        (terminalPhase == TurnPhase.USER_CANCELLED && preEndPhase == TurnPhase.DECODING)
    if (!session.isAlive || !shouldQueryDiagnostics) return SessionDiagnostics()

    val benchmark =
      try {
        session.getBenchmark()
      } catch (e: Exception) {
        Log.d(TAG, "Failed to query InferenceBenchmark: ${e.message}")
        null
      }

    val tokens = queryTokenCount(session)

    return SessionDiagnostics(benchmark = benchmark, tokenCount = tokens)
  }
}
