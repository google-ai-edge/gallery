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

package com.google.ai.edge.gallery.ui.common.tts

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.google.ai.edge.gallery.services.tts.TextChunk
import com.google.ai.edge.gallery.services.tts.TtsTextNormalizer

private val PARAGRAPH_BREAK_REGEX = Regex("\n[ \t]*\n")
private val HEADING_PREFIX_REGEX = Regex("^\\s*#{1,6}\\s+")
private val BULLET_PREFIX_REGEX = Regex("^(\\s*)[-*+]\\s+")
private val BLOCKQUOTE_PREFIX_REGEX = Regex("^\\s*>\\s?")
private val HORIZONTAL_RULE_REGEX = Regex("^[-*_]{3,}\\s*$")
private val LEADING_LIST_PREFIX_REGEX = Regex("^\\s*(?:\u2022|\\d+\\.)\\s+")
private val UNPAIRED_BOLD_DELIMITER_REGEX =
  Regex("(?<!\\s|\\d)\\*\\*(?=\\s|$|[.,!?;:)])|(?<=^|\\s|\\()\\*\\*(?!\\s|\\d)")

/**
 * Inline markdown, one alternative per group: 1-2 bold, 3 code, 4-5 link label + URL, 6-7 italic.
 *
 * Italic markers must not touch a word character on the outside, so `snake_case_names` and `2*3*4`
 * stay literal.
 */
private val INLINE_MARKDOWN_REGEX =
  Regex(
    "(?<!\\w)\\*\\*(?!\\s)(.+?)(?<!\\s)\\*\\*(?!\\w)" +
      "|__(.+?)__" +
      "|`([^`]+)`" +
      "|\\[([^\\]]+)]\\(([^)\\s]*)\\)" +
      "|(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])" +
      "|(?<![\\w_])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\w_])"
  )

/** A paragraph rendered for display, with where each sentence ended up in [text]. */
internal data class RenderedParagraph(
  val text: AnnotatedString,
  val sentenceRanges: Map<Int, IntRange>,
)

/** A half-open character range `[start, end)` of a message. */
internal data class MessageRange(val start: Int, val end: Int) {
  fun overlaps(otherStart: Int, otherEnd: Int): Boolean = otherStart < end && otherEnd > start
}

/**
 * The spoken view of a message.
 *
 * @property sentences the sentences to speak, with ids renumbered to be contiguous.
 * @property incompleteTail the trailing text after the last sentence that is not spoken yet, or
 *   `null` if there is none or it was spoken as a sentence.
 */
internal data class SpeakableText(val sentences: List<TextChunk>, val incompleteTail: MessageRange?)

/** A block of a message as laid out while it is being read aloud. */
internal sealed interface TtsMessageBlock {
  val start: Int

  /** A fenced code block or table, rendered as is and not spoken. */
  data class Unspoken(override val start: Int, val text: String) : TtsMessageBlock

  /** A prose paragraph `[start, end)` and the [sentences] that begin inside it. */
  data class Paragraph(override val start: Int, val end: Int, val sentences: List<TextChunk>) :
    TtsMessageBlock
}

/**
 * Returns the ranges of [text] that are rendered as a unit and never spoken: fenced code blocks
 * (including their fences) and tables of two or more `|` rows.
 *
 * An unclosed fence extends to the end of [text], so a code block that is still streaming in is
 * never spoken line by line.
 */
internal fun findUnspokenBlocks(text: String): List<MessageRange> {
  val blocks = mutableListOf<MessageRange>()
  var fenceStart = -1
  var tableStart = -1
  var tableEnd = -1
  var tableRows = 0

  fun closeTable() {
    if (tableRows >= 2) blocks.add(MessageRange(tableStart, tableEnd))
    tableStart = -1
    tableRows = 0
  }

  var lineStart = 0
  while (lineStart <= text.length) {
    val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
    val line = text.substring(lineStart, lineEnd).trimStart()
    when {
      fenceStart >= 0 -> {
        if (line.startsWith("```")) {
          blocks.add(MessageRange(fenceStart, lineEnd))
          fenceStart = -1
        }
      }
      line.startsWith("```") -> {
        closeTable()
        fenceStart = lineStart
      }
      line.startsWith("|") -> {
        if (tableRows == 0) tableStart = lineStart
        tableRows++
        tableEnd = lineEnd
      }
      HORIZONTAL_RULE_REGEX.matches(line) -> {
        closeTable()
        blocks.add(MessageRange(lineStart, lineEnd))
      }
      else -> closeTable()
    }
    lineStart = lineEnd + 1
  }
  if (fenceStart >= 0) blocks.add(MessageRange(fenceStart, text.length))
  closeTable()
  return blocks.sortedBy { it.start }
}

