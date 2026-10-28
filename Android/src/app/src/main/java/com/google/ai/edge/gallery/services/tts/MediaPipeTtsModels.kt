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
import androidx.core.os.ConfigurationCompat
import com.google.ai.edge.gallery.R
import java.util.Locale

/**
 * Per-model defaults for a TTS model served by [MediaPipeTtsEngine].
 *
 * The allowlist entry's `ttsConfig` (voices, languages) takes precedence; these defaults apply when
 * it declares none, and [defaultVoiceIds] until the loaded model reports its voices.
 *
 * @property engineModelId Engine model ID (`ttsConfig.engineModelId`) of the model; see
 *   [TtsModelIdentifier].
 * @property defaultVoiceIds Voice IDs packaged in the model.
 * @property defaultLanguages BCP-47 locales the model can speak.
 * @property localeOverridableVoiceId Voice that yields to a requested locale outside its own
 *   language, so per-segment language routing (XR Language Teacher) speaks each language with its
 *   native default voice; `null` if every explicit voice always wins.
 * @property passLocaleWithExplicitVoice Whether to pass `locale` alongside an explicit `voiceId` in
 *   synthesis options when the voice's language matches `locale`, or clear `voiceId` when they
 *   differ so the native engine selects the language's default voice (used by models such as
 *   PocketTTS that load language-specific Flow-LM weights and precomputed KV-cache voices based on
 *   `locale`).
 * @property supportsGpu Whether the model supports GPU acceleration via OpenCL delegates; `false`
 *   to force CPU inference.
 * @property coalesceStaticShapeClauses Whether short clauses should be coalesced up to the model's
 *   static shape size to avoid full-cost static shape padding on short utterances.
 * @property warmUpText Utterance passed to `synthesizeStream` during warm-up; models that support
 *   empty-string (`""`) metadata and KV-cache warm-up in native MediaPipe avoid synthesizing a
 *   dummy audio utterance on CPU during `prepare`.
 * @property warmUpFirstChunkOnly Whether warm-up stops collecting after the first emitted audio
 *   chunk instead of draining the entire warm-up utterance.
 * @property voiceIdFilter Predicate filtering native `listVoices()` entries down to valid voice IDs
 *   for this model.
 * @property voiceLocale Maps a voice ID to its BCP-47 locale tag, or `null` if unspecified.
 */
internal data class MediaPipeTtsModelSpec(
  val engineModelId: String,
  val defaultVoiceIds: List<String>,
  val defaultLanguages: List<String>,
  val localeOverridableVoiceId: String? = null,
  val passLocaleWithExplicitVoice: Boolean = false,
  val supportsGpu: Boolean = true,
  val coalesceStaticShapeClauses: Boolean = true,
  val warmUpText: String = MediaPipeTtsModels.WARMUP_TEXT,
  val warmUpFirstChunkOnly: Boolean = false,
  val voiceIdFilter: (String) -> Boolean = { it.isNotEmpty() },
  val voiceLocale: (String) -> String? = { null },
)

/**
 * Static model specifications, voice metadata tables, and localized voice display-name formatting
 * for on-device MediaPipe TTS models (`Kokoro 82M`, `PocketTTS 100M`, and `Phonon 31M`).
 */
internal object MediaPipeTtsModels {
  internal const val WARMUP_TEXT = "Hi."

  /**
   * BCP-47 locale of each Kokoro voice language letter (the `a` of `af_heart`), matching native
   * `kokoro::LanguageForVoiceName` and [KOKORO_82M_SPEC]'s languages.
   */
  private val KOKORO_VOICE_LOCALES: Map<Char, String> =
    mapOf(
      'a' to "en-US",
      'b' to "en-GB",
      'e' to "es-ES",
      'f' to "fr-FR",
      'h' to "hi-IN",
      'i' to "it-IT",
      'j' to "ja-JP",
      'p' to "pt-BR",
      'z' to "zh-CN",
    )

  /**
   * Parsed components of a Kokoro voice ID (`<language><gender>_<name>`, e.g. `af_heart`,
   * `zm_yunxi`), with a Kokoro language letter ([KOKORO_VOICE_LOCALES]), a gender letter `f` or `m`
   * (both letters case-insensitive), and a non-empty ASCII alphanumeric or underscore name.
   */
  internal data class KokoroVoiceId(val localeTag: String, val isFemale: Boolean, val name: String)

  internal fun parseKokoroVoiceId(voiceId: String): KokoroVoiceId? {
    if (voiceId.length < 4 || voiceId[2] != '_') return null
    val localeTag = KOKORO_VOICE_LOCALES[voiceId[0].lowercaseChar()] ?: return null
    val gender = voiceId[1].lowercaseChar()
    if (gender != 'f' && gender != 'm') return null
    val name = voiceId.substringAfter('_')
    if (
      name.isEmpty() ||
        !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
    ) {
      return null
    }
    return KokoroVoiceId(localeTag, gender == 'f', name)
  }

