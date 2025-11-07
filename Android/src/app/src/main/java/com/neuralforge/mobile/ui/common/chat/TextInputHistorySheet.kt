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

package com.neuralforge.mobile.ui.common.chat

// import androidx.compose.ui.tooling.preview.Preview
// import com.neuralforge.mobile.ui.theme.GalleryTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuralforge.mobile.R
import com.neuralforge.mobile.ui.theme.customColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextInputHistorySheet(
  history: List<String>,
  onHistoryItemClicked: (String) -> Unit,
  onHistoryItemDeleted: (String) -> Unit,
  onHistoryItemsDeleteAll: () -> Unit,
  onDismissed: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  val scope = rememberCoroutineScope()

  ModalBottomSheet(
    onDismissRequest = onDismissed,
    sheetState = sheetState,
    modifier = Modifier.wrapContentHeight(),
  ) {
    SheetContent(
      history = history,
      onHistoryItemClicked = { item ->
        scope.launch {
          sheetState.hide()
          delay(100)
          onHistoryItemClicked(item)
          onDismissed()
        }
      },
      onHistoryItemDeleted = onHistoryItemDeleted,
      onHistoryItemsDeleteAll = {
        scope.launch {
          sheetState.hide()
          onDismissed()
          onHistoryItemsDeleteAll()
        }
      },
      onDismissed = {
        scope.launch {
          sheetState.hide()
          onDismissed()
        }
      },
    )
  }
}

@Composable
private fun SheetContent(
  history: List<String>,
  onHistoryItemClicked: (String) -> Unit,
  onHistoryItemDeleted: (String) -> Unit,
  onHistoryItemsDeleteAll: () -> Unit,
  onDismissed: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var showConfirmDeleteDialog by remember { mutableStateOf(false) }

  Column {
    Box(contentAlignment = Alignment.CenterEnd) {
      Text(
        "Text input history",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        textAlign = TextAlign.Center,
      )
      IconButton(
        modifier = Modifier.padding(end = 12.dp),
        onClick = { showConfirmDeleteDialog = true },
      ) {
        Icon(
          Icons.Rounded.DeleteSweep,
          contentDescription = stringResource(R.string.cd_clear_input_history_icon),
        )
      }
    }
    LazyColumn(modifier = Modifier.weight(1f)) {
      items(history, key = { it }) { item ->
        Row(
          modifier =
            Modifier.fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 2.dp)
              .clip(RoundedCornerShape(24.dp))
              .background(MaterialTheme.customColors.agentBubbleBgColor)
              .clickable { onHistoryItemClicked(item) },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            item,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 16.dp).padding(start = 16.dp).weight(1f),
          )
          IconButton(
            modifier = Modifier.padding(end = 8.dp),
            onClick = {
              scope.launch {
                delay(400)
                onHistoryItemDeleted(item)
              }
            },
          ) {
            Icon(
              Icons.Rounded.Delete,
              contentDescription = stringResource(R.string.cd_delete_input_history_entry_icon),
            )
          }
        }
      }
    }
  }

  if (showConfirmDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showConfirmDeleteDialog = false },
      title = { Text("Clear history?") },
      text = { Text("Are you sure you want to clear the history? This action cannot be undone.") },
      confirmButton = {
        Button(
          onClick = {
            showConfirmDeleteDialog = false
            onHistoryItemsDeleteAll()
          }
        ) {
          Text(stringResource(R.string.ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { showConfirmDeleteDialog = false }) {
          Text(stringResource(R.string.cancel))
        }
      },
    )
  }
}

// @Preview(showBackground = true)
// @Composable
// fun TextInputHistorySheetContentPreview() {
//   GalleryTheme {
//     SheetContent(
//       history =
//         listOf(
//           "Analyze the sentiment of the following Tweets and classify them as POSITIVE, NEGATIVE,
// or NEUTRAL. \"It's so beautiful today!\"",
//           "I have the ingredients above. Not sure what to cook for lunch. Show me a list of foods
// with the recipes.",
//           "You are Santa Claus, write a letter back for this kid.",
//           "Generate a list of cookie recipes. Make the outputs in JSON format.",
//         ),
//       onHistoryItemClicked = {},
//       onHistoryItemDeleted = {},
//       onHistoryItemsDeleteAll = {},
//       onDismissed = {},
//     )
//   }
// }
