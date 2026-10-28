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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.tts.TtsTextNormalizer

/** Represents a single spoken segment in a structured teacher dialogue. */
data class DialogueSegment(val text: String, val isTargetLanguage: Boolean)

/**
 * Real-time streaming extraction state for `<reply>...</reply>` so raw XML tags (`<reply>`,
 * `</reply>`, `<card>...</card>`) never leak into the HUD or streaming TTS.
 *
 * @property hasReplyOpenTag Whether the stream contains an opening `<reply>` tag. Until it does,
 *   [rawReplyContent] holds untagged text from the start of the stream; once it appears,
 *   [rawReplyContent] restarts after the tag, so callers tracking offsets into it must reset them.
 */
data class StreamingReplyState(
  val replyWithTargetTags: String,
  val rawReplyContent: String,
  val isReplyComplete: Boolean,
  val hasReplyOpenTag: Boolean = false,
)

/** Complete post-stream normalized result of the Pass 1 teacher reply (`<reply>...</reply>`). */
data class TeacherReply(
  val replyWithTargetTags: String = "",
  val cleanReply: String = "",
  val dialogueSegments: List<DialogueSegment> = emptyList(),
)

/**
 * Represents a single spoken segment in a bilingual teacher commentary.
 *
 * @property isFiller Whether this is the thinking filler spoken before the reply.
 * @property presynthesizedFiller The filler's audio synthesized ahead of time, played instead of
 *   synthesizing [text], or `null` to synthesize it.
 */
data class MultilingualTtsSegment(
  val text: String,
  val languageCode: String,
  val isTargetLanguage: Boolean,
  val isFiller: Boolean = false,
  val presynthesizedFiller: PresynthesizedFiller? = null,
)

/**
 * Formatter and parser for the AR Language Teacher Pass 1 `<reply>...</reply>` schema with
 * `<target>...</target>` spans.
 */
object XrLanguageTeacherDialogueFormatter {

  const val TTS_TARGET_ANNOTATION_TAG = "TTS_TARGET"

  // Regexes on the per-token streaming path are compiled once.
  private val TRAILING_PARTIAL_TAG_REGEX = Regex("<[a-zA-Z/_]{0,20}$")
  private val TRAILING_INCOMPLETE_TAG_REGEX = Regex("<[a-zA-Z/]{0,12}$")
  private val SAY_OPEN_TAG_REGEX = Regex("(?i)<say(?:\\s+[^>]*)?>")
  private val SAY_CLOSE_TAG_REGEX = Regex("(?i)</say>")
  private val UNCLOSED_TARGET_BEFORE_OPEN_REGEX =
    Regex("(?is)<target>((?:(?!</?target>).)*?)<target>")
  private val TARGET_TAG_OPEN_REGEX = Regex("(?i)<(?:target|say)(?:\\s+[^>]*)?>")
  private val TARGET_TAG_CLOSE_REGEX = Regex("(?i)</(?:target|say)>")
  private val TARGET_SPAN_REGEX = Regex("(?is)<(?:target|say)(?:\\s+[^>]*)?>(.*?)</(?:target|say)>")
  private val ANY_XML_TAG_REGEX = Regex("<[^>]+>")

  private val LEADING_PUNCTUATION_REGEX =
    Regex("""^([!?,.:;…—–"'\)\]\u2018\u2019\u201c\u201d]+)\s*""")
  private val TRAILING_SENTENCE_PUNCTUATION_REGEX =
    Regex("""[!?,.:;…—–"'\)\]\u2018\u2019\u201c\u201d]$""")

  private val REPLY_OPEN_TAG_REGEX = Regex("(?i)<reply(?:\\s+[^>]*)?>")
  private val REPLY_END_OR_CARD_START_REGEX =
    Regex("(?i)(?:</reply\\s*>|<card(?:\\s+[^>]*)?>|<card\\s*$)")
  private val PARTIAL_LEADING_REPLY_TAG_REGEX =
    Regex("^(?i)<(?:r(?:e(?:p(?:l(?:y(?:\\s+[^>]*)?)?)?)?)?)?$")
  private val PARTIAL_TRAILING_SCHEMA_TAG_REGEX =
    Regex("(?i)</?(?:r(?:e(?:p(?:l(?:y)?)?)?)?|c(?:a(?:r(?:d)?)?)?)\\s*$")

