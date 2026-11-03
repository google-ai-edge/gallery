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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task

/**
 * The buttons of a paused download, styled like the Benchmark and Try it buttons of a downloaded
 * model: Discard, which deletes the partial files, and Resume, which downloads the rest. Both are
 * icon-only when [compact].
 */
@Composable
internal fun PausedDownloadButtons(
  task: Task?,
  compact: Boolean,
  resumeEnabled: Boolean,
  onResume: () -> Unit,
  onDiscard: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val buttonModifier = if (compact) Modifier.height(42.dp) else Modifier.weight(1f).height(42.dp)

    // Discard, styled like the Benchmark button.
    Button(
      modifier = buttonModifier,
      colors =
        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
      contentPadding = PaddingValues(horizontal = 12.dp),
      onClick = onDiscard,
    ) {
      val textColor = MaterialTheme.colorScheme.onSecondaryContainer
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          Icons.Outlined.Delete,
          contentDescription =
            if (compact) stringResource(R.string.cd_discard_partial_download) else null,
          tint = textColor,
        )
        if (!compact) {
          Text(
            stringResource(R.string.discard_partial_download),
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            autoSize =
              TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 16.sp, stepSize = 1.sp),
          )
        }
      }
    }

    // Resume, styled like the Try it button.
    val containerColor =
      if (task != null) getTaskBgGradientColors(task = task)[1]
      else MaterialTheme.colorScheme.primary
    val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val textColor = if (resumeEnabled) MaterialTheme.colorScheme.onPrimary else disabledContentColor
    Button(
      modifier = buttonModifier,
      enabled = resumeEnabled,
      colors =
        ButtonDefaults.buttonColors(
          containerColor = containerColor,
          contentColor = textColor,
          disabledContainerColor = containerColor,
          disabledContentColor = disabledContentColor,
        ),
      contentPadding = PaddingValues(horizontal = 12.dp),
      onClick = onResume,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          Icons.Outlined.FileDownload,
          contentDescription = if (compact) stringResource(R.string.cd_resume_download) else null,
          tint = textColor,
        )
        if (!compact) {
          Text(
            stringResource(R.string.resume_download),
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            autoSize =
              TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 16.sp, stepSize = 1.sp),
          )
        }
      }
    }
  }
}

/**
 * Asks the user to confirm stopping a model download, which deletes the [receivedBytes] downloaded
 * so far.
 *
 * @param onStopAndDelete Called when the user confirms.
 * @param onDismiss Called when the user keeps the download running.
 */
@Composable
fun StopDownloadDialog(receivedBytes: Long, onStopAndDelete: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.stop_download_dialog_title)) },
    text = { Text(partialDownloadWillBeDeletedMessage(receivedBytes)) },
    confirmButton = {
      TextButton(
        onClick = onStopAndDelete,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
      ) {
        Text(stringResource(R.string.stop_and_delete_download))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.keep_downloading)) }
    },
  )
}

/**
 * Asks the user to confirm deleting the [receivedBytes] that a paused model download left.
 *
 * @param onDelete Called when the user confirms.
 * @param onDismiss Called when the user keeps the partial download.
 */
@Composable
fun DiscardPartialDownloadDialog(receivedBytes: Long, onDelete: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.discard_partial_download_dialog_title)) },
    text = { Text(partialDownloadWillBeDeletedMessage(receivedBytes)) },
    confirmButton = {
      TextButton(
        onClick = onDelete,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
      ) {
        Text(stringResource(R.string.delete))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

/** Returns the message that the [receivedBytes] downloaded so far will be deleted. */
@Composable
private fun partialDownloadWillBeDeletedMessage(receivedBytes: Long): String =
  stringResource(R.string.partial_download_will_be_deleted, receivedBytes.humanReadableSize())
