/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.translation

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.GalleryTopAppBar
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.AppBarAction
import com.google.ai.edge.gallery.data.AppBarActionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationTtsModelManager(
  selectedModel: TranslationTtsModel,
  installUiState: TranslationTtsInstallUiState,
  onDownloadRequested: (TranslationTtsModel) -> Unit,
  onModelSelected: (TranslationTtsModel) -> Unit,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val installedModels = remember { mutableStateMapOf<TranslationTtsModel, Boolean>() }
  var deletionError by remember { mutableStateOf<Pair<TranslationTtsModel, String>?>(null) }
  var modelPendingDeletion by remember { mutableStateOf<TranslationTtsModel?>(null) }
  val downloading = installUiState as? TranslationTtsInstallUiState.Downloading
  val downloadingModel = downloading?.model
  val downloadProgress = downloading?.progress
  val downloadFailure = installUiState as? TranslationTtsInstallUiState.Failed

  LaunchedEffect(context) {
    val statuses =
      withContext(Dispatchers.IO) {
        TranslationTtsModel.entries.associateWith { model ->
          model == TranslationTtsModel.SYSTEM ||
            TranslationTtsModelRepository.isInstalled(context, model)
        }
      }
    installedModels.putAll(statuses)
  }
  LaunchedEffect(installUiState) {
    if (installUiState is TranslationTtsInstallUiState.Installed) {
      installedModels[installUiState.model] = true
      deletionError = null
    }
  }
  BackHandler { navigateUp() }

  Scaffold(
    modifier = modifier,
    topBar = {
      GalleryTopAppBar(
        title = stringResource(R.string.translation_voice_models_title),
        leftAction =
          AppBarAction(actionType = AppBarActionType.NAVIGATE_UP, actionFn = navigateUp),
      )
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Text(
          text = stringResource(R.string.translation_voice_models_description),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
      }
      items(TranslationTtsModel.entries, key = { it.name }) { model ->
        val isSystem = model == TranslationTtsModel.SYSTEM
        val installed = isSystem || installedModels[model] == true
        val isSelected = installed && selectedModel == model
        val isDownloading = downloadingModel == model
        Card(
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = 16.dp)
              .then(
                if (installed) {
                  Modifier.selectable(
                    selected = isSelected,
                    enabled = downloadingModel == null,
                    role = Role.RadioButton,
                    onClick = { onModelSelected(model) },
                  )
                } else {
                  Modifier
                }
              ),
          colors =
            CardDefaults.cardColors(
              containerColor =
                if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                text = model.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
              )
              Text(
                text =
                  when {
                    isSelected -> stringResource(R.string.translation_voice_model_selected)
                    isSystem -> stringResource(R.string.translation_voice_system_available)
                    installed -> stringResource(R.string.translation_voice_model_installed)
                    else ->
                      stringResource(
                        R.string.translation_voice_model_download_size,
                        Formatter.formatShortFileSize(context, model.packageSizeBytes),
                      )
                  },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
              )
            }
            Text(
              text = model.description,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isDownloading) {
              val progress = downloadProgress
              val downloadFraction =
                progress
                  ?.takeIf { it.stage == TranslationTtsInstallStage.DOWNLOADING }
                  ?.fraction
              if (downloadFraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              } else {
                LinearProgressIndicator(
                  progress = { downloadFraction.coerceIn(0f, 1f) },
                  modifier = Modifier.fillMaxWidth(),
                )
              }
              Text(
                text =
                  when (progress?.stage) {
                    TranslationTtsInstallStage.VERIFYING ->
                      stringResource(R.string.translation_voice_model_verifying)
                    TranslationTtsInstallStage.EXTRACTING ->
                      stringResource(R.string.translation_voice_model_extracting)
                    TranslationTtsInstallStage.VALIDATING ->
                      stringResource(R.string.translation_voice_model_validating)
                    TranslationTtsInstallStage.FINALIZING ->
                      stringResource(R.string.translation_voice_model_finalizing)
                    TranslationTtsInstallStage.DOWNLOADING ->
                      stringResource(
                        R.string.translation_tts_download_dialog_byte_stats,
                        Formatter.formatShortFileSize(context, progress.downloadedBytes),
                        Formatter.formatShortFileSize(context, progress.totalBytes),
                      )
                    null -> stringResource(R.string.translation_tts_download_dialog_downloading)
                  },
                style = MaterialTheme.typography.bodySmall,
              )
            }
            val modelError =
              downloadFailure?.takeIf { it.model == model }?.message
                ?: deletionError
                  ?.takeIf { downloadingModel == null && it.first == model }
                  ?.second
            modelError?.let { error ->
              Text(error, color = MaterialTheme.colorScheme.error)
            }
            if (!isSystem) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (installed) {
                  TextButton(
                    enabled = downloadingModel == null,
                    onClick = { modelPendingDeletion = model },
                  ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                    Text(stringResource(R.string.delete))
                  }
                } else {
                  Button(
                    enabled = downloadingModel == null,
                    onClick = {
                      deletionError = null
                      onDownloadRequested(model)
                    },
                  ) {
                    if (isDownloading) {
                      CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                      Icon(Icons.Outlined.Download, contentDescription = null)
                      Text(stringResource(R.string.download))
                    }
                  }
                }
              }
            }
          }
        }
      }
      item { Spacer(modifier = Modifier.height(20.dp)) }
    }
  }

  modelPendingDeletion?.let { model ->
    AlertDialog(
      onDismissRequest = { modelPendingDeletion = null },
      title = { Text(stringResource(R.string.translation_voice_model_delete_title)) },
      text = {
        Text(stringResource(R.string.translation_voice_model_delete_content, model.displayName))
      },
      confirmButton = {
        Button(
          onClick = {
            modelPendingDeletion = null
            scope.launch {
              deletionError = null
              try {
                val deleted = TranslationTtsModelRepository.deleteInstalled(context, model)
                if (deleted) {
                  installedModels[model] = false
                  if (selectedModel == model) {
                    val replacement =
                      TranslationTtsModel.entries.firstOrNull { candidate ->
                        candidate != TranslationTtsModel.SYSTEM &&
                          candidate != model &&
                          installedModels[candidate] == true
                      } ?: TranslationTtsModel.SYSTEM
                    onModelSelected(replacement)
                  }
                } else {
                  deletionError = model to "Unable to remove ${model.displayName}."
                }
              } catch (exception: CancellationException) {
                throw exception
              } catch (exception: Exception) {
                deletionError =
                  model to (exception.message ?: "Unable to remove ${model.displayName}.")
              }
            }
          },
        ) {
          Text(stringResource(R.string.delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { modelPendingDeletion = null }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
}