  internal fun isKokoroVoiceId(voiceId: String): Boolean = parseKokoroVoiceId(voiceId) != null

  /**
   * Returns the BCP-47 locale a Kokoro voice ID implies (e.g. `af_heart` -> `"en-US"`, `ef_dora` ->
   * `"es-ES"`), or `null` when [voiceId] isn't a Kokoro voice ([isKokoroVoiceId]).
   */
  internal fun kokoroVoiceLocale(voiceId: String): String? = parseKokoroVoiceId(voiceId)?.localeTag

  /**
   * BCP-47 locale of each PocketTTS voice packaged in `pocket_tts_int8.litertlm`, matching native
   * `pocket_tts::LanguageForVoiceName` (`common.cc`). Each precomputed KV-cache voice is tied to
   * its language's Flow-LM transformer weights.
   */
  private val POCKET_TTS_VOICE_LOCALES: Map<String, String> =
    mapOf(
      "alba" to "en-US",
      "marius" to "en-US",
      "javert" to "en-US",
      "jean" to "en-US",
      "fantine" to "en-US",
      "cosette" to "en-US",
      "eponine" to "en-US",
      "azelma" to "en-US",
      "estelle" to "fr-FR",
      "juergen" to "de-DE",
      "lola" to "es-ES",
      "rafael" to "pt-BR",
      "giovanni" to "it-IT",
      "daan" to "nl-NL",
    )

  /**
   * Returns the BCP-47 locale a PocketTTS voice ID is trained for (e.g. `alba` -> `"en-US"`, `lola`
   * -> `"es-ES"`), or `null` when [voiceId] is not a known PocketTTS voice.
   */
  internal fun pocketTtsVoiceLocale(voiceId: String): String? =
    POCKET_TTS_VOICE_LOCALES[voiceId.lowercase(Locale.ROOT)]

  private val PHONON_VOICE_LOCALES: Map<String, String> =
    mapOf(
      "freya" to "en-US",
      "claire" to "en-US",
      "lukas" to "en-US",
      "sofia" to "en-US",
      "lucas" to "en-US",
    )

  internal fun isPhononVoiceId(voiceId: String): Boolean =
    voiceId.lowercase(Locale.ROOT) in PHONON_VOICE_LOCALES.keys

  /**
   * Returns the BCP-47 locale a Phonon voice ID is trained for (all `"en-US"` for Phonon 31M
   * English), or `null` when [voiceId] is not a known Phonon voice.
   */
  internal fun phononVoiceLocale(voiceId: String): String? =
    PHONON_VOICE_LOCALES[voiceId.lowercase(Locale.ROOT)]

