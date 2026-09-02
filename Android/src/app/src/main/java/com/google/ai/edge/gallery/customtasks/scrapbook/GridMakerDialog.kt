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
package com.google.ai.edge.gallery.customtasks.scrapbook

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.SMALL_BUTTON_CONTENT_PADDING
import com.google.ai.edge.gallery.ui.common.chat.ZoomableImage
import kotlin.math.ceil
import kotlin.math.sqrt

enum class GridType {
  Vertical,
  Horizontal,
}

private const val DEFAULT_CELL_SIZE = 128
private const val DEFAULT_PADDING = 8

@Composable
fun GridMakerDialog(
  type: GridType,
  viewModel: ScrapbookViewModel,
  onDismiss: () -> Unit,
  onSaved: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  val interactionSource = remember { MutableInteractionSource() }
  var columns by remember {
    mutableIntStateOf(ceil(sqrt(uiState.selectedCutoutIndices.size.toDouble())).toInt())
  }
  var cellSize by remember { mutableIntStateOf(DEFAULT_CELL_SIZE) }
  var cellPadding by remember { mutableIntStateOf(DEFAULT_PADDING) }
  val listState = rememberScrollState()
  val context = LocalContext.current

  val queueGridPreview: () -> Unit = {
    viewModel.queueGridPreview(
      info =
        GridInfo(columns = columns, cellSize = cellSize, cellPadding = cellPadding, type = type)
    )
  }

  LaunchedEffect(Unit) {
    val info =
      GridInfo(columns = columns, cellSize = cellSize, cellPadding = cellPadding, type = type)
    viewModel.createGridForSelectedCutouts(info = info)
  }

  Dialog(onDismissRequest = onDismiss) {
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null, // Disable the ripple effect
        ) {},
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(modifier = Modifier.padding(20.dp)) {
        // Title
        Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            if (type == GridType.Vertical) stringResource(R.string.create_vertical_grid)
            else stringResource(R.string.create_horizonal_grid),
            style = MaterialTheme.typography.titleMedium,
          )
          IconButton(onClick = { onDismiss() }, modifier = Modifier.offset(x = 8.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
          }
        }

        // Content.
        Column(
          modifier = Modifier.verticalScroll(state = listState).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // Preview.
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .aspectRatio(1f)
                .border(
                  width = 1.dp,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                  shape = RoundedCornerShape(8.dp),
                )
          ) {
            val preview = uiState.gridPreview
            if (preview != null) {
              ZoomableImage(
                bitmap = preview.asImageBitmap(),
                minScale = 0.5f,
                maxScale = 5f,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Inside,
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            // Labels.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              // Columns.
              val columnsLabel =
                if (type == GridType.Horizontal) stringResource(R.string.rows)
                else stringResource(R.string.columns)
              SettingLabel(label = columnsLabel)

              // Cell size.
              SettingLabel(label = stringResource(R.string.grid_cell_size))

              // Cell padding.
              SettingLabel(label = stringResource(R.string.grid_cell_padding))
            }

            // Sliders.
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              SettingSlider(
                currentValue = columns,
                valueRange = 1f..8f,
                steps = 7,
                onValueChange = {
                  columns = it
                  queueGridPreview()
                },
              )
              SettingSlider(
                currentValue = cellSize,
                valueRange = 16f..256f,
                onValueChange = {
                  cellSize = it
                  queueGridPreview()
                },
              )
              SettingSlider(
                currentValue = cellPadding,
                valueRange = 0f..64f,
                onValueChange = {
                  cellPadding = it
                  queueGridPreview()
                },
              )
            }

            // Current values.
            Column(
              modifier = Modifier.width(24.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              SettingLabel(label = "$columns")
              SettingLabel(label = "$cellSize")
              SettingLabel(label = "$cellPadding")
            }
          }
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          Button(
            onClick = {
              val bitmap = uiState.gridPreview
              if (bitmap != null) {
                viewModel.shareBitmap(
                  context = context,
                  bitmap = bitmap,
                  fileName = "ai_edge_gallery_cutout_grid_${System.currentTimeMillis()}.png",
                )
              }
            },
            contentPadding = SMALL_BUTTON_CONTENT_PADDING,
          ) {
            Icon(
              Icons.Rounded.Share,
              modifier = Modifier.padding(end = 8.dp),
              contentDescription = null,
            )
            Text(stringResource(R.string.share))
          }

          Spacer(modifier = Modifier.width(8.dp))

          Button(
            onClick = {
              val bitmap = uiState.gridPreview
              if (bitmap != null) {
                viewModel.saveBitmapToAlbum(
                  bitmap = bitmap,
                  fileName = "ai_edge_gallery_cutout_grid_${System.currentTimeMillis()}.png",
                  onDone = { onSaved() },
                )
              }
            },
            contentPadding = SMALL_BUTTON_CONTENT_PADDING,
          ) {
            Icon(
              Icons.Rounded.Download,
              modifier = Modifier.padding(end = 8.dp),
              contentDescription = null,
            )
            Text(stringResource(R.string.save))
          }
        }
      }
    }
  }
}

@Composable
private fun SettingSlider(
  currentValue: Int,
  valueRange: ClosedFloatingPointRange<Float>,
  onValueChange: (Int) -> Unit,
  modifier: Modifier = Modifier,
  steps: Int = 0,
) {
  // Slider
  Slider(
    value = currentValue.toFloat(),
    onValueChange = { onValueChange(it.toInt()) },
    valueRange = valueRange,
    steps = steps,
    modifier = modifier.height(24.dp).fillMaxWidth(),
    colors = getSteppedSliderColors(),
  )
}

@Composable
private fun SettingLabel(label: String) {
  Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.CenterStart) {
    Text(
      text = label,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
  }
}
