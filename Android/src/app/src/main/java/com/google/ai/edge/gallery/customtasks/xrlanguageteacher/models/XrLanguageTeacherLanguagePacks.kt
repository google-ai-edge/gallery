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

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.modelmanager.LanguagePackUiStatus
import com.google.ai.edge.gallery.ui.modelmanager.toLanguagePackUiStatus

/**
 * The ASR and TTS engines selected for the teacher, with the BCP-47 locale tags each supports. An
 * empty tag list means the engine doesn't restrict its languages.
 */
data class SelectedSpeechEngineLocales(
  val asrModelId: String = "",
  val asrLocaleTags: List<String> = emptyList(),
  val ttsModelId: String = "",
  val ttsLocaleTags: List<String> = emptyList(),
)

/** A speech engine that installs each language as a separate pack, such as ML Kit ASR Basic. */
data class LanguagePackEngine(val model: Model, val supportedLocaleTags: List<String>)

/**
 * The selected speech engines whose languages must be installed before they can be used. An engine
 * is `null` when it needs no language packs: ML Kit ASR Advanced bundles all its languages, and
 * LiteRT-LM speech models and the system engines have none.
 */
data class TeacherLanguagePackEngines(
  val asr: LanguagePackEngine? = null,
  val tts: LanguagePackEngine? = null,
)

/** One language pack to install: the engine tag [languageTag] of [model]. */
data class LanguagePackRequirement(val model: Model, val languageTag: String)

/** The language packs that a language needs in a role, or that it can't be used at all. */
sealed interface LanguagePackRequirements {
  /** An engine that needs a pack doesn't support the language at all. */
  data object Unsupported : LanguagePackRequirements

  /** The language can be used once [packs] are installed. It needs none when [packs] is empty. */
  data class Supported(val packs: List<LanguagePackRequirement>) : LanguagePackRequirements
}

/** Which language a pack is for. */
enum class TeacherLanguageRole {
  /** The native language, which the teacher both hears (ASR) and speaks (TTS). */
  NATIVE,
  /** The language to learn, which the teacher only speaks (TTS). */
  LEARN,
}

/**
 * The language pack status of each language offered by `TeacherPreferencesForm`. Languages missing
 * from a map need no pack for the selected engines, so they're shown as installed.
 */
data class TeacherLanguagePackUiState(
  val nativeStatuses: Map<Language, LanguagePackUiStatus> = emptyMap(),
  val learnStatuses: Map<Language, LanguagePackUiStatus> = emptyMap(),
) {
  /** Returns the language pack status of [language] in [role]. */
  fun statusOf(role: TeacherLanguageRole, language: Language): LanguagePackUiStatus {
    val statuses =
      when (role) {
        TeacherLanguageRole.NATIVE -> nativeStatuses
        TeacherLanguageRole.LEARN -> learnStatuses
      }
    return statuses[language] ?: LanguagePackUiStatus.Installed
  }

  /**
   * Whether the user may pick [language] in [role]. A language whose packs aren't installed can't
   * be selected until they are.
   */
  fun isSelectable(role: TeacherLanguageRole, language: Language): Boolean =
    statusOf(role, language) == LanguagePackUiStatus.Installed
}

/**
 * Finds the supplementary models behind the selected engines in [engineLocales] and keeps those
 * that install languages separately.
 *
 * @param installsLanguagesSeparately Whether a model installs each language as a separate pack.
 */
fun resolveLanguagePackEngines(
  supplementaryModels: List<Model>,
  engineLocales: SelectedSpeechEngineLocales,
  installsLanguagesSeparately: (Model) -> Boolean,
): TeacherLanguagePackEngines {
  val asrModelId = engineLocales.asrModelId
  val asrModel = supplementaryModels.firstOrNull {
    asrModelId.isNotEmpty() &&
      it.modelType == ModelType.ASR &&
      (it.modelId == asrModelId || it.name == asrModelId)
  }
  val ttsModelId = engineLocales.ttsModelId
  val ttsModel = supplementaryModels.firstOrNull {
    ttsModelId.isNotEmpty() &&
      it.modelType == ModelType.TTS &&
      (it.ttsProfile?.engineModelId == ttsModelId || it.name == ttsModelId)
  }
  return TeacherLanguagePackEngines(
    asr =
      asrModel?.takeIf(installsLanguagesSeparately)?.let {
        LanguagePackEngine(it, engineLocales.asrLocaleTags)
      },
    tts =
      ttsModel?.takeIf(installsLanguagesSeparately)?.let {
        LanguagePackEngine(it, engineLocales.ttsLocaleTags)
      },
  )
}

/**
 * Returns the packs that [language] needs in [role]: ASR and TTS for the native language, TTS only
 * for the language to learn. Returns [LanguagePackRequirements.Unsupported] when an engine that
 * needs a pack doesn't support [language] at all.
 */
