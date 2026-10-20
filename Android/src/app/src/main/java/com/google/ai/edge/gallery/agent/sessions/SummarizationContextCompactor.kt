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
private const val FAILURE_BACKOFF_CHECKS = 3

/**
 * Default [ContextCompactor] implementation that checks the active conversation token budget and
 * performs dynamic LLM auto-summarization and conversation KV-cache reset when approaching model
 * token limits, falling back to [fallbackCompactor] when token headroom is insufficient or
 * summarization fails.
 */
@Singleton
class SummarizationContextCompactor
@Inject
constructor(private val fallbackCompactor: ContextCompactor?) : ContextCompactor {

  constructor() : this(fallbackCompactor = null)

  private val sessionMutexes = ConcurrentHashMap<String, Mutex>()
  private val backoffChecksRemaining = ConcurrentHashMap<String, Int>()

  override suspend fun compactContextIfNeeded(
    sessionId: String,
    model: Model,
    sessionConfig: SessionConfig?,
    onCompactionStart: (() -> Unit)?,
    force: Boolean,
  ): Boolean {
    val mutex = sessionMutexes.getOrPut(sessionId) { Mutex() }
    return mutex.withLock {
      currentCoroutineContext().ensureActive()
      val instance = model.instance as? LlmConversationInstance ?: return@withLock false
      val maxTokens =
        model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
      val currentTokens = getSafeTokenCount(instance)
      val overThreshold = isOverTokenThreshold(currentTokens, maxTokens)

      if (!overThreshold) {
        backoffChecksRemaining.remove(sessionId)
      }

      val tokensLeft = maxTokens - currentTokens
      Log.d(
        TAG,
        "Checking token budget for session $sessionId: currentTokens=$currentTokens, maxTokens=$maxTokens, tokensLeft=$tokensLeft, force=$force",
      )

      if (currentTokens > 0 && (force || overThreshold)) {
        if (!force) {
          val remainingBackoff = backoffChecksRemaining[sessionId] ?: 0
          if (remainingBackoff > 0) {
            backoffChecksRemaining[sessionId] = remainingBackoff - 1
            Log.d(
              TAG,
              "Skipping auto-compaction for session $sessionId due to previous failure back-off (remaining=$remainingBackoff).",
            )
            return@withLock false
          }
        }

        Log.i(
          TAG,
          "Triggering summarization and reset for session $sessionId (force=$force, tokensLeft=$tokensLeft out of $maxTokens).",
        )
        onCompactionStart?.invoke()

        val summaryText =
          if (tokensLeft >= MIN_TOKENS_LEFT_FOR_SUMMARIZATION) {
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
                null
              }

            currentCoroutineContext().ensureActive()

            val summaryDurationMs = System.currentTimeMillis() - summaryStartTimestamp
            val generatedText = summaryMessage?.toString() ?: ""
            Log.d(
              TAG,
              "Generated summary text length: ${generatedText.length}. Time taken: ${summaryDurationMs}ms.",
            )
            generatedText
          } else {
            Log.w(
              TAG,
              "Insufficient token headroom for summarization in session $sessionId (tokensLeft=$tokensLeft < min=$MIN_TOKENS_LEFT_FOR_SUMMARIZATION); falling back to truncation.",
            )
            ""
          }

        if (summaryText.isNotBlank()) {
          val initialMessages =
            listOf(
              Message.user(Contents.of("Here is a summary of our past conversation for context:")),
              Message.model(Contents.of(summaryText)),
            )

          val supportImage = sessionConfig?.supportImage ?: false
          val supportAudio = sessionConfig?.supportAudio ?: false
          val systemInstruction = sessionConfig?.systemInstruction
          val tools = sessionConfig?.tools ?: emptyList()
          val enableConversationConstrainedDecoding =
            sessionConfig?.enableConversationConstrainedDecoding ?: false

          try {
            currentCoroutineContext().ensureActive()
            model.runtimeHelper.resetConversation(
              model = model,
              supportImage = supportImage,
              supportAudio = supportAudio,
              systemInstruction = systemInstruction,
              tools = tools,
              enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
              initialMessages = initialMessages,
            )
            backoffChecksRemaining.remove(sessionId)
            Log.i(
              TAG,
              "Auto-summarization and conversation reset process complete for session $sessionId.",
            )
            return@withLock true
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Failed to reset conversation with summary for session $sessionId", e)
          }
        }

        currentCoroutineContext().ensureActive()
        val fallbackSucceeded =
          try {
            fallbackCompactor?.compactContextIfNeeded(
              sessionId = sessionId,
              model = model,
              sessionConfig = sessionConfig,
              onCompactionStart = null,
              force = true,
            ) ?: false
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Fallback context compaction failed for session $sessionId", e)
            false
          }
        if (fallbackSucceeded) {
          backoffChecksRemaining.remove(sessionId)
          return@withLock true
        }
        backoffChecksRemaining[sessionId] = FAILURE_BACKOFF_CHECKS
        return@withLock false
      }
      false
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
