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
 * `exampleTranslation`), stripping conversational frames, copula prefixes, and unsafe filename
 * characters.
 */
internal object XrLanguageTeacherVocabCardSanitizer {

  private val FILENAME_UNSAFE_REGEX = Regex("[/\\\\:*?\"<>|\\x00-\\x1F]+")
  private val LEADING_ARTICLE_REGEX =
    Regex(
      "^(?i)(?:el|la|los|las|un|una|unos|unas|le|les|une|des|der|die|das|ein|eine|the|a|an)\\s+"
    )

  private val CARD_SENTENCE_END_PUNCT_REGEX = Regex("[.!?…。！？।॥؟۔]+")
  private val LEADING_INVERTED_PUNCT_REGEX = Regex("^[¡¿]+")
  private val SURROUNDING_QUOTES_REGEX = Regex("""^["'“”‘’「」『』«»]+|["'“”‘’「」『』«»]+$""")
  private val LEADING_STRAY_PUNCTUATION_REGEX =
    Regex("""^[\s.,…;:<>"'`‘’“”«»「」『』\-–—*•·~^|/\\()\[\]{}!?、，。：；！？]+""")
  private val TRAILING_STRAY_PUNCTUATION_REGEX =
    Regex("""[\s,;:<>"'`‘’“”«»「」『』\-–—*•·~^|/\\()\[\]{}¡¿、，：；]+$""")

  // English introductory frames, e.g. "To say that, you can say...", "A polite way to ask is..."
  private val ENGLISH_CONVERSATIONAL_FRAME_PREFIX_REGEX =
    Regex(
      """(?i)^(?:""" +
        """(?:(?:to|when|if\s+you\s+want\s+to)\s+(?:say|ask|express|greet|order|answer|reply|respond|thank|apologize|introduce)\b[^,:;.!?—–]{0,80}?(?:[,:—–-]\s*|\s+)(?:(?:you\s+(?:can|could|would|should|may|might)\s+(?:just\s+|simply\s+|politely\s+)?(?:say|ask|use|reply|answer)|(?:try|practice)\s+(?:saying|asking|using)|(?:just\s+|simply\s+|politely\s+)?(?:say|ask|use)|(?:it|this|that)\s+is|it's|that's)(?:[:\s,—–-]+|$)))|""" +
        """(?:(?:to|when|if\s+you\s+want\s+to)\s+(?:say|ask|express|greet|order|answer|reply|respond|thank|apologize|introduce)\b[^,:;.!?—–]{0,80}?[,:—–-]\s*)|""" +
        """(?:(?:in\s+[a-z]+\s*,?\s*)?(?:(?:a\s+)?(?:polite|common|natural|casual|formal|good|simple|easy)\s+way\s+(?:to\s+[^,:]{0,60}?\s+)?is(?:(?:\s+to)?\s+say)?|you\s+(?:can|could|would|should|may|might)\s+(?:just\s+|simply\s+|politely\s+)?(?:say|ask|use|reply|answer)|(?:try|practice)\s+(?:saying|asking|using)|(?:just|simply|politely)\s+(?:say|ask|use)|(?:we|people|they)\s+(?:say|use|call\s+it)|(?:it|this|that)\s+(?:is\s+called|means)|the\s+(?:word|phrase|expression|term|greeting)(?:\s+for\s+[^,:]{0,60}?)?\s+is)(?:[:\s,—–-]+|$))|""" +
        """(?:(?:in\s+[a-z]+\s*,\s*)(?:(?:it|this|that)\s+is|it's|that's|say|use)(?:[:\s,—–-]+|$))|""" +
        """(?:(?:please\s+)?say\s+(?=["'“‘「『¡¿\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]))""" +
        """)"""
    )

  // Romance & German frames, e.g. "puedes decir: ...", "on dit: ...", "man sagt: ..."
  private val WESTERN_CONVERSATIONAL_FRAME_PREFIX_REGEX =
    Regex(
      """(?i)^(?:puedes\s+decir|se\s+dice|se\s+llama|on\s+dit|tu\s+peux\s+dire|vous\s+pouvez\s+dire|man\s+sagt|du\s+kannst\s+sagen)(?:[:\s,—–-]+|$)"""
    )

