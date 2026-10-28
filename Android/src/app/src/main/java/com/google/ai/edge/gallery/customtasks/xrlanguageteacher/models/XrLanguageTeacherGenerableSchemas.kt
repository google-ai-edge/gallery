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

import com.google.ai.edge.gallery.runtime.aicore.AICoreGenerableResponse
import com.google.mlkit.genai.schema.annotations.Generable
import com.google.mlkit.genai.schema.annotations.Guide

private const val VOCAB_CARD_DESCRIPTION =
  "Vocabulary flashcard for the taught word or short phrase"
private const val MEANING_FIELD_DESCRIPTION = "Native-language meaning or translation"
private const val EXAMPLE_FIELD_DESCRIPTION = "Target-language example sentence"
private const val EXAMPLE_TRANSLATION_FIELD_DESCRIPTION =
  "Native-language translation of the example sentence"

/**
 * Structured output schema for the Pass 2 vocab card on AICore models, requested with
 * `generateTypedContentRequest`.
 *
 * Property names are the output field names, so they match the keys in
 * [TeacherResponseSchema.CARD_FIELDS] and [TeacherResponseSchema.pass2CardJsonSchema].
 *
 * Builds that enable R8/ProGuard must keep this class and its generated `GenerableProvider` (for
 * example `-keep class com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.Generable* {
 * *; }`) so structured output can be parsed into it.
 */
@Generable(description = VOCAB_CARD_DESCRIPTION)
data class GenerableVocabCard(
  @Guide(description = TeacherResponseSchema.WORD_FIELD_DESCRIPTION) val word: String,
  @Guide(description = MEANING_FIELD_DESCRIPTION) val meaning: String,
  @Guide(description = EXAMPLE_FIELD_DESCRIPTION) val example: String,
  // Snake case on purpose: it is the "example_translation" schema and JSON field name.
  @Suppress("PropertyName")
  @Guide(description = EXAMPLE_TRANSLATION_FIELD_DESCRIPTION)
  val example_translation: String,
) : AICoreGenerableResponse {
  override fun toJsonString(): String =
    "{\"word\":${escapeJsonString(word)}," +
      "\"meaning\":${escapeJsonString(meaning)}," +
      "\"example\":${escapeJsonString(example)}," +
      "\"example_translation\":${escapeJsonString(example_translation)}}"
}

internal fun escapeJsonString(raw: String): String = buildString {
  append('"')
  for (ch in raw) {
    when (ch) {
      '"' -> append("\\\"")
      '\\' -> append("\\\\")
      '\b' -> append("\\b")
      '\u000C' -> append("\\f")
      '\n' -> append("\\n")
      '\r' -> append("\\r")
      '\t' -> append("\\t")
      else -> {
        if (ch.code < 0x20) {
          append("\\u%04x".format(ch.code))
        } else {
          append(ch)
        }
      }
    }
  }
  append('"')
}
