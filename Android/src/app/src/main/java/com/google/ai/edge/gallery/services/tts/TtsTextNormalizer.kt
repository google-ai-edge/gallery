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

package com.google.ai.edge.gallery.services.tts

import java.util.Locale

/**
 * A chunk of segmented text with character ranges and clean spoken representations.
 *
 * @property id Zero-based position of this chunk in the segmentation, in reading order.
 * @property text The chunk exactly as it appears in the source, with surrounding whitespace
 *   removed.
 * @property cleanSpokenText [text] with markdown stripped and emoji verbalized, ready to hand to a
 *   speech engine. Falls back to [text] when normalization would leave nothing to say.
 * @property startIndex Index of the first character of [text] **in the original string passed to
 *   [TtsTextNormalizer.segmentTextIntoChunks]**, not in any trimmed copy of it.
 * @property endIndex Index one past the last character of [text] in that same original string, so
 *   that `original.substring(startIndex, endIndex) == text`.
 */
data class TextChunk(
  val id: Int,
  val text: String,
  val cleanSpokenText: String = text,
  val startIndex: Int,
  val endIndex: Int,
) {
  val start: Int
    get() = startIndex

  val end: Int
    get() = endIndex
}

/**
 * Utility for normalizing markdown, verbalizing emojis, and segmenting text for speech synthesis.
 */
object TtsTextNormalizer {