  private val ABBREVIATION_SUFFIX_REGEX =
    Regex("""(?i)\b(?:prof|mr|mrs|ms|dr|st|jr|sr|vs|etc)\.\s*$""")
  private val SENTENCE_BOUNDARY_REGEX =
    Regex("""(?:[.!?](?:</(?:target|say)>)?\s+|[。！？](?:</(?:target|say)>)?\s*)""")

  private const val SHIELD_OPEN = "___TARGET_OPEN_TOKEN___"
  private const val SHIELD_CLOSE = "___TARGET_CLOSE_TOKEN___"

  private fun isSentenceEndingPunctuation(ch: Char): Boolean =
    ch == '.' || ch == '!' || ch == '?' || ch == '。' || ch == '！' || ch == '？'

  /**
   * Returns true if [wordA] and [wordB] represent the same vocabulary word, ignoring case, tags,
   * trailing punctuation, and leading articles (e.g., `"el pasaporte"` vs `"pasaporte"`).
   */
  fun isSameVocabularyWord(wordA: String?, wordB: String?): Boolean =
    XrLanguageTeacherVocabCardSanitizer.isSameVocabularyWord(wordA, wordB)

  /**
   * Extracts tokens inside `<reply>...</reply>` in real time during token streaming and stops
   * immediately when `</reply>` or a stray `<card>` begins so raw `<card>` XML never leaks into the
   * UI or TTS.
   */
  fun extractStreamingReply(
    rawStream: String,
    mainLanguage: Language = Language.LANGUAGE_ENGLISH,
    learnLanguage: Language = Language.LANGUAGE_SPANISH,
  ): StreamingReplyState {
    val trimmed = rawStream.trimStart().removePrefix("```xml").removePrefix("```").trimStart()
    if (trimmed.isEmpty() || PARTIAL_LEADING_REPLY_TAG_REGEX.matches(trimmed)) {
      return StreamingReplyState(
        replyWithTargetTags = "",
        rawReplyContent = "",
        isReplyComplete = false,
      )
    }

    val replyOpenMatch = REPLY_OPEN_TAG_REGEX.find(trimmed)
    val contentStartIndex =
      when {
        replyOpenMatch != null -> replyOpenMatch.range.last + 1
        trimmed.startsWith("<card", ignoreCase = true) ->
          return StreamingReplyState(
            replyWithTargetTags = "",
            rawReplyContent = "",
            isReplyComplete = true,
          )
        else -> 0
      }

    val endMarkerMatch = REPLY_END_OR_CARD_START_REGEX.find(trimmed, contentStartIndex)
    val isReplyComplete = endMarkerMatch != null
    val rawSlice =
      if (endMarkerMatch != null) {
        trimmed.substring(contentStartIndex, endMarkerMatch.range.first)
      } else {
        trimmed.substring(contentStartIndex).replace(PARTIAL_TRAILING_SCHEMA_TAG_REGEX, "")
      }

    val cleanedRaw = rawSlice.replace(TRAILING_PARTIAL_TAG_REGEX, "").trimStart()
    val sanitized = stripHtmlPreservingTargetTags(cleanedRaw)

    return StreamingReplyState(
      replyWithTargetTags = sanitized,
      rawReplyContent = cleanedRaw,
      isReplyComplete = isReplyComplete,
      hasReplyOpenTag = replyOpenMatch != null,
    )
  }

