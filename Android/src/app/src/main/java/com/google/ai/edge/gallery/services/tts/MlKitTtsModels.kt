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

import android.content.Context
import com.google.ai.edge.gallery.R
import com.google.mlkit.genai.texttospeech.VoiceInfo
import java.util.Locale

/** Holds the warmed selected [VoiceInfo] and supported voices metadata for a specific locale. */
internal data class WarmedLocaleVoices(
  @Volatile var voice: VoiceInfo,
  val supportedVoices: List<VoiceInfo>,
)

internal val PERSONA_TO_STAR_VOICE =
  STAR_VOICE_TO_PERSONA.entries.associate { (code, persona) -> persona to code }

/**
 * Expected [VoiceInfo.Gender] for each prebuilt ML Kit persona voice, used to select a
 * gender-matching fallback voice when a locale's voice pack does not include the exact persona
 * voice.
 */
internal val PERSONA_TO_GENDER: Map<String, VoiceInfo.Gender> =
  mapOf(
    "Zephyr" to VoiceInfo.Gender.FEMALE,
    "Puck" to VoiceInfo.Gender.MALE,
    "Charon" to VoiceInfo.Gender.MALE,
    "Kore" to VoiceInfo.Gender.FEMALE,
    "Fenrir" to VoiceInfo.Gender.MALE,
    "Leda" to VoiceInfo.Gender.FEMALE,
    "Orus" to VoiceInfo.Gender.MALE,
    "Aoede" to VoiceInfo.Gender.FEMALE,
  )

/**
 * Static locale catalogs, persona voice mappings, localized voice labels, and [TtsModelMetadata]
 * builders for [MlKitTtsEngine].
 */
internal object MlKitTtsModels {
  /**
   * Additional locales offered only on a subset of device model manifests in ML Kit GenAI
   * Text-to-Speech.
   */
  internal val PREVIEW_EXTRA_LOCALES: Set<String> =
    setOf("ar-XA", "da-DK", "en-GB", "ko-KR", "nb-NO", "nl-NL", "pl-PL", "tr-TR")

  /** Superset of locales in ML Kit GenAI Text-to-Speech. */
  val SUPPORTED_LOCALES: List<String> =
    listOf(
      "ar-XA",
      "da-DK",
      "de-DE",
      "en-GB",
      "en-US",
      "es-ES",
      "es-US",
      "fr-CA",
      "fr-FR",
      "hi-IN",
      "id-ID",
      "it-IT",
      "ja-JP",
      "ko-KR",
      "nb-NO",
      "nl-NL",
      "pl-PL",
      "pt-BR",
      "ru-RU",
      "sv-SE",
      "tr-TR",
    )

  internal fun normalizeLocale(locale: Locale): Locale {
    val tag = locale.toLanguageTag()
    return if (tag.isNotEmpty()) Locale.forLanguageTag(tag) else locale
  }

  internal fun resolveSupportedLocale(languageCode: String): Locale {
    val normalizedInput = languageCode.trim().replace('_', '-')
    val exactMatch = SUPPORTED_LOCALES.firstOrNull { it.equals(normalizedInput, ignoreCase = true) }
    val resolvedTag =
      when {
        exactMatch != null -> exactMatch
        '-' !in normalizedInput -> {
          when (normalizedInput.lowercase(Locale.ROOT)) {
            "en" -> "en-US"
            "es" -> "es-ES"
            "fr" -> "fr-FR"
            else ->
              SUPPORTED_LOCALES.firstOrNull {
                it.substringBefore('-').equals(normalizedInput, ignoreCase = true)
              } ?: normalizedInput
          }
        }
        else -> normalizedInput
      }
    return normalizeLocale(Locale.forLanguageTag(resolvedTag))
  }

  internal fun getDisplayNameForVoice(rawName: String): String {
    for ((starCode, persona) in STAR_VOICE_TO_PERSONA) {
      if (rawName.contains(starCode, ignoreCase = true)) {
        return persona
      }
    }
    return rawName
  }

