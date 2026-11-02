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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import kotlin.math.max
import kotlin.math.min

/**
 * The words of a transcript that a study game hasn't used yet, returned by
 * [XrLanguageTeacherTranscriptCursor.takeUnconsumed].
 *
 * @property text The normalized unused words (see
 *   [XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText]), or an empty string.
 * @property stableLength How many leading characters of [text] are stable: unchanged since the
 *   previous transcript, so the recognizer is unlikely to revise them. Only these may match fuzzily
 *   (see [XrLanguageTeacherSpeechMatcher.selectSpokenTargets]).
 */
data class XrLanguageTeacherUnconsumedSpeech(val text: String, val stableLength: Int) {
  /** Whether there are no unused words. */
  fun isEmpty(): Boolean = text.isEmpty()
}

/**
 * Tracks which words of the speech recognizer's cumulative transcript of the current utterance
 * segment a study game already used, so that each spoken word only matches once.
 *
 * Words used by a match are remembered by their text without spaces, and found again in a revised
 * transcript however the recognizer joins or splits them ("apfel saft" becoming "Apfelsaft",
 * "hotdog" becoming "hot dog") or drops a word before them ("eh gato" becoming "gato"). A word that
 * only partly overlaps them counts as used, so a matched word the recognizer revises later (e.g.
 * "gato" becoming "gatos") doesn't match again. When the matched words can't be found, their count
 * is used instead. Words passed over at a speaking pause are remembered by their text: when the
 * recognizer revises one (typically the last, e.g. "ga" becoming "gato" in its final result), the
 * revised word counts as new and is checked again.
 */
class XrLanguageTeacherTranscriptCursor {
  private var matchedTokenCount = 0
  private var matchedScalarCount = 0
  /** The transcript up to the end of the last match, without spaces. */
  private var matchedPrefix = ""
  /** The words of the last match, without spaces. */
  private var matchedWords = ""
  private var pausedTokens: List<String> = emptyList()
  private var pausedScalars: List<Int> = emptyList()
  private var latestTranscript = ""
  private var latestNormalized = ""
  private var latestTokens: List<String> = emptyList()
  private var latestScalars: List<Int> = emptyList()
  /** Offset in [latestNormalized] of the text last returned by [takeUnconsumed]. */
  private var latestUnconsumedOffset = 0

  /** Starts over when the speech recognizer starts a new utterance segment. */
  fun reset() {
    matchedTokenCount = 0
    matchedScalarCount = 0
    matchedPrefix = ""
    matchedWords = ""
    pausedTokens = emptyList()
    pausedScalars = emptyList()
    latestTranscript = ""
    latestNormalized = ""
    latestTokens = emptyList()
    latestScalars = emptyList()
    latestUnconsumedOffset = 0
  }

  /**
   * Marks the words seen so far as used after a speaking pause (or a bump), so they can't match
   * later targets.
   */
  fun consumePending() {
    pausedTokens = latestTokens
    pausedScalars = latestScalars
  }

  /**
   * Returns the words of the cumulative [transcript] that weren't used yet (at most the last
   * [MAX_UNCONSUMED_WORDS]), and remembers the transcript.
   */
  fun takeUnconsumed(transcript: String): XrLanguageTeacherUnconsumedSpeech {
    val normalized = XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText(transcript)
    if (normalized.isEmpty()) return XrLanguageTeacherUnconsumedSpeech(text = "", stableLength = 0)
    val tokens = XrLanguageTeacherSpeechMatcher.tokenizeNormalized(normalized)
    val scalars = normalized.codePoints().toArray().toList()
    val (usedTokens, usedScalars) = matchedCounts(normalized, tokens, scalars)
    val extracted =
      XrLanguageTeacherSpeechMatcher.extractUnconsumedTranscript(
        transcript = normalized,
        consumedTokenCount = max(usedTokens, commonPrefixLength(pausedTokens, tokens)),
        consumedScalarCount = max(usedScalars, commonPrefixLength(pausedScalars, scalars)),
        maxTrailingTokens = MAX_UNCONSUMED_WORDS,
      )
    val stableEnd = stableEnd(normalized, tokens, scalars)
    val text = extracted.unconsumedText
    // The unused words are a suffix of the normalized transcript.
    val offset = normalized.length - text.length
    latestTranscript = transcript
    latestNormalized = normalized
    latestTokens = tokens
    latestScalars = scalars
    latestUnconsumedOffset = offset
    return XrLanguageTeacherUnconsumedSpeech(
      text = text,
      stableLength = (stableEnd - offset).coerceIn(0, text.length),
    )
  }

  /**
   * Returns the unused words of the latest transcript, all stable: after a speaking pause (or the
   * end of a segment), the recognizer has stopped revising them.
   */
  fun takeUnconsumedAtPause(): XrLanguageTeacherUnconsumedSpeech {
    if (latestTranscript.isEmpty())
      return XrLanguageTeacherUnconsumedSpeech(text = "", stableLength = 0)
    // Taking the same transcript again leaves every word unchanged since the previous one.
    return takeUnconsumed(latestTranscript)
  }

