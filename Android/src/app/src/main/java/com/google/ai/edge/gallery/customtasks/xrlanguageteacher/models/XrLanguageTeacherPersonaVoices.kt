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

import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import java.util.Locale

/**
 * The voices one TTS model uses for the teacher personas.
 *
 * A persona's voice is looked up for the language being spoken: first the entry in [voicesByLocale]
 * for that exact locale tag, then the first entry for the same language, then [defaultVoices]. So a
 * model whose voices speak every language only needs [defaultVoices], and a model whose voices are
 * tied to one language (such as Kokoro, where the voice ID prefix selects the language) lists one
 * entry per locale.
 *
 * @property defaultVoices The voice ID for each persona when no entry matches the language.
 * @property voicesByLocale Voice IDs for each persona, keyed by BCP-47 locale tag, in lookup order.
 */
data class PersonaVoiceTable(
  val defaultVoices: Map<CharacterPersona, String>,
  val voicesByLocale: Map<String, Map<CharacterPersona, String>> = emptyMap(),
) {
  /** Returns the voice ID [persona] speaks [languageCode] with. */
  fun voiceFor(persona: CharacterPersona, languageCode: String?): String? {
    val voices =
      languageCode?.let { tag ->
        voicesByLocale.entries.firstOrNull { it.key.equals(tag, ignoreCase = true) }?.value
          ?: voicesByLocale.entries.firstOrNull { languageOf(it.key) == languageOf(tag) }?.value
      } ?: defaultVoices
    return voices[persona] ?: defaultVoices[persona]
  }

  private fun languageOf(localeTag: String): String =
    localeTagToLocale(localeTag).language.lowercase(Locale.ROOT)
}

/**
 * The voice each teacher persona speaks with, per TTS model, picked to match the persona's
 * character (for example the upbeat Puck for Buddy and the informative Charon for Nerdy).
 *
 * To give the personas voices on a new TTS model, add a [PersonaVoiceTable] under the model's
 * engine model ID ([TtsModelIdentifier]) with a voice for every [CharacterPersona]:
 * - Voice IDs are the IDs the engine lists for the model (its `TtsModelMetadata.availableVoices`).
 * - If a voice only speaks one language, list the voices per locale in
 *   [PersonaVoiceTable.voicesByLocale], and use [PersonaVoiceTable.defaultVoices] for the rest.
 * - Models without a table speak with the engine's default voice for the language, so every persona
 *   sounds the same. The system engine has none on purpose: it uses the device's voice.
 */
object PersonaVoices {
  private val ML_KIT =
    PersonaVoiceTable(
      defaultVoices =
        mapOf(
          CharacterPersona.BUDDY to "Puck",
          CharacterPersona.NERDY to "Charon",
          CharacterPersona.SECRET_AGENT to "Kore",
          CharacterPersona.WIZARD to "Aoede",
        )
    )

  private val KOKORO_US_ENGLISH =
    mapOf(
      CharacterPersona.BUDDY to "am_puck",
      CharacterPersona.NERDY to "am_fenrir",
      CharacterPersona.SECRET_AGENT to "af_kore",
      CharacterPersona.WIZARD to "af_aoede",
    )

