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

import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ExperimentalApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Engine-agnostic benchmark telemetry and performance diagnostics reported directly by the
 * underlying inference runtime for a completed or active turn.
 *
 * Runtimes capable of internal benchmarking populate these ground-truth measurements. Any
 * unsupported or unavailable metrics remain `null`, allowing trackers to compute fallback estimates
 * from app-level observation.
 */
data class InferenceBenchmark(
  val timeToFirstToken: Duration? = null,
  val prefillTokenCount: Int? = null,
  val decodeTokenCount: Int? = null,
  val prefillSpeedTps: Float? = null,
  val decodeSpeedTps: Float? = null,
  val initDuration: Duration? = null,
)

/**
 * Abstraction providing access to conversation runtime state, token count, and engine benchmark
 * diagnostics across diverse ML execution engines (e.g., LiteRT-LM, AICore).
 */
interface ConversationSession {
  /** Indicates whether the underlying conversation session is valid and active. */
  val isAlive: Boolean

  /**
   * Returns the cumulative token count currently stored in the KV cache, or null if unsupported.
   */
  fun getTokenCount(): Int?

  /** Returns runtime benchmark metrics from the inference engine, if supported. */
  fun getBenchmark(): InferenceBenchmark?
}

/** Wraps a LiteRT-LM [Conversation] into an engine-agnostic [ConversationSession]. */
fun Conversation.asSession(): ConversationSession =
  object : ConversationSession {
    override val isAlive: Boolean
      get() = this@asSession.isAlive

    override fun getTokenCount(): Int? =
    null

    override fun getBenchmark(): InferenceBenchmark? =
      try {
        @OptIn(ExperimentalApi::class) this@asSession.getBenchmarkInfo().toInferenceBenchmark()
      } catch (_: Throwable) {
        null
      }
  }

/** Converts a LiteRT-LM [BenchmarkInfo] instance into an engine-agnostic [InferenceBenchmark]. */
internal fun BenchmarkInfo.toInferenceBenchmark(): InferenceBenchmark =
  InferenceBenchmark(
    timeToFirstToken = timeToFirstTokenInSecond.takeIf { it > 0.0 }?.seconds,
    prefillTokenCount = lastPrefillTokenCount.takeIf { it > 0 },
    decodeTokenCount = lastDecodeTokenCount.takeIf { it > 0 },
    prefillSpeedTps = lastPrefillTokensPerSecond.takeIf { it > 0.0 }?.toFloat(),
    decodeSpeedTps = lastDecodeTokensPerSecond.takeIf { it > 0.0 }?.toFloat(),
    initDuration = initTimeInSecond.takeIf { it > 0.0 }?.seconds,
  )