  /**
   * Parses the complete Pass 1 response (`<reply>...</reply>`), wrapping untagged occurrences of
   * [fallbackTargetWord], the word of the active card on follow-up turns. Anything after the reply,
   * such as a stray `<card>` block, is dropped.
   */
  fun parseReply(
    rawResponse: String,
    mainLanguage: Language = Language.LANGUAGE_ENGLISH,
    learnLanguage: Language = Language.LANGUAGE_SPANISH,
    fallbackTargetWord: String? = null,
  ): TeacherReply {
    val trimmed =
      rawResponse.trim().removePrefix("```xml").removePrefix("```").removeSuffix("```").trim()
    if (trimmed.isEmpty()) {
      return TeacherReply()
    }

    val rawReply =
      extractTagContent("reply", trimmed).ifEmpty {
        val streamingExtracted =
          extractStreamingReply(trimmed, mainLanguage, learnLanguage).rawReplyContent
        if (
          streamingExtracted.isNotEmpty() &&
            !streamingExtracted.contains("<word>", ignoreCase = true)
        ) {
          streamingExtracted
        } else {
          ""
        }
      }

    val normalizedReply =
      normalizeReply(rawReply = rawReply, fallbackTargetWord = fallbackTargetWord)
    val segments = parseDialogueSegments(normalizedReply)
    val cleanReply = stripTargetAndHtmlTags(normalizedReply)

    return TeacherReply(
      replyWithTargetTags = normalizedReply,
      cleanReply = cleanReply,
      dialogueSegments = segments,
    )
  }

  /**
   * Strips leading/trailing stray punctuation and quotes (while preserving Spanish leading `¡`/`¿`
   * and sentence-ending punctuation unless [stripSentenceEndPunct] is true) and clears strings that
   * contain no letters or digits.
   *
   * A paired quote or bracket at the edge is kept when its partner is inside the field (e.g. the
   * `」` in `彼は「ありがとう」`), and removed when it is unpaired or wraps the whole field (e.g. `「つくえ」`
   * becomes `つくえ`).
   */
  fun cleanCardFieldValue(raw: String, stripSentenceEndPunct: Boolean = false): String =
    XrLanguageTeacherVocabCardSanitizer.cleanCardFieldValue(raw, stripSentenceEndPunct)

  /**
   * Sanitizes [card] fields by stripping stray HTML/XML tags and edge punctuation, backfilling an
   * omitted translation field from the other, and isolating the shorter `<target>` span from
   * [replyWithTargets] if `word` accidentally holds a full sentence.
   */
  fun sanitizeVocabCard(
    card: VocabCardData,
    replyWithTargets: String = "",
    mainLanguage: Language = Language.LANGUAGE_ENGLISH,
    learnLanguage: Language = Language.LANGUAGE_UNSPECIFIED,
  ): VocabCardData =
    XrLanguageTeacherVocabCardSanitizer.sanitizeVocabCard(
      card = card,
      replyWithTargets = replyWithTargets,
      mainLanguage = mainLanguage,
      learnLanguage = learnLanguage,
    )

  /** Wraps untagged occurrences of [fallbackTargetWord] and normalizes the target tags. */
  private fun normalizeReply(rawReply: String, fallbackTargetWord: String?): String {
    var reply = stripHtmlPreservingTargetTags(rawReply)

    val cleanFallbackWord = fallbackTargetWord?.trim().orEmpty()
    if (
      reply.isNotEmpty() &&
        cleanFallbackWord.isNotEmpty() &&
        !reply.contains("<target>$cleanFallbackWord</target>", ignoreCase = true)
    ) {
      val (wordWrapped, _) =
        wrapPhraseWithTargetInDialogue(reply, cleanFallbackWord, onlyIfUntagged = true)
      reply = wordWrapped
    }
    return normalizeAndSanitizeTargetTags(reply)
  }

  private data class PlainIndexMapping(
    val plainText: String,
    val plainToRawStart: List<Int>,
    val plainToRawEnd: List<Int>,
  )

  private fun buildPlainIndexMapping(dialogueWithTargets: String): PlainIndexMapping {
    val sb = StringBuilder(dialogueWithTargets.length)
    val starts = ArrayList<Int>(dialogueWithTargets.length)
    val ends = ArrayList<Int>(dialogueWithTargets.length)
    var i = 0
    while (i < dialogueWithTargets.length) {
      if (dialogueWithTargets.startsWith("<target>", startIndex = i, ignoreCase = true)) {
        i += "<target>".length
        continue
      }
      if (dialogueWithTargets.startsWith("</target>", startIndex = i, ignoreCase = true)) {
        i += "</target>".length
        continue
      }
      sb.append(dialogueWithTargets[i])
      starts.add(i)
      ends.add(i + 1)
      i++
    }
    return PlainIndexMapping(
      plainText = sb.toString(),
      plainToRawStart = starts,
      plainToRawEnd = ends,
    )
  }

