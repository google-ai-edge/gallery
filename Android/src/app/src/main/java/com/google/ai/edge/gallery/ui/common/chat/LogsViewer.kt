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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.clearFocusOnKeyboardDismiss
import com.google.ai.edge.gallery.ui.theme.customColors
import kotlinx.coroutines.launch

/**
 * A Composable function to display console logs within a ModalBottomSheet.
 *
 * @param logs The list of [LogMessage] to display.
 * @param onDismissRequest Callback to be invoked when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsViewer(logs: List<LogMessage>, onDismissRequest: () -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()

  ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Top Bar: Title and Close Button
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          stringResource(R.string.logs_viewer_title),
          style = MaterialTheme.typography.titleLarge,
        )
        IconButton(
          onClick = {
            scope.launch {
              sheetState.hide()
              onDismissRequest()
            }
          }
        ) {
          Icon(Icons.Filled.Close, contentDescription = "Close viewer")
        }
      }

      // Filter Field
      var filterText by remember { mutableStateOf("") }
      TextField(
        value = filterText,
        onValueChange = { filterText = it },
        placeholder = { Text(stringResource(R.string.logs_viewer_filter_text_input_placeholder)) },
        modifier =
          Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .clearFocusOnKeyboardDismiss(),
        shape = CircleShape,
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
          if (filterText.isNotEmpty()) {
            IconButton(onClick = { filterText = "" }) {
              Icon(Icons.Outlined.Cancel, contentDescription = "Clear filter")
            }
          }
        },
        colors =
          TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
          ),
      )

      // Level Filter
      var selectedLevels by remember {
        mutableStateOf(setOf(LogMessageLevel.Info, LogMessageLevel.Warning, LogMessageLevel.Error))
      }
      MultiChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)
      ) {
        LogMessageLevel.entries.forEachIndexed { index, level ->
          SegmentedButton(
            shape = SegmentedButtonDefaults.itemShape(index, LogMessageLevel.entries.size),
            onCheckedChange = {
              selectedLevels =
                if (selectedLevels.contains(level)) {
                  selectedLevels - level
                } else {
                  selectedLevels + level
                }
            },
            checked = selectedLevels.contains(level),
          ) {
            Text(level.name)
          }
        }
      }

      // LazyColumn for Logs
      val filteredLogs =
        remember(logs, filterText, selectedLevels) {
          logs.filter { log ->
            log.message.contains(filterText.trim(), ignoreCase = true) &&
              selectedLevels.contains(log.level)
          }
        }

      LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).weight(1f),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (filteredLogs.isEmpty()) {
          item {
            Text(
              stringResource(R.string.logs_viewer_no_matching_logs),
              modifier = Modifier.padding(16.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          items(filteredLogs) { log -> LogItem(log = log) }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

/**
 * Composable to display a single [LogMessage].
 *
 * @param log The [LogMessage] to display.
 */
@Composable
private fun LogItem(log: LogMessage) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Severity Icon
    val icon =
      when (log.level) {
        LogMessageLevel.Info -> Icons.Filled.Info
        LogMessageLevel.Warning -> Icons.Filled.Warning
        LogMessageLevel.Error -> Icons.Filled.Error
      }
    val tint =
      when (log.level) {
        LogMessageLevel.Info -> MaterialTheme.colorScheme.outlineVariant
        LogMessageLevel.Warning -> MaterialTheme.customColors.warningTextColor
        LogMessageLevel.Error -> MaterialTheme.customColors.errorTextColor
      }
    Icon(
      imageVector = icon,
      contentDescription = log.level.name,
      tint = tint,
      modifier = Modifier.size(20.dp),
    )

    // Source, LineNumber, and Message Column
    Column(modifier = Modifier.weight(1f)) {
      if (log.source.isNotEmpty() && log.lineNumber != -1) {
        Text(
          "${log.source}:${log.lineNumber}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
      } else if (log.source.isNotEmpty()) {
        Text(
          log.source,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
      }
      Text(
        log.message,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}