  /**
   * Marks the words of the text last returned by [takeUnconsumed] up to character [end] as used by
   * a match of the words from character [start] (see [XrLanguageTeacherSpokenTarget]). Later words
   * stay unused.
   */
  fun consumeThrough(start: Int, end: Int) {
    val boundary = (latestUnconsumedOffset + end).coerceIn(0, latestNormalized.length)
    val matchStart = (latestUnconsumedOffset + start).coerceIn(0, boundary)
    val usedText = latestNormalized.substring(0, boundary)
    matchedPrefix = usedText.replace(" ", "")
    matchedWords = latestNormalized.substring(matchStart, boundary).replace(" ", "")
    val isMidWord = boundary < latestNormalized.length && latestNormalized[boundary] != ' '
    val usedTokens = XrLanguageTeacherSpeechMatcher.tokenizeNormalized(usedText).size
    matchedTokenCount = max(matchedTokenCount, if (isMidWord) usedTokens - 1 else usedTokens)
    matchedScalarCount = max(matchedScalarCount, usedText.codePointCount(0, usedText.length))
  }

  /**
   * Returns how many of [tokens] and [scalars] of [normalized] the matches used: up to the end of
   * [matchedPrefix] if the transcript (without spaces) still starts with it, counting a partly used
   * word as used; or else up to the end of the occurrence of [matchedWords] that is as many
   * occurrences in as it was in [matchedPrefix] (see [findMatchedWords]); or else the counts of the
   * used words, at most the transcript's.
   */
  private fun matchedCounts(
    normalized: String,
    tokens: List<String>,
    scalars: List<Int>,
  ): Pair<Int, Int> {
    if (matchedPrefix.isEmpty()) return 0 to 0
    val compact = normalized.replace(" ", "")
    val usedLength =
      when {
        compact.startsWith(matchedPrefix) -> matchedPrefix.length
        else ->
          findMatchedWords(compact, tokens)
            ?: return min(matchedTokenCount, tokens.size) to min(matchedScalarCount, scalars.size)
      }
    var usedTokens = 0
    var tokensLength = 0
    while (usedTokens < tokens.size && tokensLength < usedLength) {
      tokensLength += tokens[usedTokens].length
      usedTokens++
    }
    // The offset in normalized right after the usedLength-th character that isn't a space.
    var end = 0
    var nonSpaceCount = 0
    while (end < normalized.length && nonSpaceCount < usedLength) {
      if (normalized[end] != ' ') nonSpaceCount++
      end++
    }
    return usedTokens to normalized.codePointCount(0, end)
  }

  /**
   * Returns the end, in [compact] (the transcript without spaces, made of [tokens]), of the
   * occurrence of [matchedWords] that is as many occurrences in as the last one in [matchedPrefix],
   * so a repeated word ("gato gato") isn't confused with its first copy, or `null` if there is no
   * such occurrence. In scripts written with spaces, an occurrence must start and end at word
   * boundaries, so "te" isn't found in "tetera".
   */
  private fun findMatchedWords(compact: String, tokens: List<String>): Int? {
    if (matchedWords.isEmpty()) return null
    val wordBoundaries = tokens.runningFold(0) { offset, token -> offset + token.length }.toSet()
    val needsWordBoundaries = !XrLanguageTeacherSpeechMatcher.containsSpaceFreeScript(compact)
    val occurrence = occurrences(matchedPrefix, matchedWords).size
    val ends =
      occurrences(compact, matchedWords)
        .map { it + matchedWords.length }
        .filter { end ->
          !needsWordBoundaries ||
            (end - matchedWords.length in wordBoundaries && end in wordBoundaries)
        }
    return ends.getOrNull(occurrence - 1)
  }

  /** Returns where [part] starts in [text], at every occurrence. */
  private fun occurrences(text: String, part: String): List<Int> = buildList {
    var index = text.indexOf(part)
    while (index >= 0) {
      add(index)
      index = text.indexOf(part, index + 1)
    }
  }

  /**
   * Returns the character offset in [normalized] up to which it is unchanged since the previous
   * transcript, by words, or by characters for space-free scripts.
   */
  private fun stableEnd(normalized: String, tokens: List<String>, scalars: List<Int>): Int {
    if (normalized == latestNormalized) return normalized.length
    if (XrLanguageTeacherSpeechMatcher.containsSpaceFreeScript(normalized)) {
      val stableScalars = commonPrefixLength(latestScalars, scalars)
      return normalized.offsetByCodePoints(0, stableScalars)
    }
    val stableTokens = commonPrefixLength(latestTokens, tokens)
    return tokens.take(stableTokens).sumOf { it.length } + max(0, stableTokens - 1)
  }

  /** Returns how many leading elements [previous] and [current] have in common. */
  private fun <T> commonPrefixLength(previous: List<T>, current: List<T>): Int {
    val maxLength = min(previous.size, current.size)
    var length = 0
    while (length < maxLength && previous[length] == current[length]) length++
    return length
  }

  private companion object {
    /**
     * Most unused words checked against the targets. A target of n words is compared with windows
     * of up to n + 1 spoken words, so this covers phrases of up to 5 words with one inserted word.
     */
    const val MAX_UNCONSUMED_WORDS = 6
  }
}