  /**
   * Locates [phrase] in the tag-stripped text of [dialogue] (even if `<target>` and `</target>`
   * tags sit partially inside [phrase]) and wraps the matched span in a single
   * `<target>...</target>` tag.
   */
  fun wrapPhraseWithTargetInDialogue(
    dialogue: String,
    phrase: String,
    onlyIfUntagged: Boolean = false,
  ): Pair<String, Boolean> {
    val cleanPhrase = stripTargetAndHtmlTags(phrase).trim()
    if (dialogue.isEmpty() || cleanPhrase.isEmpty()) {
      return Pair(dialogue, false)
    }

    val mapping = buildPlainIndexMapping(dialogue)
    val plain = mapping.plainText
    val phraseCore = cleanPhrase.trimEnd('.', '!', '?', '。', '！', '？')

    val candidateRanges: List<IntRange> =
      findAllPhraseRangesInPlain(plain, cleanPhrase).ifEmpty {
        if (phraseCore.length >= 3 && phraseCore != cleanPhrase) {
          findAllPhraseRangesInPlain(plain, phraseCore)
        } else {
          emptyList()
        }
      }
    if (candidateRanges.isEmpty()) {
      return Pair(dialogue, false)
    }

    val openSpans = TARGET_SPAN_REGEX.findAll(dialogue).toList()

    for (matchRange in candidateRanges) {
      var rawStart = mapping.plainToRawStart[matchRange.first]
      var rawEnd = mapping.plainToRawEnd[matchRange.last]

      // Include immediately adjacent <target> before rawStart or </target> after rawEnd.
      if (
        rawStart >= "<target>".length &&
          dialogue.startsWith(
            "<target>",
            startIndex = rawStart - "<target>".length,
            ignoreCase = true,
          )
      ) {
        rawStart -= "<target>".length
      }
      if (dialogue.startsWith("</target>", startIndex = rawEnd, ignoreCase = true)) {
        rawEnd += "</target>".length
      }

      val enclosingSpan = openSpans.firstOrNull {
        it.range.first <= rawStart && it.range.last + 1 >= rawEnd
      }
      if (enclosingSpan != null) {
        if (onlyIfUntagged) continue
        return Pair(dialogue, true)
      }

      if (onlyIfUntagged) {
        if (openSpans.any { it.range.first < rawEnd && it.range.last + 1 > rawStart }) continue
      } else {
        // Expand rawStart/rawEnd if a partial <target> span straddles the boundary.
        for (span in openSpans) {
          val spanStart = span.range.first
          val spanEnd = span.range.last + 1
          if (spanStart < rawStart && spanEnd > rawStart) rawStart = spanStart
          if (spanStart < rawEnd && spanEnd > rawEnd) rawEnd = spanEnd
        }
      }

      if (
        cleanPhrase != phraseCore &&
          rawEnd < dialogue.length &&
          isSentenceEndingPunctuation(dialogue[rawEnd])
      ) {
        rawEnd += 1
      }

      val matchedDialoguePlain = stripTargetAndHtmlTags(dialogue.substring(rawStart, rawEnd))
      val replacementPlain =
        when {
          matchedDialoguePlain.isNotEmpty() &&
            isSentenceEndingPunctuation(matchedDialoguePlain.last()) -> matchedDialoguePlain
          cleanPhrase.isNotEmpty() && isSentenceEndingPunctuation(cleanPhrase.last()) -> cleanPhrase
          else -> matchedDialoguePlain
        }

      val updated =
        dialogue.substring(0, rawStart) +
          "<target>$replacementPlain</target>" +
          dialogue.substring(rawEnd)
      return Pair(updated, true)
    }

    return Pair(dialogue, false)
  }

