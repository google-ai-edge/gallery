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

package com.google.ai.edge.gallery.common.tokens

import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Estimates token counts without running a model tokenizer.
 *
 * Use it to plan context budgets before a model is loaded or when exact counts are too expensive,
 * such as when sizing chunks, truncating history, or limiting prompt output length. When a live
 * conversation can report its exact token count, prefer that count over these estimates.
 */
interface TokenEstimator {

  /**
   * Returns an estimate of the number of tokens in [text].
   *
   * For Latin-script text the estimate leans toward overcounting, so that budgets built on it
   * rarely overflow the context window. Scripts that tokenize to about one token per character,
   * such as CJK, are undercounted by up to several times; callers that must not overflow the
   * context window with such text should leave extra headroom or use an exact token count.
   *
   * Returns 0 for empty text and at least 1 for non-empty text.
   */
  fun estimateTextTokens(text: String): Int

  /**
   * Returns the estimated tokens for [text] sent as one chat message, including the chat template
   * tokens that wrap every message.
   *
   * Empty text still costs the template overhead.
   */
  fun estimateMessageTokens(text: String): Int

  /**
   * Returns the estimated tokens for an audio clip lasting [durationSec] seconds.
   *
   * Returns 0 when [durationSec] is zero, negative, or non-finite.
   */
  fun estimateAudioTokens(durationSec: Float): Int

  /**
   * Returns the estimated tokens for an audio clip lasting [duration].
   *
   * Returns 0 when [duration] is zero, negative, or infinite.
   */
  fun estimateAudioTokens(duration: Duration): Int =
    if (!duration.isFinite() || !duration.isPositive()) {
      0
    } else {
      estimateAudioTokens(duration.toDouble(DurationUnit.SECONDS).toFloat())
    }

  /**
   * Returns the approximate number of words that fit in [tokens], such as a word limit to state in
   * a prompt so the model's response stays within a token budget.
   *
   * Returns 0 when [tokens] is zero or negative.
   */
  fun tokensToWords(tokens: Int): Int

  /**
   * Returns the maximum number of characters of text that fit in [tokens], such as a slice length
   * for splitting text to a token budget.
   *
   * Any text no longer than this is estimated by [estimateTextTokens] at no more than [tokens], so
   * it inherits the same limitations, including the undercount for scripts such as CJK.
   *
   * Returns 0 when [tokens] is zero or negative.
   */
  fun tokensToChars(tokens: Int): Int
}
