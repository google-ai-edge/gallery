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

import android.util.Log
import com.google.ai.edge.gallery.data.ChatSessionRepository
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.ChatMessageProto
import com.google.ai.edge.gallery.proto.ChatSideProto
import com.google.ai.edge.gallery.runtime.LlmConversationInstance
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TAG = "AGTruncationCompactor"
private const val TOKEN_LIMIT_THRESHOLD_RATIO = 0.9f
private const val TRUNCATION_TARGET_RATIO = 0.5f
private const val CHARS_PER_TOKEN_ESTIMATE = 4
private const val MESSAGE_TEMPLATE_OVERHEAD_TOKENS = 8
private const val ESTIMATED_TOKENS_PER_TOOL = 64

/**
 * [ContextCompactor] implementation that compacts conversation context by truncating older
 * persisted messages and resetting the conversation KV cache with the most recent turns that fit
 * within a target token budget.
 *
 * When the conversation has not been prefilled yet (e.g. right after a session is restored or reset
 * with its history, which is only prefilled together with the next turn), the conversation reports
 * zero tokens, so token usage is estimated from the persisted history, system instruction, and
 * tools instead.
 */
@Singleton
class TruncationContextCompactor
@Inject
constructor(private val chatSessionRepository: ChatSessionRepository) : ContextCompactor {

  override val algorithm: ContextCompactionAlgorithm = ContextCompactionAlgorithm.TRUNCATION

  override suspend fun compactContextIfNeeded(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig?,
    onCompactionStart: (() -> Unit)?,
    force: Boolean,
  ): CompactionResult =
    compactContextReportingSize(
        sessionId = sessionId,
        model = model,
        sessionConfig = sessionConfig,
        onCompactionStart = onCompactionStart,
        force = force,
      )
      .result

  /**
   * Compacts the context by truncating it if needed, reporting the total content length of the
   * messages the conversation was reset with as [CompactionReport.compactedCharCount].
   */
  override suspend fun compactContextReportingSize(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig?,
    onCompactionStart: (() -> Unit)?,
    force: Boolean,
  ): CompactionReport {
    currentCoroutineContext().ensureActive()
    val instance =
      model.instance as? LlmConversationInstance
        ?: return CompactionReport(CompactionResult.NOT_NEEDED)
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    if (maxTokens <= 0) {
      return CompactionReport(CompactionResult.NOT_NEEDED)
    }
    val currentTokens = getSafeTokenCount(instance)
    val isPrefilled = currentTokens > 0
    if (isPrefilled && !force && !isOverTokenThreshold(currentTokens, maxTokens)) {
      return CompactionReport(CompactionResult.NOT_NEEDED)
    }

    currentCoroutineContext().ensureActive()
    val allTextMessages = loadPersistedTextMessages(sessionId)

    val supportImage = sessionConfig?.supportImage ?: false
    val supportAudio = sessionConfig?.supportAudio ?: false
    val systemInstruction = sessionConfig?.systemInstruction
    val tools = sessionConfig?.tools ?: emptyList()
    val enableConversationConstrainedDecoding =
      sessionConfig?.enableConversationConstrainedDecoding ?: false

    val systemInstructionText = systemInstruction?.toString().orEmpty()
    val systemTokens =
      if (systemInstructionText.isNotEmpty()) estimateMessageTokens(systemInstructionText) else 0
    val toolTokens = tools.size * ESTIMATED_TOKENS_PER_TOOL

    if (!isPrefilled && !force) {
      val estimatedTokens =
        systemTokens + toolTokens + allTextMessages.sumOf { estimateMessageTokens(it.content) }
      if (!isOverTokenThreshold(estimatedTokens, maxTokens)) {
        return CompactionReport(CompactionResult.NOT_NEEDED)
      }
      Log.d(
        TAG,
        "Estimated $estimatedTokens of $maxTokens tokens for un-prefilled session $sessionId; truncating.",
      )
    }

    onCompactionStart?.invoke()

    // A trailing user message is a pending turn that the next inference request sends itself, so
    // it is never seeded into the conversation history.
    val candidateMessages = allTextMessages.dropLastWhile {
      it.side == ChatSideProto.CHAT_SIDE_USER
    }
    val rawTargetBudget = (maxTokens * TRUNCATION_TARGET_RATIO).toInt()
    val overheadTokens = systemTokens + toolTokens
    val targetTokenBudget =
      if (overheadTokens < rawTargetBudget) {
        rawTargetBudget - overheadTokens
      } else {
        val fallbackBudget =
          ((maxTokens - overheadTokens) * TRUNCATION_TARGET_RATIO).toInt().coerceAtLeast(0)
        Log.w(
          TAG,
          "System and tool token overhead ($overheadTokens) exhausts truncation target budget ($rawTargetBudget) for session $sessionId; using fallback budget of $fallbackBudget tokens.",
        )
        fallbackBudget
      }
    val selectedReversed = mutableListOf<ChatMessageProto>()
    var accumulatedTokens = 0
    for (i in candidateMessages.indices.reversed()) {
      val message = candidateMessages[i]
      val messageTokens = estimateMessageTokens(message.content)
      if (accumulatedTokens + messageTokens > targetTokenBudget) {
        break
      }
      accumulatedTokens += messageTokens
      selectedReversed.add(message)
    }

    val keptMessages = selectedReversed.asReversed().toMutableList()
    if (keptMessages.size == candidateMessages.size && keptMessages.isNotEmpty()) {
      keptMessages.removeAt(0)
    }
    while (keptMessages.isNotEmpty() && keptMessages.first().side != ChatSideProto.CHAT_SIDE_USER) {
      keptMessages.removeAt(0)
    }

    val truncatedMessages = keptMessages.map { proto ->
      if (proto.side == ChatSideProto.CHAT_SIDE_USER) {
        Message.user(Contents.of(proto.content))
      } else {
        Message.model(Contents.of(proto.content))
      }
    }

    return try {
      currentCoroutineContext().ensureActive()
      model.runtimeHelper.resetConversation(
        model = model,
        supportImage = supportImage,
        supportAudio = supportAudio,
        systemInstruction = systemInstruction,
        tools = tools,
        enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
        initialMessages = truncatedMessages,
      )
      Log.i(
        TAG,
        "Context truncation and conversation reset complete for session $sessionId (kept ${truncatedMessages.size} of ${allTextMessages.size} messages, targetBudget=$targetTokenBudget tokens).",
      )
      CompactionReport(
        result = CompactionResult.COMPACTED,
        compactedCharCount = keptMessages.sumOf { it.content.length },
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Failed to reset conversation with truncated history for session $sessionId", e)
      CompactionReport(CompactionResult.FAILED)
    }
  }

  override fun isTokenLimitReached(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig?,
  ): Boolean {
    val instance = model.instance as? LlmConversationInstance ?: return false
    val maxTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
    val currentTokens = getSafeTokenCount(instance)
    return isOverTokenThreshold(currentTokens, maxTokens)
  }

  /**
   * Returns the persisted user and model text messages of [sessionId], or an empty list if they
   * cannot be read.
   */
  private suspend fun loadPersistedTextMessages(sessionId: String): List<ChatMessageProto> {
    val persistedMessages =
      try {
        chatSessionRepository
          .getAllChatSessions()
          .firstOrNull { it.sessionId == sessionId }
          ?.messagesList
          .orEmpty()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(
          TAG,
          "Failed to read persisted chat sessions for $sessionId; resetting with empty history",
          e,
        )
        emptyList()
      }
    return persistedMessages.filter { it.isSeededTextMessage }
  }

  private fun isOverTokenThreshold(currentTokens: Int, maxTokens: Int): Boolean =
    currentTokens > 0 && maxTokens > 0 && currentTokens > (maxTokens * TOKEN_LIMIT_THRESHOLD_RATIO)

  private fun estimateMessageTokens(content: String): Int =
    (content.length + CHARS_PER_TOKEN_ESTIMATE - 1) / CHARS_PER_TOKEN_ESTIMATE +
      MESSAGE_TEMPLATE_OVERHEAD_TOKENS

  private fun getSafeTokenCount(instance: LlmConversationInstance): Int =
    try {
      instance.getTokenCount()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      0
    }
}
