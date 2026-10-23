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
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider

/** Outcome of a context compaction call. */
enum class CompactionResult {
  /** Context was compacted and the conversation was reset with the compacted history. */
  COMPACTED,
  /** Compaction was not needed, e.g. because the token usage is below the compactor's threshold. */
  NOT_NEEDED,
  /** Compaction was needed (or forced) and attempted, but it failed. */
  FAILED,
}

/** Algorithm used to compact conversation context. */
enum class ContextCompactionAlgorithm(val value: String) {
  SUMMARIZATION("summarization"),
  TRUNCATION("truncation"),
}

/** Source reason that triggered conversation context compaction. */
enum class ContextCompactionSource(val value: String) {
  TOKEN_LIMIT("token_limit"),
  SUMMARIZATION_FALLBACK("summarization_fallback"),
  CHAT_HISTORY("chat_history"),
}

/**
 * Result of a context compaction call together with the size of the compacted history.
 *
 * @property result The [CompactionResult] of the compaction call.
 * @property compactedCharCount Total characters of the compacted history the conversation was reset
 *   with (e.g. the generated summary, or the kept messages), or `null` if the conversation was not
 *   compacted or the compactor does not report it.
 */
data class CompactionReport(val result: CompactionResult, val compactedCharCount: Int? = null)

/**
 * Sizes of the conversation context before and after a context compaction.
 *
 * All sizes are lengths in UTF-16 code units ([String.length]).
 *
 * @property chatCharCount Total characters of the session's cumulative persisted user and model
 *   text chat history (excluding system and non-text messages). This is not the size of the live
 *   conversation context, which can be smaller after an earlier compaction in the session.
 * @property compactedCharCount Total characters of the compacted history the conversation was reset
 *   with: for summarization, the summary text only (excluding the framing user turn that introduces
 *   it); for truncation, the content of every kept message as seeded into the conversation.
 */
data class ContextCompactionSizes(val chatCharCount: Int, val compactedCharCount: Int)

/**
 * Outcome of a successful context compaction operation.
 *
 * @property algorithm The [ContextCompactionAlgorithm] that compacted the conversation.
 * @property source The [ContextCompactionSource] describing why that algorithm ran.
 * @property sizes The [ContextCompactionSizes] of the conversation context before and after
 *   compaction, or `null` if the compactor did not report them or the session has no persisted text
 *   chat history.
 */
data class ContextCompactionOutcome(
  val algorithm: ContextCompactionAlgorithm,
  val source: ContextCompactionSource,
  val sizes: ContextCompactionSizes? = null,
)

/**
 * Whether this persisted message is a user or model text message, i.e. a message that is seeded
 * into the conversation history when a session is restored or its context is truncated.
 */
internal val ChatMessageProto.isSeededTextMessage: Boolean
  get() =
    messageType == "TEXT" &&
      (side == ChatSideProto.CHAT_SIDE_USER || side == ChatSideProto.CHAT_SIDE_MODEL)

/**
 * Data class representing session configuration (model, taskId, system prompt, tools).
 *
 * Note that a session ID does not tie the session to a specific model; a session configuration can
 * be created or updated with different models over the lifetime of the same session ID.
 *
 * @property model The [Model] associated with this session configuration.
 * @property taskId The task ID associated with this session.
 * @property supportImage Whether image input is enabled for this session.
 * @property supportAudio Whether audio input is enabled for this session.
 * @property systemInstruction Optional system instruction prompt prefix.
 * @property tools List of tool providers available to the model.
 * @property enableConversationConstrainedDecoding Whether conversation constrained decoding is
 *   enabled for this session.
 */
data class SessionConfig(
  val model: Model,
  val taskId: String,
  val supportImage: Boolean = false,
  val supportAudio: Boolean = false,
  val systemInstruction: Contents? = null,
  val tools: List<ToolProvider> = emptyList(),
  val enableConversationConstrainedDecoding: Boolean = false,
)
