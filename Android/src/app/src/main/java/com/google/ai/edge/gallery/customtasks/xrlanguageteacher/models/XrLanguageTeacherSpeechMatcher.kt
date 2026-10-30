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

@file:Suppress("KotlinArray")

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import android.icu.text.BreakIterator
import android.icu.text.Transliterator
import android.util.Log
import java.text.Normalizer
import java.util.Locale
import java.util.MissingResourceException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "AGXrTeacherSpeechMatcher"

/**
 * Result of [XrLanguageTeacherSpeechMatcher.extractUnconsumedTranscript].
 *
 * @property unconsumedText The normalized tail of the transcript that has not been consumed yet, or
 *   an empty string when every token (or scalar, for space-free scripts) has been consumed.
 * @property totalTokens Number of normalized whitespace-delimited tokens in the transcript.
 * @property totalScalars Number of Unicode code points in the normalized transcript.
 * @property consumedTokenCount The caller's token watermark after this call. It is reset to `0`
 *   when the recognizer revised the transcript to fewer tokens or scalars than were consumed.
 * @property consumedScalarCount The caller's scalar watermark after this call, reset like
 *   [consumedTokenCount].
 */
data class XrLanguageTeacherUnconsumedTranscript(
  val unconsumedText: String,
  val totalTokens: Int,
  val totalScalars: Int,
  val consumedTokenCount: Int,
  val consumedScalarCount: Int,
)

/**
 * Pure speech normalization and multi-script vocabulary matching for the XR Language Teacher study
 * games (`Vocab Popper` and `Vocab Runner`).
 *
 * Mirrors the iOS `XrLanguageTeacherSpeechMatcher` so both platforms accept the same spoken forms:
 * NFKC + diacritic folding, Katakana-to-Hiragana folding, Japanese romaji/kana transliteration,
 * article-less noun matching, space-free CJK/Thai substring matching, and length-scaled fuzzy
 * (Levenshtein) matching. Character counts and edit distances are measured in extended grapheme
 * clusters to match Swift `String.count` semantics.
 */
object XrLanguageTeacherSpeechMatcher {
  private val parentheticalRegex = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")
  private val termSplitRegex = Regex("""\s*(?:/|•|·|,|;|—|–|\s-\s)\s*""")
  private val parentheticalReadingRegex = Regex("""[(\[（【]([^)\]）】]+)[)\]）】]""")
  private const val PARENTHETICAL_TRIM_CHARS = "()[]（）【】 "

  private val japaneseFunctionWords =
    listOf("です", "ます", "だ", "だよ", "だね", "かな", "の", "は", "が", "を", "に", "で", "と", "も", "へ", "よ", "ね")

  /** Maximum number of trailing scalars evaluated for space-free scripts (CJK, Japanese, Thai). */
  private const val MAX_TRAILING_SPACE_FREE_SCALARS = 24

  private val hiraganaToLatin: Transliterator? by lazy { loadTransliterator("Hiragana-Latin") }
  private val anyToLatin: Transliterator? by lazy { loadTransliterator("Any-Latin") }
  private val latinToHiragana: Transliterator? by lazy { loadTransliterator("Latin-Hiragana") }

  /** Per-thread grapheme cluster iterator; ICU iterators are stateful and not thread-safe. */
  private val graphemeIterator: ThreadLocal<BreakIterator> = ThreadLocal.withInitial {
    BreakIterator.getCharacterInstance(Locale.ROOT)
  }

  /**
   * Strips parenthetical romanization or notes (e.g. `"りんご (ringo)"` -> `"りんご"`) and splits on
   * slash/bullet/comma/dash separators to return the primary target-language term.
   */
  fun extractPrimaryLearnTerm(rawWord: String): String {
    val trimmed = rawWord.trim()
    if (trimmed.isEmpty()) return ""
    val withoutParens = parentheticalRegex.replace(trimmed, "")
    val firstSegment =
      termSplitRegex.find(withoutParens)?.let { withoutParens.substring(0, it.range.first) }
        ?: withoutParens
    val candidate = firstSegment.trim()
    return candidate.ifEmpty { trimmed }
  }