  /**
   * Derives a localized display label from a voice identifier:
   * - Kokoro voice IDs (e.g. `af_heart`) format as `"Heart (English, Female)"`.
   * - Single-word ASCII letter voice IDs with a known [localeTag] (such as PocketTTS `"alba"`,
   *   `"estelle"` or Phonon `"freya"`) format as `"Alba (English)"`, `"Estelle (French)"`, `"Freya
   *   (English)"`.
   * - Single-word ASCII letter voice IDs without a [localeTag] format with a capitalized initial
   *   letter (`"Alba"`).
   * - Other IDs return `null`.
   */
  internal fun deriveVoiceDisplayName(
    context: Context,
    voiceId: String,
    localeTag: String? = pocketTtsVoiceLocale(voiceId) ?: phononVoiceLocale(voiceId),
  ): String? {
    val displayLocale =
      ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
    val parsed = parseKokoroVoiceId(voiceId)
    if (parsed == null) {
      if (
        voiceId.isNotEmpty() && '_' !in voiceId && voiceId.all { it in 'a'..'z' || it in 'A'..'Z' }
      ) {
        val capitalizedName = voiceId.replaceFirstChar { ch ->
          if (ch.isLowerCase()) ch.titlecase(displayLocale) else ch.toString()
        }
        if (!localeTag.isNullOrBlank()) {
          val language = Locale.forLanguageTag(localeTag).getDisplayLanguage(displayLocale)
          if (language.isNotBlank()) {
            return context.getString(
              R.string.tts_voice_display_name_language_only_format,
              capitalizedName,
              language,
            )
          }
        }
        return capitalizedName
      }
      return null
    }
    val language = Locale.forLanguageTag(parsed.localeTag).getDisplayLanguage(displayLocale)
    val gender =
      if (parsed.isFemale) {
        context.getString(R.string.tts_voice_gender_female)
      } else {
        context.getString(R.string.tts_voice_gender_male)
      }
    val capitalizedName =
      parsed.name.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase(displayLocale) else ch.toString()
      }
    return context.getString(
      R.string.tts_voice_display_name_format,
      capitalizedName,
      language,
      gender,
    )
  }

  /**
   * Orders [listedVoiceIds] by their canonical position in [defaultVoiceIds], appending any extra
   * bundle voices in their listed order so the default voice (such as `af_heart`) stays first after
   * runtime loading.
   */
  internal fun orderVoiceIds(
    listedVoiceIds: List<String>,
    defaultVoiceIds: List<String>,
  ): List<String> {
    if (listedVoiceIds.isEmpty()) return defaultVoiceIds
    val listedSet = listedVoiceIds.toSet()
    val defaultSet = defaultVoiceIds.toSet()
    val canonicalPrefix = defaultVoiceIds.filter { it in listedSet }
    val extras = listedVoiceIds.filter { it !in defaultSet }
    return canonicalPrefix + extras
  }

  /** Kokoro 82M (`kokoro_int8_gpu_fp16.litertlm`). */
  internal val KOKORO_82M_SPEC =
    MediaPipeTtsModelSpec(
      engineModelId = TtsModelIdentifier.MEDIAPIPE_KOKORO_82M,
      defaultVoiceIds =
        listOf(
          "af_heart",
          "af_alloy",
          "af_aoede",
          "af_bella",
          "af_jessica",
          "af_kore",
          "af_nicole",
          "af_nova",
          "af_river",
          "af_sarah",
          "af_sky",
          "am_adam",
          "am_echo",
          "am_eric",
          "am_fenrir",
          "am_liam",
          "am_michael",
          "am_onyx",
          "am_puck",
          "am_santa",
          "bf_alice",
          "bf_emma",
          "bf_isabella",
          "bf_lily",
          "bm_daniel",
          "bm_fable",
          "bm_george",
          "bm_lewis",
          "ef_dora",
          "em_alex",
          "em_santa",
          "ff_siwis",
          "hf_alpha",
          "hf_beta",
          "hm_omega",
          "hm_psi",
          "if_sara",
          "im_nicola",
          "jf_alpha",
          "jf_gongitsune",
          "jf_nezumi",
          "jf_tebukuro",
          "jm_kumo",
          "pf_dora",
          "pm_alex",
          "pm_santa",
          "zf_xiaobei",
          "zf_xiaoni",
          "zf_xiaoxiao",
          "zf_xiaoyi",
          "zm_yunjian",
          "zm_yunxi",
          "zm_yunxia",
          "zm_yunyang",
        ),
      defaultLanguages =
        listOf("en-US", "en-GB", "es-ES", "fr-FR", "hi-IN", "it-IT", "pt-BR", "ja-JP", "zh-CN"),
      localeOverridableVoiceId = "af_heart",
      voiceIdFilter = ::isKokoroVoiceId,
      voiceLocale = ::kokoroVoiceLocale,
    )

  /**
   * PocketTTS 100M (`pocket_tts_int8.litertlm`). Voices and locales match MediaPipe C++
   * `pocket_tts/common.cc`.
   */
  internal val POCKET_TTS_100M_SPEC =
    MediaPipeTtsModelSpec(
      engineModelId = TtsModelIdentifier.MEDIAPIPE_POCKET_TTS_100M,
      defaultVoiceIds =
        listOf(
          "alba",
          "marius",
          "javert",
          "jean",
          "fantine",
          "cosette",
          "eponine",
          "azelma",
          "estelle",
          "juergen",
          "lola",
          "rafael",
          "giovanni",
          "daan",
        ),
      defaultLanguages = listOf("en-US", "fr-FR", "de-DE", "es-ES", "it-IT", "pt-BR", "nl-NL"),
      passLocaleWithExplicitVoice = true,
      supportsGpu = false,
      coalesceStaticShapeClauses = false,
      warmUpText = "",
      warmUpFirstChunkOnly = true,
      voiceLocale = ::pocketTtsVoiceLocale,
    )

  /**
   * Phonon 31M (`phonon_int8.litertlm`). Packaged voices (`"freya"`, `"claire"`, `"lukas"`,
   * `"sofia"`, `"lucas"`) from Hugging Face (`litert-community/phonon-31m-en`) run on CPU at 24 kHz
   * mono.
   */
  internal val PHONON_31M_SPEC =
    MediaPipeTtsModelSpec(
      engineModelId = TtsModelIdentifier.MEDIAPIPE_PHONON_31M,
      defaultVoiceIds = listOf("freya", "claire", "lukas", "sofia", "lucas"),
      defaultLanguages = listOf("en-US"),
      passLocaleWithExplicitVoice = true,
      supportsGpu = false,
      coalesceStaticShapeClauses = false,
      warmUpText = "",
      warmUpFirstChunkOnly = true,
      voiceIdFilter = ::isPhononVoiceId,
      voiceLocale = ::phononVoiceLocale,
    )

  /**
   * Models served by [MediaPipeTtsEngine]. Add a [MediaPipeTtsModelSpec] to serve another model.
   */
  internal val MODEL_SPECS: List<MediaPipeTtsModelSpec> =
    listOf(KOKORO_82M_SPEC, POCKET_TTS_100M_SPEC, PHONON_31M_SPEC)
}