  internal fun getFormattedVoiceLabel(
    context: Context,
    persona: String,
    gender: VoiceInfo.Gender?,
  ): String {
    val allLanguages = context.getString(R.string.tts_voice_all_languages)
    val genderLabel =
      when (gender) {
        VoiceInfo.Gender.MALE -> context.getString(R.string.tts_voice_gender_male)
        VoiceInfo.Gender.FEMALE -> context.getString(R.string.tts_voice_gender_female)
        else -> null
      }
    return if (genderLabel != null) {
      context.getString(R.string.tts_voice_display_name_format, persona, allLanguages, genderLabel)
    } else {
      context.getString(R.string.tts_voice_display_name_language_only_format, persona, allLanguages)
    }
  }

  internal fun getFormattedVoiceLabel(context: Context, voice: VoiceInfo): String {
    val persona = getDisplayNameForVoice(voice.name)
    val gender =
      voice.gender.takeIf { it == VoiceInfo.Gender.MALE || it == VoiceInfo.Gender.FEMALE }
        ?: PERSONA_TO_GENDER[persona]
    return getFormattedVoiceLabel(context, persona, gender)
  }

  internal fun findVoice(voices: List<VoiceInfo>, requestedVoiceName: String): VoiceInfo? {
    val targetStarCode =
      PERSONA_TO_STAR_VOICE.entries
        .firstOrNull { it.key.equals(requestedVoiceName, ignoreCase = true) }
        ?.value
    val directMatch = voices.find { voice ->
      voice.name.equals(requestedVoiceName, ignoreCase = true) ||
        (targetStarCode != null && voice.name.contains(targetStarCode, ignoreCase = true)) ||
        voice.name.contains(requestedVoiceName, ignoreCase = true)
    }
    if (directMatch != null) {
      return directMatch
    }
    val personaName =
      STAR_VOICE_TO_PERSONA.entries
        .firstOrNull { (starCode, _) -> requestedVoiceName.contains(starCode, ignoreCase = true) }
        ?.value ?: requestedVoiceName
    val expectedGender =
      PERSONA_TO_GENDER.entries.firstOrNull { it.key.equals(personaName, ignoreCase = true) }?.value
    return if (expectedGender != null) {
      voices.firstOrNull { it.gender == expectedGender }
    } else {
      null
    }
  }

  internal fun buildModelMetadata(
    context: Context,
    supportedVoices: List<VoiceInfo>,
    activeVoice: VoiceInfo?,
  ): List<TtsModelMetadata> {
    val defaultPersonas = STAR_VOICE_TO_PERSONA.values.distinct()
    val voices =
      if (supportedVoices.isNotEmpty()) {
        MediaPipeTtsModels.orderVoiceIds(
          listedVoiceIds = supportedVoices.map { getDisplayNameForVoice(it.name) }.distinct(),
          defaultVoiceIds = defaultPersonas,
        )
      } else {
        defaultPersonas
      }
    val supportedVoicesByPersona = supportedVoices.associateBy { getDisplayNameForVoice(it.name) }
    val voiceDescriptions = voices.associateWith { persona ->
      val voiceInfo = supportedVoicesByPersona[persona]
      if (voiceInfo != null) {
        getFormattedVoiceLabel(context, voiceInfo)
      } else {
        getFormattedVoiceLabel(context, persona, PERSONA_TO_GENDER[persona])
      }
    }
    val voiceLocales =
      if (supportedVoices.isNotEmpty()) {
        supportedVoices
          .mapNotNull {
            val tag = it.locale.toLanguageTag()
            if (tag.isNotEmpty()) getDisplayNameForVoice(it.name) to tag else null
          }
          .toMap()
      } else {
        emptyMap()
      }
    val activeVoiceName = activeVoice?.let { getDisplayNameForVoice(it.name) }
    return listOf(
      TtsModelMetadata(
        modelId = TtsModelIdentifier.ML_KIT,
        displayName =
          if (activeVoiceName != null) "ML Kit TTS ($activeVoiceName)" else "ML Kit TTS",
        engineType = TtsEngineType.ML_KIT,
        supportedLanguages = SUPPORTED_LOCALES,
        availableVoices = voices,
        voiceDescriptions = voiceDescriptions,
        voiceLocales = voiceLocales,
      )
    )
  }
}
