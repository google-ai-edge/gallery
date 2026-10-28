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

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.core.Delegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Amplitude ratio relative to peak below which samples at an inner clause boundary count as silence
 * (-40 dB).
 */
internal const val CLAUSE_JOIN_SILENCE_RATIO = 0.01f

/** Leading silence kept at the start of an inner clause (5 ms at 24 kHz). */
internal const val CLAUSE_JOIN_MARGIN_SAMPLES = 120

/** Trailing silence kept at the end of a non-final clause (200 ms at 24 kHz). */
internal const val CLAUSE_PUNCTUATION_PAUSE_SAMPLES = 4800

private val TRAILING_QUOTES_AND_BRACKETS_AFTER_PUNCTUATION =
  Regex("""(?<=[.!?。！？…])["“”„‟«»‹›「」『』‘’'‚‛)\]}）】］]+""")
private val DOUBLE_AND_DIRECTIONAL_QUOTES = Regex("""["“”„‟«»‹›「」『』]+""")
private val NON_CONTRACTION_SINGLE_QUOTES =
  Regex("""[‘‚‛]+|(?<![\p{L}\p{N}])['’]+|['’]+(?![\p{L}\p{N}])""")
private val INTERNAL_ASCII_SENTENCE_DELIMITER =
  Regex(
    """(?i)(?<!\b(?:e\.g|i\.e|dr|mr|mrs|ms|prof|vs|etc|fig|al|approx|inc|corp))[.!?]+(?=\s+\S)"""
  )
private val INTERNAL_CJK_SENTENCE_DELIMITER = Regex("""[。！？]+(?=\S)""")

/**
 * Maximum character length of a coalesced static-shape Kokoro pass, chosen to fit within a single
 * inference pass.
 */
internal const val MAX_STATIC_SHAPE_CLAUSE_CHARS = 110

/**
 * Coalesces short clauses up to [MAX_STATIC_SHAPE_CLAUSE_CHARS] and replaces internal
 * sentence-ending delimiters (`.`, `!`, `?`, `。`, `！`, `？`) with clause commas.
 */
internal fun coalesceClausesForStaticShapeImpl(text: String): List<String> {
  val rawClauses = TtsTextNormalizer.splitIntoClauses(text)
  if (rawClauses.isEmpty()) return emptyList()
  val merged = mutableListOf<String>()
  val current = StringBuilder()
  for (clause in rawClauses) {
    when {
      current.isEmpty() -> current.append(clause)
      current.length + 1 + clause.length <= MAX_STATIC_SHAPE_CLAUSE_CHARS ->
        current.append(' ').append(clause)
      else -> {
        merged.add(replaceInternalSentenceDelimiters(current.toString()))
        current.clear()
        current.append(clause)
      }
    }
  }
  if (current.isNotEmpty()) {
    merged.add(replaceInternalSentenceDelimiters(current.toString()))
  }
  return merged
}

internal fun replaceInternalSentenceDelimiters(clause: String): String =
  clause
    .replace(INTERNAL_ASCII_SENTENCE_DELIMITER, ",")
    .replace(INTERNAL_CJK_SENTENCE_DELIMITER, "，")

/**
 * Sanitizes [text] before passing it to MediaPipe `synthesizeStream`:
 * - Drops closing quotes and brackets immediately after terminal punctuation.
 * - Removes double, directional, and CJK quotation marks (`" “ ” „ ‟ « » ‹ › 「 」 『 』`).
 * - Preserves apostrophes (`'` and `’`) inside words (contractions such as `don’t`) while removing
 *   other single quotation marks (`‘ ’ '`).
 */
internal fun sanitizeTextForMediaPipeImpl(text: String): String =
  text
    .replace(TRAILING_QUOTES_AND_BRACKETS_AFTER_PUNCTUATION, "")
    .replace(DOUBLE_AND_DIRECTIONAL_QUOTES, "")
    .replace(NON_CONTRACTION_SINGLE_QUOTES, "")
    .trim()

/**
 * Returns the `[begin, end)` sample range to keep when trimming excess utterance edge silence at
 * inner clause joins within a sentence.
 */
@Suppress("KotlinArray")
internal fun trimClauseJoinSilenceRangeImpl(
  samples: FloatArray,
  trimHead: Boolean,
  trimTail: Boolean,
): IntRange {
  if (samples.isEmpty() || (!trimHead && !trimTail)) return 0 until samples.size

  var peak = 0f
  for (sample in samples) {
    val magnitude = abs(sample)
    if (magnitude > peak) peak = magnitude
  }
  if (peak <= 0f) {
    if (trimHead && trimTail) {
      val keep = min(samples.size, CLAUSE_JOIN_MARGIN_SAMPLES + CLAUSE_PUNCTUATION_PAUSE_SAMPLES)
      return 0 until keep
    }
    return 0 until samples.size
  }

  val threshold = peak * CLAUSE_JOIN_SILENCE_RATIO
  var begin = 0
  var end = samples.size
  if (trimHead) {
    while (begin < end && abs(samples[begin]) < threshold) {
      begin++
    }
    begin = (begin - CLAUSE_JOIN_MARGIN_SAMPLES).coerceAtLeast(0)
  }
  if (trimTail) {
    while (end > begin && abs(samples[end - 1]) < threshold) {
      end--
    }
    end = (end + CLAUSE_PUNCTUATION_PAUSE_SAMPLES).coerceAtMost(samples.size)
  }
  return begin until end
}

