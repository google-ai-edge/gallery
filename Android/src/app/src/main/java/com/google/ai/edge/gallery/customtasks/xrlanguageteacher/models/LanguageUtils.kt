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

@file:Suppress("DiscourageTopLevelFunctions")

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private data class LanguageMetadata(
  val isoLanguageCode: String,
  val defaultLocaleTag: String,
  @StringRes val nameResId: Int,
)

private val LANGUAGE_METADATA: Map<Language, LanguageMetadata> =
  linkedMapOf(
    Language.LANGUAGE_ENGLISH to
      LanguageMetadata("en", "en-US", R.string.xrteacher_language_english),
    Language.LANGUAGE_CHINESE to
      LanguageMetadata("zh", "zh-CN", R.string.xrteacher_language_chinese),
    Language.LANGUAGE_FRENCH to LanguageMetadata("fr", "fr-FR", R.string.xrteacher_language_french),
    Language.LANGUAGE_SPANISH to
      LanguageMetadata("es", "es-ES", R.string.xrteacher_language_spanish),
    Language.LANGUAGE_KOREAN to LanguageMetadata("ko", "ko-KR", R.string.xrteacher_language_korean),
    Language.LANGUAGE_HINDI to LanguageMetadata("hi", "hi-IN", R.string.xrteacher_language_hindi),
    Language.LANGUAGE_JAPANESE to
      LanguageMetadata("ja", "ja-JP", R.string.xrteacher_language_japanese),
    Language.LANGUAGE_GERMAN to LanguageMetadata("de", "de-DE", R.string.xrteacher_language_german),
    Language.LANGUAGE_ITALIAN to
      LanguageMetadata("it", "it-IT", R.string.xrteacher_language_italian),
    Language.LANGUAGE_PORTUGUESE to
      LanguageMetadata("pt", "pt-PT", R.string.xrteacher_language_portuguese),
    Language.LANGUAGE_THAI to LanguageMetadata("th", "th-TH", R.string.xrteacher_language_thai),
    Language.LANGUAGE_VIETNAMESE to
      LanguageMetadata("vi", "vi-VN", R.string.xrteacher_language_vietnamese),
    Language.LANGUAGE_ARABIC to LanguageMetadata("ar", "ar", R.string.xrteacher_language_arabic),
    Language.LANGUAGE_RUSSIAN to
      LanguageMetadata("ru", "ru-RU", R.string.xrteacher_language_russian),
  )

private val ISO_CODE_TO_LANGUAGE: Map<String, Language> =
  LANGUAGE_METADATA.entries.associate { (language, metadata) ->
    metadata.isoLanguageCode to language
  }

/** Supported [Language] entries in canonical UI order. */
val ALL_SUPPORTED_LANGUAGES: List<Language> = LANGUAGE_METADATA.keys.toList()

/** Learn language used when the user has not chosen one yet. */
val DEFAULT_LEARN_LANGUAGE: Language = Language.LANGUAGE_SPANISH

/**
 * Returns the learn language of this [DictionaryEntry], falling back to [DEFAULT_LEARN_LANGUAGE]
 * for legacy entries whose [DictionaryEntry.getLearnLanguage] is [Language.LANGUAGE_UNSPECIFIED].
 */
fun DictionaryEntry.displayLearnLanguage(): Language =
  if (learnLanguage == Language.LANGUAGE_UNSPECIFIED) DEFAULT_LEARN_LANGUAGE else learnLanguage

/**
 * Normalizes a BCP-47 locale tag (such as `"cmn-CN"`, `"es_ES"`, or `"pt-BR"`) so that
 * [Locale.forLanguageTag] resolves standard ISO 639-1 language codes across all Android/JVM
 * versions.
 */
fun normalizeLocaleTag(localeTag: String): String {
  val sanitized = localeTag.trim().replace('_', '-')
  if (sanitized.isEmpty()) return "en-US"
  val parts = sanitized.split('-')
  val primary =
    when (parts.first().lowercase(Locale.ROOT)) {
      "cmn" -> "zh"
      "iw" -> "he"
      "in" -> "id"
      "ji" -> "yi"
      else -> parts.first()
    }
  return (listOf(primary) + parts.drop(1)).joinToString("-")
}