  /**
   * Kokoro speaks the language of the voice's prefix (`a` US English, `b` British English, `e`
   * Spanish, `f` French, `h` Hindi, `i` Italian, `j` Japanese, `p` Brazilian Portuguese, `z`
   * Mandarin) and ignores the requested locale once a voice is given, so each language needs its
   * own voices. Languages with fewer than four voices share them between personas; French ships a
   * single voice.
   */
  private val KOKORO =
    PersonaVoiceTable(
      defaultVoices = KOKORO_US_ENGLISH,
      voicesByLocale =
        mapOf(
          "en-US" to KOKORO_US_ENGLISH,
          "en-GB" to
            mapOf(
              CharacterPersona.BUDDY to "bm_george",
              CharacterPersona.NERDY to "bm_fable",
              CharacterPersona.SECRET_AGENT to "bf_emma",
              CharacterPersona.WIZARD to "bf_alice",
            ),
          "es-ES" to
            mapOf(
              CharacterPersona.BUDDY to "em_alex",
              CharacterPersona.NERDY to "em_santa",
              CharacterPersona.SECRET_AGENT to "ef_dora",
              CharacterPersona.WIZARD to "ef_dora",
            ),
          "fr-FR" to CharacterPersona.entries.associateWith { "ff_siwis" },
          "hi-IN" to
            mapOf(
              CharacterPersona.BUDDY to "hm_omega",
              CharacterPersona.NERDY to "hm_psi",
              CharacterPersona.SECRET_AGENT to "hf_alpha",
              CharacterPersona.WIZARD to "hf_beta",
            ),
          "it-IT" to
            mapOf(
              CharacterPersona.BUDDY to "im_nicola",
              CharacterPersona.NERDY to "im_nicola",
              CharacterPersona.SECRET_AGENT to "if_sara",
              CharacterPersona.WIZARD to "if_sara",
            ),
          "ja-JP" to
            mapOf(
              CharacterPersona.BUDDY to "jm_kumo",
              CharacterPersona.NERDY to "jm_kumo",
              CharacterPersona.SECRET_AGENT to "jf_alpha",
              CharacterPersona.WIZARD to "jf_gongitsune",
            ),
          "pt-BR" to
            mapOf(
              CharacterPersona.BUDDY to "pm_alex",
              CharacterPersona.NERDY to "pm_santa",
              CharacterPersona.SECRET_AGENT to "pf_dora",
              CharacterPersona.WIZARD to "pf_dora",
            ),
          "zh-CN" to
            mapOf(
              CharacterPersona.BUDDY to "zm_yunxi",
              CharacterPersona.NERDY to "zm_yunjian",
              CharacterPersona.SECRET_AGENT to "zf_xiaoxiao",
              CharacterPersona.WIZARD to "zf_xiaoyi",
            ),
        ),
    )

  private val POCKET_TTS_ENGLISH =
    mapOf(
      CharacterPersona.BUDDY to "alba",
      CharacterPersona.NERDY to "azelma",
      CharacterPersona.SECRET_AGENT to "eponine",
      CharacterPersona.WIZARD to "javert",
    )

  /**
   * PocketTTS ships eight English voices and one native precomputed KV-cache voice per non-English
   * language (`estelle` for French, `juergen` for German, `lola` for Spanish, `giovanni` for
   * Italian, `rafael` for Brazilian Portuguese, and `daan` for Dutch). Each language's Flow-LM
   * requires its matching language voice.
   */
  private val POCKET_TTS =
    PersonaVoiceTable(
      defaultVoices = POCKET_TTS_ENGLISH,
      voicesByLocale =
        mapOf(
          "en-US" to POCKET_TTS_ENGLISH,
          "es-ES" to CharacterPersona.entries.associateWith { "lola" },
          "fr-FR" to CharacterPersona.entries.associateWith { "estelle" },
          "de-DE" to CharacterPersona.entries.associateWith { "juergen" },
          "it-IT" to CharacterPersona.entries.associateWith { "giovanni" },
          "pt-BR" to CharacterPersona.entries.associateWith { "rafael" },
          "nl-NL" to CharacterPersona.entries.associateWith { "daan" },
        ),
    )

  private val TABLES: Map<String, PersonaVoiceTable> =
    mapOf(
      TtsModelIdentifier.ML_KIT to ML_KIT,
      TtsModelIdentifier.MEDIAPIPE_KOKORO_82M to KOKORO,
      TtsModelIdentifier.MEDIAPIPE_POCKET_TTS_100M to POCKET_TTS,
    )

  /**
   * Returns the voice ID [persona] speaks [languageCode] with on the TTS model [modelId], or `null`
   * to use the engine's default voice for the language.
   */
  fun voiceIdFor(modelId: String, persona: CharacterPersona, languageCode: String?): String? =
    TABLES[modelId]?.voiceFor(persona, languageCode)
}