/**
 * Segments [text] into the sentences to speak, skipping fenced code blocks and tables.
 *
 * @param includeIncompleteTail whether trailing text with no sentence-ending punctuation is spoken
 *   as a final sentence. When `false`, its range is reported as [SpeakableText.incompleteTail].
 */
internal fun segmentSpeakableText(text: String, includeIncompleteTail: Boolean): SpeakableText {
  val rawChunks = TtsTextNormalizer.segmentTextIntoChunks(text, includeIncompleteTail)
  val blocks = findUnspokenBlocks(text)
  val lastChunk = rawChunks.lastOrNull()
  val isAmbiguousDecimalDot =
    lastChunk != null &&
      lastChunk.endIndex == text.length &&
      lastChunk.text.endsWith('.') &&
      lastChunk.text.dropLast(1).lastOrNull()?.isDigit() == true
  val chunks =
    if (!includeIncompleteTail && isAmbiguousDecimalDot) {
      rawChunks.dropLast(1)
    } else {
      rawChunks
    }
  val sentences =
    chunks
      .filter { chunk -> blocks.none { it.overlaps(chunk.startIndex, chunk.endIndex) } }
      .mapIndexed { index, chunk -> chunk.copy(id = index) }
  val tail = if (includeIncompleteTail) null else incompleteTailRange(text, chunks, blocks)
  return SpeakableText(sentences, tail)
}

/**
 * Returns the trimmed range of prose after the last of [chunks], stopping at the next unspoken
 * block, or `null` if it is blank.
 */
private fun incompleteTailRange(
  text: String,
  chunks: List<TextChunk>,
  blocks: List<MessageRange>,
): MessageRange? {
  var start = chunks.lastOrNull()?.endIndex ?: 0
  blocks.firstOrNull { start >= it.start && start < it.end }?.let { start = it.end }
  val end = blocks.firstOrNull { it.start >= start }?.start ?: text.length
  return trimmedRange(text, start, end)
}

/**
 * Splits [text] into blocks for display: unspoken blocks are kept whole, and the prose between them
 * is split into paragraphs at blank lines, each carrying the [sentences] that start inside it.
 *
 * @param hiddenRange a range to leave out, such as an in-flight tail that is shown separately.
 */
internal fun splitIntoBlocks(
  text: String,
  sentences: List<TextChunk>,
  hiddenRange: MessageRange? = null,
): List<TtsMessageBlock> {
  val result = mutableListOf<TtsMessageBlock>()

  fun addProse(proseStart: Int, proseEnd: Int) {
    var paragraphStart = proseStart
    val breaks =
      PARAGRAPH_BREAK_REGEX.findAll(text.substring(proseStart, proseEnd)).map {
        MessageRange(proseStart + it.range.first, proseStart + it.range.last + 1)
      }
    for (range in breaks + MessageRange(proseEnd, proseEnd)) {
      addParagraph(text, paragraphStart, range.start, sentences, hiddenRange, result)
      paragraphStart = range.end
    }
  }

  var cursor = 0
  for (block in findUnspokenBlocks(text)) {
    addProse(cursor, block.start)
    if (hiddenRange?.overlaps(block.start, block.end) != true) {
      result.add(TtsMessageBlock.Unspoken(block.start, text.substring(block.start, block.end)))
    }
    cursor = block.end
  }
  addProse(cursor, text.length)
  return result
}

private fun addParagraph(
  text: String,
  start: Int,
  end: Int,
  sentences: List<TextChunk>,
  hiddenRange: MessageRange?,
  result: MutableList<TtsMessageBlock>,
) {
  var visibleStart = start
  var visibleEnd = end
  if (hiddenRange != null && hiddenRange.overlaps(start, end)) {
    if (hiddenRange.start > start) {
      visibleEnd = hiddenRange.start
    } else {
      visibleStart = hiddenRange.end
    }
  }
  val range = trimmedRange(text, visibleStart, visibleEnd) ?: return
  result.add(
    TtsMessageBlock.Paragraph(
      start = range.start,
      end = range.end,
      sentences = sentences.filter { it.startIndex >= range.start && it.startIndex < range.end },
    )
  )
}

