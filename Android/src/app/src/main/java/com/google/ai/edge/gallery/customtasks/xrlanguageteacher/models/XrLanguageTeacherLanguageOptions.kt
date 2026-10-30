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

import com.google.ai.edge.gallery.proto.Language

/**
 * The languages the teacher can use with the selected speech models.
 *
 * The student speaks their native language into the microphone and the teacher speaks both the
 * native language and the language to learn, so:
 * - [native] holds the languages that both the ASR model and the TTS model support, and
 * - [learn] holds the languages that the TTS model supports.
 *
 * Both lists are in [ALL_SUPPORTED_LANGUAGES] order. [native] is empty when the two models share no
 * language; such a pair can't run the teacher (see [hasSharedLanguage]).
 */
data class TeacherLanguageOptions(val native: List<Language>, val learn: List<Language>) {

  /** Whether the selected models share at least one language, so the teacher can run at all. */
  val hasSharedLanguage: Boolean
    get() = native.isNotEmpty()

  /** The languages to offer for learning while the native language is [mainLanguage]. */
  fun learnOptionsFor(mainLanguage: Language): List<Language> = learn.filter { it != mainLanguage }

  /**
   * Returns the languages to use for [saved] with these options: each saved language if the models
   * support it, otherwise a supported replacement. The language to learn is never the native one.
   *
   * Keeps [saved] when the models share no language, because no choice can work then; the model
   * selection doesn't offer such a pair.
   */
  fun resolveLanguages(saved: TeacherLanguages): TeacherLanguages {
    if (!hasSharedLanguage) return saved
    val main =
      resolveSupportedLanguage(
        current = saved.main,
        supportedLanguages = native,
        preferredFallback = Language.LANGUAGE_ENGLISH,
      )
    val learn =
      resolveSupportedLanguage(
        current = saved.learn,
        supportedLanguages = learn,
        preferredFallback = defaultLearnLanguage(main),
        avoidLanguage = main,
      )
    return TeacherLanguages(main = main, learn = learn)
  }

  /**
   * Returns how [resolveLanguages] changes [saved] with these options, or `null` when the saved
   * languages are supported as they are.
   */
  fun languageAdjustment(saved: TeacherLanguages): TeacherLanguageAdjustment? {
    val used = resolveLanguages(saved)
    return if (used == saved) null else TeacherLanguageAdjustment(saved = saved, used = used)
  }

  /** Options for models that don't restrict their languages, such as the system engines. */
  companion object {
    val UNRESTRICTED =
      TeacherLanguageOptions(native = ALL_SUPPORTED_LANGUAGES, learn = ALL_SUPPORTED_LANGUAGES)

    /**
     * Builds the options from the BCP-47 tags each model supports. An empty tag list means the
     * model doesn't restrict its languages, as the system engines do. Tags are compared by
     * language, so `cmn-Hans-CN` (ASR) and `zh-CN` (TTS) are both Chinese.
     */
    fun fromLocaleTags(
      asrLocaleTags: Collection<String>,
      ttsLocaleTags: Collection<String>,
    ): TeacherLanguageOptions {
      val ttsLanguages = languagesFor(ttsLocaleTags)
      val asrLanguages = languagesFor(asrLocaleTags).toSet()
      return TeacherLanguageOptions(
        native = ttsLanguages.filter { it in asrLanguages },
        learn = ttsLanguages,
      )
    }

    private fun languagesFor(localeTags: Collection<String>): List<Language> =
      if (localeTags.isEmpty()) ALL_SUPPORTED_LANGUAGES else mapEngineLocalesToLanguages(localeTags)
  }
}

/** The native language and the language to learn. */
data class TeacherLanguages(val main: Language, val learn: Language)

data class TeacherLanguageResolution(val saved: TeacherLanguages, val used: TeacherLanguages)

/**
 * The languages the teacher uses instead of the saved ones, because the selected models don't
 * support [saved]. The saved choice is kept until the user confirms another one.
 */
data class TeacherLanguageAdjustment(val saved: TeacherLanguages, val used: TeacherLanguages) {
  /** The saved native language, when it is the one that isn't supported. */
  val unsupportedMain: Language?
    get() = saved.main.takeIf { it != used.main }

  /** The saved language to learn, when it is the one that isn't supported. */
  val unsupportedLearn: Language?
    get() = saved.learn.takeIf { it != used.learn && it != used.main }
}

/**
 * The languages the selected speech recognition model supports
 * ([SelectedSpeechEngineLocales.asrLocaleTags]) that aren't in [nativeLanguages] because the
 * selected voice model doesn't support them. The native language dropdown shows them greyed out so
 * that users know why they can't pick them. Languages that no offered voice model speaks
 * ([SelectedSpeechEngineLocales.reachableTtsLocaleTags]) are left out, since switching the voice
 * model can't make them work. Empty when the speech recognition model doesn't restrict its
 * languages.
 */
internal fun unsupportedNativeLanguages(
  engineLocales: SelectedSpeechEngineLocales,
  nativeLanguages: List<Language>,
): List<Language> {
  if (engineLocales.asrLocaleTags.isEmpty()) return emptyList()
  val reachableLanguages =
    engineLocales.reachableTtsLocaleTags?.let { mapEngineLocalesToLanguages(it).toSet() }
  return mapEngineLocalesToLanguages(engineLocales.asrLocaleTags).filter {
    it !in nativeLanguages && (reachableLanguages == null || it in reachableLanguages)
  }
}

/**
 * Whether the selected text-to-speech model ([SelectedSpeechEngineLocales.ttsLocaleTags]) supports
 * [language]. A model that doesn't restrict its languages supports them all.
 */
fun SelectedSpeechEngineLocales.voiceSupports(language: Language): Boolean =
  ttsLocaleTags.isEmpty() || language in mapEngineLocalesToLanguages(ttsLocaleTags)
