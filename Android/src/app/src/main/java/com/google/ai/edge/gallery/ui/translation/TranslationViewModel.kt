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

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.agent.AgentRuntimeExecutor
import com.google.ai.edge.gallery.agent.AiChatExecutor
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SystemPromptRepository
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.UserData
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "TranslationViewModel"

@HiltViewModel
class TranslationViewModel
@Inject
internal constructor(
  private val systemPromptRepository: SystemPromptRepository,
  private val translationUserDataStore: DataStore<UserData>,
  @AiChatExecutor runtimeExecutor: AgentRuntimeExecutor,
  private val ttsReadinessChecker: TranslationTtsReadinessChecker,
  private val translationTtsRepository: TranslationTtsModelRepository,
  private val ttsPlayerFactory: TranslationTtsPlayerFactory,
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
  private val _liveSpeechEnabled = MutableStateFlow(true)
  val liveSpeechEnabled = _liveSpeechEnabled.asStateFlow()
  private val _ttsModel = MutableStateFlow(TranslationTtsModel.DEFAULT)
  internal val ttsModel = _ttsModel.asStateFlow()
  private val _ttsReadiness =
    MutableStateFlow(
      TranslationTtsReadiness(
        model = TranslationTtsModel.DEFAULT,
        isReady = true,
        preferSherpa = false,
      )
    )
  internal val ttsReadiness = _ttsReadiness.asStateFlow()
  private val _ttsInstallUiState =
    MutableStateFlow<TranslationTtsInstallUiState>(TranslationTtsInstallUiState.Idle)
  internal val ttsInstallUiState = _ttsInstallUiState.asStateFlow()
  private val _ttsSpeaking = MutableStateFlow(false)
  internal val ttsSpeaking = _ttsSpeaking.asStateFlow()
  private val ttsStreamState = TranslationTtsStreamState()
  private var ttsPlayer: TranslationTtsPlayer? = null
  private var ttsPlayerModel: TranslationTtsModel? = null
  private var ttsSpeakingJob: Job? = null
  private var ttsSessionScope: CoroutineScope? = null

  init {
    viewModelScope.launch {
      translationUserDataStore.data.collectLatest { userData ->
        _textInputEnabled.value = userData.translationTextInputEnabled
        _liveSpeechEnabled.value = !userData.translationTtsWaitForCompletion
        _ttsModel.value = TranslationTtsModel.fromStoredValue(userData.translationTtsModel)
      }
    }
    viewModelScope.launch {
      _ttsModel.collectLatest(::updateTtsReadiness)
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

  fun selectTargetLanguage(
    task: Task,
    model: Model,
    language: TranslationLanguage,
    onApplied: (String) -> Unit = {},
  ) {
    if (_targetLanguage.value == language) {
      return
    }

    stopTtsPlayback()
    val systemPrompt = buildTranslationSystemPrompt(language)
    _targetLanguage.value = language
    setUISystemPrompt(systemPrompt)
    viewModelScope.launch {
      persistTargetLanguage(language)
      systemPromptRepository.clearCustomSystemPrompt(task.id)
    }
    resetSession(
      task = task,
      model = model,
      systemInstruction = systemPrompt,
      supportImage = false,
      supportAudio = false,
      onDone = {
        addMessage(
          model = model,
          message = ChatMessageInfo(content = "Translating to ${language.label}"),
        )
        onApplied(systemPrompt)
      },
    )
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

  internal fun activateTts() {
    if (ttsSessionScope != null) return
    ttsSessionScope =
      CoroutineScope(
        SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate
      )
    ensureTtsPlayer()
  }

  internal fun deactivateTts() {
    ttsSessionScope?.cancel()
    ttsSessionScope = null
    releaseTtsPlayer()
  }

  internal fun stopTtsPlayback() {
    ttsPlayer?.stop()
    ttsStreamState.reset()
  }

  internal fun handleTranslationResponsePartial(
    modelName: String,
    partialResult: String,
    done: Boolean,
    language: TranslationLanguage,
  ) {
    ttsSessionScope?.launch {
      val selectedTtsModel = _ttsModel.value
      val readiness = _ttsReadiness.value
      val ttsReady = readiness.model == selectedTtsModel && readiness.isReady
      if (!_liveSpeechEnabled.value || !ttsReady) return@launch

      val player = ensureTtsPlayer()
      var sessionId = ttsStreamState.sessionId
      if (
        sessionId == null ||
          ttsStreamState.modelName != modelName ||
          ttsStreamState.language != language
      ) {
        sessionId = player.startStreaming(languageTag = language.ttsLanguageTag)
        ttsStreamState.begin(
          sessionId = sessionId,
          modelName = modelName,
          language = language,
        )
      }

      val chunks = ttsStreamState.chunker.append(partialText = partialResult, flush = done)
      for (chunk in chunks) {
        if (player.enqueueStreaming(sessionId = sessionId, text = chunk)) {
          ttsStreamState.queuedChunkCount++
        }
      }
    }
  }

  internal fun handleTranslationResponseDone(model: Model, selectedLanguage: TranslationLanguage) {
    ttsSessionScope?.launch {
      val translatedMessage = getLastMessage(model) as? ChatMessageText
      val translatedText = translatedMessage?.content?.trim().orEmpty()
      if (translatedMessage?.side != ChatSide.AGENT || translatedText.isEmpty()) return@launch

      val selectedTtsModel = _ttsModel.value
      val readiness = _ttsReadiness.value
      val ttsReady = readiness.model == selectedTtsModel && readiness.isReady
      if (!ttsReady) ttsStreamState.reset()

      val player = ensureTtsPlayer()
      val streamMatchesModel = ttsStreamState.modelName == model.name
      val language =
        if (streamMatchesModel) ttsStreamState.language ?: selectedLanguage else selectedLanguage
      val languageTag = language.ttsLanguageTag

      val activeSessionId =
        if (!_liveSpeechEnabled.value && ttsReady) {
          val completedSessionId = player.startStreaming(languageTag = languageTag)
          ttsStreamState.begin(
            sessionId = completedSessionId,
            modelName = model.name,
            language = language,
          )
          val completedChunks =
            ttsStreamState.chunker.append(partialText = translatedText, flush = true)
          for (chunk in completedChunks) {
            if (player.enqueueStreaming(sessionId = completedSessionId, text = chunk)) {
              ttsStreamState.queuedChunkCount++
            }
          }
          completedSessionId
        } else {
          ttsStreamState.sessionId.takeIf { streamMatchesModel }
        }

      if (activeSessionId != null) {
        for (chunk in ttsStreamState.chunker.flush()) {
          if (player.enqueueStreaming(sessionId = activeSessionId, text = chunk)) {
            ttsStreamState.queuedChunkCount++
          }
        }

        val queuedChunkCount = ttsStreamState.queuedChunkCount
        ttsStreamState.reset()
        val streamingResult = player.finishStreaming(sessionId = activeSessionId)
        if (streamingResult.cancelled) return@launch
        if (streamingResult.error == null && queuedChunkCount > 0) return@launch
        if (streamingResult.playedChunkCount > 0) return@launch
      } else {
        ttsStreamState.reset()
      }

      try {
        player.speak(
          text = translatedText,
          languageTag = languageTag,
          preferSherpa =
            readiness.model == selectedTtsModel && readiness.preferSherpa,
        )
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        Log.w(TAG, "Translation speech playback failed.", exception)
      }
    }
  }

  internal fun downloadTtsModel(model: TranslationTtsModel) {
    if (model == TranslationTtsModel.SYSTEM) return
    if (_ttsInstallUiState.value is TranslationTtsInstallUiState.Downloading) return

    viewModelScope.launch {
      _ttsInstallUiState.value = TranslationTtsInstallUiState.Downloading(model)
      try {
        translationTtsRepository.ensureInstalled(
          model = model,
          onProgress = { progress ->
            _ttsInstallUiState.value =
              TranslationTtsInstallUiState.Downloading(model = model, progress = progress)
          },
        )
        _ttsInstallUiState.value = TranslationTtsInstallUiState.Installed(model)
        setTtsModel(model)
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        Log.e(TAG, "TTS model download failed: ${model.name}", exception)
        _ttsInstallUiState.value =
          TranslationTtsInstallUiState.Failed(
            model = model,
            message = exception.message ?: "Download failed.",
          )
      }
    }
  }

  internal suspend fun getTtsModelInstallationStatuses(): Map<TranslationTtsModel, Boolean> =
    TranslationTtsModel.entries.associateWith { model ->
      model == TranslationTtsModel.SYSTEM || translationTtsRepository.isInstalled(model)
    }

  internal suspend fun deleteTtsModel(model: TranslationTtsModel): Boolean =
    translationTtsRepository.deleteInstalled(model)

  private suspend fun persistTargetLanguage(language: TranslationLanguage) {
    translationUserDataStore.updateData { userData ->
      userData.toBuilder().setTranslationTargetLanguage(language.name).build()
    }
  }

  private suspend fun updateTtsReadiness(model: TranslationTtsModel) {
    if (ttsSessionScope != null) replaceTtsPlayer(model)
    _ttsReadiness.value =
      TranslationTtsReadiness(model = model, isReady = false, preferSherpa = false)
    val readiness =
      try {
        ttsReadinessChecker.check(model)
      } catch (exception: CancellationException) {
        throw exception
      } catch (exception: Exception) {
        Log.e(TAG, "Failed to check TTS model readiness: ${model.name}", exception)
        TranslationTtsReadiness(model = model, isReady = false, preferSherpa = false)
      }

    if (_ttsModel.value != model) return
    _ttsReadiness.value = readiness
    if (!readiness.isReady) {
      setTtsModel(TranslationTtsModel.SYSTEM)
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

  private fun ensureTtsPlayer(): TranslationTtsPlayer {
    val model = _ttsModel.value
    if (ttsPlayer == null || ttsPlayerModel != model) replaceTtsPlayer(model)
    return checkNotNull(ttsPlayer)
  }

  private fun replaceTtsPlayer(model: TranslationTtsModel) {
    if (ttsPlayer != null && ttsPlayerModel == model) return
    releaseTtsPlayer()
    val player = ttsPlayerFactory.create(model)
    ttsPlayer = player
    ttsPlayerModel = model
    ttsSpeakingJob =
      viewModelScope.launch {
        player.isSpeaking.collect { speaking ->
          if (ttsPlayer === player) _ttsSpeaking.value = speaking
        }
      }
  }

  private fun releaseTtsPlayer() {
    ttsSpeakingJob?.cancel()
    ttsSpeakingJob = null
    ttsPlayer?.release()
    ttsPlayer = null
    ttsPlayerModel = null
    ttsStreamState.reset()
    _ttsSpeaking.value = false
  }

  override fun onCleared() {
    deactivateTts()
    super.onCleared()
  }
}

private class TranslationTtsStreamState {
  val chunker = TranslationTtsChunker()
  var sessionId: Long? = null
  var modelName: String? = null
  var language: TranslationLanguage? = null
  var queuedChunkCount: Int = 0

  fun begin(sessionId: Long, modelName: String, language: TranslationLanguage) {
    reset()
    this.sessionId = sessionId
    this.modelName = modelName
    this.language = language
  }

  fun reset() {
    chunker.reset()
    sessionId = null
    modelName = null
    language = null
    queuedChunkCount = 0
  }
}

internal sealed interface TranslationTtsInstallUiState {
  data object Idle : TranslationTtsInstallUiState

  data class Downloading(
    val model: TranslationTtsModel,
    val progress: TranslationTtsDownloadProgress? = null,
  ) : TranslationTtsInstallUiState

  data class Installed(val model: TranslationTtsModel) : TranslationTtsInstallUiState

  data class Failed(
    val model: TranslationTtsModel,
    val message: String,
  ) : TranslationTtsInstallUiState
}
