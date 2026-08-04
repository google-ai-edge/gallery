/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.translation

private const val MIN_CLAUSE_LENGTH = 28
private const val TARGET_PHRASE_LENGTH = 40
private const val MAX_PHRASE_LENGTH = 48

internal class TranslationTtsChunker {
  private val trailingText = StringBuilder()

  fun append(partialText: String, flush: Boolean = false): List<String> {
    trailingText.append(partialText)
    val chunks = mutableListOf<String>()

    while (trailingText.isNotEmpty()) {
      val boundary =
        findSentenceBoundary().takeIf { it > 0 }
          ?: findClauseBoundary().takeIf { it > 0 }
          ?: findSoftPhraseBoundary()
      if (boundary <= 0) {
        break
      }
      takeChunk(boundary = boundary)?.let(chunks::add)
    }

    if (flush) {
      takeChunk(boundary = trailingText.length)?.let(chunks::add)
    }
    return chunks
  }

  fun flush(): List<String> = append(partialText = "", flush = true)

  fun reset() {
    trailingText.clear()
  }

  private fun findSentenceBoundary(): Int {
    for (index in trailingText.indices) {
      val character = trailingText[index]
      if (character == '\n') {
        return index + 1
      }
      if (character !in SENTENCE_TERMINATORS) {
        continue
      }

      var nextIndex = index + 1
      while (nextIndex < trailingText.length && trailingText[nextIndex] in CLOSING_PUNCTUATION) {
        nextIndex++
      }
      if (nextIndex == trailingText.length) {
        return nextIndex
      }
      if (nextIndex < trailingText.length && trailingText[nextIndex].isWhitespace()) {
        return nextIndex
      }
    }
    return -1
  }

  private fun findSoftPhraseBoundary(): Int {
    if (trailingText.length < MAX_PHRASE_LENGTH) {
      return -1
    }

    val searchEnd = (TARGET_PHRASE_LENGTH - 1).coerceAtMost(trailingText.length - 1)
    for (index in searchEnd downTo (MIN_CLAUSE_LENGTH - 1)) {
      if (trailingText[index].isWhitespace()) {
        return index + 1
      }
    }
    val preferredSearchEnd =
      (MAX_PHRASE_LENGTH - 1).coerceAtMost(trailingText.length - 1)
    for (index in (searchEnd + 1)..preferredSearchEnd) {
      if (trailingText[index].isWhitespace()) {
        return index + 1
      }
    }
    for (index in (preferredSearchEnd + 1) until trailingText.length) {
      if (trailingText[index].isWhitespace()) {
        return index + 1
      }
    }
    return -1
  }

  private fun findClauseBoundary(): Int {
    for (index in (MIN_CLAUSE_LENGTH - 1) until trailingText.length) {
      if (trailingText[index] !in CLAUSE_TERMINATORS) continue

      var nextIndex = index + 1
      while (nextIndex < trailingText.length && trailingText[nextIndex] in CLOSING_PUNCTUATION) {
        nextIndex++
      }
      if (nextIndex == trailingText.length || trailingText[nextIndex].isWhitespace()) {
        return nextIndex
      }
    }
    return -1
  }

  private fun takeChunk(boundary: Int): String? {
    if (boundary <= 0) {
      return null
    }
    val chunk = trailingText.substring(0, boundary.coerceAtMost(trailingText.length)).trim()
    trailingText.delete(0, boundary.coerceAtMost(trailingText.length))
    while (trailingText.isNotEmpty() && trailingText.first().isWhitespace()) {
      trailingText.deleteCharAt(0)
    }
    return chunk.ifEmpty { null }
  }

  private companion object {
    val SENTENCE_TERMINATORS = setOf('.', '!', '?', '\u3002', '\uFF01', '\uFF1F')
    val CLAUSE_TERMINATORS = setOf(',', ':', ';', '\u2013', '\u2014')
    val CLOSING_PUNCTUATION = setOf('"', '\'', '\u2019', '\u201D', ')', ']', '}')
  }
}
