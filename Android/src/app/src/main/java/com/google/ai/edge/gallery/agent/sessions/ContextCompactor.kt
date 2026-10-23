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

package com.google.ai.edge.gallery.agent.sessions

import com.google.ai.edge.gallery.data.Model

/**
 * Strategy interface for compacting active LLM conversation context when token usage approaches
 * model capacity limits.
 */
interface ContextCompactor {
  /** The [ContextCompactionAlgorithm] performed by this compactor. */
  val algorithm: ContextCompactionAlgorithm
    get() = ContextCompactionAlgorithm.SUMMARIZATION

  /**
   * Inspects the model's active conversation token count and compacts context if token budget
   * thresholds are exceeded.
   *
   * @param sessionId The active session identifier.
   * @param model The target model instance.
   * @param sessionConfig Optional session configuration (system prompt, tools).
   * @param onCompactionStart Optional callback invoked immediately when compaction is triggered,
   *   before the context is compacted and the conversation is reset.
   * @param force Whether to compact regardless of the compactor's token budget threshold.
   * @return [CompactionResult.COMPACTED] if context was compacted and the conversation was reset,
   *   [CompactionResult.NOT_NEEDED] if compaction was not needed, or [CompactionResult.FAILED] if
   *   compaction was attempted but failed.
   */
  suspend fun compactContextIfNeeded(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig? = null,
    onCompactionStart: (() -> Unit)? = null,
    force: Boolean = false,
  ): CompactionResult

  /**
   * Same as [compactContextIfNeeded], but also reports the size of the compacted history the
   * conversation was reset with.
   *
   * The default implementation delegates to [compactContextIfNeeded] and does not report a size.
   *
   * @param sessionId The active session identifier.
   * @param model The target model instance.
   * @param sessionConfig Optional session configuration (system prompt, tools).
   * @param onCompactionStart Optional callback invoked immediately when compaction is triggered,
   *   before the context is compacted and the conversation is reset.
   * @param force Whether to compact regardless of the compactor's token budget threshold.
   * @return A [CompactionReport] with the [CompactionResult] and, if the context was compacted, the
   *   total characters of the compacted history.
   */
  suspend fun compactContextReportingSize(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig? = null,
    onCompactionStart: (() -> Unit)? = null,
    force: Boolean = false,
  ): CompactionReport =
    CompactionReport(
      result =
        compactContextIfNeeded(
          sessionId = sessionId,
          model = model,
          sessionConfig = sessionConfig,
          onCompactionStart = onCompactionStart,
          force = force,
        )
    )

  /**
   * Inspects the model's active conversation token count and compacts context if token budget
   * thresholds are exceeded, returning a [ContextCompactionOutcome] when compaction succeeds or
   * `null` if no compaction occurred.
   *
   * @param sessionId The active session identifier.
   * @param model The target model instance.
   * @param sessionConfig Optional session configuration (system prompt, tools).
   * @param onCompactionStart Optional callback invoked immediately when compaction is triggered,
   *   before summarization and reset begin.
   * @param force Whether to force compaction regardless of token budget thresholds.
   * @param source The [ContextCompactionSource] describing what initiated the compaction check.
   * @return [ContextCompactionOutcome] if context was compacted, or `null` otherwise.
   */
  suspend fun compactContextWithOutcome(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig? = null,
    onCompactionStart: (() -> Unit)? = null,
    force: Boolean = false,
    source: ContextCompactionSource = ContextCompactionSource.TOKEN_LIMIT,
  ): ContextCompactionOutcome? {
    val result =
      compactContextIfNeeded(
        sessionId = sessionId,
        model = model,
        sessionConfig = sessionConfig,
        onCompactionStart = onCompactionStart,
        force = force,
      )
    if (result != CompactionResult.COMPACTED) {
      return null
    }
    val resolvedAlgorithm =
      if (
        algorithm == ContextCompactionAlgorithm.TRUNCATION ||
          source != ContextCompactionSource.TOKEN_LIMIT
      ) {
        ContextCompactionAlgorithm.TRUNCATION
      } else {
        ContextCompactionAlgorithm.SUMMARIZATION
      }
    return ContextCompactionOutcome(algorithm = resolvedAlgorithm, source = source)
  }

  /**
   * Checks whether the active conversation has reached or is approaching the model's token limit.
   *
   * @param sessionId The active session identifier.
   * @param model The target model instance.
   * @param sessionConfig Optional session configuration.
   * @return True if the token budget is low / limit is reached, false otherwise.
   */
  fun isTokenLimitReached(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig? = null,
  ): Boolean = false
}
