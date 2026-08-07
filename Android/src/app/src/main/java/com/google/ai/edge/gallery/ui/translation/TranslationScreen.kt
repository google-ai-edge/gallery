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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.ChatViewWrapper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGTranslationScreen"

private class TranslationTtsStreamState {
  val chunker = TranslationTtsChunker()
  var sessionId: Long? = null
  var modelName: String? = null
  var language: TranslationLanguage? = null
  var queuedChunkCount: Int = 0

  fun begin(
    sessionId: Long,
    modelName: String,
    language: TranslationLanguage,
  ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScreen(
  modifier: Modifier = Modifier,
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: TranslationViewModel,
  navigateUp: () -> Unit,
  navigateToVoiceModels: () -> Unit,
) {
  val selectedLanguageState by viewModel.targetLanguage.collectAsStateWithLifecycle()
  val textInputEnabled by viewModel.textInputEnabled.collectAsStateWithLifecycle()
  val liveSpeechEnabled by viewModel.liveSpeechEnabled.collectAsStateWithLifecycle()
  val selectedTtsModel by viewModel.ttsModel.collectAsStateWithLifecycle()
  val translationUiState by viewModel.uiState.collectAsStateWithLifecycle()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
  val selectedModel = modelManagerUiState.selectedModel
  val baseTask = modelManagerViewModel.getTaskById(id = BuiltInTaskId.LLM_TRANSLATION)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val selectedLanguage = selectedLanguageState
  if (selectedLanguage == null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  val translationSystemPrompt = buildTranslationSystemPrompt(selectedLanguage)
  val task =
    remember(baseTask, translationSystemPrompt) {
      baseTask?.copy(defaultSystemPrompt = translationSystemPrompt)
    }
  val translationTtsPlayer =
    remember(context, selectedTtsModel) {
      TranslationTtsPlayer(context.applicationContext, selectedTtsModel)
    }
  val sherpaTtsEnabled = BuildConfig.TRANSLATION_TTS_SHERPA_ENABLED
  val translationSpeaking by translationTtsPlayer.isSpeaking.collectAsStateWithLifecycle()
  val ttsStreamState = remember { TranslationTtsStreamState() }
  var selectedTtsPackageInstalled by remember { mutableStateOf<Boolean?>(null) }
  val ttsReady =
    !sherpaTtsEnabled ||
      selectedTtsModel == TranslationTtsModel.SYSTEM ||
      selectedTtsPackageInstalled == true

  DisposableEffect(translationTtsPlayer) {
    onDispose {
      translationTtsPlayer.release()
    }
  }

  LaunchedEffect(context, selectedLanguage, selectedTtsModel) {
    val systemVoiceSelected = selectedTtsModel == TranslationTtsModel.SYSTEM
    val installed =
      if (sherpaTtsEnabled && !systemVoiceSelected) {
        withContext(Dispatchers.IO) {
          TranslationTtsModelRepository.isInstalled(context.applicationContext, selectedTtsModel)
        }
      } else {
        false
      }
    selectedTtsPackageInstalled = installed
    if (sherpaTtsEnabled && !systemVoiceSelected && !installed) {
      viewModel.setTtsModel(TranslationTtsModel.SYSTEM)
    }
  }

  val onLanguageSelected: (TranslationLanguage) -> Unit = { language ->
    if (language != selectedLanguage && task != null) {
      translationTtsPlayer.stop()
      ttsStreamState.reset()
      selectedTtsPackageInstalled = null
      val newPrompt = buildTranslationSystemPrompt(language)
      viewModel.setTargetLanguage(task = task, language = language)
      viewModel.resetSession(
        task = task,
        model = selectedModel,
        systemInstruction = newPrompt,
        supportImage = false,
        supportAudio = false,
        onDone = {
          viewModel.addMessage(
            model = selectedModel,
            message = ChatMessageInfo(content = "Translating to ${language.label}"),
          )
          modelManagerViewModel.updateActiveModelSystemPrompt(
            task = task,
            model = selectedModel,
            systemPrompt = newPrompt,
          )
        },
      )
    }
  }

  val navigateUpRetainingModel = {
    if (task != null && selectedModel.instance != null) {
      viewModel.resetSession(
        task = task,
        model = selectedModel,
        systemInstruction = translationSystemPrompt,
        supportImage = false,
        supportAudio = false,
        clearHistory = false,
        onDone = { scope.launch { navigateUp() } },
      )
    } else {
      navigateUp()
    }
  }
  val translatingStatusText = stringResource(R.string.translating)
  val speakingStatusText = stringResource(R.string.speaking)
  val voiceInputProcessingStatusText =
    when {
      translationSpeaking -> speakingStatusText
      translationUiState.inProgress -> translatingStatusText
      else -> null
    }

  ChatViewWrapper(
    viewModel = viewModel,
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_TRANSLATION,
    navigateUp = navigateUpRetainingModel,
    modifier = modifier,
    curSystemPrompt = translationSystemPrompt,
    taskOverride = task,
    emptyStateComposable = { TranslationEmptyState(textInputEnabled) },
    composableBelowMessageList = {
      TranslationControls(
        selectedLanguage = selectedLanguage,
        selectedTtsModel = selectedTtsModel,
        onLanguageSelected = onLanguageSelected,
        onTtsModelClick = navigateToVoiceModels,
      )
    },
    onGenerateResponsePartial = { model, partialResult, done ->
      scope.launch {
        val language = selectedLanguage
        val languageTag = language.ttsLanguageTag
        if (!liveSpeechEnabled || !ttsReady) {
          return@launch
        }

        var sessionId = ttsStreamState.sessionId
        if (
          sessionId == null ||
            ttsStreamState.modelName != model.name ||
            ttsStreamState.language != language
        ) {
          sessionId = translationTtsPlayer.startStreaming(languageTag = languageTag)
          ttsStreamState.begin(
            sessionId = sessionId,
            modelName = model.name,
            language = language,
          )
        }

        val chunks = ttsStreamState.chunker.append(partialText = partialResult, flush = done)
        for (chunk in chunks) {
          if (translationTtsPlayer.enqueueStreaming(sessionId = sessionId, text = chunk)) {
            ttsStreamState.queuedChunkCount++
          }
        }
      }
    },
    onGenerateResponseDone = { model ->
      scope.launch {
        val translatedMessage = viewModel.getLastMessage(model) as? ChatMessageText
        val translatedText = translatedMessage?.content?.trim().orEmpty()
        if (translatedMessage?.side == ChatSide.AGENT && translatedText.isNotEmpty()) {
          if (!ttsReady) {
            ttsStreamState.reset()
          }
          val streamMatchesModel = ttsStreamState.modelName == model.name
          val language =
            if (streamMatchesModel) ttsStreamState.language ?: selectedLanguage
            else selectedLanguage
          val languageTag = language.ttsLanguageTag

          val activeSessionId =
            if (!liveSpeechEnabled && ttsReady) {
              val completedSessionId =
                translationTtsPlayer.startStreaming(languageTag = languageTag)
              ttsStreamState.begin(
                sessionId = completedSessionId,
                modelName = model.name,
                language = language,
              )
              val completedChunks =
                ttsStreamState.chunker.append(partialText = translatedText, flush = true)
              for (chunk in completedChunks) {
                if (
                  translationTtsPlayer.enqueueStreaming(
                    sessionId = completedSessionId,
                    text = chunk,
                  )
                ) {
                  ttsStreamState.queuedChunkCount++
                }
              }
              completedSessionId
            } else {
              ttsStreamState.sessionId.takeIf { streamMatchesModel }
            }
          if (activeSessionId != null) {
            for (chunk in ttsStreamState.chunker.flush()) {
              if (
                translationTtsPlayer.enqueueStreaming(sessionId = activeSessionId, text = chunk)
              ) {
                ttsStreamState.queuedChunkCount++
              }
            }

            val queuedChunkCount = ttsStreamState.queuedChunkCount
            ttsStreamState.reset()
            val streamingResult =
              translationTtsPlayer.finishStreaming(sessionId = activeSessionId)
            if (streamingResult.cancelled) {
              return@launch
            }
            if (streamingResult.error == null && queuedChunkCount > 0) {
              return@launch
            }
            if (streamingResult.playedChunkCount > 0) {
              return@launch
            }
          } else {
            ttsStreamState.reset()
          }

          try {
            translationTtsPlayer.speak(
              text = translatedText,
              languageTag = languageTag,
              preferSherpa = sherpaTtsEnabled && selectedTtsPackageInstalled == true,
            )
          } catch (exception: CancellationException) {
            throw exception
          } catch (exception: Exception) {
            Log.w(TAG, "Translation speech playback failed.", exception)
          }
        }
      }
    },
    voiceInputOnly = !textInputEnabled,
    voiceInputProcessingStatusText = voiceInputProcessingStatusText,
    retainModelOnNavigateUp = true,
  )
}

@Composable
private fun TranslationEmptyState(textInputEnabled: Boolean) {
  Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier =
        Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.translation_emptystate_title), style = emptyStateTitle)
      Text(
        stringResource(
          if (textInputEnabled) {
            R.string.translation_emptystate_content
          } else {
            R.string.translation_voice_emptystate_content
          }
        ),
        style = emptyStateContent,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun TranslationControls(
  selectedLanguage: TranslationLanguage,
  selectedTtsModel: TranslationTtsModel,
  onLanguageSelected: (TranslationLanguage) -> Unit,
  onTtsModelClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LanguagePickerChip(
      selectedLanguage = selectedLanguage,
      onLanguageSelected = onLanguageSelected,
    )
    TtsModelManagerChip(selectedModel = selectedTtsModel, onClick = onTtsModelClick)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerChip(
  selectedLanguage: TranslationLanguage,
  onLanguageSelected: (TranslationLanguage) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showPicker by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    modifier =
      modifier
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable { showPicker = true }
        .padding(start = 8.dp, end = 2.dp)
        .padding(vertical = 4.dp),
  ) {
    Icon(
      Icons.Outlined.Translate,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      selectedLanguage.label,
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.padding(start = 4.dp),
      maxLines = 1,
    )
    Icon(
      Icons.Rounded.ArrowDropDown,
      modifier = Modifier.size(20.dp),
      contentDescription = null,
    )
  }

  if (showPicker) {
    ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = sheetState) {
      Column(modifier = Modifier.padding(bottom = 32.dp)) {
        Text(
          stringResource(R.string.translation_language_picker_title),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        TranslationLanguage.entries.forEach { language ->
          ListItem(
            headlineContent = { Text(language.label) },
            trailingContent = {
              if (language == selectedLanguage) {
                Icon(
                  Icons.Rounded.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            },
            modifier = Modifier.clickable {
              onLanguageSelected(language)
              showPicker = false
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
          )
        }
      }
    }
  }
}

@Composable
private fun TtsModelManagerChip(
  selectedModel: TranslationTtsModel,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    modifier =
      modifier
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClick = onClick)
        .padding(start = 8.dp, end = 2.dp)
        .padding(vertical = 4.dp),
  ) {
    Icon(
      Icons.Rounded.Mic,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      selectedModel.displayName,
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.padding(start = 4.dp),
      maxLines = 1,
    )
    Icon(
      Icons.Rounded.ArrowDropDown,
      modifier = Modifier.size(20.dp),
      contentDescription = null,
    )
  }
}
