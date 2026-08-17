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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.llmchat.ChatViewWrapper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import kotlinx.coroutines.launch

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
  val selectedTtsModel by viewModel.ttsModel.collectAsStateWithLifecycle()
  val translationUiState by viewModel.uiState.collectAsStateWithLifecycle()
  val translationSpeaking by viewModel.ttsSpeaking.collectAsStateWithLifecycle()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
  val selectedModel = modelManagerUiState.selectedModel
  val baseTask = modelManagerViewModel.getTaskById(id = BuiltInTaskId.LLM_TRANSLATION)
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
  DisposableEffect(viewModel) {
    viewModel.activateTts()
    onDispose { viewModel.deactivateTts() }
  }

  val onLanguageSelected: (TranslationLanguage) -> Unit = { language ->
    task?.let { translationTask ->
      viewModel.selectTargetLanguage(
        task = translationTask,
        model = selectedModel,
        language = language,
        onApplied = { systemPrompt ->
          modelManagerViewModel.updateActiveModelSystemPrompt(
            task = translationTask,
            model = selectedModel,
            systemPrompt = systemPrompt,
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
      viewModel.handleTranslationResponsePartial(
        modelName = model.name,
        partialResult = partialResult,
        done = done,
        language = selectedLanguage,
      )
    },
    onGenerateResponseDone = { model ->
      viewModel.handleTranslationResponseDone(model = model, selectedLanguage = selectedLanguage)
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
