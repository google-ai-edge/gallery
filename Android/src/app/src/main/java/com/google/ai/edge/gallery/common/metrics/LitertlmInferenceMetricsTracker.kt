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
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.supportModelBenchmark
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Tracks model inference latency, token throughput, and KV-cache context utilization for an active
 * LiteRT-LM model with benchmark mode enabled.
 *
 * @throws IllegalArgumentException if [model] does not support benchmark mode.
 */
class LitertlmInferenceMetricsTracker(
  val model: Model,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {

  init {
    require(model.supportModelBenchmark) {
      "Cannot instantiate LitertlmInferenceMetricsTracker for '${model.name}': benchmark mode is not supported or enabled."
    }
  }

  private val maxContextTokens: Int =
    model.getIntConfigValue(
      key = ConfigKeys.MAX_TOKENS,
      defaultValue = if (model.llmMaxToken > 0) model.llmMaxToken else DEFAULT_MAX_TOKEN,
    )

  /** Encapsulates ephemeral state and timing for an active inference turn. */
  private class TurnState(val session: ConversationSession, private val timeSource: TimeSource) {
    val startTimeMark: TimeMark = timeSource.markNow()
    var ttftDuration: Duration? = null
      private set

    var totalDuration: Duration? = null
      private set

    var streamedOutputTokens: Int = 0
      private set

    fun recordToken(tokenText: String, thinkingText: String?) {
      val hasContent = tokenText.isNotEmpty() || !thinkingText.isNullOrEmpty()
      if (hasContent) {
        if (ttftDuration == null) {
          ttftDuration = startTimeMark.elapsedNow()
        }
        streamedOutputTokens++
      }
    }

    fun endTurn(): Duration {
      val duration = startTimeMark.elapsedNow()
      totalDuration = duration
      return duration
    }
  }

  /** Encapsulates cumulative token context stored in the KV cache across completed turns. */
  private class SessionState {
    var cumulativeContextTokens: Int? = null
      private set

    fun updateTokens(tokens: Int) {
      cumulativeContextTokens = tokens
    }

    fun addTokens(tokens: Int) {
      cumulativeContextTokens = (cumulativeContextTokens ?: 0) + tokens
    }

    fun reset() {
      cumulativeContextTokens = null
    }
  }

  private val activeTurn = AtomicReference<TurnState?>()
  private val sessionState = SessionState()

  /** Starts tracking a new inference turn with a [ConversationSession] abstraction. */
  fun startTurn(session: ConversationSession) {
    check(session.isAlive) { "Cannot start turn: Conversation is not alive." }
    val newTurn = TurnState(session = session, timeSource = timeSource)
    check(activeTurn.compareAndSet(null, newTurn)) {
      "Cannot start turn: previous turn is still in progress."
    }
  }

  /** Resets cumulative conversation token state when the session is cleared. */
  fun resetSession() {
    activeTurn.set(null)
    sessionState.reset()
  }

  /**
   * Processes streaming token callbacks, recording the initial token arrival time (TTFT) and
   * incrementing output token counts.
   *
   * @param tokenText Generated token text piece.
   * @param thinkingText Optional thinking text emitted by reasoning models.
   */
  fun onNewToken(tokenText: String, thinkingText: String? = null) {
    val turn =
      checkNotNull(activeTurn.get()) { "Received token outside of an active inference turn." }
    turn.recordToken(tokenText = tokenText, thinkingText = thinkingText)
  }

  private data class SessionDiagnostics(
    val benchmark: InferenceBenchmark? = null,
    val tokenCount: Int? = null,
  )

  private data class RawTurnSnapshot(
    val diagnostics: SessionDiagnostics,
    val streamedOutputTokens: Int,
    val turnDuration: Duration,
    val streamedTtft: Duration?,
    val modelInitDuration: Duration?,
    val maxContextTokens: Int,
    val previousCumulativeTokens: Int?,
  )

  private data class TokenBreakdown(
    val promptTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val newCumulativeSessionTokens: Int?,
  )

  private class TurnMetricsCalculator(
    private val snapshot: RawTurnSnapshot,
    private val isSuccess: Boolean,
  ) {
    private val tokenBreakdown: TokenBreakdown = computeTokens()

    val updatedSessionTokens: Int?
      get() = tokenBreakdown.newCumulativeSessionTokens

    fun calculateMetrics(): TurnInferenceMetrics {
      return turnInferenceMetrics {
        this.tokens = buildTokenMetrics()
        this.latency = buildLatencyMetrics()
        this.context = buildContextMetrics()
      }
    }

    private fun computeTokens(): TokenBreakdown {
      val benchmark = snapshot.diagnostics.benchmark
      val sessionTokens = snapshot.diagnostics.tokenCount
      val streamedOutput = snapshot.streamedOutputTokens

      val prompt: Int?
      val output: Int?
      val total: Int?

      if (benchmark?.prefillTokenCount != null && benchmark.prefillTokenCount > 0) {
        prompt = benchmark.prefillTokenCount
        output = benchmark.decodeTokenCount?.takeIf { it > 0 } ?: streamedOutput.takeIf { it > 0 }
        total = if (output != null) prompt + output else prompt
      } else if (sessionTokens != null && sessionTokens >= 0) {
        output = streamedOutput.takeIf { it > 0 }
        val prevTokens = snapshot.previousCumulativeTokens ?: 0
        val delta = (sessionTokens - prevTokens).takeIf { it > 0 }
        prompt =
          if (delta != null && output != null) {
            (delta - output).coerceAtLeast(0).takeIf { it > 0 }
          } else {
            null
          }
        total = delta ?: output
      } else {
        prompt = null
        output = streamedOutput.takeIf { it > 0 }
        total = output
      }

      val newCumulative =
        if (isSuccess) {
          sessionTokens?.takeIf { it > 0 }
            ?: if (total != null) {
              (snapshot.previousCumulativeTokens ?: 0) + total
            } else {
              snapshot.previousCumulativeTokens
            }
        } else {
          snapshot.previousCumulativeTokens
        }

      return TokenBreakdown(
        promptTokens = prompt,
        outputTokens = output,
        totalTokens = total,
        newCumulativeSessionTokens = newCumulative,
      )
    }

    private fun buildTokenMetrics(): TurnTokenMetrics = turnTokenMetrics {
      tokenBreakdown.promptTokens?.let { this.promptTokens = it }
      tokenBreakdown.outputTokens?.let { this.outputTokens = it }
      tokenBreakdown.totalTokens?.let { this.totalTokens = it }
    }

    private fun buildLatencyMetrics(): LatencyMetrics = latencyMetrics {
      val benchmark = snapshot.diagnostics.benchmark
      val ttft = benchmark?.timeToFirstToken ?: snapshot.streamedTtft
      val totalDuration = snapshot.turnDuration
      val decodeDuration = if (ttft != null && totalDuration >= ttft) totalDuration - ttft else null

      val prefillSpeed =
        benchmark?.prefillSpeedTps?.takeIf { it > 0f }
          ?: calculatePrefillSpeedTps(tokenBreakdown.promptTokens, ttft)
      val decodeSpeed =
        benchmark?.decodeSpeedTps?.takeIf { it > 0f }
          ?: calculateDecodeSpeedTps(tokenBreakdown.outputTokens, decodeDuration)

      ttft?.let { this.ttftMs = it.inWholeMilliseconds }
      this.totalLatencyMs = totalDuration.inWholeMilliseconds
      decodeDuration?.let { this.decodeDurationMs = it.inWholeMilliseconds }
      prefillSpeed?.takeIf { it > 0f }?.let { this.prefillSpeedTps = it }
      decodeSpeed?.takeIf { it > 0f }?.let { this.decodeSpeedTps = it }
      val initDuration = benchmark?.initDuration ?: snapshot.modelInitDuration
      initDuration?.takeIf { it.isPositive() }?.let { this.initDurationMs = it.inWholeMilliseconds }
    }

    private fun buildContextMetrics(): ContextMetrics = contextMetrics {
      tokenBreakdown.newCumulativeSessionTokens
        ?.takeIf { it > 0 }
        ?.let { this.consumedContextTokens = it }
      this.maxContextTokens = snapshot.maxContextTokens
    }

    private fun calculatePrefillSpeedTps(promptTokens: Int?, ttftDuration: Duration?): Float? {
      if (
        promptTokens == null ||
          promptTokens <= 0 ||
          ttftDuration == null ||
          ttftDuration <= Duration.ZERO
      ) {
        return null
      }
      val ttftSeconds = ttftDuration.toDouble(DurationUnit.SECONDS)
      if (ttftSeconds <= 0.0) return null
      return (promptTokens / ttftSeconds).toFloat()
    }

    /**
     * Computes the token decode throughput in tokens per second (tok/s).
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
   * Finalizes turn latency milestones, token counts, and context capacity at app level, and returns
   * [TurnInferenceMetrics].
   */
  fun endTurn(status: InferenceStatus.Code = InferenceStatus.Code.SUCCESS): TurnInferenceMetrics {
    val turn =
      checkNotNull(activeTurn.getAndSet(null)) { "Cannot end turn: No active turn in progress." }
    val turnDuration = turn.endTurn()
    val isSuccess = status == InferenceStatus.Code.SUCCESS

    val sessionDiagnostics = querySessionDiagnostics(turn.session, isSuccess)
    val snapshot =
      RawTurnSnapshot(
        diagnostics = sessionDiagnostics,
        streamedOutputTokens = turn.streamedOutputTokens,
        turnDuration = turnDuration,
        streamedTtft = turn.ttftDuration,
        modelInitDuration = model.initDuration,
        maxContextTokens = maxContextTokens,
        previousCumulativeTokens = sessionState.cumulativeContextTokens,
      )

    val calculator = TurnMetricsCalculator(snapshot = snapshot, isSuccess = isSuccess)
    val result = calculator.calculateMetrics()

    calculator.updatedSessionTokens?.let { sessionState.updateTokens(it) }

    return result
  }

  private fun querySessionDiagnostics(
    session: ConversationSession,
    isSuccess: Boolean,
  ): SessionDiagnostics {
    if (!session.isAlive || !isSuccess) return SessionDiagnostics()

    val benchmark =
      try {
        session.getBenchmark()
      } catch (t: Throwable) {
        Log.d(TAG, "Failed to query InferenceBenchmark: ${t.message}")
        null
      }

    val tokens =
      try {
        session.getTokenCount()
      } catch (t: Throwable) {
        Log.d(TAG, "Failed to query token count: ${t.message}")
        null
      }

    return SessionDiagnostics(benchmark = benchmark, tokenCount = tokens)
  }

  companion object {
    private const val TAG = "AGLitertlmInferenceMetricsTracker"
  }
}