/** Returns `[start, end)` with surrounding whitespace removed, or `null` if it is blank. */
private fun trimmedRange(text: String, start: Int, end: Int): MessageRange? {
  var trimmedStart = start
  var trimmedEnd = end
  while (trimmedStart < trimmedEnd && text[trimmedStart].isWhitespace()) trimmedStart++
  while (trimmedEnd > trimmedStart && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
  return if (trimmedStart < trimmedEnd) MessageRange(trimmedStart, trimmedEnd) else null
}

internal fun renderParagraph(
  content: String,
  paragraph: TtsMessageBlock.Paragraph,
  activeSentenceId: Int,
  highlightColor: Color,
  inlineCodeBackground: Color,
  linkColor: Color,
  onSentenceClick: (Int) -> Unit,
  activeWordIndex: Int? = null,
  wordHighlightColor: Color = highlightColor,
): RenderedParagraph {
  val inlineCodeStyle =
    SpanStyle(fontFamily = FontFamily.Monospace, background = inlineCodeBackground)
  val sentenceRanges = mutableMapOf<Int, IntRange>()
  val text = buildAnnotatedString {
    var cursor = paragraph.start
    for (sentence in paragraph.sentences.sortedBy { it.startIndex }) {
      val sentenceEnd = minOf(sentence.endIndex, paragraph.end)
      if (sentence.startIndex < cursor || sentenceEnd <= sentence.startIndex) continue
      appendMarkdown(content, cursor, sentence.startIndex, inlineCodeStyle, linkColor)

      val outputStart = length
      val isCurrentSentence = sentence.id == activeSentenceId
      val hasWordHighlight = isCurrentSentence && activeWordIndex != null
      val link =
        LinkAnnotation.Clickable(
          tag = "tts_sentence_${sentence.id}",
          styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.None)),
        ) {
          onSentenceClick(sentence.id)
        }
      withLink(link) {
        if (isCurrentSentence) {
          val sentenceBg =
            if (hasWordHighlight) {
              highlightColor.copy(alpha = (highlightColor.alpha * 0.45f).coerceAtLeast(0.10f))
            } else {
              highlightColor
            }
          val sentenceWeight = if (hasWordHighlight) null else FontWeight.Bold
          withStyle(SpanStyle(background = sentenceBg, fontWeight = sentenceWeight)) {
            appendMarkdown(content, sentence.startIndex, sentenceEnd, inlineCodeStyle, linkColor)
          }
        } else {
          appendMarkdown(content, sentence.startIndex, sentenceEnd, inlineCodeStyle, linkColor)
        }
      }
      val outputEnd = length
      if (isCurrentSentence && activeWordIndex != null && outputEnd > outputStart) {
        val renderedSentence = toAnnotatedString().text.substring(outputStart, outputEnd)
        val wordRange = findRenderedWordRange(renderedSentence, activeWordIndex)
        if (wordRange != null) {
          addStyle(
            SpanStyle(background = wordHighlightColor),
            outputStart + wordRange.first,
            outputStart + wordRange.last + 1,
          )
        }
      }
      sentenceRanges[sentence.id] = outputStart until outputEnd
      cursor = sentenceEnd
    }
    appendMarkdown(content, cursor, paragraph.end, inlineCodeStyle, linkColor)
  }
  return RenderedParagraph(text, sentenceRanges)
}

/**
 * Returns the character range within [renderedSentence] of the [wordIndex]-th speakable word,
 * skipping any leading bullet or numbered-list marker (`• ` or `1. `) and emoji symbols, and
 * matching native `ComputeWordTimestamps` CJK character tokenization (`U+3040..U+9FFF`).
 */
internal fun findRenderedWordRange(renderedSentence: String, wordIndex: Int): IntRange? {
  if (wordIndex < 0 || renderedSentence.isEmpty()) return null
  var i = LEADING_LIST_PREFIX_REGEX.find(renderedSentence)?.let { it.range.last + 1 } ?: 0
  val n = renderedSentence.length
  val ranges = mutableListOf<IntRange>()
  while (i < n) {
    while (i < n) {
      val cp = renderedSentence.codePointAt(i)
      if (renderedSentence[i].isWhitespace() || isEmojiOrModifier(cp)) {
        i += Character.charCount(cp)
      } else {
        break
      }
    }
    if (i >= n) break
    val start = i
    if (isCjkChar(renderedSentence[i])) {
      i += 1
      while (i < n && isCjkOrAsciiPunct(renderedSentence[i])) {
        i += 1
      }
    } else {
      while (i < n) {
        val cp = renderedSentence.codePointAt(i)
        if (
          renderedSentence[i].isWhitespace() ||
            isCjkChar(renderedSentence[i]) ||
            isEmojiOrModifier(cp)
        ) {
          break
        }
        i += Character.charCount(cp)
      }
    }
    if (i > start) {
      ranges.add(start until i)
    }
  }
  if (ranges.isEmpty()) return null
  return ranges[wordIndex.coerceAtMost(ranges.lastIndex)]
}

private fun isCjkChar(ch: Char): Boolean = ch.code in 0x3040..0x9FFF