/** Trims excess utterance edge silence at inner clause joins within a sentence. */
@Suppress("KotlinArray")
internal fun trimClauseJoinSilenceImpl(
  samples: FloatArray,
  trimHead: Boolean,
  trimTail: Boolean,
): FloatArray {
  if (samples.isEmpty() || (!trimHead && !trimTail)) return samples
  val range = trimClauseJoinSilenceRangeImpl(samples, trimHead, trimTail)
  if (range.first == 0 && range.last + 1 == samples.size) return samples
  if (range.isEmpty()) return FloatArray(0)
  return samples.copyOfRange(range.first, range.last + 1)
}

/** Prefers GPU when the model spec and allowlist model support it. */
internal fun preferredDelegateImpl(model: Model?, supportsGpu: Boolean = true): Delegate =
  if (supportsGpu && model != null && Accelerator.GPU in model.backendSpec.accelerators) {
    Delegate.GPU
  } else {
    Delegate.CPU
  }

/**
 * Resolves per-utterance [SynthesisOptions] from [config], matching iOS
 * `KokoroTtsEngine.resolveSynthesisOptions`:
 * - No voice, a sentinel such as `"Default"` or `"System Default"`, or a voice that is not in
 *   [knownVoiceIds]: the requested locale picks its native default voice.
 * - [localeOverridableVoiceId] with a locale outside US English: the locale wins.
 * - When [passLocaleWithExplicitVoice] is `true` (e.g. PocketTTS): if the explicit voice's language
 *   ([voiceLocale]) matches [locale]'s primary language subtag, both `voiceId` and `locale` are
 *   forwarded; if they differ (such as an English voice with `es-ES`), `voiceId` is cleared to
 *   `null` so native MediaPipe selects the target language's precomputed KV-cache voice.
 * - Otherwise the explicit voice wins (`locale = null`).
 */
internal fun resolveSynthesisOptionsImpl(
  config: TtsConfig?,
  knownVoiceIds: List<String>,
  localeOverridableVoiceId: String?,
  passLocaleWithExplicitVoice: Boolean = false,
  voiceLocale: (String) -> String?,
): SynthesisOptions {
  val voice = config?.voiceId?.trim()?.takeIf { it in knownVoiceIds }
  val locale = config?.languageCode?.trim()?.takeIf { it.isNotEmpty() }
  if (voice == null) {
    return SynthesisOptions(voiceId = null, locale = locale)
  }
  if (
    voice == localeOverridableVoiceId && locale != null && !locale.lowercase().startsWith("en-us")
  ) {
    return SynthesisOptions(voiceId = null, locale = locale)
  }
  if (passLocaleWithExplicitVoice && locale != null) {
    val resolvedVoiceLocale = voiceLocale(voice)
    if (resolvedVoiceLocale != null && !isCompatibleLocaleImpl(resolvedVoiceLocale, locale)) {
      return SynthesisOptions(voiceId = null, locale = locale)
    }
  }
  return SynthesisOptions(
    voiceId = voice,
    locale = if (passLocaleWithExplicitVoice) locale else null,
  )
}

/**
 * Checks if [voiceLocale] is compatible with [requestedLocale], matching full BCP-47 tags (e.g.
 * `pt-BR` != `pt-PT`) or language subtags when no region is requested.
 */
internal fun isCompatibleLocaleImpl(voiceLocale: String, requestedLocale: String): Boolean {
  val normVoice = voiceLocale.replace('_', '-').lowercase(Locale.ROOT)
  val normReq = requestedLocale.replace('_', '-').lowercase(Locale.ROOT)
  if (normVoice == normReq) return true
  val voiceLang = normVoice.substringBefore('-')
  val reqLang = normReq.substringBefore('-')
  if (voiceLang != reqLang) return false
  val voiceRegion = normVoice.substringAfter('-', "")
  val reqRegion = normReq.substringAfter('-', "")
  if (reqRegion.isEmpty()) return true
  return voiceRegion == reqRegion
}

/** Converts float PCM samples in `[-1, 1]` to 16-bit signed little-endian PCM bytes. */
@Suppress("KotlinArray")
internal fun floatSamplesToPcm16Impl(samples: FloatArray): ByteArray {
  val buffer = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
  for (sample in samples) {
    buffer.putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
  }
  return buffer.array()
}
