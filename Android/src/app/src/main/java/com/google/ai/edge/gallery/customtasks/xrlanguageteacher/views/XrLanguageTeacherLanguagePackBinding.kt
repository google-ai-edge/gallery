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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SelectedSpeechEngineLocales
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguagePackUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.TeacherLanguageRole
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.buildTeacherLanguagePackUiState
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.languagePackTagsToRefresh
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.languagePacksToDownload
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.resolveLanguagePackEngines
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.downloadLanguagePack
import com.google.ai.edge.gallery.ui.modelmanager.installsLanguagesSeparately
import com.google.ai.edge.gallery.ui.modelmanager.refreshLanguagePackStatuses

/** Language pack state for [TeacherPreferencesForm] and the action that downloads a language. */
class TeacherLanguagePacks(
  val uiState: TeacherLanguagePackUiState,
  val onDownloadLanguagePack: (TeacherLanguageRole, Language) -> Unit,
)

/**
 * Connects [TeacherPreferencesForm]'s language pack controls to [modelManagerViewModel], the same
 * source as the global model manager's "Supported languages" lists.
 *
 * Asks for fresh install statuses of [nativeLanguages] and [learnLanguages] when it enters the
 * composition, so call it only while the form is shown. Which packs each language needs is decided
 * by the pure helpers in `XrLanguageTeacherLanguagePacks.kt`.
 */
@Composable
internal fun rememberTeacherLanguagePacks(
  modelManagerViewModel: ModelManagerViewModel,
  speechEngineLocales: SelectedSpeechEngineLocales,
  nativeLanguages: List<Language>,
  learnLanguages: List<Language>,
): TeacherLanguagePacks {
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
  val engines =
    remember(modelManagerUiState.supplementaryModels, speechEngineLocales) {
      resolveLanguagePackEngines(
        supplementaryModels = modelManagerUiState.supplementaryModels,
        engineLocales = speechEngineLocales,
        installsLanguagesSeparately = modelManagerViewModel::installsLanguagesSeparately,
      )
    }
  val tagsToRefresh =
    remember(engines, nativeLanguages, learnLanguages) {
      languagePackTagsToRefresh(nativeLanguages, learnLanguages, engines)
    }
  LaunchedEffect(tagsToRefresh) {
    for ((model, languageTags) in tagsToRefresh) {
      modelManagerViewModel.refreshLanguagePackStatuses(model, languageTags)
    }
  }
  val languagePackStatus = modelManagerUiState.languagePackStatus
  val uiState =
    remember(engines, nativeLanguages, learnLanguages, languagePackStatus) {
      buildTeacherLanguagePackUiState(nativeLanguages, learnLanguages, engines, languagePackStatus)
    }
  return remember(uiState, engines) {
    TeacherLanguagePacks(
      uiState = uiState,
      onDownloadLanguagePack = { role, language ->
        val packs =
          languagePacksToDownload(
            language = language,
            role = role,
            engines = engines,
            statuses = modelManagerViewModel.uiState.value.languagePackStatus,
          )
        for (pack in packs) {
          modelManagerViewModel.downloadLanguagePack(pack.model, pack.languageTag)
        }
      },
    )
  }
}
