/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.translation

import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class TranslationViewModel
@Inject
constructor(
  private val systemPromptRepository: SystemPromptRepository,
  private val translationUserDataStore: DataStore<UserData>,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
) : LlmChatViewModelBase(
    systemPromptRepository = systemPromptRepository,
    userDataDataStore = translationUserDataStore,
    modelFeedbackRepository = null,
    runtimeExecutor = runtimeExecutor,
  ) {
  private val _targetLanguage = MutableStateFlow<TranslationLanguage?>(null)
  val targetLanguage = _targetLanguage.asStateFlow()
  private val _textInputEnabled = MutableStateFlow(false)
  val textInputEnabled = _textInputEnabled.asStateFlow()
  private val _ttsModel = MutableStateFlow(TranslationTtsModel.DEFAULT)
  internal val ttsModel = _ttsModel.asStateFlow()

  init {
    viewModelScope.launch {
      translationUserDataStore.data.collectLatest { userData ->
        _textInputEnabled.value = userData.translationTextInputEnabled
        _ttsModel.value = TranslationTtsModel.fromStoredValue(userData.translationTtsModel)
      }
    }
  }

  suspend fun loadTargetLanguage(task: Task) {
    if (_targetLanguage.value != null) {
      return
    }

    val userData = translationUserDataStore.data.first()
    val storedLanguage =
      userData.translationTargetLanguage
        .takeIf { it.isNotBlank() }
        ?.let { value ->
          TranslationLanguage.entries.firstOrNull { language ->
            language.name.equals(value, ignoreCase = true)
          }
        }
    val legacyPrompt = systemPromptRepository.getCustomSystemPrompt(task.id).first()
    val language =
      storedLanguage ?: languageFromLegacyPrompt(legacyPrompt) ?: TranslationLanguage.SPANISH

    persistTargetLanguage(language)
    systemPromptRepository.clearCustomSystemPrompt(task.id)
    setUISystemPrompt(buildTranslationSystemPrompt(language))
    _targetLanguage.value = language
  }

  fun setTargetLanguage(task: Task, language: TranslationLanguage) {
    if (_targetLanguage.value == language) {
      return
    }

    _targetLanguage.value = language
    setUISystemPrompt(buildTranslationSystemPrompt(language))
    viewModelScope.launch {
      persistTargetLanguage(language)
      systemPromptRepository.clearCustomSystemPrompt(task.id)
    }
  }

  internal fun setTtsModel(model: TranslationTtsModel) {
    if (_ttsModel.value == model) return
    _ttsModel.value = model
    viewModelScope.launch {
      translationUserDataStore.updateData { userData ->
        userData.toBuilder().setTranslationTtsModel(model.name).build()
      }
    }
  }

  private suspend fun persistTargetLanguage(language: TranslationLanguage) {
    translationUserDataStore.updateData { userData ->
      userData.toBuilder().setTranslationTargetLanguage(language.name).build()
    }
  }

  private fun languageFromLegacyPrompt(prompt: String?): TranslationLanguage? {
    if (prompt.isNullOrBlank()) {
      return null
    }

    TranslationLanguage.entries.firstOrNull { language ->
      prompt.trim().equals(buildTranslationSystemPrompt(language), ignoreCase = true)
    }?.let { language -> return language }

    val mentionedLanguages =
      TranslationLanguage.entries.filter { language ->
        prompt.contains(language.label, ignoreCase = true)
      }
    return mentionedLanguages.singleOrNull()
  }
}
