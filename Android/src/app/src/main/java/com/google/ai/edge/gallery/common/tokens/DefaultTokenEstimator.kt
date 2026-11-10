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

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Characters of text per token.
 *
 * English text averages about 4 characters per token with SentencePiece-style tokenizers. 3.5 is
 * used instead so the estimate overcounts slightly: running over a context budget fails a request,
 * while running under it only leaves some context unused. Characters are counted in UTF-16 code
 * units, so a character outside the Basic Multilingual Plane, such as an emoji, counts as two.
 *
 * Scripts that tokenize to about one token per character, such as CJK, are undercounted.
 */
private const val CHARS_PER_TOKEN = 3.5

/**
 * Chat template tokens that wrap each message, such as turn start and end markers and the role
 * name.
 */
private const val MESSAGE_TEMPLATE_OVERHEAD_TOKENS = 8

/**
 * Audio tokens the streaming audio encoder emits per second of 16 kHz mono audio.
 *
 * Derived rather than measured: the mel frontend produces 100 frames per second (16 kHz with a 160
 * sample hop) and the encoder shrinks them by a factor of 16.
 *
 * `Float` durations are widened to `Double` before multiplying. A duration that is a whole number
 * of tokens in decimal but not exactly representable as a `Float` can therefore round up to one
 * extra token (for example, `1.6f` yields 11 rather than 10). That is acceptable because it errs
 * toward overcounting.
 */
private const val AUDIO_TOKENS_PER_SECOND = 6.25

/** Words of English text per token, the inverse of the common 4-tokens-per-3-words rule. */
private const val WORDS_PER_TOKEN = 0.75

/**
 * [TokenEstimator] based on fixed per-character, per-second, and per-word ratios.
 *
 * Counts are rounded up when estimating tokens and rounded down when converting tokens to words or
 * characters, so both directions stay within the budget.
 */
@Singleton
class DefaultTokenEstimator @Inject constructor() : TokenEstimator {

  override fun estimateTextTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN).toInt()

  override fun estimateMessageTokens(text: String): Int =
    estimateTextTokens(text) + MESSAGE_TEMPLATE_OVERHEAD_TOKENS

  override fun estimateAudioTokens(durationSec: Float): Int {
    if (!durationSec.isFinite() || durationSec <= 0f) return 0
    return ceil(durationSec * AUDIO_TOKENS_PER_SECOND).toInt()
  }

  override fun estimateAudioTokens(duration: Duration): Int {
    if (!duration.isFinite() || !duration.isPositive()) return 0
    return ceil(duration.toDouble(DurationUnit.SECONDS) * AUDIO_TOKENS_PER_SECOND).toInt()
  }

  override fun tokensToWords(tokens: Int): Int {
    if (tokens <= 0) return 0
    return floor(tokens * WORDS_PER_TOKEN).toInt()
  }

  override fun tokensToChars(tokens: Int): Int {
    if (tokens <= 0) return 0
    return floor(tokens * CHARS_PER_TOKEN).toInt()
  }
}