/** Converts a BCP-47 locale tag into a normalized [Locale]. */
fun localeTagToLocale(localeTag: String): Locale =
  Locale.forLanguageTag(normalizeLocaleTag(localeTag))

/**
 * Dynamically maps a BCP-47 locale tag (e.g., `"es"`, `"es-ES"`, `"cmn-CN"`, `"pt-BR"`, `"ar-XA"`)
 * into its full human-readable language display name (e.g., `"Spanish"`, `"Chinese"`) via [Locale].
 */
fun localeTagToDisplayName(localeTag: String, displayLocale: Locale = Locale.getDefault()): String {
  val locale = localeTagToLocale(localeTag)
  val rawName = locale.getDisplayLanguage(displayLocale).trim()
  if (rawName.isEmpty()) return localeTag
  return rawName.replaceFirstChar { ch ->
    if (ch.isLowerCase()) ch.titlecase(displayLocale) else ch.toString()
  }
}

/**
 * Maps a BCP-47 locale tag reported by a TTS or ASR engine to the corresponding [Language] enum, or
 * `null` if the tag does not correspond to a supported [Language].
 */
fun localeTagToLanguage(localeTag: String): Language? {
  if (localeTag.isBlank()) return null
  val isoCode = localeTagToLocale(localeTag).language.lowercase(Locale.ROOT)
  return ISO_CODE_TO_LANGUAGE[isoCode]
}

/**
 * Maps a collection of engine-reported BCP-47 locale tags to distinct selectable [Language] values
 * in canonical order.
 */
fun mapEngineLocalesToLanguages(localeTags: Collection<String>): List<Language> {
  val supportedSet = localeTags.mapNotNull(::localeTagToLanguage).toSet()
  if (supportedSet.isEmpty()) return emptyList()
  return ALL_SUPPORTED_LANGUAGES.filter { it in supportedSet }
}

/**
 * Resolves the exact BCP-47 locale tag advertised by an engine for [requestedLocaleTag] (for
 * example mapping `"zh-CN"` to `"cmn-CN"`, `"pt-PT"` to `"pt-BR"`, or `"ar"` to `"ar-XA"`), or
 * returns `null` when [supportedLocaleTags] is non-empty and the engine does not support that
 * language.
 */
fun resolveEngineLocaleTag(
  requestedLocaleTag: String,
  supportedLocaleTags: Collection<String>,
): String? {
  if (supportedLocaleTags.isEmpty()) return requestedLocaleTag
  supportedLocaleTags
    .firstOrNull { it.equals(requestedLocaleTag, ignoreCase = true) }
    ?.let {
      return it
    }
  val requestedLanguage = localeTagToLanguage(requestedLocaleTag)
  if (requestedLanguage != null) {
    return resolveEngineLocaleTagForLanguage(requestedLanguage, supportedLocaleTags)
  }
  val requestedIso = localeTagToLocale(requestedLocaleTag).language.lowercase(Locale.ROOT)
  if (requestedIso.isEmpty()) return null
  return supportedLocaleTags.firstOrNull { tag ->
    localeTagToLocale(tag).language.lowercase(Locale.ROOT) == requestedIso
  }
}

/**
 * Resolves the best matching BCP-47 locale tag advertised in [supportedLocaleTags] for [language],
 * or returns `null` if [supportedLocaleTags] is non-empty and [language] is not supported by the
 * engine.
 *
 * Prefers the language's default tag (such as `"es-ES"` over `"es-US"`), then a tag for the same
 * region, so Chinese (`"zh-CN"`) resolves to `"cmn-Hans-CN"` rather than `"cmn-Hant-TW"` whatever
 * the engine's order. Otherwise returns the engine's first tag for the language, for example
 * `"pt-BR"` for Portuguese (`"pt-PT"`) or `"ar-XA"` for Arabic (`"ar"`).
 */