  private fun findAllPhraseRangesInPlain(plain: String, target: String): List<IntRange> {
    if (plain.isEmpty() || target.isEmpty()) return emptyList()
    val results = mutableListOf<IntRange>()
    val checkStartBoundary = target.first().isLetterOrDigit() && !isCjkCharacter(target.first())
    val checkEndBoundary = target.last().isLetterOrDigit() && !isCjkCharacter(target.last())

    var searchStart = 0
    while (searchStart <= plain.length - target.length) {
      val idx = plain.indexOf(target, startIndex = searchStart, ignoreCase = true)
      if (idx == -1) break
      val end = idx + target.length
      val validStart =
        !checkStartBoundary ||
          idx == 0 ||
          !plain[idx - 1].isLetterOrDigit() ||
          isCjkCharacter(plain[idx - 1])
      val validEnd =
        !checkEndBoundary ||
          end == plain.length ||
          !plain[end].isLetterOrDigit() ||
          isCjkCharacter(plain[end])
      if (validStart && validEnd) {
        results.add(idx until end)
      }
      searchStart = idx + 1
    }
    return results
  }

  private fun isCjkCharacter(ch: Char): Boolean {
    val block = Character.UnicodeBlock.of(ch)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
      block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
      block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
      block == Character.UnicodeBlock.HIRAGANA ||
      block == Character.UnicodeBlock.KATAKANA ||
      block == Character.UnicodeBlock.HANGUL_SYLLABLES
  }

  /**
   * Formats structured [DialogueSegment] items into an [AnnotatedString] with clickable bold
   * styling for target language words.
   */
  fun formatAnnotatedStringFromSegments(
    segments: List<DialogueSegment>,
    targetColor: Color = Color.Unspecified,
    pillBackgroundColor: Color = Color.Transparent,
  ): AnnotatedString {
    if (segments.isEmpty()) return AnnotatedString("")
    return buildAnnotatedString {
      for (segment in segments) {
        if (segment.isTargetLanguage) {
          pushStringAnnotation(tag = TTS_TARGET_ANNOTATION_TAG, annotation = segment.text.trim())
          withStyle(
            SpanStyle(
              color = targetColor,
              fontWeight = FontWeight.Bold,
              background = pillBackgroundColor,
            )
          ) {
            append(segment.text)
          }
          pop()
        } else {
          append(segment.text)
        }
      }
    }
  }

  /**
   * Splits teacher dialogue containing `<target>...</target>` or `<say>...</say>` tags into
   * sequential visual [DialogueSegment] items for UI card and HUD rendering, preserving exact
   * visual token boundaries and emojis.
   */
  fun parseDialogueSegments(text: String): List<DialogueSegment> {
    val sanitized = stripHtmlPreservingTargetTags(text)
    if (sanitized.isEmpty()) return emptyList()

    val segments = mutableListOf<DialogueSegment>()
    var currentIndex = 0
    val matches = TARGET_SPAN_REGEX.findAll(sanitized).toList()

    if (matches.isEmpty()) {
      val clean = stripTargetAndHtmlTags(sanitized)
      if (clean.isNotEmpty()) {
        segments.add(DialogueSegment(text = clean, isTargetLanguage = false))
      }
      return segments
    }

    for (match in matches) {
      val range = match.range
      if (range.first > currentIndex) {
        val hostChunk =
          stripTargetAndHtmlTagsPreservingSpaces(sanitized.substring(currentIndex, range.first))
        if (hostChunk.isNotEmpty()) {
          segments.add(DialogueSegment(text = hostChunk, isTargetLanguage = false))
        }
      }

      val targetChunk = stripTargetAndHtmlTags(match.groupValues[1])
      if (targetChunk.isNotEmpty()) {
        segments.add(DialogueSegment(text = targetChunk, isTargetLanguage = true))
      }

      currentIndex = range.last + 1
    }

    if (currentIndex < sanitized.length) {
      val trailingChunk = stripTargetAndHtmlTagsPreservingSpaces(sanitized.substring(currentIndex))
      if (trailingChunk.isNotEmpty()) {
        segments.add(DialogueSegment(text = trailingChunk, isTargetLanguage = false))
      }
    }

    return segments
  }