private fun isCjkOrAsciiPunct(ch: Char): Boolean {
  val code = ch.code
  if (code in 0x3001..0x303F || code in 0xFF00..0xFFFF) return true
  return code in 0x21..0x7E && !ch.isLetterOrDigit()
}

private fun isEmojiOrModifier(codePoint: Int): Boolean =
  codePoint == 0x200D ||
    codePoint == 0x20E3 ||
    (codePoint in 0xFE00..0xFE0F) ||
    (codePoint in 0x1F3FB..0x1F3FF) ||
    (codePoint in 0xE0020..0xE007F) ||
    (codePoint in 0x1F600..0x1F64F) ||
    (codePoint in 0x1F300..0x1F5FF) ||
    (codePoint in 0x1F680..0x1F6FF) ||
    (codePoint in 0x1F1E6..0x1F1FF) ||
    (codePoint in 0x2600..0x26FF) ||
    (codePoint in 0x2700..0x27BF) ||
    (codePoint in 0x1F900..0x1F9FF) ||
    (codePoint in 0x1FA00..0x1FAFF) ||
    (codePoint in 0x231A..0x231B) ||
    (codePoint in 0x23E9..0x23FA) ||
    (codePoint in 0x25AA..0x25FE) ||
    (codePoint in 0x2934..0x2935) ||
    (codePoint in 0x2B05..0x2B55) ||
    codePoint == 0x3030 ||
    codePoint == 0x303D ||
    codePoint == 0x3297 ||
    codePoint == 0x3299

/**
 * Appends `content[start, end)` with lightweight markdown rendering: headings, bullets, and quotes
 * at the start of a line, plus bold, italic, inline code, and clickable links.
 */
internal fun AnnotatedString.Builder.appendMarkdown(
  content: String,
  start: Int,
  end: Int,
  inlineCodeStyle: SpanStyle,
  linkColor: Color = Color.Unspecified,
) {
  var lineStart = start
  while (lineStart < end) {
    val newline = content.indexOf('\n', lineStart)
    val lineEnd = if (newline == -1 || newline >= end) end else newline
    var line = content.substring(lineStart, lineEnd)
    var isHeading = false
    if (lineStart == 0 || content[lineStart - 1] == '\n') {
      val heading = HEADING_PREFIX_REGEX.find(line)
      if (heading != null) {
        line = line.substring(heading.range.last + 1)
        isHeading = true
      } else {
        line =
          line
            .replaceFirst(BULLET_PREFIX_REGEX, "$1\u2022 ")
            .replaceFirst(BLOCKQUOTE_PREFIX_REGEX, "")
      }
    }
    if (isHeading) {
      withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
        appendInlineMarkdown(line, inlineCodeStyle, linkColor)
      }
    } else {
      appendInlineMarkdown(line, inlineCodeStyle, linkColor)
    }
    if (lineEnd < end) append('\n')
    lineStart = lineEnd + 1
  }
}

internal fun AnnotatedString.Builder.appendInlineMarkdown(
  text: String,
  inlineCodeStyle: SpanStyle,
  linkColor: Color = Color.Unspecified,
) {
  var cursor = 0
  for (match in INLINE_MARKDOWN_REGEX.findAll(text)) {
    appendPlain(text.substring(cursor, match.range.first))
    val bold = match.groupValues[1]
    val altBold = match.groupValues[2]
    val code = match.groupValues[3]
    val linkLabel = match.groupValues[4]
    val linkUrl = match.groupValues[5]
    val italic = match.groupValues[6]
    val altItalic = match.groupValues[7]
    when {
      bold.isNotEmpty() || altBold.isNotEmpty() ->
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
          appendInlineMarkdown(bold.ifEmpty { altBold }, inlineCodeStyle, linkColor)
        }
      code.isNotEmpty() -> withStyle(inlineCodeStyle) { append(code) }
      linkLabel.isNotEmpty() -> {
        if (linkUrl.isNotEmpty()) {
          val link =
            LinkAnnotation.Url(
              url = linkUrl,
              styles = TextLinkStyles(style = SpanStyle(color = linkColor)),
            )
          withLink(link) { appendInlineMarkdown(linkLabel, inlineCodeStyle, linkColor) }
        } else {
          appendInlineMarkdown(linkLabel, inlineCodeStyle, linkColor)
        }
      }
      else ->
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
          appendInlineMarkdown(italic.ifEmpty { altItalic }, inlineCodeStyle, linkColor)
        }
    }
    cursor = match.range.last + 1
  }
  appendPlain(text.substring(cursor))
}

/** Appends [text], dropping unpaired bold delimiters across sentence boundaries. */
internal fun AnnotatedString.Builder.appendPlain(text: String) {
  append(text.replace(UNPAIRED_BOLD_DELIMITER_REGEX, ""))
}
