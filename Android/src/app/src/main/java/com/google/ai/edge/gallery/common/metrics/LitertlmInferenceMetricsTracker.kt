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

  /** Encapsulates ephemeral state and timing for an active inference turn. */
  private class TurnState(
    val session: ConversationSession,
    val turnIndex: Int,
    timeSource: TimeSource,
  ) {
    private class TtftMark(val duration: Duration)

    private val startTimeMark: TimeMark = timeSource.markNow()
    private val ttft = AtomicReference<TtftMark?>(null)

    val ttftDuration: Duration?
      get() = ttft.get()?.duration

    fun recordToken(tokenText: String, thinkingText: String?) {
      val hasContent = tokenText.isNotEmpty() || !thinkingText.isNullOrEmpty()
      if (hasContent && ttft.get() == null) {
        ttft.compareAndSet(null, TtftMark(startTimeMark.elapsedNow()))
      }
    }

    fun endTurn(): Duration = startTimeMark.elapsedNow()
  }

  /** Tracker state: the active turn and the index of the next turn in this session. */
  private data class State(val activeTurn: TurnState? = null, val nextTurnIndex: Int = 0)

  private val state = AtomicReference(State())

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
    val current = state.get()
    if (current.activeTurn != null) {
      Log.w(TAG, "Cannot start turn: previous turn is still in progress.")
      return false
    }
    val started =
      current.copy(
        activeTurn =
          TurnState(session = session, turnIndex = current.nextTurnIndex, timeSource = timeSource),
        nextTurnIndex = current.nextTurnIndex + 1,
      )
    // Fails if another call started a turn or closed the session meanwhile.
    if (!state.compareAndSet(current, started)) {
      Log.w(TAG, "Cannot start turn: tracker state changed concurrently.")
      return false
    }
    return true
  }

  /** Aborts any active turn when this session is closed or replaced by a session reset. */
  fun abortActiveTurn() {
    state.set(State())
  }

  /**
   * Processes streaming token callbacks, recording the initial token arrival time (TTFT) and
   * incrementing output token counts. Safely no-ops if no turn is active.
   *
   * @param tokenText Generated token text piece.
   * @param thinkingText Optional thinking text emitted by reasoning models.
   */
  fun onNewToken(tokenText: String, thinkingText: String? = null) {
    state.get().activeTurn?.recordToken(tokenText = tokenText, thinkingText = thinkingText)
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
   * Computes finalized turn metrics at turn end (`endTurn`).
   *
   * At turn end, engine benchmark info and KV-cache token counts are available and used as the
   * source of truth for token counts and prefill throughput, while app-measured timings are
   * preferred for latency milestones and decode throughput.
   *
   * Engine diagnostics are queried only after C++ `Tasks::Decode` finishes (`SUCCESS`, or
   * `CANCELLED` after at least one token was emitted) and releases
   * `ResourceManager::executor_mutex_`. If a turn ends before any token is emitted (e.g. cancelled
   * during prefill or failed with `ERROR`), engine diagnostics are not queried and unavailable
   * fields remain unset.
   */
  private class TurnMetricsCalculator(private val snapshot: RawTurnSnapshot) {
    fun calculateStatsOnTurnEnd(): TurnInferenceMetrics {
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

      return turnInferenceMetrics {
        this.turnIndex = snapshot.turnIndex
        this.status = snapshot.status
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
          consumedContextTokens?.let { this.consumedContextTokens = it }
          this.maxContextTokens = snapshot.maxContextTokens
        }
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
   * Finalizes the active turn's latency milestones, token counts, and context utilization.
   *
   * @param status Final execution status of the turn.
   * @return The finalized [TurnInferenceMetrics], or `null` if no turn is active.
   */
  fun endTurn(status: InferenceStatus): TurnInferenceMetrics? {
    val current = state.get()
    val turn = current.activeTurn ?: return null
    val turnDuration = turn.endTurn()
    val sessionDiagnostics = querySessionDiagnostics(turn, status)

    val snapshot =
      RawTurnSnapshot(
        turnIndex = turn.turnIndex,
        status = status,
        diagnostics = sessionDiagnostics,
        turnDuration = turnDuration,
        streamedTtft = turn.ttftDuration,
        maxContextTokens = maxContextTokens,
      )
    val calculator = TurnMetricsCalculator(snapshot = snapshot)
    val ended = current.copy(activeTurn = null)
    // Fails if another call ended the turn or reset the session meanwhile.
    if (!state.compareAndSet(current, ended)) return null
    return calculator.calculateStatsOnTurnEnd()
  }

  /**
   * Queries engine-level benchmark and KV-cache token count after the C++ worker has finished the
   * turn (`SUCCESS`, or `CANCELLED` after at least one token was emitted) and released
   * `ResourceManager::executor_mutex_`.
   */
  private fun querySessionDiagnostics(
    turn: TurnState,
    status: InferenceStatus,
  ): SessionDiagnostics {
    val session = turn.session
    val shouldQueryDiagnostics =
      status.code == InferenceStatus.Code.SUCCESS ||
        (status.code == InferenceStatus.Code.CANCELLED && turn.ttftDuration != null)
    if (!session.isAlive || !shouldQueryDiagnostics) return SessionDiagnostics()

    val benchmark =
      try {
        session.getBenchmark()
      } catch (e: Exception) {
        Log.d(TAG, "Failed to query InferenceBenchmark: ${e.message}")
        null
      }

    val tokens =
      try {
        session.getTokenCount()
      } catch (e: Exception) {
        Log.d(TAG, "Failed to query token count: ${e.message}")
        null
      }

    return SessionDiagnostics(benchmark = benchmark, tokenCount = tokens)
  }
}
