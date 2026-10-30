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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A single recently recognized word chip displayed in the study game status bar.
 *
 * @property id Stable identifier, preserved while the same word stays visible across transcripts.
 * @property appearedAtNanos [System.nanoTime] timestamp of when the chip first appeared.
 */
data class XrLanguageTeacherGameHeardWord(val id: Long, val text: String, val appearedAtNanos: Long)

/**
 * Tracks a rolling window of up to [MAX_VISIBLE_CHIPS] recently spoken word chips for the `Vocab
 * Popper` and `Vocab Runner` status bars.
 *
 * Feed every cumulative ASR transcript of the current recognition segment to [onTranscript]. Not
 * thread-safe: call all methods from the main thread.
 *
 * @param autoExpireScope Scope used to hide the chips [CHIP_LIFETIME_MILLIS] after the last new
 *   word, typically the owning ViewModel's `viewModelScope`; `null` disables auto-expiry (tests).
 * @param nanoTime Monotonic clock used to stamp new chips.
 */
class XrLanguageTeacherGameHeardWords(
  private val autoExpireScope: CoroutineScope? = null,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  private val _words = MutableStateFlow<List<XrLanguageTeacherGameHeardWord>>(emptyList())

  /** The currently visible heard-word chips, oldest first. */
  val words: StateFlow<List<XrLanguageTeacherGameHeardWord>> = _words.asStateFlow()

  private var nextId = 1L
  private var hiddenWordCount = 0
  private var latestWordCount = 0
  private var previousTokens: List<String> = emptyList()
  private var holdJob: Job? = null

  /**
   * Updates the visible heard-word chips from a cumulative recognition [transcript].
   *
   * @param transcript Cumulative transcript for the current recognition segment.
   * @param isUsed When `true` (a spoken word matched a target or obstacle), all words in the
   *   transcript up to this point are consumed and hidden immediately so they do not linger.
   */
  fun onTranscript(transcript: String, isUsed: Boolean = false) {
    val tokens = extractDisplayWords(transcript)
    if (tokens.isEmpty()) return

    if (tokens.size < hiddenWordCount) {
      hiddenWordCount = 0
    }
    latestWordCount = tokens.size

    if (isUsed) {
      previousTokens = tokens
      hideAll()
      return
    }

    if (tokensMatch(tokens, previousTokens)) return
    previousTokens = tokens

    val visibleSlice =
      collapseConsecutiveDuplicates(tokens.drop(hiddenWordCount)).takeLast(MAX_VISIBLE_CHIPS)
    if (visibleSlice.isEmpty()) {
      _words.value = emptyList()
      return
    }

    val now = nanoTime()
    val remainingExisting = _words.value.toMutableList()
    _words.value = visibleSlice.map { token ->
      val existingIndex = remainingExisting.indexOfFirst {
        it.text.equals(token, ignoreCase = true)
      }
      if (existingIndex >= 0) {
        remainingExisting.removeAt(existingIndex).copy(text = token)
      } else {
        XrLanguageTeacherGameHeardWord(id = nextId++, text = token, appearedAtNanos = now)
      }
    }

    if (autoExpireScope != null) {
      scheduleHoldExpiry(autoExpireScope)
    }
  }

  /** Resets per-segment token counters when the speech recognizer starts a new segment. */
  fun resetSession() {
    hiddenWordCount = 0
    latestWordCount = 0
    previousTokens = emptyList()
  }

  /** Clears all heard-word chips and cancels the pending expiry. */
  fun clear() {
    holdJob?.cancel()
    holdJob = null
    hiddenWordCount = 0
    latestWordCount = 0
    previousTokens = emptyList()
    _words.value = emptyList()
  }

  private fun hideAll() {
    holdJob?.cancel()
    holdJob = null
    hiddenWordCount = latestWordCount
    _words.value = emptyList()
  }

  private fun scheduleHoldExpiry(scope: CoroutineScope) {
    holdJob?.cancel()
    holdJob = scope.launch {
      delay(CHIP_LIFETIME_MILLIS)
      hideAll()
    }
  }

  companion object {
    /** Maximum number of heard-word chips visible at once. */
    const val MAX_VISIBLE_CHIPS = 3

    /** How long chips stay visible after the last new word. */
    const val CHIP_LIFETIME_MILLIS = 1_500L

    /** Splits [transcript] on whitespace and trims leading and trailing punctuation and symbols. */
    private fun extractDisplayWords(transcript: String): List<String> =
      transcript.split(WHITESPACE_REGEX).map(::trimPunctuationAndSymbols).filter { it.isNotEmpty() }

    private fun trimPunctuationAndSymbols(part: String): String {
      var start = 0
      var end = part.length
      while (start < end) {
        val codePoint = part.codePointAt(start)
        if (!isPunctuationOrSymbol(codePoint)) break
        start += Character.charCount(codePoint)
      }
      while (end > start) {
        val codePoint = part.codePointBefore(end)
        if (!isPunctuationOrSymbol(codePoint)) break
        end -= Character.charCount(codePoint)
      }
      return part.substring(start, end)
    }

    /** Unicode general categories `P*` and `S*`. */
    private fun isPunctuationOrSymbol(codePoint: Int): Boolean =
      when (Character.getType(codePoint).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL,
        Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL,
        Character.OTHER_SYMBOL -> true
        else -> false
      }

    private fun collapseConsecutiveDuplicates(tokens: List<String>): List<String> {
      val result = mutableListOf<String>()
      for (token in tokens) {
        if (result.lastOrNull()?.equals(token, ignoreCase = true) == true) continue
        result += token
      }
      return result
    }

    private fun tokensMatch(lhs: List<String>, rhs: List<String>): Boolean =
      lhs.size == rhs.size && lhs.zip(rhs).all { (a, b) -> a.equals(b, ignoreCase = true) }

    /** ASCII whitespace plus Unicode separators (e.g. the ideographic space `U+3000`). */
    private val WHITESPACE_REGEX = Regex("[\\s\\p{Z}\\u0085]+")
  }
}
