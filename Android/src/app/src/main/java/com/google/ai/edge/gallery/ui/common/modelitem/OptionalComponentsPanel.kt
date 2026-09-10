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

package com.google.ai.edge.gallery.ui.common.modelitem

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.formatZeroBytes
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OptionalComponents"

internal fun getModelDirectory(context: Context, model: Model): File {
  return File(model.getPath(context = context, fileName = "placeholder")).parentFile
    ?: File(model.getPath(context = context, fileName = "placeholder"))
}

internal fun areOptionalComponentsPresent(
  context: Context,
  model: Model,
  taskId: String? = null,
  modelVariants: List<Model> = listOf(),
): Boolean {
  val modelsToCheck = listOf(model) + modelVariants
  return modelsToCheck.any { m ->
    val extraFiles = m.extraDataFiles(taskId)
    if (extraFiles.isEmpty()) return@any false
    val modelDir = getModelDirectory(context, m)
    if (!modelDir.exists()) return@any false

    extraFiles.any { extraFile ->
      val directFile = File(modelDir, extraFile.downloadFileName)
      if (directFile.exists()) return@any true
      val nameFile = File(modelDir, extraFile.name)
      if (nameFile.exists()) return@any true
      val folderName = extraFile.downloadFileName.substringBeforeLast(".")
      val dirFile = File(modelDir, folderName)
      if (dirFile.exists()) return@any true
      false
    }
  }
}

/**
 * An expandable section under the model download panel that displays optional components such as
 * extra files that can be downloaded alongside the model or removed/downloaded after download.
 */
