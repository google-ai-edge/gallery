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

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.huggingface.getCompatibleModelFiles
import com.google.ai.edge.gallery.huggingface.getDeviceTotalRamBytes
import com.google.ai.edge.gallery.huggingface.isFileCompatibleWithDevice
import com.google.ai.edge.gallery.huggingface.isFileTooLarge
import com.google.ai.edge.gallery.huggingface.modelName
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.ui.common.formatCount
import com.google.ai.edge.gallery.ui.common.formatLastModifiedDate
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import kotlinx.coroutines.launch

private val SHEET_PADDING = 24.dp
private val SECTION_SPACING = 20.dp
private val ITEM_SPACING_MEDIUM = 16.dp
private val ITEM_SPACING_SMALL = 12.dp
private val ITEM_SPACING_XSMALL = 8.dp
private val ITEM_SPACING_XXSMALL = 4.dp
private val BADGE_VERTICAL_SPACING = 6.dp
private val BADGE_ICON_SIZE = 16.dp
private val CARD_CORNER_RADIUS = 12.dp
private const val DISABLED_CONTENT_ALPHA = 0.6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfModelDetailsSheet(
  modelItem: HfModelItemProto,
  onDismiss: () -> Unit,
  onImportModelFile: (modelId: String, fileName: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = modifier) {
    HfModelDetailsContent(
      modelItem = modelItem,
      onImportModelFile = { modelId, fileName ->
        scope
          .launch { sheetState.hide() }
          .invokeOnCompletion {
            if (!sheetState.isVisible) {
              onDismiss()
              onImportModelFile(modelId, fileName)
            }
          }
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HfModelDetailsContent(
  modelItem: HfModelItemProto,
  onImportModelFile: (modelId: String, fileName: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val clipboard = LocalClipboard.current
  val files = remember(modelItem) { modelItem.getCompatibleModelFiles() }
  val totalRam = remember(context) { getDeviceTotalRamBytes(context) }

  val fileInfos =
    remember(files, modelItem, totalRam) {
      files.map { fileName ->
        val isCompatible = isFileCompatibleWithDevice(fileName)
        val sibling = modelItem.siblingsList.firstOrNull { it.rfilename == fileName }
        val fileSize = sibling?.size ?: 0L
        val isTooLarge = isFileTooLarge(sizeBytes = fileSize, deviceRamBytes = totalRam)
        val isDisabled = !isCompatible || isTooLarge
        ModelFileInfo(
          fileName = fileName,
          fileSize = fileSize,
          isCompatible = isCompatible,
          isTooLarge = isTooLarge,
          isDisabled = isDisabled,
        )
      }
    }

  val compatibleFiles = remember(fileInfos) { fileInfos.filter { !it.isDisabled } }
  val otherFiles = remember(fileInfos) { fileInfos.filter { it.isDisabled } }

  var selectedFileName by
    remember(modelItem) { mutableStateOf(compatibleFiles.firstOrNull()?.fileName) }
  val snackbarHostState = remember { SnackbarHostState() }

  val urlCopiedMessage = stringResource(R.string.url_copied)
  val incompatiblePrompt = stringResource(R.string.incompatible_file_selection_prompt)

  Box(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Column(
        modifier =
          Modifier.weight(1f, fill = false)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(SHEET_PADDING)
      ) {
        Text(
          text = modelItem.modelName,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text =
            if (modelItem.author.isNotEmpty()) {
              modelItem.author
            } else {
              modelItem.id.substringBefore("/", "Hugging Face")
            },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(ITEM_SPACING_SMALL))

        // Stats row: Downloads, Likes, Last Updated
        ModelStatsRow(
          downloads = modelItem.downloads,
          likes = modelItem.likes,
          lastModified = modelItem.lastModified,
        )

        Spacer(modifier = Modifier.height(SECTION_SPACING))

        if (files.isEmpty()) {
          Text(
            text = stringResource(R.string.hf_no_model_files_in_card),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = ITEM_SPACING_XSMALL),
          )
        } else {
          // Compatible Files section
          if (compatibleFiles.isNotEmpty()) {
            Text(
              text = stringResource(R.string.compatible_files_header, compatibleFiles.size),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(ITEM_SPACING_XXSMALL))
            Text(
              text = stringResource(R.string.compatible_files_subheader),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(ITEM_SPACING_XSMALL))

            for (fileInfo in compatibleFiles) {
              ModelFileItemRow(
                fileInfo = fileInfo,
                isSelected = fileInfo.fileName == selectedFileName,
                modelId = modelItem.id,
                onSelect = {
                  selectedFileName = fileInfo.fileName
                  snackbarHostState.currentSnackbarData?.dismiss()
                },
                onCopyUrl = { modelUrl ->
                  val clipData = ClipData.newPlainText("model_url", modelUrl)
                  scope.launch { clipboard.setClipEntry(clipData.toClipEntry()) }
                  Toast.makeText(context, urlCopiedMessage, Toast.LENGTH_SHORT).show()
                },
              )
            }
          }

          // Other Files section
          if (otherFiles.isNotEmpty()) {
            if (compatibleFiles.isNotEmpty()) {
              Spacer(modifier = Modifier.height(SECTION_SPACING))
            }
            Text(
              text = stringResource(R.string.other_files_header, otherFiles.size),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(ITEM_SPACING_XXSMALL))
            Text(
              text = stringResource(R.string.other_files_subheader),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(ITEM_SPACING_XSMALL))

            for (fileInfo in otherFiles) {
              ModelFileItemRow(
                fileInfo = fileInfo,
                isSelected = fileInfo.fileName == selectedFileName,
                modelId = modelItem.id,
                onSelect = {
                  scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                      message = incompatiblePrompt,
                      withDismissAction = true,
                      duration = SnackbarDuration.Long,
                    )
                  }
                },
                onCopyUrl = { modelUrl ->
                  val clipData = ClipData.newPlainText("model_url", modelUrl)
                  scope.launch { clipboard.setClipEntry(clipData.toClipEntry()) }
                  Toast.makeText(context, urlCopiedMessage, Toast.LENGTH_SHORT).show()
                },
              )
            }
          }
        }
      }

      // Always-visible Import Selected Model button at bottom
      if (files.isNotEmpty()) {
        Surface(color = BottomSheetDefaults.ContainerColor, modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Box(
              modifier =
                Modifier.fillMaxWidth()
                  .padding(horizontal = SHEET_PADDING, vertical = ITEM_SPACING_MEDIUM)
                  .navigationBarsPadding()
            ) {
              val currentSelectedFile = selectedFileName
              Button(
                onClick = {
                  if (currentSelectedFile != null) {
                    onImportModelFile(modelItem.id, currentSelectedFile)
                  }
                },
                enabled = currentSelectedFile != null,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = stringResource(R.string.import_selected_model),
                  style = MaterialTheme.typography.titleMedium,
                )
              }
            }
          }
        }
      }
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp),
    )
  }
}

