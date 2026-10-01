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
  /**
   * Inspects the model's active conversation token count and compacts context if token budget
   * thresholds are exceeded.
   *
   * @param sessionId The active session identifier.
   * @param model The target model instance.
   * @param sessionConfig Optional session configuration (system prompt, tools).
   * @param onCompactionStart Optional callback invoked immediately when compaction is triggered,
   *   before summarization and reset begin.
   * @param force Whether to force compaction regardless of token budget thresholds (as long as
   *   there are tokens in the conversation).
   * @return True if context was compacted and conversation was reset, false otherwise.
   */
  suspend fun compactContextIfNeeded(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig? = null,
    onCompactionStart: (() -> Unit)? = null,
    force: Boolean = false,
  ): Boolean

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