fun languagePackRequirements(
  language: Language,
  role: TeacherLanguageRole,
  engines: TeacherLanguagePackEngines,
): LanguagePackRequirements {
  // TTS language packs are installed per locale.
  val roleEngines =
    when (role) {
      TeacherLanguageRole.NATIVE -> listOfNotNull(engines.asr, engines.tts)
      TeacherLanguageRole.LEARN -> listOfNotNull(engines.tts)
    }
  val packs = roleEngines.map { engine ->
    val languageTag =
      resolveEngineLocaleTagForLanguage(language, engine.supportedLocaleTags)
        ?: return LanguagePackRequirements.Unsupported
    LanguagePackRequirement(engine.model, languageTag)
  }
  return LanguagePackRequirements.Supported(packs)
}

/**
 * Combines the statuses of [requirements] into the one status shown for a language.
 *
 * A language is installed only when all of its packs are. Otherwise the most important status wins:
 * unavailable (it can never be installed), then failed and stalled downloads (so the user can
 * retry), downloading, checking, and downloadable. An unsupported language is unavailable.
 *
 * @param statuses Language pack statuses keyed by model name, then by language tag.
 */
fun combinedLanguagePackStatus(
  requirements: LanguagePackRequirements,
  statuses: Map<String, Map<String, ModelDownloadStatus>>,
): LanguagePackUiStatus {
  val packs =
    when (requirements) {
      LanguagePackRequirements.Unsupported -> return LanguagePackUiStatus.Unavailable
      is LanguagePackRequirements.Supported -> requirements.packs
    }
  val packStatuses = packs.map { requirement ->
    statuses[requirement.model.name]?.get(requirement.languageTag).toLanguagePackUiStatus()
  }
  return packStatuses.firstOrNull { it == LanguagePackUiStatus.Unavailable }
    ?: packStatuses.firstOrNull { it == LanguagePackUiStatus.Failed }
    ?: packStatuses.firstOrNull { it is LanguagePackUiStatus.Downloading && it.stalled }
    ?: packStatuses.firstOrNull { it is LanguagePackUiStatus.Downloading }
    ?: packStatuses.firstOrNull { it == LanguagePackUiStatus.Checking }
    ?: packStatuses.firstOrNull { it == LanguagePackUiStatus.Downloadable }
    ?: LanguagePackUiStatus.Installed
}

/**
 * Builds the language pack status of every offered language. Languages that need no pack are left
 * out, so [TeacherLanguagePackUiState.statusOf] reports them as installed.
 */
fun buildTeacherLanguagePackUiState(
  nativeLanguages: List<Language>,
  learnLanguages: List<Language>,
  engines: TeacherLanguagePackEngines,
  statuses: Map<String, Map<String, ModelDownloadStatus>>,
): TeacherLanguagePackUiState {
  fun statusesFor(languages: List<Language>, role: TeacherLanguageRole) =
    languages
      .mapNotNull { language ->
        val requirements = languagePackRequirements(language, role, engines)
        if (requirements is LanguagePackRequirements.Supported && requirements.packs.isEmpty()) {
          null
        } else {
          language to combinedLanguagePackStatus(requirements, statuses)
        }
      }
      .toMap()
  return TeacherLanguagePackUiState(
    nativeStatuses = statusesFor(nativeLanguages, TeacherLanguageRole.NATIVE),
    learnStatuses = statusesFor(learnLanguages, TeacherLanguageRole.LEARN),
  )
}

/**
 * Returns the packs of [language] in [role] to download: those that are downloadable or whose last
 * download failed or stalled. Packs that are installed, downloading with progress in the app, or
 * still being checked are skipped.
 */
fun languagePacksToDownload(
  language: Language,
  role: TeacherLanguageRole,
  engines: TeacherLanguagePackEngines,
  statuses: Map<String, Map<String, ModelDownloadStatus>>,
): List<LanguagePackRequirement> =
  when (val requirements = languagePackRequirements(language, role, engines)) {
    LanguagePackRequirements.Unsupported -> emptyList()
    is LanguagePackRequirements.Supported ->
      requirements.packs.filter { requirement ->
        when (
          val status =
            statuses[requirement.model.name]?.get(requirement.languageTag).toLanguagePackUiStatus()
        ) {
          LanguagePackUiStatus.Downloadable,
          LanguagePackUiStatus.Failed -> true
          is LanguagePackUiStatus.Downloading -> status.stalled
          else -> false
        }
      }
  }

/**
 * Returns, for each engine that needs packs, the language tags whose install status the form shows:
 * the native languages for ASR, and the native and learn languages for TTS.
 */
fun languagePackTagsToRefresh(
  nativeLanguages: List<Language>,
  learnLanguages: List<Language>,
  engines: TeacherLanguagePackEngines,
): List<Pair<Model, List<String>>> {
  fun tagsFor(engine: LanguagePackEngine, languages: List<Language>): List<String> =
    languages
      .mapNotNull { resolveEngineLocaleTagForLanguage(it, engine.supportedLocaleTags) }
      .distinct()
  return listOfNotNull(
    engines.asr?.let { it.model to tagsFor(it, nativeLanguages) },
    engines.tts?.let { it.model to tagsFor(it, nativeLanguages + learnLanguages) },
  )
}
