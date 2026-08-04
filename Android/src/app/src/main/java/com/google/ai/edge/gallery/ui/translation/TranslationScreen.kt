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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

enum class TranslationLanguage(
  val label: String,
  val ttsLanguageTag: String,
) {
  SPANISH("Spanish", "es"),
  ENGLISH("English", "en-us"),
  FRENCH("French", "fr-fr"),
  ITALIAN("Italian", "it"),
}

private class TranslationTtsStreamState {
  val chunker = TranslationTtsChunker()
  var sessionId: Long? = null
  var modelName: String? = null
  var languageTag: String? = null
  var language: TranslationLanguage? = null
  var queuedChunkCount: Int = 0

  fun begin(
    sessionId: Long,
    modelName: String,
    languageTag: String,
    language: TranslationLanguage,
  ) {
    reset()
    this.sessionId = sessionId
    this.modelName = modelName
    this.languageTag = languageTag
    this.language = language
  }

  fun reset() {
    chunker.reset()
    sessionId = null
    modelName = null
    languageTag = null
    language = null
    queuedChunkCount = 0
  }
}

fun buildTranslationSystemPrompt(language: TranslationLanguage): String {
  return "Translate into ${language.label}. Output only the translation."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: TranslationViewModel,
) {
  val selectedLanguageState by viewModel.targetLanguage.collectAsState()
  val textInputEnabled by viewModel.textInputEnabled.collectAsState()
  val selectedTtsModel by viewModel.ttsModel.collectAsState()
  val translationUiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val baseTask = modelManagerViewModel.getTaskById(id = BuiltInTaskId.LLM_TRANSLATION)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var showTtsModelManager by rememberSaveable { mutableStateOf(false) }

  if (showTtsModelManager) {
    TranslationTtsModelManager(
      selectedModel = selectedTtsModel,
      onModelSelected = viewModel::setTtsModel,
      navigateUp = { showTtsModelManager = false },
      modifier = modifier,
    )
    return
  }

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
  val sherpaTtsEnabled = TranslationTtsBackendFlags.sherpaEnabled
  val translationSpeaking by translationTtsPlayer.isSpeaking.collectAsState()
  val ttsStreamState = remember { TranslationTtsStreamState() }
  var pendingTtsDownloadLanguage by remember { mutableStateOf<TranslationLanguage?>(null) }
  var dismissedTtsDownloadKey by remember { mutableStateOf<String?>(null) }
  var ttsDownloadInProgress by remember { mutableStateOf(false) }
  var ttsDownloadError by remember { mutableStateOf<String?>(null) }
  var ttsDownloadProgress by
    remember { mutableStateOf<TranslationTtsDownloadProgress?>(null) }
  var selectedTtsPackageInstalled by remember { mutableStateOf<Boolean?>(null) }

  DisposableEffect(translationTtsPlayer) {
    onDispose {
      translationTtsPlayer.release()
    }
  }

  LaunchedEffect(context, selectedLanguage, selectedTtsModel) {
    val installed =
      if (sherpaTtsEnabled) {
        withContext(Dispatchers.IO) {
          TranslationTtsModelRepository.isInstalled(context.applicationContext, selectedTtsModel)
        }
      } else {
        false
      }
    selectedTtsPackageInstalled = installed
    Log.i(
      TAG,
      "backend=${selectedTtsModel.backendId} revision=${selectedTtsModel.revision} " +
        "language=${selectedLanguage.ttsLanguageTag} " +
        "outcome=${if (installed) "package_ready" else "package_missing"}",
    )
  }

  LaunchedEffect(
    selectedLanguage,
    selectedTtsPackageInstalled,
    dismissedTtsDownloadKey,
    ttsDownloadInProgress,
    selectedTtsModel,
  ) {
    val downloadKey = selectedTtsModel.revision
    if (
      sherpaTtsEnabled &&
        selectedTtsPackageInstalled == false &&
        dismissedTtsDownloadKey != downloadKey &&
        !ttsDownloadInProgress
    ) {
      ttsDownloadError = null
      pendingTtsDownloadLanguage = selectedLanguage
    } else if (!ttsDownloadInProgress) {
      pendingTtsDownloadLanguage = null
    }
  }

  pendingTtsDownloadLanguage?.let { language ->
    val downloadKey = selectedTtsModel.revision
    AlertDialog(
      onDismissRequest = {
        dismissedTtsDownloadKey = downloadKey
        pendingTtsDownloadLanguage = null
        ttsDownloadError = null
        if (!ttsDownloadInProgress) {
          ttsDownloadProgress = null
        }
      },
      title = { Text(stringResource(R.string.translation_tts_download_dialog_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            stringResource(
              R.string.translation_tts_download_dialog_content,
              selectedTtsModel.displayName,
            )
          )
          if (ttsDownloadInProgress) {
            val progress = ttsDownloadProgress
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.translation_tts_download_dialog_downloading))
              }
              if (progress != null) {
                progress.fraction?.let { fraction ->
                  LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                  )
                } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                  text = progress.currentFileName,
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text =
                    if (progress.totalBytes > 0L) {
                      stringResource(
                        R.string.translation_tts_download_dialog_byte_stats,
                        formatDownloadBytes(progress.downloadedBytes),
                        formatDownloadBytes(progress.totalBytes),
                      )
                    } else {
                      stringResource(
                        R.string.translation_tts_download_dialog_downloaded_stats,
                        formatDownloadBytes(progress.downloadedBytes),
                      )
                    },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text =
                    stringResource(
                      R.string.translation_tts_download_dialog_file_stats,
                      progress.completedFiles,
                      progress.totalFiles,
                    ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
          ttsDownloadError?.let { error ->
            Text(text = error, color = MaterialTheme.colorScheme.error)
          }
        }
      },
      confirmButton = {
        Button(
          enabled = !ttsDownloadInProgress,
          onClick = {
            scope.launch {
              ttsDownloadInProgress = true
              ttsDownloadError = null
              ttsDownloadProgress = null
              try {
                TranslationTtsModelRepository.ensureInstalled(
                  context = context.applicationContext,
                  model = selectedTtsModel,
                  onProgress = { progress ->
                    scope.launch {
                      ttsDownloadProgress = progress
                    }
                  },
                )
                if (selectedLanguage == language) {
                  selectedTtsPackageInstalled = true
                }
                pendingTtsDownloadLanguage = null
                ttsDownloadProgress = null
                try {
                  translationTtsPlayer.preload()
                } catch (exception: CancellationException) {
                  throw exception
                } catch (exception: Exception) {
                  Log.w(
                    TAG,
                    "backend=${selectedTtsModel.backendId} " +
                      "revision=${selectedTtsModel.revision} " +
                      "language=${language.ttsLanguageTag} outcome=preload_failed",
                  )
                }
              } catch (exception: CancellationException) {
                throw exception
              } catch (exception: Exception) {
                Log.w(
                  TAG,
                  "backend=${selectedTtsModel.backendId} " +
                    "revision=${selectedTtsModel.revision} " +
                    "language=${language.ttsLanguageTag} outcome=download_failed",
                )
                ttsDownloadError = exception.message ?: "Download failed."
              } finally {
                ttsDownloadInProgress = false
              }
            }
          },
        ) {
          Text(stringResource(R.string.download))
        }
      },
      dismissButton = {
        TextButton(
          enabled = true,
          onClick = {
            dismissedTtsDownloadKey = downloadKey
            pendingTtsDownloadLanguage = null
            ttsDownloadError = null
            ttsDownloadProgress = null
          },
        ) {
          Text(stringResource(R.string.not_now))
        }
      },
    )
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
    emptyStateComposable = {
      Box(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier =
            Modifier.align(Alignment.Center).padding(horizontal = 48.dp).padding(bottom = 48.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          LanguagePickerChip(
            selectedLanguage = selectedLanguage,
            onLanguageSelected = onLanguageSelected,
          )
          TtsModelManagerChip(
            selectedModel = selectedTtsModel,
            onClick = { showTtsModelManager = true },
          )
          Spacer(modifier = Modifier.height(8.dp))
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
    },
    composableBelowMessageList = {
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LanguagePickerChip(
          selectedLanguage = selectedLanguage,
          onLanguageSelected = onLanguageSelected,
        )
        TtsModelManagerChip(
          selectedModel = selectedTtsModel,
          onClick = { showTtsModelManager = true },
        )
      }
    },
    onGenerateResponsePartial = { model, partialResult, done ->
      scope.launch {
        val language = selectedLanguage
        val languageTag = language.ttsLanguageTag
        if (sherpaTtsEnabled && selectedTtsPackageInstalled != true) {
          return@launch
        }

        var sessionId = ttsStreamState.sessionId
        if (
          sessionId == null ||
            ttsStreamState.modelName != model.name ||
            ttsStreamState.languageTag != languageTag
        ) {
          sessionId = translationTtsPlayer.startStreaming(languageTag = languageTag)
          ttsStreamState.begin(
            sessionId = sessionId,
            modelName = model.name,
            languageTag = languageTag,
            language = language,
          )
        }

        val chunks = ttsStreamState.chunker.append(partialText = partialResult, flush = done)
        for (chunk in chunks) {
          val cleanedChunk = cleanTranslationForTts(text = chunk, language = language)
          if (
            cleanedChunk.isNotEmpty() &&
              translationTtsPlayer.enqueueStreaming(sessionId = sessionId, text = cleanedChunk)
          ) {
            ttsStreamState.queuedChunkCount++
          }
        }
      }
    },
    onGenerateResponseDone = { model ->
      scope.launch {
        val translatedMessage = viewModel.getLastMessage(model) as? ChatMessageText
        val translatedText = translatedMessage?.content?.trim().orEmpty()
        Log.i(
          TAG,
          "Translation generation done: model=${model.name}, " +
            "side=${translatedMessage?.side}, length=${translatedText.length}",
        )
        if (translatedMessage?.side == ChatSide.AGENT && translatedText.isNotEmpty()) {
          if (sherpaTtsEnabled && selectedTtsPackageInstalled != true) {
            Log.i(
              TAG,
              "backend=${selectedTtsModel.backendId} revision=${selectedTtsModel.revision} " +
                "language=${selectedLanguage.ttsLanguageTag} outcome=fallback_started_no_package",
            )
            ttsStreamState.reset()
          }
          val streamMatchesModel = ttsStreamState.modelName == model.name
          val language =
            if (streamMatchesModel) ttsStreamState.language ?: selectedLanguage
            else selectedLanguage
          val ttsText = cleanTranslationForTts(translatedText, language)
          val languageTag = language.ttsLanguageTag

          val activeSessionId =
            ttsStreamState.sessionId.takeIf {
              streamMatchesModel && ttsStreamState.languageTag == languageTag
            }
          if (activeSessionId != null) {
            for (chunk in ttsStreamState.chunker.flush()) {
              val cleanedChunk = cleanTranslationForTts(text = chunk, language = language)
              if (
                cleanedChunk.isNotEmpty() &&
                  translationTtsPlayer.enqueueStreaming(
                    sessionId = activeSessionId,
                    text = cleanedChunk,
                  )
              ) {
                ttsStreamState.queuedChunkCount++
              }
            }

            val queuedChunkCount = ttsStreamState.queuedChunkCount
            ttsStreamState.reset()
            val streamingResult =
              translationTtsPlayer.finishStreaming(sessionId = activeSessionId)
            Log.i(
              TAG,
              "backend=${selectedTtsModel.backendId} revision=${selectedTtsModel.revision} " +
                "language=$languageTag outcome=" +
                when {
                  streamingResult.cancelled -> "playback_cancelled"
                  streamingResult.error != null -> "playback_failed"
                  else -> "playback_completed"
                },
            )
            if (streamingResult.cancelled) {
              return@launch
            }
            if (streamingResult.error == null && queuedChunkCount > 0) {
              return@launch
            }
            if (streamingResult.playedChunkCount > 0) {
              Log.w(
                TAG,
                "backend=${selectedTtsModel.backendId} revision=${selectedTtsModel.revision} " +
                  "language=$languageTag outcome=playback_partial",
              )
              return@launch
            }
          } else {
            ttsStreamState.reset()
          }

          Log.i(
            TAG,
            "backend=${selectedTtsModel.backendId} revision=${selectedTtsModel.revision} " +
              "language=$languageTag outcome=fallback_started",
          )
          try {
            translationTtsPlayer.speak(
              text = ttsText,
              languageTag = languageTag,
              preferSherpa = sherpaTtsEnabled && selectedTtsPackageInstalled == true,
            )
          } catch (exception: CancellationException) {
            throw exception
          } catch (exception: Exception) {
            Log.w(
              TAG,
              "backend=${selectedTtsModel.backendId} revision=${selectedTtsModel.revision} " +
                "language=$languageTag outcome=playback_failed",
            )
          }
        }
      }
    },
    voiceInputOnly = !textInputEnabled,
    voiceInputProcessingStatusText = voiceInputProcessingStatusText,
    retainModelOnNavigateUp = true,
  )
}