fun resolveEngineLocaleTagForLanguage(
  language: Language,
  supportedLocaleTags: Collection<String>,
): String? {
  val defaultLocale = language.toLocale()
  val defaultTag = defaultLocale.toLanguageTag()
  if (supportedLocaleTags.isEmpty()) return defaultTag
  val matchingTags = supportedLocaleTags.filter { localeTagToLanguage(it) == language }
  if (matchingTags.isEmpty()) return null
  return matchingTags.firstOrNull { it.equals(defaultTag, ignoreCase = true) }
    ?: matchingTags.firstOrNull { tag ->
      defaultLocale.country.isNotEmpty() &&
        localeTagToLocale(tag).country.equals(defaultLocale.country, ignoreCase = true)
    }
    ?: matchingTags.first()
}

fun Language?.toLocale(): Locale {
  val tag = LANGUAGE_METADATA[this]?.defaultLocaleTag ?: "en-US"
  return Locale.forLanguageTag(tag)
}

/** Returns the ISO 639-1 code of this [Language] (such as `"ko"`), or English's if it has none. */
fun Language.toIsoLanguageCode(): String =
  (LANGUAGE_METADATA[this] ?: LANGUAGE_METADATA.getValue(Language.LANGUAGE_ENGLISH)).isoLanguageCode

/**
 * Returns the native language to use while the user has not picked one: the
 * [ALL_SUPPORTED_LANGUAGES] entry with the same language as [systemLocale] (so `zh-TW` maps to
 * Chinese and `pt-BR` to Portuguese), or English when there is none.
 */
fun defaultMainLanguage(systemLocale: Locale = Locale.getDefault()): Language =
  localeTagToLanguage(systemLocale.toLanguageTag()) ?: Language.LANGUAGE_ENGLISH

/**
 * Returns this stored native language, or [defaultMainLanguage] when it is
 * [Language.LANGUAGE_UNSPECIFIED]. The default is computed on every call and never persisted, so it
 * follows the system language until the user picks one.
 */
fun Language.orDefaultMainLanguage(): Language =
  if (this == Language.LANGUAGE_UNSPECIFIED) defaultMainLanguage() else this

/**
 * Returns the learn language to use while the user has not picked one: [DEFAULT_LEARN_LANGUAGE], or
 * English when [mainLanguage] is already [DEFAULT_LEARN_LANGUAGE], so the student is never taught
 * their own native language.
 */
internal fun defaultLearnLanguage(mainLanguage: Language): Language =
  if (mainLanguage == DEFAULT_LEARN_LANGUAGE) Language.LANGUAGE_ENGLISH else DEFAULT_LEARN_LANGUAGE

/**
 * Returns this stored learn language, or [defaultLearnLanguage] for [mainLanguage] when it is
 * [Language.LANGUAGE_UNSPECIFIED]. Like [orDefaultMainLanguage], the default is never persisted.
 */
internal fun Language.orDefaultLearnLanguage(mainLanguage: Language): Language =
  if (this == Language.LANGUAGE_UNSPECIFIED) defaultLearnLanguage(mainLanguage) else this

/**
 * Returns [current] if it is in [supportedLanguages] and distinct from [avoidLanguage] (or when
 * [supportedLanguages] is empty); otherwise returns [preferredFallback] if it is supported and
 * distinct from [avoidLanguage], or the first supported language distinct from [avoidLanguage], or
 * [preferredFallback].
 */
fun resolveSupportedLanguage(
  current: Language,
  supportedLanguages: Collection<Language>,
  preferredFallback: Language = Language.LANGUAGE_ENGLISH,
  avoidLanguage: Language? = null,
): Language {
  if (supportedLanguages.isEmpty() || (current in supportedLanguages && current != avoidLanguage)) {
    return current
  }
  if (preferredFallback in supportedLanguages && preferredFallback != avoidLanguage) {
    return preferredFallback
  }
  return supportedLanguages.firstOrNull { it != avoidLanguage }
    ?: supportedLanguages.firstOrNull()
    ?: preferredFallback
}

