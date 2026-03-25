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

package com.google.ai.edge.gallery.ui.common.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.GalleryWebView
import kotlinx.coroutines.launch

private const val TAG = "AGMessageBodyWebview"

/** A Composable that displays a WebView to render web content within a chat message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBodyWebview(message: ChatMessageWebView, modifier: Modifier = Modifier) {
  var showBottomSheet by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  Column(modifier = modifier) {
    GalleryWebView(
      modifier = Modifier.fillMaxWidth().aspectRatio(message.aspectRatio),
      initialUrl = message.url,
      useIframeWrapper = message.iframe,
      preventParentScrolling = true,
      allowRequestPermission = true,
    )
    AssistChip(
      onClick = { showBottomSheet = true },
      leadingIcon = {
        Icon(
          Icons.Outlined.FitScreen,
          contentDescription = null,
          Modifier.size(AssistChipDefaults.IconSize),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      },
      label = { Text(stringResource(R.string.view_in_full_screen)) },
    )
  }

  if (showBottomSheet) {
    ModalBottomSheet(
      onDismissRequest = { showBottomSheet = false },
      sheetState = sheetState,
      modifier = Modifier.fillMaxSize(),
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        GalleryWebView(
          modifier = Modifier.fillMaxSize(),
          initialUrl = message.url,
          useIframeWrapper = message.iframe,
          preventParentScrolling = true,
          allowRequestPermission = true,
        )
        OutlinedIconButton(
          onClick = {
            scope.launch {
              sheetState.hide()
              showBottomSheet = false
            }
          },
          colors =
            IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
          border =
            IconButtonDefaults.outlinedIconButtonBorder(true)
              .copy(
                brush = SolidColor(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
              ),
          modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
        ) {
          Icon(
            Icons.Outlined.Close,
            contentDescription = stringResource(R.string.cd_close_icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