private data class ModelFileInfo(
  val fileName: String,
  val fileSize: Long,
  val isCompatible: Boolean,
  val isTooLarge: Boolean,
  val isDisabled: Boolean,
)

@Composable
private fun ModelFileItemRow(
  fileInfo: ModelFileInfo,
  isSelected: Boolean,
  modelId: String,
  onSelect: () -> Unit,
  onCopyUrl: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val modelUrl = "https://huggingface.co/$modelId/resolve/main/${fileInfo.fileName}?download=true"
  Surface(
    shape = RoundedCornerShape(CARD_CORNER_RADIUS),
    color =
      if (fileInfo.isDisabled) {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = DISABLED_CONTENT_ALPHA)
      } else {
        MaterialTheme.colorScheme.surfaceContainer
      },
    modifier =
      modifier.fillMaxWidth().padding(vertical = ITEM_SPACING_XXSMALL).clickable { onSelect() },
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(ITEM_SPACING_MEDIUM),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      RadioButton(
        selected = isSelected,
        onClick = null,
        colors =
          if (fileInfo.isDisabled) {
            RadioButtonDefaults.colors(
              unselectedColor =
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
            )
          } else {
            RadioButtonDefaults.colors()
          },
      )
      Spacer(modifier = Modifier.width(ITEM_SPACING_XSMALL))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = fileInfo.fileName,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color =
            if (fileInfo.isDisabled) {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          modifier = Modifier.fillMaxWidth(),
        )
        if (fileInfo.fileSize > 0L) {
          Spacer(modifier = Modifier.height(ITEM_SPACING_XXSMALL))
          Text(
            text = fileInfo.fileSize.humanReadableSize(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (!fileInfo.isCompatible) {
          Text(
            text = stringResource(R.string.incompatible_device_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
          )
        } else if (fileInfo.isTooLarge) {
          Text(
            text = stringResource(R.string.file_too_large_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      IconButton(onClick = { onCopyUrl(modelUrl) }) {
        Icon(
          imageVector = Icons.Rounded.ContentCopy,
          contentDescription = stringResource(R.string.copy_url),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelStatsRow(
  downloads: Long,
  likes: Long,
  lastModified: String,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING_MEDIUM),
    verticalArrangement = Arrangement.spacedBy(BADGE_VERTICAL_SPACING),
    modifier = modifier.fillMaxWidth(),
  ) {
    if (downloads > 0L) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Rounded.CloudDownload,
          contentDescription = null,
          modifier = Modifier.size(BADGE_ICON_SIZE),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(ITEM_SPACING_XXSMALL))
        Text(
          text = formatCount(downloads),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (likes > 0L) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Rounded.Favorite,
          contentDescription = null,
          modifier = Modifier.size(BADGE_ICON_SIZE),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(ITEM_SPACING_XXSMALL))
        Text(
          text = formatCount(likes),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (lastModified.isNotBlank()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Rounded.Schedule,
          contentDescription = null,
          modifier = Modifier.size(BADGE_ICON_SIZE),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(ITEM_SPACING_XXSMALL))
        Text(
          text = stringResource(R.string.hf_last_updated, formatLastModifiedDate(lastModified)),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