  /**
   * Maps structured [DialogueSegment] items to [MultilingualTtsSegment] for speech synthesis,
   * applying emoji stripping and leading punctuation reattachment.
   */
  fun dialogueSegmentsToMultilingualTtsSegments(
    segments: List<DialogueSegment>,
    hostLocaleCode: String,
    targetLocaleCode: String,
  ): List<MultilingualTtsSegment> {
    val raw =
      segments
        .filter { it.text.isNotBlank() }
        .map { segment ->
          MultilingualTtsSegment(
            text = segment.text.trim(),
            languageCode = if (segment.isTargetLanguage) targetLocaleCode else hostLocaleCode,
            isTargetLanguage = segment.isTargetLanguage,
          )
        }
    return normalizeMultilingualTtsSegments(raw)
  }

  /** Concatenates all dialogue segments into plain text for chat history. */
  fun plainTextFromSegments(segments: List<DialogueSegment>): String =
    segments.joinToString("") { it.text }.trim()

  /**
   * Extracts the text content inside `<tag>...</tag>`, handling unclosed trailing tags and nested
   * `<target>` / `<say>` tags without truncating at the first `<`.
   */
  fun extractTagContent(tag: String, text: String): String {
    val closedPattern = Regex("(?is)<$tag(?:\\s+[^>]*)?>(.*?)</$tag>")
    closedPattern.find(text)?.let {
      return it.groupValues[1].trim()
    }

    val openPattern =
      if (tag.equals("reply", ignoreCase = true)) {
        Regex("(?is)<$tag(?:\\s+[^>]*)?>(.*?)(?:</$tag\\s*>|<card(?:\\s+[^>]*)?>|<card\\s*$|$)")
      } else {
        Regex("(?is)<$tag(?:\\s+[^>]*)?>(.*?)(?:</$tag\\s*>|<(?!/?(?:target|say)\\b)[a-zA-Z_/]|$)")
      }
    return openPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()
  }

  /**
   * Strips all HTML/XML tags except `<target>` and `</target>`, normalizing `<target ...>` or `<say
   * ...>` to canonical `<target>`.
   */
  fun stripHtmlPreservingTargetTags(text: String): String {
    if (text.isEmpty()) return ""
    return normalizeAndSanitizeTargetTags(text)
      .replace(TARGET_TAG_OPEN_REGEX, SHIELD_OPEN)
      .replace(TARGET_TAG_CLOSE_REGEX, SHIELD_CLOSE)
      .replace(ANY_XML_TAG_REGEX, "")
      .replace(SHIELD_OPEN, "<target>")
      .replace(SHIELD_CLOSE, "</target>")
      .trim()
  }

  /**
   * Normalizes `<say>` and `<target>` tags to canonical `<target>`, strips trailing incomplete tags
   * during streaming to prevent UI flicker, auto-closes unclosed target tags, and ensures target
   * spans do not merge across sentence boundaries.
   */
  fun normalizeAndSanitizeTargetTags(text: String): String {
    if (text.isEmpty()) return ""
    var normalized =
      text
        .replace(TRAILING_INCOMPLETE_TAG_REGEX, "")
        .replace(SAY_OPEN_TAG_REGEX, "<target>")
        .replace(SAY_CLOSE_TAG_REGEX, "</target>")
        .replace(UNCLOSED_TARGET_BEFORE_OPEN_REGEX) { match ->
          "<target>${match.groupValues[1]}</target><target>"
        }

    val lastOpen = normalized.lastIndexOf("<target>")
    if (lastOpen != -1 && normalized.lastIndexOf("</target>") < lastOpen) {
      normalized += "</target>"
    }
    return normalized
  }

  private fun stripTargetAndHtmlTagsPreservingSpaces(text: String): String {
    if (text.isEmpty()) return ""
    return text
      .replace(TARGET_TAG_OPEN_REGEX, "")
      .replace(TARGET_TAG_CLOSE_REGEX, "")
      .replace(ANY_XML_TAG_REGEX, "")
      .replace(SHIELD_OPEN, "")
      .replace(SHIELD_CLOSE, "")
  }

  private fun startsWithAttachedPunctuation(text: String): Boolean =
    text.firstOrNull()?.let { it in "!.?,:;)]}”’'\"。！？，、—" } ?: false

  private fun isOpeningPunctuationOrWhitespace(lastChar: Char?): Boolean =
    lastChar == null || lastChar.isWhitespace() || lastChar in "([{\"“‘¿¡—"