  private val MARKDOWN_BOLD_ITALIC_REGEX = Regex("""(\*\*\*|\*\*|\*|___|__|_)""")
  private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)\]\([^\)]+\)""")
  private val MARKDOWN_HEADER_REGEX = Regex("""(?m)^#{1,6}\s+""")
  private val MARKDOWN_LIST_REGEX = Regex("""(?m)^[\s]*([\*\-\+]|\d+\.)\s+""")
  private val MARKDOWN_CODE_BLOCK_REGEX = Regex("```[a-zA-Z]*")
  private val MARKDOWN_BLOCKQUOTE_REGEX = Regex("""(?m)^>\s*""")
  private val MARKDOWN_HORIZONTAL_RULE_REGEX = Regex("""(?m)^[-*_]{3,}\s*$""")
  private val WHITESPACE_REGEX = Regex("""\s+""")

  // Portable abbreviation-aware sentence boundary regex.
  // Negative lookbehind protects known abbreviations (e.g., i.e., Dr., Mr., vs., etc.) before
  // periods only, while always splitting on exclamation/question marks and newlines.
  private const val SENTENCE_BOUNDARY_PATTERN =
    "(?i)(?:(?<!\\b(?:e\\.g|i\\.e|dr|mr|mrs|ms|prof|vs|etc|fig|al|approx|inc|corp))\\.+|[!?]+)[\"')\\]\u2019\u201d]*(?=\\s|$)|(?:\\n+)"
  private val SENTENCE_REGEX = Regex(SENTENCE_BOUNDARY_PATTERN)

  /** Minimum effective character length of the first clause before splitting at punctuation. */
  internal const val MIN_FIRST_CLAUSE_CHARS = 20

  /** Minimum effective character length of subsequent clauses before splitting at punctuation. */
  internal const val MIN_SUBSEQUENT_CLAUSE_CHARS = 32

  /** Minimum effective character length that must remain after a clause split. */
  internal const val MIN_REMAINING_CLAUSE_CHARS = 20

  /**
   * Converts Unicode emoji characters into spoken English word descriptions (e.g. 😊 -> "smiling
   * face with smiling eyes emoji").
   */
  fun convertEmojisToSpokenWords(text: String): String = buildString {
    var i = 0
    val len = text.length
    while (i < len) {
      val codePoint = text.codePointAt(i)
      val charCount = Character.charCount(codePoint)
      if (isRegionalIndicator(codePoint) && i + charCount < len) {
        val nextCodePoint = text.codePointAt(i + charCount)
        if (isRegionalIndicator(nextCodePoint)) {
          val firstLetter = ('A'.code + (codePoint - 0x1F1E6)).toChar()
          val secondLetter = ('A'.code + (nextCodePoint - 0x1F1E6)).toChar()
          val countryCode = "$firstLetter$secondLetter"
          val countryName =
            Locale.Builder().setRegion(countryCode).build().getDisplayCountry(Locale.US).ifEmpty {
              countryCode
            }
          append(" ").append(countryName.lowercase(Locale.US)).append(" flag emoji ")
          i += charCount + Character.charCount(nextCodePoint)
          continue
        }
      }
      if (isEmojiModifierOrJoiner(codePoint)) {
        i += charCount
        continue
      }
      if (isEmoji(codePoint)) {
        val name = Character.getName(codePoint)
        if (name != null) {
          append(" ").append(name.lowercase(Locale.US).replace('_', ' ')).append(" emoji ")
        } else {
          appendCodePoint(codePoint)
        }
      } else {
        appendCodePoint(codePoint)
      }
      i += charCount
    }
  }

  /**
   * Strips all Unicode emoji characters from the text so speech engines do not speak verbalized
   * descriptions or awkward sound tokens.
   */
  fun stripEmojis(text: String): String {
    if (text.isEmpty()) return ""
    return buildString {
        var i = 0
        val len = text.length
        while (i < len) {
          val codePoint = text.codePointAt(i)
          val charCount = Character.charCount(codePoint)
          if (!isEmoji(codePoint) && !isEmojiModifierOrJoiner(codePoint)) {
            appendCodePoint(codePoint)
          }
          i += charCount
        }
      }
      .replace(Regex("[ \\t]+"), " ")
      .trim()
  }

  private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

  private fun isEmojiModifierOrJoiner(codePoint: Int): Boolean {
    return codePoint == 0x200D || // Zero-width joiner
      codePoint == 0x20E3 || // Combining enclosing keycap
      (codePoint in 0xFE00..0xFE0F) || // Variation selectors
      (codePoint in 0x1F3FB..0x1F3FF) || // Fitzpatrick skin-tone modifiers
      (codePoint in 0xE0020..0xE007F) // Tag characters for subdivision flags
  }

  private fun isEmoji(codePoint: Int): Boolean {
    return (codePoint in 0x1F600..0x1F64F) || // Emoticons
      (codePoint in 0x1F300..0x1F5FF) || // Misc Symbols and Pictographs
      (codePoint in 0x1F680..0x1F6FF) || // Transport and Map
      (codePoint in 0x1F1E6..0x1F1FF) || // Regional indicator symbols
      (codePoint in 0x2600..0x26FF) || // Misc symbols
      (codePoint in 0x2700..0x27BF) || // Dingbats
      (codePoint in 0x1F900..0x1F9FF) || // Supplemental Symbols and Pictographs
      (codePoint in 0x1FA00..0x1FAFF) || // Symbols and Pictographs Extended-A
      (codePoint in 0x231A..0x231B) || // Watch, Hourglass
      (codePoint in 0x23E9..0x23FA) || // Media control symbols
      (codePoint in 0x25AA..0x25FE) || // Geometric shapes used as emoji
      (codePoint in 0x2934..0x2935) || // Curved arrows
      (codePoint in 0x2B05..0x2B55) || // Arrows, stars, circles
      codePoint == 0x3030 || // Wavy dash
      codePoint == 0x303D || // Part alternation mark
      codePoint == 0x3297 || // Circled Ideograph Congratulation
      codePoint == 0x3299 // Circled Ideograph Secret
  }

  /**
   * Removes unmatched opening or closing brackets (`(`, `)`, `[`, `]`) at any index in [text] while
   * keeping balanced pairs intact.
   *
   * When code-switching tags or streaming sentence splits cut across a parenthetical phrase, an
   * orphaned bracket causes TTS engines to verbalize `"open parenthesis"` or `"close parenthesis"`.
   */
  fun stripUnmatchedBrackets(text: String): String {
    if (text.isEmpty()) return text
    val unmatchedIndices = HashSet<Int>()
    val openParenStack = ArrayDeque<Int>()
    val openSquareStack = ArrayDeque<Int>()
    for (i in text.indices) {
      when (text[i]) {
        '(' -> openParenStack.addLast(i)
        ')' -> {
          if (openParenStack.isEmpty()) {
            unmatchedIndices.add(i)
          } else {
            openParenStack.removeLast()
          }
        }
        '[' -> openSquareStack.addLast(i)
        ']' -> {
          if (openSquareStack.isEmpty()) {
            unmatchedIndices.add(i)
          } else {
            openSquareStack.removeLast()
          }
        }
      }
    }
    unmatchedIndices.addAll(openParenStack)
    unmatchedIndices.addAll(openSquareStack)
    if (unmatchedIndices.isEmpty()) return text
    return buildString(text.length - unmatchedIndices.size) {
      for (i in text.indices) {
        if (i !in unmatchedIndices) {
          append(text[i])
        }
      }
    }
  }

  /** Cleans markdown formatting symbols, links, and code delimiters for speech synthesis. */
  fun cleanMarkdownForSpeech(markdown: String, stripEmojis: Boolean = false): String {
    var cleaned = if (stripEmojis) stripEmojis(markdown) else convertEmojisToSpokenWords(markdown)
    cleaned = cleaned.replace(MARKDOWN_BOLD_ITALIC_REGEX, "")
    cleaned = cleaned.replace(MARKDOWN_LINK_REGEX, "$1")
    cleaned = cleaned.replace(MARKDOWN_HEADER_REGEX, "")
    cleaned = cleaned.replace(MARKDOWN_LIST_REGEX, "")
    cleaned = cleaned.replace(MARKDOWN_CODE_BLOCK_REGEX, "")
    cleaned = cleaned.replace("`", "")
    cleaned = cleaned.replace(MARKDOWN_BLOCKQUOTE_REGEX, "")
    cleaned = cleaned.replace(MARKDOWN_HORIZONTAL_RULE_REGEX, "")
    cleaned = stripUnmatchedBrackets(cleaned)
    cleaned = cleaned.replace(WHITESPACE_REGEX, " ")
    return cleaned.trim()
  }

  /**
   * Splits a document or streaming response into sentence chunks with clean spoken text.
   *
   * @param text the source string. [TextChunk.startIndex] and [TextChunk.endIndex] are offsets into
   *   this string, so `text.substring(chunk.startIndex, chunk.endIndex) == chunk.text` holds for
   *   every returned chunk.
   * @param includeIncompleteTail whether to emit a final chunk for trailing text that has no
   *   sentence-ending punctuation yet. Callers that segment a streaming response as it arrives
   *   usually want `false`, so that a half-received sentence is not spoken early.
   */
  fun segmentTextIntoChunks(text: String, includeIncompleteTail: Boolean = true): List<TextChunk> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()

    // Offsets below are computed against `trimmed`; this shifts them back onto `text`.
    val trimmedOffset = text.length - text.trimStart().length

    val chunks = mutableListOf<TextChunk>()
    var chunkId = 0
    var lastIndex = 0

    val matches = SENTENCE_REGEX.findAll(trimmed).toList()
    for (match in matches) {
      val endIndex = match.range.last + 1
      if (endIndex > lastIndex) {
        val rawSubstring = trimmed.substring(lastIndex, endIndex)
        val substring = rawSubstring.trim()
        if (substring.isNotEmpty()) {
          val clean = cleanMarkdownForSpeech(substring, stripEmojis = true)
          if (clean.isNotBlank()) {
            val chunkStart =
              trimmedOffset + lastIndex + (rawSubstring.length - rawSubstring.trimStart().length)
            chunks.add(
              TextChunk(
                id = chunkId++,
                text = substring,
                cleanSpokenText = clean,
                startIndex = chunkStart,
                endIndex = chunkStart + substring.length,
              )
            )
          }
        }
        lastIndex = endIndex
      }
    }

    if (includeIncompleteTail && lastIndex < trimmed.length) {
      val rawTail = trimmed.substring(lastIndex)
      val tailSubstring = rawTail.trim()
      if (tailSubstring.isNotEmpty()) {
        val clean = cleanMarkdownForSpeech(tailSubstring, stripEmojis = true)
        if (clean.isNotBlank()) {
          val chunkStart = trimmedOffset + lastIndex + (rawTail.length - rawTail.trimStart().length)
          chunks.add(
            TextChunk(
              id = chunkId++,
              text = tailSubstring,
              cleanSpokenText = clean,
              startIndex = chunkStart,
              endIndex = chunkStart + tailSubstring.length,
            )
          )
        }
      }
    }

    return chunks
  }

  /**
   * Splits a sentence into clause sub-chunks at clause punctuation (`,`, `;`, `:`, `—`, `–`, `，`,
   * `；`, `：`, `、`) when both sides of the boundary are long enough to reduce first-audio latency
   * without producing clipped one-word fragments.
   */
  fun splitIntoClauses(text: String): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (
      effectiveClauseLength(trimmed, 0, trimmed.length) <
        MIN_FIRST_CLAUSE_CHARS + MIN_REMAINING_CLAUSE_CHARS
    ) {
      return listOf(trimmed)
    }
    val clauses = mutableListOf<String>()
    var clauseStart = 0
    var i = 0
    while (i < trimmed.length) {
      val ch = trimmed[i]
      val isCjkDelimiter = ch == '，' || ch == '；' || ch == '：' || ch == '、'
      val isAsciiDelimiter =
        (ch == ',' || ch == ';' || ch == ':' || ch == '—' || ch == '–') &&
          i + 1 < trimmed.length &&
          (trimmed[i + 1].isWhitespace() || (ch == '—' && trimmed[i + 1].isLetter()))
      if (isCjkDelimiter || isAsciiDelimiter) {
        val splitEnd = i + 1
        val minCurrentChars =
          if (clauses.isEmpty()) MIN_FIRST_CLAUSE_CHARS else MIN_SUBSEQUENT_CLAUSE_CHARS
        val currentLen = effectiveClauseLength(trimmed, clauseStart, splitEnd)
        var nextStart = splitEnd
        while (nextStart < trimmed.length && trimmed[nextStart].isWhitespace()) {
          nextStart++
        }
        val remainingLen = effectiveClauseLength(trimmed, nextStart, trimmed.length)
        if (currentLen >= minCurrentChars && remainingLen >= MIN_REMAINING_CLAUSE_CHARS) {
          clauses.add(trimmed.substring(clauseStart, splitEnd).trim())
          clauseStart = nextStart
          i = nextStart
          continue
        }
      }
      i++
    }
    if (clauseStart < trimmed.length) {
      val tail = trimmed.substring(clauseStart).trim()
      if (tail.isNotEmpty()) {
        clauses.add(tail)
      }
    }
    return if (clauses.isEmpty()) listOf(trimmed) else clauses
  }

  private fun effectiveClauseLength(text: String, startIndex: Int, endIndexExclusive: Int): Int {
    var length = 0
    for (idx in startIndex until endIndexExclusive) {
      val code = text[idx].code
      length += if (isCjkCodePoint(code)) 3 else 1
    }
    return length
  }

  private fun isCjkCodePoint(code: Int): Boolean =
    code in 0x3040..0x30FF || code in 0x3400..0x9FFF || code in 0xF900..0xFAFF
}