@Composable
fun OptionalComponentsPanel(
  model: Model,
  task: Task?,
  modelManagerViewModel: ModelManagerViewModel,
  downloadStatus: ModelDownloadStatusType?,
  modifier: Modifier = Modifier,
  modelVariants: List<Model> = listOf(),
  downloadLabel: String? = null,
  componentLabel: String? = null,
  showProgressIndicator: Boolean = true,
) {
  val context = LocalContext.current

  val allModels = remember(model, modelVariants) { listOf(model) + modelVariants }

  if (allModels.none { it.hasOptionalComponents(task?.id) }) {
    return
  }

  val uiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
  val allDownloadStatuses = allModels.mapNotNull { m ->
    if (m.name == model.name && downloadStatus != null) {
      downloadStatus
    } else {
      uiState.modelDownloadStatus[m.name]?.status
    }
  }

  var hasOptionalComponents by remember(model, modelVariants) { mutableStateOf(false) }

  val isModelDownloaded = allDownloadStatuses.any { it == ModelDownloadStatusType.SUCCEEDED }
  val isDownloadStarted = allDownloadStatuses.any {
    it == ModelDownloadStatusType.IN_PROGRESS ||
      it == ModelDownloadStatusType.UNZIPPING ||
      it == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
  }

  var isExpanded by rememberSaveable { mutableStateOf(false) }
  val isCheckboxChecked =
    uiState.downloadOptionalComponents[model.name]
      ?: modelManagerViewModel.isDownloadOptionalComponentsEnabled(model.name)

  val setOptionalComponentsEnabled: (Boolean) -> Unit = { enabled ->
    for (m in allModels) {
      modelManagerViewModel.setDownloadOptionalComponents(m.name, enabled)
    }
  }

  val allExtraDataStatuses = allModels.mapNotNull { uiState.extraDataDownloadStatus[it.name] }
  val extraDataStatus =
    allExtraDataStatuses.find {
      it.status == ModelDownloadStatusType.IN_PROGRESS ||
        it.status == ModelDownloadStatusType.UNZIPPING
    } ?: uiState.extraDataDownloadStatus[model.name] ?: allExtraDataStatuses.firstOrNull()
  val isExtraDataDownloading =
    extraDataStatus?.status == ModelDownloadStatusType.IN_PROGRESS ||
      extraDataStatus?.status == ModelDownloadStatusType.UNZIPPING

  LaunchedEffect(model, modelVariants, allDownloadStatuses, allExtraDataStatuses) {
    if (
      allExtraDataStatuses.isNotEmpty() &&
        allExtraDataStatuses.all { it.status == ModelDownloadStatusType.NOT_DOWNLOADED }
    ) {
      hasOptionalComponents = false
    } else if (allExtraDataStatuses.any { it.status == ModelDownloadStatusType.SUCCEEDED }) {
      hasOptionalComponents = true
    } else {
      withContext(Dispatchers.IO) {
        hasOptionalComponents =
          areOptionalComponentsPresent(context, model, task?.id, modelVariants)
      }
    }
  }

  val targetedExtraFiles =
    allModels.firstNotNullOfOrNull { m -> m.extraDataFiles(task?.id).takeIf { it.isNotEmpty() } }
      ?: emptyList()
  val optionalComponentsSizeBytes = targetedExtraFiles.sumOf { it.sizeInBytes }
  val optionalComponentsSizeText = formatOptionalComponentSize(optionalComponentsSizeBytes)

  val resolvedDownloadLabel =
    downloadLabel
      ?: model.optionalComponentsDownloadLabel(context, task?.id).ifEmpty {
        targetedExtraFiles.firstOrNull()?.downloadLabel(context) ?: ""
      }
  val resolvedComponentLabel =
    componentLabel
      ?: model.optionalComponentsLabel(context, task?.id).ifEmpty {
        targetedExtraFiles.firstOrNull()?.componentLabel(context) ?: ""
      }

  Column(modifier = modifier.fillMaxWidth()) {
    HorizontalDivider(
      modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )

    // Collapsible header: "▾ Optional components"
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
        Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable { isExpanded = !isExpanded }
          .padding(vertical = 4.dp),
    ) {
      Icon(
        imageVector =
          if (isExpanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
        contentDescription =
          stringResource(if (isExpanded) R.string.cd_collapse_icon else R.string.cd_expand_icon),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
      )
      Spacer(modifier = Modifier.width(4.dp))
      Text(
        text = stringResource(R.string.optional_components),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    AnimatedVisibility(
      visible = isExpanded,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 8.dp)
      ) {
        if (!isModelDownloaded) {
          // Before model download: Checkbox to download optional components
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth().clickable(enabled = !isDownloadStarted) {
                setOptionalComponentsEnabled(!isCheckboxChecked)
              },
          ) {
            Checkbox(
              checked = isCheckboxChecked,
              enabled = !isDownloadStarted,
              onCheckedChange = { setOptionalComponentsEnabled(it) },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = resolvedDownloadLabel,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color =
                  if (isDownloadStarted) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
              )
              if (optionalComponentsSizeText.isNotEmpty()) {
                Text(
                  text = optionalComponentsSizeText,
                  style = MaterialTheme.typography.bodySmall,
                  color =
                    if (isDownloadStarted) {
                      MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
              }
            }
          }
        } else {
          // After model download: Show component label, size/progress, and action
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = resolvedComponentLabel,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (isExtraDataDownloading) {
                val totalBytes =
                  extraDataStatus?.totalBytes?.takeIf { it > 0L } ?: optionalComponentsSizeBytes
                val receivedBytes = extraDataStatus?.receivedBytes ?: 0L
                val progressText =
                  if (receivedBytes == 0L) {
                    stringResource(
                      R.string.modelitem_optional_components_progress_format,
                      formatZeroBytes(totalBytes),
                      totalBytes.humanReadableSize(),
                    )
                  } else {
                    stringResource(
                      R.string.modelitem_optional_components_progress_format,
                      receivedBytes.humanReadableSize(),
                      totalBytes.humanReadableSize(),
                    )
                  }
                Text(
                  text = progressText,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else if (optionalComponentsSizeText.isNotEmpty()) {
                Text(
                  text = optionalComponentsSizeText,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            if (isExtraDataDownloading) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (showProgressIndicator) {
                  CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                  )
                } else {
                  Spacer(modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                  onClick = {
                    modelManagerViewModel.cancelDownloadExtraDataFiles(model, modelVariants)
                  }
                ) {
                  Text(
                    text = stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.primary,
                    style =
                      MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                  )
                }
              }
            } else if (hasOptionalComponents) {
              TextButton(
                enabled = !isDownloadStarted,
                onClick = {
                  modelManagerViewModel.deleteExtraDataFiles(model, task, modelVariants) {
                    hasOptionalComponents = false
                  }
                },
              ) {
                Text(
                  text = stringResource(R.string.remove),
                  color =
                    if (isDownloadStarted) {
                      MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                      MaterialTheme.colorScheme.primary
                    },
                  style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
              }
            } else {
              TextButton(
                enabled = !isDownloadStarted,
                onClick = {
                  modelManagerViewModel.downloadExtraDataFiles(task, model, modelVariants)
                },
              ) {
                Text(
                  text = stringResource(R.string.download),
                  color =
                    if (isDownloadStarted) {
                      MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                      MaterialTheme.colorScheme.primary
                    },
                  style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
              }
            }
          }
        }
      }
    }
  }
}

private fun formatOptionalComponentSize(bytes: Long): String {
  if (bytes <= 0) return ""
  val mb = bytes.toDouble() / (1024.0 * 1024.0)
  return if (mb >= 1024.0) {
    val gb = mb / 1024.0
    "${gb.roundToInt()} GB"
  } else {
    "${mb.roundToInt()} MB"
  }
}
