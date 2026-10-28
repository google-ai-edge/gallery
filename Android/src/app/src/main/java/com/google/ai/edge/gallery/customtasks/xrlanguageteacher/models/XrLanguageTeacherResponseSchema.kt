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

/**
 * Builds the JSON Schema that constrains Pass 2 vocabulary card extraction on LiteRT-LM:
 * ```
 * {"word":"...","meaning":"...","example":"...","example_translation":"..."}
 * ```
 *
 * Patterns are matched against the raw token pieces of the model's vocabulary (a space is `▁`, and
 * characters outside the vocabulary are byte-fallback pieces such as `<0xE3>`). Byte-fallback
 * pieces are allowed, since scripts such as Devanagari need them; any other `<` is not, so card
 * fields cannot contain tags. [TeacherJsonResponse.parseCardOnly] removes typed byte-piece text.
 * Backslashes and control characters (tabs, newlines) are not allowed inside card fields.
 *
 * Card fields must not end in whitespace.
 *
 * Whitespace between JSON tokens is allowed (`x-guidance.whitespace_flexible = true`), matching how
 * Gemma formats JSON. The output length stays bounded by the Pass 2 max output tokens.
 */
object TeacherResponseSchema {
  /** Card fields, in the order the model generates them. */
  val CARD_FIELDS: List<String> = listOf("word", "meaning", "example", "example_translation")

  /** Shared field guidance for the primary card term across LiteRT-LM and AICore schemas. */
  const val WORD_FIELD_DESCRIPTION =
    "main focal word/phrase discussed when multiple nouns appear; never a sentence or copula frame"

  /** A byte-fallback token piece, such as `<0xE3>`. */
  private const val BYTE_PIECE = "<0x[0-9A-Fa-f]{2}>"
  private const val TEXT_CHAR = "(?:[^<\\\\\\x00-\\x1F]|$BYTE_PIECE)"
  private const val WORD_CHAR = "(?:[^<\\\\.!?…。！？।॥؟۔\\x00-\\x1F]|$BYTE_PIECE)"
  private const val WORD_LAST_CHAR = "(?:[^<\\\\.!?…。！？।॥؟۔\\s\\x00-\\x1F\u2581]|$BYTE_PIECE)"
  const val WORD_FIELD_PATTERN = "^$WORD_CHAR*$WORD_LAST_CHAR$"
  private const val CARD_FIELD_LAST_CHAR = "(?:[^<\\\\\\s\\x00-\\x1F\u2581]|$BYTE_PIECE)"
  const val CARD_FIELD_PATTERN = "^$TEXT_CHAR*$CARD_FIELD_LAST_CHAR$"

  /**
   * Returns the JSON Schema string for Pass 2 card extraction on LiteRT-LM: a card object
   * (`{"word": ..., "meaning": ..., ...}`) with every field required.
   */
  fun pass2CardJsonSchema(): String {
    val properties =
      CARD_FIELDS.joinToString(",") { field ->
        val schema =
          if (field == "word") {
            stringSchema(WORD_FIELD_PATTERN, description = WORD_FIELD_DESCRIPTION)
          } else {
            stringSchema(CARD_FIELD_PATTERN)
          }
        "${jsonString(field)}:$schema"
      }
    val required = CARD_FIELDS.joinToString(",") { jsonString(it) }
    return "{\"type\":\"object\",\"properties\":{$properties},\"required\":[$required]," +
      "\"additionalProperties\":false,\"x-guidance\":{\"whitespace_flexible\":true}}"
  }

  private fun stringSchema(pattern: String, description: String? = null): String {
    val descriptionEntry =
      if (description != null) "\"description\":${jsonString(description)}," else ""
    return "{\"type\":\"string\",$descriptionEntry\"pattern\":${jsonString(pattern)}}"
  }

  private fun jsonString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** Reads the JSON vocabulary card produced by Pass 2 of 2-pass decoding. */
object TeacherJsonResponse {

  private val CODE_FENCE_REGEX = Regex("""(?i)^```(?:json)?\s*|\s*```$""")

  /**
   * Byte-fallback piece syntax typed out as text. Real byte pieces are decoded into characters, so
   * this only matches text the model typed, which never belongs on a card.
   */
  private val TYPED_BYTE_PIECE_REGEX = Regex("<0x[0-9A-Fa-f]{2}>")

  /**
   * Parses the card JSON object (`{"word":...}`) produced by Pass 2 of 2-pass decoding. Returns an
   * empty map when there is no complete card. Byte-fallback piece syntax typed as text (`<0xE3>`)
   * is removed from the values.
   */
  fun parseCardOnly(rawJson: String): Map<String, String> {
    val cursor = JsonCursor(rawJson.trim().replace(CODE_FENCE_REGEX, "").trim())
    if (!cursor.consume('{')) return emptyMap()
    return readCardFields(cursor).mapValues { (_, value) ->
      value.replace(TYPED_BYTE_PIECE_REGEX, "")
    }
  }

  /** Reads the remaining fields of a card object, or returns an empty map if it is incomplete. */
  private fun readCardFields(cursor: JsonCursor): Map<String, String> {
    val card = linkedMapOf<String, String>()
    while (true) {
      val (key, isKeyComplete) = cursor.readString() ?: return emptyMap()
      if (!isKeyComplete || !cursor.consume(':')) return emptyMap()
      val (value, isValueComplete) = cursor.readString() ?: return emptyMap()
      if (!isValueComplete) return emptyMap()
      card[key] = value
      when {
        cursor.consume(',') -> continue
        cursor.consume('}') -> return card
        else -> return emptyMap()
      }
    }
  }

  /** A forward-only reader over JSON text that may end at any character. */
  private class JsonCursor(private val text: String) {
    private var index = 0

    private fun skipWhitespace() {
      while (index < text.length && text[index].isWhitespace()) index++
    }

    /** Consumes [ch] after optional whitespace, returning whether it was present. */
    fun consume(ch: Char): Boolean {
      skipWhitespace()
      if (index < text.length && text[index] == ch) {
        index++
        return true
      }
      return false
    }

    /**
     * Reads a JSON string value. Returns `null` when no string starts here, otherwise the decoded
     * text so far and whether the closing quote was reached.
     */
    fun readString(): Pair<String, Boolean>? {
      skipWhitespace()
      if (index >= text.length || text[index] != '"') return null
      index++
      val decoded = StringBuilder()
      while (index < text.length) {
        val ch = text[index]
        if (ch == '"') {
          index++
          return decoded.toString() to true
        }
        if (ch != '\\') {
          decoded.append(ch)
          index++
          continue
        }
        if (index + 1 >= text.length) break
        when (val escaped = text[index + 1]) {
          'n' -> decoded.append('\n')
          't' -> decoded.append('\t')
          'r' -> decoded.append('\r')
          'b' -> decoded.append('\b')
          'f' -> decoded.append('\u000C')
          'u' -> {
            if (index + 6 > text.length) break
            val code = text.substring(index + 2, index + 6).toIntOrNull(radix = 16)
            if (code != null) decoded.append(code.toChar())
            index += 6
            continue
          }
          else -> decoded.append(escaped)
        }
        index += 2
      }
      index = text.length
      return decoded.toString() to false
    }
  }
}