  private fun AnnotatedString.Builder.appendSpaceIfNeeded(lastChar: Char?, nextChunk: String) {
    if (
      length > 0 &&
        !isOpeningPunctuationOrWhitespace(lastChar) &&
        !nextChunk.first().isWhitespace() &&
        !startsWithAttachedPunctuation(nextChunk)
    ) {
      append(" ")
    }
  }

  /** Strips `<target>` tags and all other HTML/XML markup, returning plain text. */
  fun stripTargetAndHtmlTags(text: String): String =
    stripTargetAndHtmlTagsPreservingSpaces(text).trim()

  /** Checks if the text has an open `<target>` or `<say>` tag that has not been closed yet. */
  fun hasUnclosedTargetTag(text: String): Boolean =
    text.isNotEmpty() &&
      TARGET_TAG_OPEN_REGEX.findAll(text).count() > TARGET_TAG_CLOSE_REGEX.findAll(text).count()

  private fun endsWithAbbreviation(candidate: String): Boolean =
    ABBREVIATION_SUFFIX_REGEX.containsMatchIn(stripTargetAndHtmlTags(candidate).trimEnd())

  /**
   * Splits a streaming text buffer into completed sentences without breaking across unclosed
   * `<target>` tags or common title/honorific abbreviations (`Prof.`, `Dr.`, `Mr.`, `Mrs.`, `Ms.`),
   * and supporting CJK sentence-ending punctuation (`。`, `！`, `？`) without requiring trailing ASCII
   * whitespace. Returns a pair of (completedSentences, remainingBuffer).
   */
  fun extractCompleteSentences(buffer: String): Pair<List<String>, String> {
    if (buffer.isEmpty()) return Pair(emptyList(), "")
    val completed = mutableListOf<String>()
    var sentenceStart = 0
    for (match in SENTENCE_BOUNDARY_REGEX.findAll(buffer)) {
      val boundaryEnd = match.range.last + 1
      val candidate = buffer.substring(sentenceStart, boundaryEnd)
      if (!hasUnclosedTargetTag(candidate) && !endsWithAbbreviation(candidate)) {
        val trimmed = candidate.trim()
        if (trimmed.isNotEmpty()) {
          completed.add(trimmed)
        }
        sentenceStart = boundaryEnd
      }
    }
    val remaining = buffer.substring(sentenceStart)
    return Pair(completed, remaining)
  }

  /** Extracts all target language phrases enclosed in `<target>...</target>`. */
  fun extractAllTargetPhrases(text: String): List<String> =
    if (text.isEmpty()) {
      emptyList()
    } else {
      TARGET_SPAN_REGEX.findAll(text)
        .map { stripTargetAndHtmlTags(it.groupValues[1]) }
        .filter { it.isNotEmpty() }
        .toList()
    }

  /**
   * Splits teacher dialogue containing `<target>...</target>` into sequential bilingual TTS
   * segments so the speech synthesizer can switch between the host persona voice and target
   * language pronunciation, with emojis stripped and leading punctuation reattached.
   */
  fun parseMultilingualSegments(
    text: String,
    hostLocaleCode: String,
    targetLocaleCode: String,
  ): List<MultilingualTtsSegment> =
    dialogueSegmentsToMultilingualTtsSegments(
      segments = parseDialogueSegments(text),
      hostLocaleCode = hostLocaleCode,
      targetLocaleCode = targetLocaleCode,
    )