  /**
   * Normalizes [text] for speech matching:
   * - Applies NFKC compatibility normalization and strips combining diacritics from non-kana
   *   characters (Japanese dakuten/handakuten stay composed).
   * - Converts Katakana (`U+30A1..U+30F6`) to Hiragana (`-0x60`) so Japanese ASR transcripts in
   *   either kana script match identically.
   * - Lowercases, replaces punctuation/symbols with single spaces, and trims the result.
   */
  fun normalizeSpeechMatchText(text: String): String {
    if (text.isEmpty()) return ""

    // Compatibility decomposition + canonical composition keeps kana voiced marks intact while
    // normalizing full-width characters.
    val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
    val folded = StringBuilder(nfkc.length)
    nfkc.codePoints().forEach { codePoint ->
      when {
        // Fast path for ASCII characters (no Katakana or combining accents to decompose).
        codePoint < 0x80 -> folded.appendCodePoint(codePoint)
        // Convert Katakana (ァ..ヶ) to Hiragana (ぁ..ゖ).
        codePoint in 0x30A1..0x30F6 -> folded.appendCodePoint(codePoint - 0x60)
        else -> {
          // Decompose non-kana characters to strip Latin/Arabic accents while keeping kana intact.
          val nfd = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFD)
          val first = nfd.codePointAt(0)
          if (nfd.codePointCount(0, nfd.length) > 1 && first !in 0x3040..0x30FF) {
            nfd.codePoints().forEach { sub ->
              if (!isNonBaseCharacter(sub)) folded.appendCodePoint(sub)
            }
          } else {
            folded.appendCodePoint(codePoint)
          }
        }
      }
    }

