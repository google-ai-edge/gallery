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
import androidx.annotation.VisibleForTesting
import java.text.Normalizer
import java.util.Locale
import java.util.MissingResourceException
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
 * A target found in a transcript by [XrLanguageTeacherSpeechMatcher.selectSpokenTargets].
 *
 * @property targetIndex Index of the target in the list passed to `selectSpokenTargets`.
 * @property score Similarity of the spoken words to the target, in `(0, 1]`; `1` for an exact
 *   match.
 * @property start Character offset in the normalized transcript
 *   ([XrLanguageTeacherSpeechMatcher.normalizeSpeechMatchText]) of the matched words.
 * @property end Character offset in the normalized transcript right after the matched words.
 */
data class XrLanguageTeacherSpokenTarget(
  val targetIndex: Int,
  val score: Double,
  val start: Int,
  val end: Int,
)

/**
 * Pure speech normalization and multi-script vocabulary matching for the XR Language Teacher study
 * games (`Vocab Popper` and `Vocab Runner`).
 *
 * Shares the iOS `XrLanguageTeacherSpeechMatcher` normalization: NFKC + diacritic folding,
 * Katakana-to-Hiragana folding, Japanese romaji/kana transliteration, article-less noun matching,
 * and space-free CJK/Thai substring matching. Fuzzy matching picks the closest target on screen by
 * a length-normalized similarity with a margin over the runner-up (see [selectSpokenTargets]),
 * which is more lenient than iOS for now. Character counts and edit distances are measured in
 * extended grapheme clusters to match Swift `String.count` semantics.
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
   * Returns the subset of [activeTargetWords] spoken in [transcript] (see [selectSpokenTargets]),
   * ordered longest-target-first.
   */
  @VisibleForTesting
  fun matchSpokenVocabulary(transcript: String, activeTargetWords: List<String>): List<String> {
    if (activeTargetWords.isEmpty()) return emptyList()
    return selectSpokenTargets(transcript, activeTargetWords.map { targetMatchForms(it) })
      .map { activeTargetWords[it.targetIndex] }
      .sortedByDescending { graphemeCount(extractPrimaryLearnTerm(it)) }
  }

  /** Returns `true` when [transcript] contains [targetWord] (see [selectSpokenTargets]). */
  @VisibleForTesting
  fun findTargetWordSpoken(transcript: String, targetWord: String): Boolean =
    selectSpokenTargets(transcript, listOf(targetMatchForms(targetWord))).isNotEmpty()

  /**
   * Returns the targets spoken in [transcript], best match first, among the on-screen targets whose
   * [targetMatchForms] are [targetFormsList].
   *
   * Each target is compared, space- and hyphen-insensitively, with every window of consecutive
   * spoken words that is one word shorter to one word longer than the target, so compound words and
   * phrases match however the recognizer splits or joins them ("apfel saft" for "Apfelsaft",
   * "hotdog" for "hot dog", "zumo naranja" for "zumo de naranja"). A target that starts with an
   * article (see [ARTICLES]) also matches without it ("manzana" for "la manzana"). Space-free
   * scripts (CJK, kana, Thai) match as substrings (every occurrence), or within 1 edit (2 from 6
   * graphemes) of a part of the transcript from 3 graphemes.
   *
   * A window's score is `1 - editDistance / max(length)` over graphemes, `1` for an exact match. An
   * exact match always counts. A fuzzy match counts only when:
   * - the target has more than [MAX_EXACT_ONLY_GRAPHEMES] graphemes, not counting a leading article
   *   (so "el sal" doesn't match "el sol"), and the score is at least [MATCH_THRESHOLD]; in scripts
   *   whose graphemes are syllables (e.g. Hangul or Devanagari), it is also at most 1 edit from 4
   *   graphemes and 2 edits from 7 (see [syllabicEditLimit]);
   * - it is in the transcript as recognized, not in a transliteration of it (e.g. the romaji "nego"
   *   of "ねご" doesn't match the romaji "neko" of "ねこ");
   * - it beats every other target scored on the same spoken words by at least [MATCH_MARGIN], so a
   *   word halfway between two stickers ("dato" with "gato" and "pato") matches neither;
   * - its words end within the first [stableLength] characters of the normalized transcript: text
   *   the recognizer may still revise only matches exactly.
   *
   * Each spoken word matches at most one target, and each target matches once, the best score
   * first, then the longer target ("perro caliente" doesn't also match "perro").
   */
  fun selectSpokenTargets(
    transcript: String,
    targetFormsList: List<Set<String>>,
    stableLength: Int = Int.MAX_VALUE,
  ): List<XrLanguageTeacherSpokenTarget> {
    val transcriptForms = speechMatchForms(transcript)
    if (transcriptForms.isEmpty() || targetFormsList.isEmpty()) return emptyList()
    val primaryForm = transcriptForms.first()
    val isSpaceFreeTranscript = containsSpaceFreeScript(primaryForm)
    val candidates = mutableListOf<MatchCandidate>()
    for ((targetIndex, targetForms) in targetFormsList.withIndex()) {
      // The kana forms of a word written in another script (e.g. "れちぇ" for "leche") are only
      // transliterations: they match a kana transcript exactly, but never fuzzily, and never the
      // kana transliteration of a transcript in another script ("まんざなs" for "manzanas").
      val isSpaceFreeWord = targetForms.firstOrNull()?.let { containsSpaceFreeScript(it) } == true
      // "el sol" matches as exactly as "sol", in all its forms (e.g. "elsol").
      val requiresExactNoun =
        targetForms.firstOrNull()?.let { isShortNounAfterArticle(splitTokens(it)) } == true
      for (targetForm in targetForms) {
        if (targetForm.isEmpty()) continue
        val isSpaceFreeForm = containsSpaceFreeScript(targetForm)
        if (isSpaceFreeForm && !isSpaceFreeWord && !isSpaceFreeTranscript) continue
        for (transcriptForm in transcriptForms) {
          // Only the transcript as recognized keeps the positions of its words; a match in another
          // form (e.g. the kana form of a romaji transcript) counts as using all of it.
          val spanSource = if (transcriptForm == primaryForm) transcriptForm else null
          if (isSpaceFreeForm) {
            candidates +=
              spaceFreeCandidates(
                targetIndex,
                targetForm,
                transcriptForm,
                spanSource,
                primaryForm,
                allowsFuzzy = isSpaceFreeWord && spanSource != null,
              )
          } else {
            addWordWindowCandidates(
              candidates,
              targetIndex,
              targetForm,
              transcriptForm,
              spanSource,
              primaryForm,
              requiresExactNoun,
            )
          }
        }
      }
    }
    return pickSpokenTargets(candidates, targetFormsList, stableLength)
  }

  /**
   * A window of the transcript scored against a target: [start] and [end] are character offsets in
   * the normalized transcript, [isAligned] whether they are the window's own words (rather than the
   * whole transcript), and [targetLength] the length of the matched target form in graphemes.
   */
  private data class MatchCandidate(
    val targetIndex: Int,
    val score: Double,
    val isAcceptable: Boolean,
    val start: Int,
    val end: Int,
    val isAligned: Boolean,
    val targetLength: Int,
  ) {
    fun overlaps(other: MatchCandidate): Boolean = start < other.end && other.start < end
  }

  /** Applies the margin, stability, and one-target-per-word rules of [selectSpokenTargets]. */
  private fun pickSpokenTargets(
    candidates: List<MatchCandidate>,
    targetFormsList: List<Set<String>>,
    stableLength: Int,
  ): List<XrLanguageTeacherSpokenTarget> {
    fun runnerUpScore(candidate: MatchCandidate): Double =
      candidates
        .filter {
          // The same word on two stickers isn't a rival.
          targetFormsList[it.targetIndex] != targetFormsList[candidate.targetIndex] &&
            it.overlaps(candidate)
        }
        .maxOfOrNull { it.score } ?: 0.0

    val accepted =
      candidates
        .filter { candidate ->
          candidate.isAcceptable &&
            (candidate.score >= 1.0 ||
              (candidate.end <= stableLength &&
                candidate.score - runnerUpScore(candidate) >= MATCH_MARGIN - SCORE_EPSILON))
        }
        .sortedWith(
          compareByDescending<MatchCandidate> { it.score }
            .thenByDescending { it.isAligned }
            .thenByDescending { it.targetLength }
            .thenBy { it.start }
            .thenBy { it.targetIndex }
        )
    val picked = mutableListOf<MatchCandidate>()
    for (candidate in accepted) {
      if (picked.any { it.targetIndex == candidate.targetIndex || it.overlaps(candidate) }) continue
      picked += candidate
    }
    return picked.map { XrLanguageTeacherSpokenTarget(it.targetIndex, it.score, it.start, it.end) }
  }

  /**
   * Adds a candidate for each window of [transcriptForm]'s words that scores at least
   * [MIN_CANDIDATE_SCORE] against [targetForm] (or its article-less form). Windows of [spanSource]
   * keep their own position and may match fuzzily; others span all of [primaryForm] and only match
   * exactly.
   */
  private fun addWordWindowCandidates(
    candidates: MutableList<MatchCandidate>,
    targetIndex: Int,
    targetForm: String,
    transcriptForm: String,
    spanSource: String?,
    primaryForm: String,
    requiresExactNoun: Boolean,
  ) {
    val words = splitTokens(transcriptForm)
    if (words.isEmpty()) return
    val wordStarts = spanSource?.let { wordStartOffsets(it) }
    val wordGraphemes = words.map { graphemes(it) }
    for (targetWords in targetVariants(splitTokens(targetForm))) {
      val target = graphemes(targetWords.joinToString(""))
      if (target.isEmpty()) continue
      val minWindow = max(1, targetWords.size - 1)
      val maxWindow = min(words.size, targetWords.size + 1)
      for (windowSize in minWindow..maxWindow) {
        for (first in 0..(words.size - windowSize)) {
          val last = first + windowSize - 1
          val window = wordGraphemes.subList(first, last + 1).flatten()
          val distance = levenshteinDistance(window, target)
          val score = similarity(distance, window.size, target.size)
          if (score < MIN_CANDIDATE_SCORE) continue
          candidates +=
            MatchCandidate(
              targetIndex = targetIndex,
              score = score,
              isAcceptable =
                distance == 0 ||
                  (wordStarts != null &&
                    !requiresExactNoun &&
                    isAcceptableWordMatch(target, distance, score)),
              start = wordStarts?.get(first) ?: 0,
              end = wordStarts?.let { it[last] + words[last].length } ?: primaryForm.length,
              isAligned = wordStarts != null,
              targetLength = target.size,
            )
        }
      }
    }
  }

  /**
   * Whether [targetWords] are an article and a noun of at most [MAX_EXACT_ONLY_GRAPHEMES] graphemes
   * (e.g. "el sol"), which only match exactly.
   */
  private fun isShortNounAfterArticle(targetWords: List<String>): Boolean =
    targetWords.size >= 2 &&
      targetWords.first() in ARTICLES &&
      graphemeCount(targetWords.drop(1).joinToString("")) <= MAX_EXACT_ONLY_GRAPHEMES

  /** Returns [targetWords] and, after a leading article, the words without it. */
  private fun targetVariants(targetWords: List<String>): List<List<String>> {
    if (targetWords.size >= 2 && targetWords.first() in ARTICLES) {
      val withoutArticle = targetWords.drop(1)
      if (graphemeCount(withoutArticle.joinToString("")) >= MIN_ARTICLE_LESS_GRAPHEMES) {
        return listOf(targetWords, withoutArticle)
      }
    }
    return listOf(targetWords)
  }

  /** Whether a spoken window [distance] edits from [target], with [score], may count. */
  private fun isAcceptableWordMatch(target: List<String>, distance: Int, score: Double): Boolean {
    if (distance == 0) return true
    if (target.size <= MAX_EXACT_ONLY_GRAPHEMES || score < MATCH_THRESHOLD - SCORE_EPSILON) {
      return false
    }
    return isAlphabeticScript(target) || distance <= syllabicEditLimit(target.size)
  }

  /**
   * Returns the space-free script (CJK, kana, Thai) matches of [targetForm] in [transcriptForm]:
   * every occurrence as a substring (score `1`), or else, if [allowsFuzzy], the closest part of the
   * transcript within 1 edit (2 from 6 graphemes) for targets of at least 3 graphemes.
   */
  private fun spaceFreeCandidates(
    targetIndex: Int,
    targetForm: String,
    transcriptForm: String,
    spanSource: String?,
    primaryForm: String,
    allowsFuzzy: Boolean,
  ): List<MatchCandidate> {
    val target = targetForm.replace(" ", "")
    // Offsets in transcriptForm of the characters kept without spaces.
    val sourceOffsets = transcriptForm.indices.filter { transcriptForm[it] != ' ' }
    val compact = stripJapaneseFunctionWords(transcriptForm.replace(" ", ""))
    if (target.isEmpty() || compact.isEmpty()) return emptyList()
    val targetGraphemes = graphemes(target)

    fun candidate(score: Double, compactStart: Int, compactEnd: Int) =
      MatchCandidate(
        targetIndex = targetIndex,
        score = score,
        isAcceptable = true,
        start = if (spanSource != null) sourceOffsets[compactStart] else 0,
        end = if (spanSource != null) sourceOffsets[compactEnd - 1] + 1 else primaryForm.length,
        isAligned = spanSource != null,
        targetLength = targetGraphemes.size,
      )

    val exactMatches = buildList {
      var exactStart = compact.indexOf(target)
      while (exactStart >= 0) {
        add(candidate(1.0, exactStart, exactStart + target.length))
        exactStart = compact.indexOf(target, exactStart + 1)
      }
    }
    if (exactMatches.isNotEmpty()) return exactMatches
    if (!allowsFuzzy || targetGraphemes.size < 3) return emptyList()

    val maxDistance = if (targetGraphemes.size >= 6) 2 else 1
    val textGraphemes = graphemes(compact)
    val graphemeStarts =
      textGraphemes.runningFold(0) { offset, grapheme -> offset + grapheme.length }
    var best: Triple<Int, Int, Int>? = null // (distance, first grapheme, end grapheme)
    val minLength = max(1, targetGraphemes.size - maxDistance)
    val maxLength = min(textGraphemes.size, targetGraphemes.size + maxDistance)
    for (windowLength in minLength..maxLength) {
      for (first in 0..(textGraphemes.size - windowLength)) {
        val window = textGraphemes.subList(first, first + windowLength)
        val distance = levenshteinDistance(window, targetGraphemes)
        if (distance <= maxDistance && (best == null || distance < best.first)) {
          best = Triple(distance, first, first + windowLength)
        }
      }
    }
    val (distance, first, end) = best ?: return emptyList()
    return listOf(
      candidate(
        score = similarity(distance, end - first, targetGraphemes.size),
        compactStart = graphemeStarts[first],
        compactEnd = graphemeStarts[end],
      )
    )
  }

  /** Returns `1 - distance / max(lengths)`, the similarity of two grapheme strings. */
  private fun similarity(distance: Int, length: Int, otherLength: Int): Double =
    1.0 - distance.toDouble() / max(1, max(length, otherLength))

  /** Returns the character offset in [text] where each of its words starts. */
  private fun wordStartOffsets(text: String): List<Int> = buildList {
    var index = 0
    while (index < text.length) {
      if (text[index] != ' ') {
        add(index)
        while (index < text.length && text[index] != ' ') index++
      } else {
        index++
      }
    }
  }

  /**
   * Returns how many edits a target of [length] graphemes in a script whose graphemes are syllables
   * (e.g. Hangul or Devanagari) may differ by: 1 from 4 graphemes, and 2 from 7.
   */
  private fun syllabicEditLimit(length: Int): Int =
    when {
      length >= 7 -> 2
      length >= 4 -> 1
      else -> 0
    }

  private val ALPHABETIC_SCRIPTS =
    setOf(
      Character.UnicodeScript.LATIN,
      Character.UnicodeScript.CYRILLIC,
      Character.UnicodeScript.GREEK,
      Character.UnicodeScript.ARMENIAN,
      Character.UnicodeScript.GEORGIAN,
    )

  /** Whether every letter of [graphemes] is in an alphabetic script (and there is at least one). */
  private fun isAlphabeticScript(graphemes: List<String>): Boolean {
    var hasLetter = false
    for (grapheme in graphemes) {
      for (codePoint in grapheme.codePoints().toArray()) {
        if (!Character.isLetter(codePoint)) continue
        if (Character.UnicodeScript.of(codePoint) !in ALPHABETIC_SCRIPTS) return false
        hasLetter = true
      }
    }
    return hasLetter
  }

  /**
   * Lowest similarity at which a spoken word counts for a target. With [MATCH_MARGIN], one edit is
   * allowed from 4 graphemes, two from 7, and three from 10, while 2 edits in a 5-letter word
   * ("noche" for "leche", similarity 0.6) don't count.
   */
  private const val MATCH_THRESHOLD = 0.7

  /** How much a fuzzy match must beat the next-closest target on the same spoken words by. */
  private const val MATCH_MARGIN = 0.15

  /** Targets of at most this many graphemes (e.g. "col" or "sol") only match exactly. */
  private const val MAX_EXACT_ONLY_GRAPHEMES = 3

  /**
   * Lowest score kept as a candidate; lower ones can neither match nor be a runner-up that blocks.
   */
  private const val MIN_CANDIDATE_SCORE = MATCH_THRESHOLD - MATCH_MARGIN - 0.05

  /** Shortest article-less part of a target (e.g. "sol" in "el sol") that matches on its own. */
  private const val MIN_ARTICLE_LESS_GRAPHEMES = 3

  /** Tolerance for comparing scores computed in floating point. */
  private const val SCORE_EPSILON = 1e-9

  /** Leading articles a spoken word may leave out (e.g. "manzana" for "la manzana"). */
  private val ARTICLES =
    setOf(
      // Spanish.
      "el",
      "la",
      "los",
      "las",
      "un",
      "una",
      "unos",
      "unas",
      // French ("l'" is split off as "l").
      "le",
      "les",
      "l",
      "une",
      "des",
      // Italian.
      "il",
      "lo",
      "i",
      "gli",
      "uno",
      // Portuguese.
      "o",
      "a",
      "os",
      "as",
      "um",
      "uma",
      // German.
      "der",
      "die",
      "das",
      "den",
      "dem",
      "ein",
      "eine",
      "einen",
      "einem",
      "einer",
      // English.
      "the",
      "an",
    )

  private fun addNormalizedVariant(forms: MutableSet<String>, variant: String?) {
    if (variant == null) return
    val normalized = normalizeSpeechMatchText(variant)
    if (normalized.isNotEmpty()) {
      forms += normalized
      forms += normalized.replace(" ", "")
    }
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

  /** Whether [text] has a script written without spaces between words (CJK, kana, Thai). */
  internal fun containsSpaceFreeScript(text: String): Boolean =
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