private fun cleanTranslationForTts(text: String, language: TranslationLanguage): String {
  val normalized =
    text
      .replace('\u201c', '"')
      .replace('\u201d', '"')
      .replace('\u2018', '\'')
      .replace('\u2019', '\'')

  val unfenced =
    Regex("(?s)```(?:\\w+)?\\s*(.*?)\\s*```")
      .replace(normalized) { match -> match.groupValues[1] }

  val contentLines = mutableListOf<String>()
  var discardedArtifact = false
  val candidateLines =
    unfenced
    .lines()
    .map { line -> line.trim() }
    .filter { line -> line.isNotEmpty() }

  for (line in candidateLines) {
    val cleanedLine = stripTranslationPrefix(stripListMarker(line), language)
    when {
      cleanedLine.isBlank() -> discardedArtifact = true
      isTranslationCommentary(cleanedLine) && contentLines.isNotEmpty() -> {
        discardedArtifact = true
        break
      }
      isTranslationCommentary(cleanedLine) -> discardedArtifact = true
      isTranslationPreamble(cleanedLine) -> discardedArtifact = true
      else -> contentLines.add(cleanedLine)
    }
  }

  val cleaned =
    stripWrappingQuotes(contentLines.joinToString(" "))
      .replace(Regex("\\s+"), " ")
      .trim()

  return when {
    cleaned.isNotBlank() -> cleaned
    discardedArtifact -> ""
    else -> text.trim()
  }
}