  /**
   * Normalizes a sequence of bilingual TTS segments:
   * 1. Strips emojis from spoken text buffers while preserving them in HUD display text.
   * 2. Re-attaches isolated leading punctuation (e.g. `!`, `?`, `.`, `,`) produced by tag splitting
   *    to the preceding segment (e.g. `"<target>manzana</target>! Say"` -> `["manzana!", "Say"]`),
   *    or trims it from the initial segment, preventing TTS engines from verbalizing punctuation
   *    tokens aloud (e.g. "exclamation mark").
   * 3. Drops any segments that become empty after emoji and punctuation normalization.
   */
  fun normalizeMultilingualTtsSegments(
    rawSegments: List<MultilingualTtsSegment>
  ): List<MultilingualTtsSegment> {
    if (rawSegments.isEmpty()) return emptyList()

    val normalized = mutableListOf<MultilingualTtsSegment>()
    for (seg in rawSegments) {
      var textWithoutEmojis = TtsTextNormalizer.cleanMarkdownForSpeech(seg.text, stripEmojis = true)
      if (textWithoutEmojis.isEmpty()) continue

      val match = LEADING_PUNCTUATION_REGEX.find(textWithoutEmojis)
      if (match != null) {
        val punctuation = match.groupValues[1]
        val remainder = textWithoutEmojis.substring(match.range.last + 1).trim()

        if (normalized.isNotEmpty()) {
          val prev = normalized.removeAt(normalized.size - 1)
          val prevEndsWithPunct =
            TRAILING_SENTENCE_PUNCTUATION_REGEX.containsMatchIn(prev.text.trimEnd())
          val updatedPrevText =
            if (prevEndsWithPunct) prev.text.trim() else "${prev.text.trimEnd()}$punctuation"
          normalized.add(prev.copy(text = updatedPrevText))
        }

        textWithoutEmojis = remainder
      }

      if (textWithoutEmojis.isNotEmpty()) {
        normalized.add(seg.copy(text = textWithoutEmojis))
      }
    }

    return normalized
  }

  /**
   * Formats teacher dialogue containing `<target>...</target>` into an interactive
   * [AnnotatedString] where target phrases are styled as clean inline bold spans (without
   * artificial leading/trailing spaces) and annotated with [TTS_TARGET_ANNOTATION_TAG] for
   * tap-to-pronounce playback.
   */
  fun formatAnnotatedString(
    text: String,
    targetColor: Color = Color.Unspecified,
    pillBackgroundColor: Color = Color.Transparent,
  ): AnnotatedString {
    val sanitized = stripHtmlPreservingTargetTags(text)
    if (sanitized.isEmpty()) return AnnotatedString("")

    val matches = TARGET_SPAN_REGEX.findAll(sanitized).toList()
    if (matches.isEmpty()) {
      return AnnotatedString(stripTargetAndHtmlTags(sanitized))
    }

    return buildAnnotatedString {
      var currentIndex = 0
      var lastChar: Char? = null
      for (match in matches) {
        val range = match.range
        if (range.first > currentIndex) {
          val prefix =
            stripTargetAndHtmlTagsPreservingSpaces(sanitized.substring(currentIndex, range.first))
          if (prefix.isNotEmpty()) {
            appendSpaceIfNeeded(lastChar, prefix)
            append(prefix)
            lastChar = prefix.last()
          }
        }

        val targetPhrase = stripTargetAndHtmlTags(match.groupValues[1])
        if (targetPhrase.isNotEmpty()) {
          appendSpaceIfNeeded(lastChar, targetPhrase)
          pushStringAnnotation(tag = TTS_TARGET_ANNOTATION_TAG, annotation = targetPhrase)
          withStyle(
            style =
              SpanStyle(
                color = targetColor,
                background = pillBackgroundColor,
                fontWeight = FontWeight.Bold,
              )
          ) {
            append(targetPhrase)
          }
          pop()
          lastChar = targetPhrase.last()
        }

        currentIndex = range.last + 1
      }

      if (currentIndex < sanitized.length) {
        val suffix = stripTargetAndHtmlTagsPreservingSpaces(sanitized.substring(currentIndex))
        if (suffix.isNotEmpty()) {
          appendSpaceIfNeeded(lastChar, suffix)
          append(suffix)
        }
      }
    }
  }

  /**
   * Sanitizes a vocabulary word into a safe single-component filename so slashes or special
   * characters in generated words (e.g. `"el / la estudiante"`) never fail file I/O.
   */
  fun sanitizeWordForFilename(word: String): String =
    XrLanguageTeacherVocabCardSanitizer.sanitizeWordForFilename(word)

  /**
   * Sanitizes [word] (case-insensitively) and prefixes [learnLanguage] so homographs across
   * different target languages never overwrite or delete each other's saved thumbnail files.
   */
  fun sanitizeWordForFilename(word: String, learnLanguage: Language): String =
    XrLanguageTeacherVocabCardSanitizer.sanitizeWordForFilename(word, learnLanguage)
}
