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
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.LlmConversationInstance
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGSummarizationCompactor"
private const val WORD_TO_TOKEN_RATIO = 0.75
private const val MIN_SUMMARY_WORD_LIMIT = 50
private const val MAX_SUMMARY_WORD_LIMIT = 1000
private const val SUMMARY_PROMPT_OVERHEAD_TOKENS = 64
private const val MIN_SUMMARY_OUTPUT_TOKENS = 64
private const val MIN_TOKENS_LEFT_FOR_SUMMARIZATION =
  SUMMARY_PROMPT_OVERHEAD_TOKENS + MIN_SUMMARY_OUTPUT_TOKENS
private const val MAX_SUMMARY_OUTPUT_TOKENS = 1350

/**
 * [ContextCompactor] implementation that checks the active conversation token budget and performs
 * dynamic LLM auto-summarization and conversation KV-cache reset when approaching model token
 * limits.
 *
 * Summarization needs a prefilled conversation and enough token headroom to generate the summary;
 * it reports [CompactionResult.FAILED] when compaction is needed but cannot be completed, so that
 * the caller can fall back to another compaction strategy.
 */
@Singleton
class SummarizationContextCompactor @Inject constructor() : ContextCompactor {

  override val algorithm: ContextCompactionAlgorithm = ContextCompactionAlgorithm.SUMMARIZATION

  private val sessionMutexes = ConcurrentHashMap<String, Mutex>()

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
   * Compacts the context by summarizing it if needed, reporting the length of the summary text the
   * conversation was reset with as [CompactionReport.compactedCharCount].
   */
  override suspend fun compactContextReportingSize(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig?,
    onCompactionStart: (() -> Unit)?,
    force: Boolean,
  ): CompactionReport {
    val mutex = sessionMutexes.getOrPut(sessionId) { Mutex() }
    return mutex.withLock {
      currentCoroutineContext().ensureActive()
      val instance =
        model.instance as? LlmConversationInstance
          ?: return@withLock CompactionReport(CompactionResult.NOT_NEEDED)
      val maxTokens =
        model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
      val currentTokens = getSafeTokenCount(instance)
      val tokensLeft = maxTokens - currentTokens
      Log.d(
        TAG,
        "Checking token budget for session $sessionId: currentTokens=$currentTokens, maxTokens=$maxTokens, tokensLeft=$tokensLeft, force=$force",
      )

      if (currentTokens == 0) {
        // The conversation has not been prefilled yet (e.g. right after a session restore or
        // reset), so its token usage is unknown and it cannot summarize itself.
        return@withLock CompactionReport(
          if (force) CompactionResult.FAILED else CompactionResult.NOT_NEEDED
        )
      }
      if (!force && !isOverTokenThreshold(currentTokens, maxTokens)) {
        return@withLock CompactionReport(CompactionResult.NOT_NEEDED)
      }

      Log.i(
        TAG,
        "Triggering summarization and reset for session $sessionId (force=$force, tokensLeft=$tokensLeft out of $maxTokens).",
      )
      onCompactionStart?.invoke()

      if (tokensLeft < MIN_TOKENS_LEFT_FOR_SUMMARIZATION) {
        Log.w(
          TAG,
          "Insufficient token headroom for summarization in session $sessionId (tokensLeft=$tokensLeft < min=$MIN_TOKENS_LEFT_FOR_SUMMARIZATION).",
        )
        return@withLock CompactionReport(CompactionResult.FAILED)
      }

      val availableSummaryTokens =
        (tokensLeft - SUMMARY_PROMPT_OVERHEAD_TOKENS).coerceAtMost(MAX_SUMMARY_OUTPUT_TOKENS)
      val wordLimit =
        min(
          (availableSummaryTokens * WORD_TO_TOKEN_RATIO)
            .toInt()
            .coerceAtLeast(MIN_SUMMARY_WORD_LIMIT),
          MAX_SUMMARY_WORD_LIMIT,
        )
      val summaryStartTimestamp = System.currentTimeMillis()
      val summaryMessage =
        try {
          instance.sendMessage(
            prompt =
              "Summarize the core points of our conversation so far in less than $wordLimit words, ensuring no important context is lost.",
            maxOutputTokens = availableSummaryTokens,
          )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(
            TAG,
            "Failed to dynamically summarize conversation history for session $sessionId",
            e,
          )
          return@withLock CompactionReport(CompactionResult.FAILED)
        }

      currentCoroutineContext().ensureActive()

      val summaryDurationMs = System.currentTimeMillis() - summaryStartTimestamp
      val summaryText = summaryMessage.toString().trim()
      Log.d(
        TAG,
        "Generated summary text length: ${summaryText.length}. Time taken: ${summaryDurationMs}ms.",
      )
      if (summaryText.isBlank()) {
        Log.w(TAG, "Generated a blank summary for session $sessionId.")
        return@withLock CompactionReport(CompactionResult.FAILED)
      }

      val initialMessages =
        listOf(
          Message.user(Contents.of("Here is a summary of our past conversation for context:")),
          Message.model(Contents.of(summaryText)),
        )
      try {
        currentCoroutineContext().ensureActive()
        model.runtimeHelper.resetConversation(
          model = model,
          supportImage = sessionConfig?.supportImage ?: false,
          supportAudio = sessionConfig?.supportAudio ?: false,
          systemInstruction = sessionConfig?.systemInstruction,
          tools = sessionConfig?.tools ?: emptyList(),
          enableConversationConstrainedDecoding =
            sessionConfig?.enableConversationConstrainedDecoding ?: false,
          initialMessages = initialMessages,
        )
        Log.i(
          TAG,
          "Auto-summarization and conversation reset process complete for session $sessionId.",
        )
        CompactionReport(
          result = CompactionResult.COMPACTED,
          compactedCharCount = summaryText.length,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Failed to reset conversation with summary for session $sessionId", e)
        CompactionReport(CompactionResult.FAILED)
      }
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

  private fun getSafeTokenCount(instance: LlmConversationInstance): Int =
    try {
      instance.getTokenCount()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      0
    }

  companion object {
    /** Fraction of the maximum context window at which automatic context compaction triggers. */
    const val TOKEN_LIMIT_THRESHOLD_RATIO: Float = 0.75f

    /** Returns true if [currentTokens] exceeds the context compaction threshold for [maxTokens]. */
    fun isOverTokenThreshold(currentTokens: Int, maxTokens: Int): Boolean =
      currentTokens > 0 &&
        maxTokens > 0 &&
        currentTokens > (maxTokens * TOKEN_LIMIT_THRESHOLD_RATIO)
  }
}