  // CJK introductory frames, e.g. "日本語では...", "韓国語では...", "中国語では...", "それを言うには、..."
  private val CJK_CONVERSATIONAL_FRAME_PREFIX_REGEX =
    Regex("""^(?:(?:日本語では?|韓国語では?|中国語では?|それを言うには、?)\s*)""")

  private val ASCII_FRAME_BEFORE_NON_LATIN_REGEX =
    Regex(
      """^(?:[A-Za-z']+[,\s:;'"“”‘’()—–-]+){1,15}[A-Za-z']+\s*[,:;'"“”‘’—–-]?\s*(?=["'“‘「『]?[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}\p{IsThai}\p{IsArabic}\p{IsDevanagari}\p{IsCyrillic}])"""
    )
  private val JA_SAY_SUFFIX_REGEX =
    Regex("""(?:と(?:言います|いいます|呼びます|よびます)|って(?:言います|いいます))[.!?…。！？]*$""")
  private val JA_LOCATIVE_EXISTENTIAL_REGEX = Regex("^(?:.+[にでへは])?(.+?)(?:があります|がいます)[.!?…。！？]*$")
  private val JA_COPULA_SENTENCE_REGEX =
    Regex(
      "^(?:これは|それは|あれは|こちらは|そちらは|あちらは|ここは|そこは|あそこは|この.+?は|その.+?は|あの.+?は)(.+?)(?:です|だ|である|でございます|だね|だよ|ですね|ですよ)[.!?…。！？]*$"
    )
  private val KO_COPULA_SENTENCE_REGEX =
    Regex("^(?:이것은|그것은|저것은|이건|グ건|저건|이게|그게|저게)\\s*(.+?)(?:입니다|이에요|예요|이다)[.!?…。！？]*$")
  private val ZH_COPULA_SENTENCE_REGEX = Regex("^(?:这是|那是|这里是|那里是|這是|那是|這裡是|那裡是)(.+?)[.!?…。！？]*$")
  private val WESTERN_COPULA_PREFIX_REGEX =
    Regex(
      "^(?i)(?:esto es|esta es|este es|eso es|esa es|aquí hay|c'est|ce sont|voici|voilà|das ist|dies ist|hier ist|questo è|questa è|ecco|isto é|isso é|this is|that is|it is|it's|here is)\\s+"
    )