/**
 * Returns [current] if the engine's [supportedLocaleTags] cover it (or when [supportedLocaleTags]
 * is empty); otherwise falls back via [resolveSupportedLanguage].
 */
fun resolveSupportedLanguageForLocaleTags(
  current: Language,
  supportedLocaleTags: Collection<String>,
  preferredFallback: Language = Language.LANGUAGE_ENGLISH,
  avoidLanguage: Language? = null,
): Language {
  val supportedLanguages =
    if (supportedLocaleTags.isEmpty()) {
      emptyList()
    } else {
      ALL_SUPPORTED_LANGUAGES.filter { language ->
        resolveEngineLocaleTag(language.toLocale().toLanguageTag(), supportedLocaleTags) != null
      }
    }
  return resolveSupportedLanguage(
    current = current,
    supportedLanguages = supportedLanguages,
    preferredFallback = preferredFallback,
    avoidLanguage = avoidLanguage,
  )
}

/**
 * Returns the canonical English name of this [Language] dynamically via [Locale] for constructing
 * LLM prompts outside Compose.
 */
fun Language?.toEnglishName(fallback: String = "English"): String {
  if (this == null || this == Language.LANGUAGE_UNSPECIFIED || this == Language.UNRECOGNIZED) {
    return fallback
  }
  val metadata = LANGUAGE_METADATA[this] ?: return fallback
  return localeTagToDisplayName(metadata.defaultLocaleTag, Locale.ENGLISH).ifBlank { fallback }
}

@StringRes
fun Language.toNameResId(): Int {
  return LANGUAGE_METADATA[this]?.nameResId ?: R.string.xrteacher_language_unknown
}

/**
 * Formats a session start timestamp as `"Today, 2:15 PM"`, `"Yesterday, 4:30 PM"`, or `"Sep 27,
 * 4:30 PM"` using Android's locale- and 12h/24h-aware [DateUtils] formatting and localized string
 * templates.
 */
fun formatLessonSessionTimestamp(
  context: Context,
  timestampMs: Long,
  nowMs: Long = System.currentTimeMillis(),
): String {
  val effectiveTimestampMs = if (timestampMs > 0L) timestampMs else nowMs
  val zoneId = ZoneId.systemDefault()
  val targetZoned = Instant.ofEpochMilli(effectiveTimestampMs).atZone(zoneId)
  val nowDate = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
  val targetDate = targetZoned.toLocalDate()
  val formattedTime =
    DateUtils.formatDateTime(context, effectiveTimestampMs, DateUtils.FORMAT_SHOW_TIME)

  if (targetDate == nowDate) {
    return context.getString(R.string.xrteacher_today_time, formattedTime)
  }
  if (targetDate == nowDate.minusDays(1)) {
    return context.getString(R.string.xrteacher_yesterday_time, formattedTime)
  }

  val formattedDate =
    DateUtils.formatDateTime(
      context,
      effectiveTimestampMs,
      DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
    )
  return context.getString(R.string.xrteacher_date_time, formattedDate, formattedTime)
}

/**
 * Returns the theme-derived status dot [Color] for [readiness] using semantic [ColorScheme] roles
 * ([ColorScheme.primary] when ready, [ColorScheme.tertiary] while warming up, and
 * [ColorScheme.error] when running on a fallback engine).
 */
fun speechEngineStatusDotColor(readiness: SpeechEngineReadiness, colorScheme: ColorScheme): Color =
  when (readiness) {
    SpeechEngineReadiness.READY -> colorScheme.primary
    SpeechEngineReadiness.WARMING -> colorScheme.tertiary
    SpeechEngineReadiness.ERROR -> colorScheme.error
  }

@Composable
fun getLanguageName(language: Language): String {
  return stringResource(language.toNameResId())
}