private fun stripListMarker(text: String): String {
  return text.replace(Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+)"), "").trim()
}

private fun stripTranslationPrefix(text: String, language: TranslationLanguage): String {
  val prefixes =
    listOf(
      "translation",
      "translated text",
      "translated ${language.label}",
      "${language.label} translation",
      language.label,
      "answer",
      "output",
    )
  var result = text
  result =
    result.replace(
      Regex(
        "^(?:sure,?\\s+)?(?:here is|here's)\\s+(?:(?:the|your)\\s+)?" +
          "(?:${Regex.escape(language.label)}\\s+)?translation\\s*[:\\-]\\s*",
        RegexOption.IGNORE_CASE,
      ),
      "",
    )
  prefixes.forEach { prefix ->
    result =
      result.replace(
        Regex("^${Regex.escape(prefix)}\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE),
        "",
      )
  }
  return result.trim()
}

private fun stripWrappingQuotes(text: String): String {
  var result = text.trim()
  while (result.length >= 2) {
    val first = result.first()
    val last = result.last()
    val isWrapped =
      (first == '"' && last == '"') ||
        (first == '\'' && last == '\'') ||
        (first == '`' && last == '`')
    if (!isWrapped) {
      break
    }
    result = result.substring(1, result.length - 1).trim()
  }
  return result
}

private fun isTranslationPreamble(text: String): Boolean {
  val lower = text.lowercase()
  return lower == "here is the translation" ||
    lower == "here's the translation" ||
    lower == "sure" ||
    lower == "sure:"
}

private fun isTranslationCommentary(text: String): Boolean {
  val lower = text.lowercase()
  return lower.startsWith("note:") ||
    lower.startsWith("notes:") ||
    lower.startsWith("explanation:") ||
    lower.startsWith("literal translation:") ||
    lower.startsWith("transliteration:") ||
    lower.startsWith("pronunciation:")
}

private fun formatDownloadBytes(bytes: Long): String {
  val units = listOf("B", "KB", "MB", "GB")
  var value = bytes.toDouble()
  var unitIndex = 0
  while (value >= 1024.0 && unitIndex < units.lastIndex) {
    value /= 1024.0
    unitIndex++
  }
  return if (unitIndex == 0) {
    "${bytes} ${units[unitIndex]}"
  } else {
    String.format("%.1f %s", value, units[unitIndex])
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
          "Translate to",
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
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    modifier =
      modifier
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 4.dp),
  ) {
    Icon(
      Icons.Outlined.RecordVoiceOver,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      selectedModel.displayName,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 1,
    )
  }
}
