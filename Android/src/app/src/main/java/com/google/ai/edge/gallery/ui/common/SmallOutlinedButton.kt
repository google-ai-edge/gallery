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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val BUTTON_CONTENT_PADDING =
  PaddingValues(start = 12.dp, top = 2.dp, end = 12.dp, bottom = 2.dp)

/** A small OutlinedButton composable with a label and an optional icon. */
@Composable
fun SmallOutlinedButton(
  onClick: () -> Unit,
  labelResId: Int,
  imageVector: ImageVector? = null,
  iconResId: Int? = null,
  size: Dp = 18.dp,
) {
  OutlinedButton(
    onClick = onClick,
    modifier = Modifier.height(32.dp),
    contentPadding = BUTTON_CONTENT_PADDING,
  ) {
    if (imageVector != null) {
      Icon(imageVector = imageVector, contentDescription = null, modifier = Modifier.size(size))
    } else if (iconResId != null) {
      Icon(
        ImageVector.vectorResource(iconResId),
        contentDescription = null,
        modifier = Modifier.size(size),
      )
    }
    Text(
      stringResource(labelResId),
      style = MaterialTheme.typography.labelMedium,
      modifier = Modifier.padding(start = 4.dp),
    )
  }
}
