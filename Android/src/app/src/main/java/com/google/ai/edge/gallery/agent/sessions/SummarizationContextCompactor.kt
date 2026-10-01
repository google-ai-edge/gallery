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
private const val TOKEN_LIMIT_THRESHOLD_RATIO = 0.75
private const val WORD_TO_TOKEN_RATIO = 0.75
private const val MIN_SUMMARY_WORD_LIMIT = 50
private const val MAX_SUMMARY_WORD_LIMIT = 1000
private const val FAILURE_BACKOFF_CHECKS = 3

/**
 * Default [ContextCompactor] implementation that checks the active conversation token budget and
 * performs dynamic LLM auto-summarization and conversation KV-cache reset when approaching model
 * token limits.
 */
@Singleton
class SummarizationContextCompactor @Inject constructor() : ContextCompactor {

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
        val wordLimit =
          min(
            (tokensLeft * WORD_TO_TOKEN_RATIO).toInt().coerceAtLeast(MIN_SUMMARY_WORD_LIMIT),
            MAX_SUMMARY_WORD_LIMIT,
          )
        val summaryStartTimestamp = System.currentTimeMillis()
        val summaryMessage =
          try {
            instance.sendMessage(
              "Summarize the core points of our conversation so far in less than $wordLimit words, ensuring no important context is lost."
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
        val summaryText = summaryMessage?.toString() ?: ""
        Log.d(
          TAG,
          "Generated summary text length: ${summaryText.length}. Time taken: ${summaryDurationMs}ms.",
        )

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

          return@withLock try {
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
            true
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            backoffChecksRemaining[sessionId] = FAILURE_BACKOFF_CHECKS
            Log.e(TAG, "Failed to reset conversation with summary for session $sessionId", e)
            false
          }
        } else {
          backoffChecksRemaining[sessionId] = FAILURE_BACKOFF_CHECKS
        }
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

  private fun isOverTokenThreshold(currentTokens: Int, maxTokens: Int): Boolean =
    currentTokens > 0 && currentTokens > (maxTokens * TOKEN_LIMIT_THRESHOLD_RATIO)
}
