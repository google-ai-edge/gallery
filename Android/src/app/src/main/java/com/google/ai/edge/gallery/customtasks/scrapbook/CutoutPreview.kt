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

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.customColors

private val CORNER_RADIUS = 4.dp

@Composable
fun CutoutPreview(
  cutoutInfo: CutoutInfo,
  index: Int,
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  topLeftElement: @Composable () -> Unit,
  onClick: () -> Unit = {},
  onLongClick: () -> Unit = {},
) {
  Box(
    modifier =
      modifier
        .aspectRatio(1f)
        .clip(RoundedCornerShape(CORNER_RADIUS))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .combinedClickable(
          hapticFeedbackEnabled = true,
          onClick = onClick,
          onLongClick = onLongClick,
        )
        .border(
          width = if (selected) 2.dp else 1.dp,
          color =
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
          shape = RoundedCornerShape(CORNER_RADIUS),
        ),
    contentAlignment = Alignment.Center,
  ) {
    // Loaded bitmap.
    val cutoutBitmap = cutoutInfo.bitmap
    if (cutoutBitmap != null) {
      Image(
        cutoutBitmap.asImageBitmap(),
        contentDescription = stringResource(R.string.cd_cutout_with_index, index),
        modifier = Modifier.fillMaxSize().padding(8.dp),
        contentScale = ContentScale.Inside,
      )
    }
    // Error.
    else if (cutoutInfo.errorLoadingBitmap) {
      Icon(
        Icons.Rounded.QuestionMark,
        contentDescription = stringResource(R.string.cd_missing_cutout_with_index, index),
        tint = MaterialTheme.customColors.warningTextColor,
      )
    }

    // Top left element.
    Box(modifier = Modifier.align(Alignment.TopStart)) { topLeftElement() }
  }
}