    val lowered = folded.toString().lowercase(Locale.ROOT)
    val cleaned = StringBuilder(lowered.length)
    var previousWasSpace = true
    lowered.codePoints().forEach { codePoint ->
      when {
        isAlphanumeric(codePoint) ||
          isNonBaseCharacter(codePoint) ||
          isCjkOrKanaOrHangulOrIndicOrThaiOrArabic(codePoint) -> {
          cleaned.appendCodePoint(codePoint)
          previousWasSpace = false
        }
        !previousWasSpace -> {
          cleaned.append(' ')
          previousWasSpace = true
        }
      }
    }
    if (cleaned.isNotEmpty() && cleaned.last() == ' ') {
      cleaned.setLength(cleaned.length - 1)
    }
    return cleaned.toString()
  }

  /**
   * Precomputes the union of candidate normalized match forms for a vocabulary card's [targetWord]
   * (including its stripped primary learn term) so game engines do not recompute ICU script
   * transliterations on every partial ASR update.
   */
  fun targetMatchForms(targetWord: String): Set<String> {
    val cleanedTarget = extractPrimaryLearnTerm(targetWord)
    return speechMatchForms(cleanedTarget) + speechMatchForms(targetWord)
  }

  /**
   * Returns candidate normalized forms for [text] (including Japanese romaji-to-hiragana and
   * hiragana-to-romaji transliterations when applicable) so Kanji/Kana/Romaji cards match ASR
   * output in any script.
   */
  fun speechMatchForms(text: String): Set<String> {
    val primary = normalizeSpeechMatchText(text)
    if (primary.isEmpty()) return emptySet()
    val forms = linkedSetOf(primary)

    val strippedSpace = primary.replace(" ", "")
    if (strippedSpace.isNotEmpty()) {
      forms += strippedSpace
    }

    // Also include the parenthetical reading if the card word had one, e.g. "林檎 (りんご)".
    parentheticalReadingRegex.find(text)?.let { match ->
      val inside = match.value.trim { it in PARENTHETICAL_TRIM_CHARS }
      val normalizedInside = normalizeSpeechMatchText(inside)
      if (normalizedInside.isNotEmpty()) {
        forms += normalizedInside
        forms += normalizedInside.replace(" ", "")
      }
    }

    for (form in forms.toList()) {
      when {
        containsHiragana(form) -> {
          val latin = transliterate(hiraganaToLatin, form) ?: transliterate(anyToLatin, form)
          addNormalizedVariant(forms, latin)
        }
        containsAsciiLetters(form) -> {
          addNormalizedVariant(forms, transliterate(latinToHiragana, form))
        }
      }
    }
    return forms
  }

  /** Returns the normalized whitespace-delimited tokens in [transcript]. */
  fun tokenizeNormalized(transcript: String): List<String> {
    val normalized = normalizeSpeechMatchText(transcript)
    if (normalized.isEmpty()) return emptyList()
    return splitTokens(normalized)
  }

  /**
   * Extracts only the unconsumed tail of a cumulative ASR [transcript] given the engine's
   * [consumedTokenCount] and [consumedScalarCount] watermarks.
   *
   * When the recognizer starts a new utterance or revises the transcript to a shorter string, the
   * returned watermarks are reset to `0`; callers must store the returned
   * [XrLanguageTeacherUnconsumedTranscript.consumedTokenCount] and
   * [XrLanguageTeacherUnconsumedTranscript.consumedScalarCount].
   */
  fun extractUnconsumedTranscript(
    transcript: String,
    consumedTokenCount: Int,
    consumedScalarCount: Int,
    maxTrailingTokens: Int = 4,
  ): XrLanguageTeacherUnconsumedTranscript {
    val normalized = normalizeSpeechMatchText(transcript)
    if (normalized.isEmpty()) {
      return XrLanguageTeacherUnconsumedTranscript(
        unconsumedText = "",
        totalTokens = 0,
        totalScalars = 0,
        consumedTokenCount = consumedTokenCount,
        consumedScalarCount = consumedScalarCount,
      )
    }

    val tokens = splitTokens(normalized)
    val scalars = normalized.codePoints().toArray()
    val totalTokens = tokens.size
    val totalScalars = scalars.size

    var tokenWatermark = consumedTokenCount
    var scalarWatermark = consumedScalarCount
    if (totalTokens < tokenWatermark || totalScalars < scalarWatermark) {
      tokenWatermark = 0
      scalarWatermark = 0
    }

    val unconsumedText =
      when {
        containsSpaceFreeScript(normalized) ->
          if (totalScalars > scalarWatermark) {
            val unconsumed = scalars.drop(scalarWatermark).takeLast(MAX_TRAILING_SPACE_FREE_SCALARS)
            buildString {
                for (codePoint in unconsumed) {
                  appendCodePoint(codePoint)
                }
              }
              .trim()
          } else {
            ""
          }
        totalTokens > tokenWatermark ->
          tokens.drop(tokenWatermark).takeLast(maxTrailingTokens).joinToString(" ")
        else -> ""
      }

    return XrLanguageTeacherUnconsumedTranscript(
      unconsumedText = unconsumedText,
      totalTokens = totalTokens,
      totalScalars = totalScalars,
      consumedTokenCount = tokenWatermark,
      consumedScalarCount = scalarWatermark,
    )
  }

  /**
   * Returns the subset of [activeTargetWords] spoken in [transcript], ordered longest-target-first
   * so specific phrases match before shorter substrings.
   */
  fun matchSpokenVocabulary(transcript: String, activeTargetWords: List<String>): List<String> {
    val transcriptForms = speechMatchForms(transcript)
    if (transcriptForms.isEmpty() || activeTargetWords.isEmpty()) return emptyList()

    return activeTargetWords
      .sortedByDescending { graphemeCount(extractPrimaryLearnTerm(it)) }
      .filter { target ->
        findTargetWordSpoken(
          transcriptForms = transcriptForms,
          targetForms = targetMatchForms(target),
        )
      }
  }

  /**
   * Returns `true` when [transcript] contains [targetWord] (by whole-token, multi-word phrase,
   * space-free CJK/Japanese/Thai substring, or fuzzy edit-distance match).
   */
  fun findTargetWordSpoken(transcript: String, targetWord: String): Boolean {
    val transcriptForms = speechMatchForms(transcript)
    if (transcriptForms.isEmpty()) return false
    return findTargetWordSpoken(
      transcriptForms = transcriptForms,
      targetForms = targetMatchForms(targetWord),
    )
  }

  /** Returns `true` when precomputed [transcriptForms] match precomputed [targetForms]. */
  fun findTargetWordSpoken(transcriptForms: Set<String>, targetForms: Set<String>): Boolean {
    if (transcriptForms.isEmpty() || targetForms.isEmpty()) return false

    for (targetForm in targetForms) {
      if (targetForm.isEmpty()) continue
      val targetHasSpaces = ' ' in targetForm
      val targetIsSpaceFreeScript = containsSpaceFreeScript(targetForm)
      val targetTokens = splitTokens(targetForm)
      val strippedTarget = targetTokens.lastOrNull() ?: targetForm

      for (transcriptForm in transcriptForms) {
        if (transcriptForm.isEmpty()) continue
        if (transcriptForm == targetForm) return true

        if (targetIsSpaceFreeScript) {
          val compactTranscript = stripJapaneseFunctionWords(transcriptForm.replace(" ", ""))
          val compactTarget = targetForm.replace(" ", "")
          if (compactTarget.isNotEmpty() && compactTarget in compactTranscript) {
            return true
          }
          val compactTargetLength = graphemeCount(compactTarget)
          if (
            compactTargetLength >= 3 &&
              withinEditDistance(
                text = compactTranscript,
                target = compactTarget,
                maxDistance = if (compactTargetLength >= 6) 2 else 1,
              )
          ) {
            return true
          }
        }

        val transcriptTokens = splitTokens(transcriptForm)
        when {
          targetHasSpaces -> {
            if (containsContiguousPhrase(transcriptTokens, targetTokens)) return true
            // Also allow matching the noun without a leading article (e.g. "manzana" for
            // "la manzana").
            if (
              targetTokens.size == 2 &&
                graphemeCount(targetTokens.first()) <= 3 &&
                graphemeCount(strippedTarget) >= 3 &&
                matchesSingleToken(transcriptTokens, strippedTarget)
            ) {
              return true
            }
          }
          matchesSingleToken(transcriptTokens, targetForm) -> return true
        }
      }
    }
    return false
  }

  private fun addNormalizedVariant(forms: MutableSet<String>, variant: String?) {
    if (variant == null) return
    val normalized = normalizeSpeechMatchText(variant)
    if (normalized.isNotEmpty()) {
      forms += normalized
      forms += normalized.replace(" ", "")
    }
  }

  private fun matchesSingleToken(transcriptTokens: List<String>, targetToken: String): Boolean {
    if (targetToken.isEmpty()) return false
    val targetGraphemes = graphemes(targetToken)
    val maxDistance = allowedEditDistance(targetGraphemes.size)
    for (token in transcriptTokens) {
      if (token == targetToken) return true
      if (maxDistance > 0) {
        val tokenGraphemes = graphemes(token)
        if (
          abs(tokenGraphemes.size - targetGraphemes.size) <= maxDistance &&
            levenshteinDistance(tokenGraphemes, targetGraphemes) <= maxDistance
        ) {
          return true
        }
      }
    }
    return false
  }

  private fun containsContiguousPhrase(
    transcriptTokens: List<String>,
    targetTokens: List<String>,
  ): Boolean {
    if (targetTokens.isEmpty() || transcriptTokens.size < targetTokens.size) return false
    val window = targetTokens.size
    val joinedTarget = targetTokens.joinToString(" ")
    val joinedTargetGraphemes = graphemes(joinedTarget)
    val maxDistance = allowedEditDistance(joinedTargetGraphemes.size)
    for (start in 0..(transcriptTokens.size - window)) {
      val slice = transcriptTokens.subList(start, start + window).joinToString(" ")
      if (slice == joinedTarget) return true
      if (maxDistance > 0) {
        val sliceGraphemes = graphemes(slice)
        if (
          abs(sliceGraphemes.size - joinedTargetGraphemes.size) <= maxDistance &&
            levenshteinDistance(sliceGraphemes, joinedTargetGraphemes) <= maxDistance
        ) {
          return true
        }
      }
    }
    return false
  }

  private fun allowedEditDistance(length: Int): Int =
    when {
      length >= 7 -> 2
      length >= 4 -> 1
      else -> 0
    }

  private fun withinEditDistance(text: String, target: String, maxDistance: Int): Boolean {
    val textChars = graphemes(text)
    val targetChars = graphemes(target)
    if (targetChars.isEmpty() || textChars.isEmpty()) return false
    if (
      abs(textChars.size - targetChars.size) <= maxDistance &&
        levenshteinDistance(textChars, targetChars) <= maxDistance
    ) {
      return true
    }
    val minLength = max(1, targetChars.size - maxDistance)
    val maxLength = min(textChars.size, targetChars.size + maxDistance)
    if (minLength > maxLength) return false
    for (windowLength in minLength..maxLength) {
      if (windowLength > textChars.size) continue
      for (start in 0..(textChars.size - windowLength)) {
        val window = textChars.subList(start, start + windowLength)
        if (levenshteinDistance(window, targetChars) <= maxDistance) return true
      }
    }
    return false
  }

  private fun levenshteinDistance(lhs: List<String>, rhs: List<String>): Int {
    val m = lhs.size
    val n = rhs.size
    if (m == 0) return n
    if (n == 0) return m

    var previous = IntArray(n + 1) { it }
    var current = IntArray(n + 1)
    for (i in 1..m) {
      current[0] = i
      for (j in 1..n) {
        val cost = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
        current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
      }
      val swap = previous
      previous = current
      current = swap
    }
    return previous[n]
  }

  private fun stripJapaneseFunctionWords(text: String): String {
    var result = text
    for (suffix in japaneseFunctionWords) {
      if (graphemeCount(result) > graphemeCount(suffix) && result.endsWith(suffix)) {
        result = result.removeSuffix(suffix)
      }
    }
    return result
  }

  /** Splits [text] on single spaces, omitting empty tokens (like Swift `split(separator:)`). */
  private fun splitTokens(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }

  /** Splits [text] into extended grapheme clusters, matching Swift `Character` semantics. */
  private fun graphemes(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = graphemeIterator.get() ?: BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val clusters = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
      clusters += text.substring(start, end)
      start = end
      end = iterator.next()
    }
    return clusters
  }

  private fun graphemeCount(text: String): Int = graphemes(text).size

  private fun containsHiragana(text: String): Boolean =
    text.codePoints().anyMatch { it in 0x3040..0x309F }

  private fun containsAsciiLetters(text: String): Boolean =
    text.codePoints().anyMatch { it in 0x41..0x5A || it in 0x61..0x7A }

  private fun containsSpaceFreeScript(text: String): Boolean =
    text.codePoints().anyMatch {
      // Hiragana, Katakana, CJK Unified Ideographs, Thai.
      it in 0x3040..0x30FF || it in 0x3400..0x9FFF || it in 0x0E00..0x0E7F
    }

  private fun isCjkOrKanaOrHangulOrIndicOrThaiOrArabic(codePoint: Int): Boolean =
    codePoint in 0x3040..0x30FF ||
      codePoint in 0x3400..0x9FFF ||
      codePoint in 0xAC00..0xD7AF ||
      codePoint in 0x0900..0x097F ||
      codePoint in 0x0E00..0x0E7F ||
      codePoint in 0x0600..0x06FF

  /** Unicode general categories `L*`, `M*`, and `N*` (Foundation `CharacterSet.alphanumerics`). */
  private fun isAlphanumeric(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
      Character.UPPERCASE_LETTER,
      Character.LOWERCASE_LETTER,
      Character.TITLECASE_LETTER,
      Character.MODIFIER_LETTER,
      Character.OTHER_LETTER,
      Character.DECIMAL_DIGIT_NUMBER,
      Character.LETTER_NUMBER,
      Character.OTHER_NUMBER -> true
      else -> isNonBaseCharacter(codePoint)
    }

  /** Unicode general category `M*` (Foundation `CharacterSet.nonBaseCharacters`). */
  private fun isNonBaseCharacter(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
      Character.NON_SPACING_MARK,
      Character.ENCLOSING_MARK,
      Character.COMBINING_SPACING_MARK -> true
      else -> false
    }

  private fun transliterate(transliterator: Transliterator?, text: String): String? =
    transliterator?.let {
      synchronized(it) { it.transliterate(text) }
    }

  private fun loadTransliterator(id: String): Transliterator? =
    try {
      Transliterator.getInstance(id)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "ICU transliterator '$id' is unavailable", e)
      null
    } catch (e: MissingResourceException) {
      Log.w(TAG, "ICU transliterator '$id' data is missing", e)
      null
    }
}
