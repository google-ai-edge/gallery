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

package com.google.ai.edge.gallery.ui.modelmanager

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.TtsVoice
import com.google.ai.edge.gallery.data.supplementary.LanguageState
import com.google.ai.edge.gallery.data.supplementary.SpeechModelState
import com.google.ai.edge.gallery.data.supplementary.SpeechSelection
import com.google.ai.edge.gallery.data.supplementary.SpeechSelectionUseCase
import com.google.ai.edge.gallery.data.supplementary.SupplementarySpeechModelRepository
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.services.tts.TtsSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "AGSupplementaryModelsVM"

/**
 * ViewModel of the Model Manager's Supplementary tab.
 *
 * Turns the [SupplementarySpeechModelRepository]'s model and language states into card UI states,
 * pre-warms availability and language packs in the background when the screen opens so that
 * expanding a card's language list reads from the cache, and owns the tab's modality filter, which
 * is saved in DataStore. Default engines and languages are read and changed through the
 * [SpeechSelectionUseCase], so only ready models can become the default.
 */
@HiltViewModel
class SupplementaryModelsViewModel
@Inject
constructor(
  private val repository: SupplementarySpeechModelRepository,
  private val speechSelectionUseCase: SpeechSelectionUseCase,
  private val ttsSettingsRepository: TtsSettingsRepository,
  private val dataStoreRepository: DataStoreRepository,
  @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

  private val filter = MutableStateFlow<SupplementaryModelFilter?>(null)

  private val selection =
    combine(
      speechSelectionUseCase.selection,
      // Refresh when an engine's voice list loads.
      ttsSettingsRepository.warmingEngineIds,
    ) { savedSelection, _ ->
      savedSelection
    }

  /** The Supplementary tab's filter and cards. */
  val uiState: StateFlow<SupplementaryModelsUiState> =
    combine(
        repository.models,
        repository.modelStates,
        repository.languageStates,
        filter,
        selection,
      ) { models, modelStates, languageStates, currentFilter, currentSelection ->
        buildUiState(
          models = models,
          modelStates = modelStates,
          languageStates = languageStates,
          currentFilter = currentFilter ?: SupplementaryModelFilter.ALL,
          currentSelection = currentSelection,
        )
      }
      .flowOn(ioDispatcher)
      .stateIn(viewModelScope, SharingStarted.Eagerly, SupplementaryModelsUiState())

  init {
    viewModelScope.launch(ioDispatcher) {
      try {
        val saved = dataStoreRepository.readSupplementaryModelFilter().toFilter()
        filter.compareAndSet(null, saved)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to read the saved Supplementary tab filter", e)
      }
    }
    // Models registered while the allowlist loaded are already warming up; this catches any whose
    // cached answers expired since. Cached answers return without a Speech Services call.
    repository.prewarm()
  }

  private fun buildUiState(
    models: List<Model>,
    modelStates: Map<String, SpeechModelState>,
    languageStates: Map<String, Map<String, LanguageState>>,
    currentFilter: SupplementaryModelFilter,
    currentSelection: SpeechSelection,
  ): SupplementaryModelsUiState {
    val cards = models.mapNotNull { model ->
      val state = modelStates[model.name]
      val modelLanguageStates = languageStates[model.name].orEmpty()
      buildSupplementaryCard(
        model = model,
        state = state,
        languageStates = modelLanguageStates,
        languageCodes = repository.supportedLanguageCodes(model),
        installsLanguagesSeparately = repository.installsLanguagePacksSeparately(model),
        defaultChoice =
          speechSelectionUseCase.defaultChoiceFor(
            model = model,
            state = state,
            languageStates = modelLanguageStates,
            selection = currentSelection,
          ),
        voices = voicesOf(model),
      )
    }
    return SupplementaryModelsUiState(
      filter = currentFilter,
      cards = cards.filter { currentFilter.matches(it.model) },
      visibleModelCount = cards.size,
    )
  }

  private fun voicesOf(model: Model): List<TtsVoice> =
    if (model.modelType == ModelType.TTS) ttsSettingsRepository.resolveVoices(model)
    else emptyList()

  /** Shows only the models that match [newFilter] and saves the choice. */
  fun selectFilter(newFilter: SupplementaryModelFilter) {
    filter.value = newFilter
    viewModelScope.launch(ioDispatcher) {
      try {
        dataStoreRepository.saveSupplementaryModelFilter(newFilter.toProto())
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to save the Supplementary tab filter", e)
      }
    }
  }

  /** Re-checks states whose cached answers expired, when the screen resumes. */
  fun refreshOnResume() {
    repository.refreshOnResume()
  }

  /** Checks [model]'s availability again after the user taps Retry. */
  fun retry(model: Model) {
    repository.retryModel(model)
  }

  /** Checks the [languageCode] pack of [model] again after the user taps Retry on its row. */
  fun retryLanguage(model: Model, languageCode: String) {
    repository.retryLanguage(model, languageCode)
  }

  /**
   * Checks the language packs of [model] that have no state yet when its language list expands, for
   * example because the model wasn't ready when the allowlist loaded. Languages the pre-warm
   * already checked aren't queried again; uncached rows show their own loading indicator meanwhile.
   */
  fun onLanguagesExpanded(model: Model) {
    if (!repository.installsLanguagePacksSeparately(model)) {
      return
    }
    val knownCodes = repository.languageStates.value[model.name].orEmpty().keys
    val uncheckedCodes = repository.supportedLanguageCodes(model).filterNot { it in knownCodes }
    if (uncheckedCodes.isEmpty()) {
      return
    }
    viewModelScope.launch(ioDispatcher) {
      try {
        repository.refreshLanguagePacks(model, uncheckedCodes)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to read language packs of '${model.name}'", e)
      }
    }
  }

  /**
   * Makes [model] the default engine for its modality when [isDefault] is `true`, or switches back
   * to the built-in Android engine when `false`. Only ready models show the checkbox, and the
   * [SpeechSelectionUseCase] refuses any other.
   */
  fun setDefault(model: Model, isDefault: Boolean) {
    speechSelectionUseCase.setDefault(model, isDefault)
  }

  /** Picks [languageCode] for [model] while it is the default engine. */
  fun selectLanguage(model: Model, languageCode: String) {
    speechSelectionUseCase.selectLanguage(model, languageCode)
  }
}