  internal data class UnframedResult(val text: String, val strippedFrame: Boolean)

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
        .trim()
    val b =
      cleanCardFieldValue(
          XrLanguageTeacherDialogueFormatter.stripTargetAndHtmlTags(wordB.orEmpty()),
          stripSentenceEndPunct = true,
        )
        .replace(LEADING_INVERTED_PUNCT_REGEX, "")
        .trim()
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.equals(b, ignoreCase = true)) return true
    val strippedA = stripLeadingArticle(a)
    val strippedB = stripLeadingArticle(b)
    return strippedA.isNotEmpty() && strippedA.equals(strippedB, ignoreCase = true)
  }

  /**
   * Strips leading/trailing stray punctuation and quotes (while preserving Spanish leading `¡`/`¿`
   * and sentence-ending punctuation unless [stripSentenceEndPunct] is true) and clears strings that
   * contain no letters or digits.
   */
  fun cleanCardFieldValue(raw: String, stripSentenceEndPunct: Boolean = false): String {
    var cleaned =
      raw
        .trim()
        .replace(LEADING_STRAY_PUNCTUATION_REGEX, "")
        .replace(TRAILING_STRAY_PUNCTUATION_REGEX, "")
    if (stripSentenceEndPunct) {
      cleaned =
        cleaned
          .replace(CARD_SENTENCE_END_PUNCT_REGEX, "")
          .replace(LEADING_STRAY_PUNCTUATION_REGEX, "")
          .replace(TRAILING_STRAY_PUNCTUATION_REGEX, "")
    }
    cleaned = cleaned.trim()
    return if (containsLetterOrDigit(cleaned)) cleaned else ""
  }

  internal fun stripConversationalFrame(
    text: String,
    mainLanguage: Language = Language.LANGUAGE_ENGLISH,
    learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED,
    isExampleField: Boolean = false,
  ): UnframedResult {
    var current = text.trim().take(300)
    if (current.isEmpty()) return UnframedResult("", false)
    var strippedAny = false

    var progress = true
    while (progress && current.isNotEmpty()) {
      progress = false
      if (ENGLISH_CONVERSATIONAL_FRAME_PREFIX_REGEX.containsMatchIn(current)) {
        current = current.replaceFirst(ENGLISH_CONVERSATIONAL_FRAME_PREFIX_REGEX, "").trim()
        strippedAny = true
        progress = true
      }
      // Only apply Western Romance/German frames when the host language is that language (or
      // unspecified/English host), but never strip them if the target language is being learned
      // so valid target-language sentences like "Se llama Juan." are never corrupted.
      val isTargetRomanceOrGerman =
        learnLanguage == Language.LANGUAGE_SPANISH ||
          learnLanguage == Language.LANGUAGE_FRENCH ||
          learnLanguage == Language.LANGUAGE_GERMAN
      if (
        !isTargetRomanceOrGerman &&
          WESTERN_CONVERSATIONAL_FRAME_PREFIX_REGEX.containsMatchIn(current)
      ) {
        current = current.replaceFirst(WESTERN_CONVERSATIONAL_FRAME_PREFIX_REGEX, "").trim()
        strippedAny = true
        progress = true
      }
      if (CJK_CONVERSATIONAL_FRAME_PREFIX_REGEX.containsMatchIn(current)) {
        current = current.replaceFirst(CJK_CONVERSATIONAL_FRAME_PREFIX_REGEX, "").trim()
        strippedAny = true
        progress = true
      }
    }
    // Only strip ASCII frames before non-Latin scripts if this is the `word` field (never
    // `example`),
    // preventing English brand names before Japanese (e.g. "Google Pixelは便利です") in example
    // sentences
    // from having their subjects stripped.
    if (
      !isExampleField &&
        (learnLanguage == Language.LANGUAGE_JAPANESE ||
          learnLanguage == Language.LANGUAGE_KOREAN ||
          learnLanguage == Language.LANGUAGE_CHINESE ||
          learnLanguage == Language.LANGUAGE_UNSPECIFIED) &&
        ASCII_FRAME_BEFORE_NON_LATIN_REGEX.containsMatchIn(current)
    ) {
      current = current.replaceFirst(ASCII_FRAME_BEFORE_NON_LATIN_REGEX, "").trim()
      strippedAny = true
    }
    if (JA_SAY_SUFFIX_REGEX.containsMatchIn(current)) {
      current = current.replaceFirst(JA_SAY_SUFFIX_REGEX, "").trim()
      strippedAny = true
    }
    current = current.replace(SURROUNDING_QUOTES_REGEX, "").trim()
    return UnframedResult(current, strippedAny)
  }

  /**
   * Sanitizes [card] so full sentences, conversational introductory frames (e.g. `"To say that, you
   * can say ..."`), or copula frames never render as the Vocab Card title, moving the full
   * target-language sentence to `"example"` and extracting the core taught term/phrase into
   * `"word"`.
   */
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

    // Strip conversational frames only from `word` to avoid corrupting valid target sentences
    // in `example`.
    val (unframedWordCandidate, hadConversationalFrame) =
      stripConversationalFrame(rawWord, mainLanguage = mainLanguage, learnLanguage = learnLanguage)
    val unframedWord = cleanCardFieldValue(unframedWordCandidate).ifEmpty { cleanExample }
    if (unframedWord.isEmpty()) {
      return card.copy(
        word = "",
        meaning = effectiveMeaning,
        example = cleanExample,
        exampleTranslation = effectiveExampleTranslation,
      )
    }

    val (unframedExampleCandidate, exampleHadFrame) =
      stripConversationalFrame(
        cleanExample,
        mainLanguage = mainLanguage,
        learnLanguage = learnLanguage,
        isExampleField = true,
      )
    if (exampleHadFrame) {
      cleanExample = cleanCardFieldValue(unframedExampleCandidate)
    }

    val wordHadSentencePunct =
      CARD_SENTENCE_END_PUNCT_REGEX.containsMatchIn(unframedWord) ||
        CARD_SENTENCE_END_PUNCT_REGEX.containsMatchIn(rawWord)
    val strippedWord = cleanCardFieldValue(unframedWord, stripSentenceEndPunct = true)
    val strippedExample = cleanCardFieldValue(cleanExample, stripSentenceEndPunct = true)
    val duplicatesExample =
      strippedExample.isNotEmpty() && strippedWord.equals(strippedExample, ignoreCase = true)

    val frameIsolated =
      cleanCardFieldValue(
        isolateCoreTermFromSentenceFrame(
          rawWord = unframedWord,
          strippedWord = strippedWord,
          forceStripWesternCopula =
            duplicatesExample || wordHadSentencePunct || hadConversationalFrame,
        ),
        stripSentenceEndPunct = true,
      )

    // 1. If <reply> explicitly wrapped a shorter focal <target> term that appears inside the full
    // sentence when `word` duplicated `example`, carried sentence punctuation, or had a
    // conversational/copula frame, prefer a span matching `frameIsolated` or the longest
    // non-sentence focal phrase (avoiding single-char incidental nouns like `机` when
    // `<target>机</target>` and `<target>青いマット</target>` both appear).
    // Append `card.word` so any explicit <target> tags emitted inside `word` itself are harvested.
    val targetSpans =
      XrLanguageTeacherDialogueFormatter.extractAllTargetPhrases("$replyWithTargets\n${card.word}")
        .map { cleanCardFieldValue(it, stripSentenceEndPunct = true) }
        .filter { it.isNotEmpty() }
    val shorterFocalTarget =
      if (
        duplicatesExample ||
          wordHadSentencePunct ||
          hadConversationalFrame ||
          frameIsolated != strippedWord
      ) {
        val candidateSpans = targetSpans.filter { span ->
          span.length < strippedWord.length &&
            strippedWord.contains(span, ignoreCase = true) &&
            !JA_LOCATIVE_EXISTENTIAL_REGEX.matches(span) &&
            !JA_COPULA_SENTENCE_REGEX.matches(span) &&
            !KO_COPULA_SENTENCE_REGEX.matches(span) &&
            !ZH_COPULA_SENTENCE_REGEX.matches(span)
        }
        candidateSpans.firstOrNull { it.equals(frameIsolated, ignoreCase = true) }
          ?: candidateSpans.maxByOrNull { it.length }
      } else {
        null
      }

    // 2. Otherwise fall back to the grammar-frame-stripped core term.
    val isolatedWord =
      cleanCardFieldValue(
        (shorterFocalTarget ?: frameIsolated).ifEmpty { strippedWord },
        stripSentenceEndPunct = true,
      )

    // If `example` was omitted (or duplicated only the isolated term) while `rawWord` originally
    // held a full sentence or conversational frame, preserve the unframed target-language sentence
    // as the card's example sentence.
    if (
      (cleanExample.isEmpty() || cleanExample.equals(isolatedWord, ignoreCase = true)) &&
        (isolatedWord != unframedWord || wordHadSentencePunct || hadConversationalFrame)
    ) {
      cleanExample = cleanCardFieldValue(unframedWord)
    }

    return card.copy(
      word = isolatedWord,
      meaning = effectiveMeaning,
      example = cleanExample,
      exampleTranslation = effectiveExampleTranslation,
    )
  }

  private fun isolateCoreTermFromSentenceFrame(
    rawWord: String,
    strippedWord: String,
    forceStripWesternCopula: Boolean,
  ): String {
    JA_LOCATIVE_EXISTENTIAL_REGEX.matchEntire(rawWord)?.groupValues?.getOrNull(1)?.trim()?.let {
      if (it.isNotEmpty()) return it
    }
    JA_COPULA_SENTENCE_REGEX.matchEntire(rawWord)?.groupValues?.getOrNull(1)?.trim()?.let {
      if (it.isNotEmpty()) return it
    }
    KO_COPULA_SENTENCE_REGEX.matchEntire(rawWord)?.groupValues?.getOrNull(1)?.trim()?.let {
      if (it.isNotEmpty()) return it
    }
    ZH_COPULA_SENTENCE_REGEX.matchEntire(rawWord)?.groupValues?.getOrNull(1)?.trim()?.let {
      if (it.isNotEmpty()) return it
    }
    val withoutWesternCopula =
      if (forceStripWesternCopula || WESTERN_COPULA_PREFIX_REGEX.containsMatchIn(rawWord)) {
        strippedWord.replace(WESTERN_COPULA_PREFIX_REGEX, "").trim()
      } else {
        strippedWord
      }
    return withoutWesternCopula.ifEmpty { strippedWord }
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
