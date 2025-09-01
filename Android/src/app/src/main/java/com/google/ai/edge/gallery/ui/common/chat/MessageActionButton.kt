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

package com.google.ai.edge.gallery.ui.common.chat

// import androidx.compose.ui.tooling.preview.Preview
// import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.ui.theme.bodySmallNarrow

/** Composable function to display an action button below a chat message. */
@Composable
fun MessageActionButton(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val curModifier =
    modifier
      .padding(top = 4.dp)
      .clip(CircleShape)
      .background(
        if (enabled) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh
      )
  val alpha: Float = if (enabled) 1.0f else 0.3f
  Row(
    modifier = if (enabled) curModifier.clickable { onClick() } else modifier,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      icon,
      contentDescription = null,
      modifier = Modifier.size(16.dp).offset(x = 6.dp).alpha(alpha),
    )
    Text(
      label,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      style = bodySmallNarrow,
      modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).alpha(alpha),
    )
  }
}

// @Preview(showBackground = true)
// @Composable
// fun MessageActionButtonPreview() {
//   GalleryTheme {
//     Column {
//       MessageActionButton(label = "run", icon = Icons.Default.PlayArrow, onClick = {})
//       MessageActionButton(
//         label = "run",
//         icon = Icons.Default.PlayArrow,
//         enabled = false,
//         onClick = {})
//     }
//   }
// }
