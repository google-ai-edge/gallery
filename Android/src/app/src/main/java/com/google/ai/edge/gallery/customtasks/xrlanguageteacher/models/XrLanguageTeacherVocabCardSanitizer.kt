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

import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.proto.VocabCardProto
import com.google.ai.edge.gallery.proto.dictionaryEntry
import com.google.ai.edge.gallery.proto.vocabCardProto

/** Structured vocabulary card that Pass 2 extracts from the teacher's reply (no phonetic field). */
data class VocabCardData(
  val word: String,
  val meaning: String,
  val example: String = "",
  val exampleTranslation: String = "",
) {
  /** Converts this [VocabCardData] into a [DictionaryEntry] for persistence and UI display. */
  fun toDictionaryEntry(
    mainLanguage: Language,
    learnLanguage: Language,
    definition: String = "",
  ): DictionaryEntry {
    val cardWord = word
    val cardMeaning = meaning
    val cardExample = example
    val cardExampleTranslation = exampleTranslation
    return dictionaryEntry {
      word = cardWord
      translation = cardMeaning
      targetSentence = cardExample
      nativeSentence = cardExampleTranslation
      this.definition = definition
      this.mainLanguage = mainLanguage
      this.learnLanguage = learnLanguage
    }
  }

  /**
   * Converts this [VocabCardData] into a [VocabCardProto] stored with the lesson session history,
   * tagged with the session's [mainLanguage] and [learnLanguage].
   */
  fun toVocabCardProto(mainLanguage: Language, learnLanguage: Language): VocabCardProto =
    vocabCardProto {
      word = this@VocabCardData.word
      meaning = this@VocabCardData.meaning
      example = this@VocabCardData.example
      exampleTranslation = this@VocabCardData.exampleTranslation
      this.mainLanguage = mainLanguage
      this.learnLanguage = learnLanguage
    }
}

/** Converts a persisted [VocabCardProto] back into [VocabCardData], trimming its text fields. */
fun VocabCardProto.toVocabCardData(): VocabCardData =
  VocabCardData(
    word = word.trim(),
    meaning = meaning.trim(),
    example = example.trim(),
    exampleTranslation = exampleTranslation.trim(),
  )

/**
 * Sanitizes and normalizes vocabulary card fields (`word`, `meaning`, `example`,
 * `exampleTranslation`), stripping stray edge punctuation, unwrapping enclosing quotes, and
 * replacing unsafe filename characters.
 */
internal object XrLanguageTeacherVocabCardSanitizer {

  private val FILENAME_UNSAFE_REGEX = Regex("[/\\\\:*?\"<>|\\x00-\\x1F]+")
  private val LEADING_ARTICLE_REGEX =
    Regex(
      "^(?i)(?:el|la|los|las|un|una|unos|unas|le|les|une|des|der|die|das|ein|eine|the|a|an)\\s+"
    )

  private val CARD_SENTENCE_END_PUNCT_REGEX = Regex("[.!?…。！？।॥؟۔]+")
  private val CARD_SENTENCE_END_PUNCT_CHARS =
    setOf('.', '!', '?', '…', '。', '！', '？', '।', '॥', '؟', '۔')
  private val LEADING_INVERTED_PUNCT_REGEX = Regex("^[¡¿]+")
  private val LEADING_STRAY_PUNCTUATION_REGEX =
    Regex("""^[\s.,…;:<>"'`‘’“”«»「」『』\-–—*•·~^|/\\()\[\]{}!?¡¿、，。：；！？]+""")
  private val TRAILING_STRAY_PUNCTUATION_REGEX =
    Regex("""[\s,;:<>"'`‘’“”«»「」『』\-–—*•·~^|/\\()\[\]{}¡¿、，：；]+$""")

  // Enclosing quotes and brackets that are unwrapped when they surround the entire field, e.g.
  // `「つくえ」` -> `つくえ`.
  private val ENCLOSING_OPENING_TO_CLOSING_PUNCTUATION =
    mapOf('「' to '」', '『' to '』', '“' to '”', '«' to '»', '(' to ')', '[' to ']', '{' to '}')

  // Opening punctuation (including Spanish `¡...!` and `¿...?`) kept at the edge of a card field
  // when its closing partner appears later in the same field.
  private val OPENING_TO_CLOSING_PUNCTUATION =
    ENCLOSING_OPENING_TO_CLOSING_PUNCTUATION + mapOf('¡' to '!', '¿' to '?')
  private val CLOSING_TO_OPENING_PUNCTUATION =
    OPENING_TO_CLOSING_PUNCTUATION.entries.associate { (opening, closing) -> closing to opening }

  private fun containsLetterOrDigit(text: String): Boolean = text.any {
    it.isLetter() || it.isDigit()
  }

