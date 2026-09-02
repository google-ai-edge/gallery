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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun getModelDirectory(context: Context, model: Model): File {
  return File(model.getPath(context = context, fileName = "placeholder")).parentFile
    ?: File(model.getPath(context = context, fileName = "placeholder"))
}

internal fun areOptionalComponentsPresent(context: Context, model: Model): Boolean {
  if (model.extraDataFiles.isEmpty()) return false
  val modelDir = getModelDirectory(context, model)
  if (!modelDir.exists()) return false

  return model.extraDataFiles.any { extraFile ->
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

internal fun deleteOptionalComponents(context: Context, model: Model) {
  val modelDir = getModelDirectory(context, model)
  if (!modelDir.exists()) return

  for (extraFile in model.extraDataFiles) {
    val directFile = File(modelDir, extraFile.downloadFileName)
    if (directFile.exists()) {
      directFile.deleteRecursively()
    }
    val folderName = extraFile.downloadFileName.substringBeforeLast(".")
    val dirFile = File(modelDir, folderName)
    if (dirFile.exists()) {
      dirFile.deleteRecursively()
    }
    val nameFile = File(modelDir, extraFile.name)
    if (nameFile.exists()) {
      nameFile.deleteRecursively()
    }
  }
}

/**
 * An expandable section under the model download panel that displays optional components such as
 * extra files that can be downloaded alongside the model or removed after download.
 */
@Composable
fun OptionalComponentsPanel(
  model: Model,
  task: Task?,
  modelManagerViewModel: ModelManagerViewModel,
  downloadStatus: ModelDownloadStatusType?,
  modifier: Modifier = Modifier,
  downloadLabel: String? = null,
  componentLabel: String? = null,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()

  if (model.extraDataFiles.isEmpty()) {
    return
  }

  val firstExtraFile = model.extraDataFiles.first()
  var hasOptionalComponents by remember { mutableStateOf(false) }

  // Re-check optional components availability when download status changes.
  LaunchedEffect(model, downloadStatus) {
    withContext(Dispatchers.IO) {
      hasOptionalComponents = areOptionalComponentsPresent(context, model)
    }
  }

  val isModelDownloaded = downloadStatus == ModelDownloadStatusType.SUCCEEDED

  // If model is downloaded and there are no optional components present, do not show the section.
  if (isModelDownloaded && !hasOptionalComponents) {
    return
  }

  var isExpanded by rememberSaveable { mutableStateOf(true) }
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val isCheckboxChecked =
    uiState.downloadOptionalComponents[model.name]
      ?: modelManagerViewModel.isDownloadOptionalComponentsEnabled(model.name)

  val optionalComponentsSizeBytes = model.extraDataFiles.sumOf { it.sizeInBytes }
  val optionalComponentsSizeText = formatOptionalComponentSize(optionalComponentsSizeBytes)

  val resolvedDownloadLabel = downloadLabel ?: "Download ${firstExtraFile.name} (optional)"
  val resolvedComponentLabel =
    componentLabel
      ?: firstExtraFile.name.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase() else it.toString()
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
          // Before download: Checkbox to download optional components (default: ticked)
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier.fillMaxWidth().clickable {
                modelManagerViewModel.setDownloadOptionalComponents(model.name, !isCheckboxChecked)
              },
          ) {
            Checkbox(
              checked = isCheckboxChecked,
              onCheckedChange = {
                modelManagerViewModel.setDownloadOptionalComponents(model.name, it)
              },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = resolvedDownloadLabel,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
              )
              if (optionalComponentsSizeText.isNotEmpty()) {
                Text(
                  text = optionalComponentsSizeText,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          // After download: Show component label with "Remove" button if present
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
              if (optionalComponentsSizeText.isNotEmpty()) {
                Text(
                  text = optionalComponentsSizeText,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            TextButton(
              onClick = {
                coroutineScope.launch(Dispatchers.IO) {
                  deleteOptionalComponents(context, model)
                  withContext(Dispatchers.Main) { hasOptionalComponents = false }
                }
              }
            ) {
              Text(
                text = stringResource(R.string.remove),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
              )
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