  /**
   * Returns true if [wordA] and [wordB] represent the same vocabulary word, ignoring case, tags,
   * trailing punctuation, and leading articles (e.g., `"el pasaporte"` vs `"pasaporte"`).
   */
  fun isSameVocabularyWord(wordA: String?, wordB: String?): Boolean {
    val a =
      cleanCardFieldValue(
          XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(wordA.orEmpty()),
          stripSentenceEndPunct = true,
        )
        .replace(LEADING_INVERTED_PUNCT_REGEX, "")
        .replace(CARD_SENTENCE_END_PUNCT_REGEX, "")
        .trim()
    val b =
      cleanCardFieldValue(
          XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(wordB.orEmpty()),
          stripSentenceEndPunct = true,
        )
        .replace(LEADING_INVERTED_PUNCT_REGEX, "")
        .replace(CARD_SENTENCE_END_PUNCT_REGEX, "")
        .trim()
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.equals(b, ignoreCase = true)) return true
    val strippedA = stripLeadingArticle(a)
    val strippedB = stripLeadingArticle(b)
    return strippedA.isNotEmpty() && strippedA.equals(strippedB, ignoreCase = true)
  }

  /**
   * Strips leading/trailing stray punctuation and quotes (while preserving paired Spanish
   * `¡...!`/`¿...?` and sentence-ending punctuation unless [stripSentenceEndPunct] is true) and
   * clears strings that contain no letters or digits.
   *
   * A paired quote or bracket at the edge is kept when its partner is inside the field (e.g. the
   * `」` in `彼は「ありがとう」`), and removed when it is unpaired or wraps the whole field (e.g. `「つくえ」`
   * becomes `つくえ`).
   */
  fun cleanCardFieldValue(raw: String, stripSentenceEndPunct: Boolean = false): String {
    var cleaned = stripStrayEdgePunctuation(raw)
    if (stripSentenceEndPunct) {
      cleaned = stripStrayEdgePunctuation(stripUnpairedSentenceEndPunctuation(cleaned))
    }
    return if (containsLetterOrDigit(cleaned)) cleaned else ""
  }

  private fun stripUnpairedSentenceEndPunctuation(text: String): String =
    buildString(text.length) {
      for ((index, ch) in text.withIndex()) {
        if (ch !in CARD_SENTENCE_END_PUNCT_CHARS || isPairedClosingPunctuation(text, index)) {
          append(ch)
        }
      }
    }

  /**
   * Removes [LEADING_STRAY_PUNCTUATION_REGEX] and [TRAILING_STRAY_PUNCTUATION_REGEX] runs from
   * [text], except for quotes and brackets whose partner is inside the text, and unwraps quotes or
   * brackets that enclose the whole text.
   */
  private fun stripStrayEdgePunctuation(text: String): String {
    var current = text.trim()
    while (true) {
      val leadingRunLength = LEADING_STRAY_PUNCTUATION_REGEX.find(current)?.value?.length ?: 0
      val leadingEnd =
        (0 until leadingRunLength).firstOrNull { isPairedOpeningPunctuation(current, it) }
          ?: leadingRunLength
      current = current.substring(leadingEnd)

      val trailingRunLength = TRAILING_STRAY_PUNCTUATION_REGEX.find(current)?.value?.length ?: 0
      val trailingStart = current.length - trailingRunLength
      val trailingEnd =
        (current.lastIndex downTo trailingStart)
          .firstOrNull { isPairedClosingPunctuation(current, it) }
          ?.plus(1) ?: trailingStart
      current = current.substring(0, trailingEnd).trim()

      val unwrapped = removeEnclosingPunctuationPair(current)
      if (unwrapped == current) return current
      current = unwrapped
    }
  }

  /**
   * Returns true if `text[index]` is an opening quote/bracket with an unmatched closer after it.
   */
  private fun isPairedOpeningPunctuation(text: String, index: Int): Boolean {
    val opening = text[index]
    val closing = OPENING_TO_CLOSING_PUNCTUATION[opening] ?: return false
    return (index + 1 until text.length).count { text[it] == closing } >
      (index + 1 until text.length).count { text[it] == opening }
  }

  /**
   * Returns true if `text[index]` is a closing quote/bracket with an unmatched opener before it.
   */
  private fun isPairedClosingPunctuation(text: String, index: Int): Boolean {
    val closing = text[index]
    val opening = CLOSING_TO_OPENING_PUNCTUATION[closing] ?: return false
    return (0 until index).count { text[it] == opening } >
      (0 until index).count { text[it] == closing }
  }

  /** Removes a quote or bracket pair that encloses all of [text], e.g. `「つくえ」` to `つくえ`. */
  private fun removeEnclosingPunctuationPair(text: String): String {
    if (text.length < 2) return text
    val opening = text.first()
    val closing = ENCLOSING_OPENING_TO_CLOSING_PUNCTUATION[opening] ?: return text
    if (text.last() != closing) return text
    var depth = 0
    for ((index, ch) in text.withIndex()) {
      if (ch == opening) depth++ else if (ch == closing) depth--
      if (depth == 0 && index < text.lastIndex) return text
    }
    if (depth != 0) return text
    return text.substring(1, text.lastIndex).trim()
  }

  /**
   * Sanitizes [card] fields by stripping stray HTML/XML tags and edge punctuation, backfilling an
   * omitted translation field from the other, and isolating the shorter `<target>` span from
   * [replyWithTargets] if `word` accidentally holds a full sentence.
   */
  @Suppress("UNUSED_PARAMETER")
  fun sanitizeVocabCard(
    card: VocabCardData,
    replyWithTargets: String = "",
    mainLanguage: Language = Language.LANGUAGE_ENGLISH,
    learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED,
  ): VocabCardData {
    val rawWord =
      cleanCardFieldValue(XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(card.word))
    var cleanExample =
      cleanCardFieldValue(XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(card.example))
    val cleanMeaningInput =
      cleanCardFieldValue(
        XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(card.meaning),
        stripSentenceEndPunct = true,
      )
    val cleanExampleTranslationInput =
      cleanCardFieldValue(
        XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(card.exampleTranslation)
      )

    // Backfill empty meaning with exampleTranslation or vice-versa so target-language cards
    // are not dropped if one translation field was omitted by the model.
    val effectiveMeaning = cleanMeaningInput.ifBlank { cleanExampleTranslationInput }
    val effectiveExampleTranslation = cleanExampleTranslationInput.ifBlank { cleanMeaningInput }

    val candidateWord = rawWord.ifEmpty { cleanExample }
    if (candidateWord.isEmpty()) {
      return card.copy(
        word = "",
        meaning = effectiveMeaning,
        example = cleanExample,
        exampleTranslation = effectiveExampleTranslation,
      )
    }

    val wordHadSentencePunct = CARD_SENTENCE_END_PUNCT_REGEX.containsMatchIn(candidateWord)
    val strippedWord = cleanCardFieldValue(candidateWord, stripSentenceEndPunct = true)
    val strippedExample = cleanCardFieldValue(cleanExample, stripSentenceEndPunct = true)

    // If <reply> wrapped a shorter focal <target> term that appears inside `word` (and is not just
    // `word` without its leading article or the example sentence itself), prefer the longest
    // matching <target> span.
    val shorterFocalTarget =
      XrLanguageTeacherDialogueFormatter.extractAllTargetPhrases("$replyWithTargets\n${card.word}")
        .map { cleanCardFieldValue(it, stripSentenceEndPunct = true) }
        .filter { span ->
          span.isNotEmpty() &&
            span.length < strippedWord.length &&
            !span.equals(strippedExample, ignoreCase = true) &&
            !isSameVocabularyWord(span, strippedWord) &&
            strippedWord.contains(span, ignoreCase = true)
        }
        .maxByOrNull { it.length }

    val isolatedWord =
      cleanCardFieldValue(
        (shorterFocalTarget ?: strippedWord).ifEmpty { strippedWord },
        stripSentenceEndPunct = true,
      )

    if (
      (cleanExample.isEmpty() || cleanExample.equals(isolatedWord, ignoreCase = true)) &&
        (isolatedWord != candidateWord || wordHadSentencePunct)
    ) {
      cleanExample = cleanCardFieldValue(candidateWord)
    }

    return card.copy(
      word = isolatedWord,
      meaning = effectiveMeaning,
      example = cleanExample,
      exampleTranslation = effectiveExampleTranslation,
    )
  }

  internal fun stripLeadingArticle(phrase: String): String {
    return phrase.trim().replace(LEADING_ARTICLE_REGEX, "").trim()
  }

  /**
   * Sanitizes a vocabulary word into a safe single-component filename so slashes or special
   * characters in generated words (e.g. `"el / la estudiante"`) never fail file I/O.
   */
  fun sanitizeWordForFilename(word: String): String {
    val cleaned = word.trim().replace(FILENAME_UNSAFE_REGEX, "_").trim('.', ' ', '_').take(64)
    return cleaned.ifEmpty { "vocab_entry" }
  }

  /**
   * Sanitizes [word] (case-insensitively) and prefixes [learnLanguage] so homographs across
   * different target languages never overwrite or delete each other's saved thumbnail files.
   */
  fun sanitizeWordForFilename(word: String, learnLanguage: Language): String {
    val base = sanitizeWordForFilename(word.lowercase())
    return if (learnLanguage != Language.LANGUAGE_UNSPECIFIED) {
      "${learnLanguage.name.lowercase()}_$base"
    } else {
      base
    }
  }
}
