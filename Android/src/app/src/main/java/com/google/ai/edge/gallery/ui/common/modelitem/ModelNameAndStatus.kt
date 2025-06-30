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

package com.google.ai.edge.gallery.ui.common.modelitem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.formatToHourMinSecond
import com.google.ai.edge.gallery.ui.common.getTaskIconColor
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow

/**
 * Composable function to display the model name and its download status information.
 *
 * This function renders the model's name and its current download status, including:
 * - Model name.
 * - Failure message (if download failed).
 * - Download progress (received size, total size, download rate, remaining time) for in-progress
 *   downloads.
 * - "Unzipping..." status for unzipping processes.
 * - Model size for successful downloads.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ModelNameAndStatus(
  model: Model,
  task: Task,
  downloadStatus: ModelDownloadStatus?,
  isExpanded: Boolean,
  sharedTransitionScope: SharedTransitionScope,
  animatedVisibilityScope: AnimatedVisibilityScope,
  modifier: Modifier = Modifier,
) {
  val inProgress = downloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS
  val isPartiallyDownloaded = downloadStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
  var curDownloadProgress = 0f

  with(sharedTransitionScope) {
    Column(
      horizontalAlignment = if (isExpanded) Alignment.CenterHorizontally else Alignment.Start
    ) {
      // Model name.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          model.name,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
          style = MaterialTheme.typography.titleMedium,
          modifier =
            Modifier.sharedElement(
              rememberSharedContentState(key = "model_name"),
              animatedVisibilityScope = animatedVisibilityScope,
            ),
        )
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        // Status icon.
        if (!inProgress && !isPartiallyDownloaded) {
          StatusIcon(
            downloadStatus = downloadStatus,
            modifier =
              modifier
                .padding(end = 4.dp)
                .sharedElement(
                  rememberSharedContentState(key = "download_status_icon"),
                  animatedVisibilityScope = animatedVisibilityScope,
                ),
          )
        }

        // Failure message.
        if (downloadStatus != null && downloadStatus.status == ModelDownloadStatusType.FAILED) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              downloadStatus.errorMessage,
              color = MaterialTheme.colorScheme.error,
              style = labelSmallNarrow,
              overflow = TextOverflow.Ellipsis,
              modifier =
                Modifier.sharedElement(
                  rememberSharedContentState(key = "failure_messsage"),
                  animatedVisibilityScope = animatedVisibilityScope,
                ),
            )
          }
        }

        // Status label
        else {
          var sizeLabel = model.totalBytes.humanReadableSize()
          var fontSize = 11.sp

          // Populate the status label.
          if (downloadStatus != null) {
            // For in-progress model, show {receivedSize} / {totalSize} - {rate} - {remainingTime}
            if (inProgress || isPartiallyDownloaded) {
              var totalSize = downloadStatus.totalBytes
              if (totalSize == 0L) {
                totalSize = model.totalBytes
              }
              sizeLabel =
                "${downloadStatus.receivedBytes.humanReadableSize(extraDecimalForGbAndAbove = true)} of ${totalSize.humanReadableSize()}"
              if (downloadStatus.bytesPerSecond > 0) {
                sizeLabel = "$sizeLabel · ${downloadStatus.bytesPerSecond.humanReadableSize()} / s"
                if (downloadStatus.remainingMs >= 0) {
                  sizeLabel =
                    "$sizeLabel\n${downloadStatus.remainingMs.formatToHourMinSecond()} left"
                }
              }
              if (isPartiallyDownloaded) {
                sizeLabel = "$sizeLabel (resuming...)"
              }
              curDownloadProgress =
                downloadStatus.receivedBytes.toFloat() / downloadStatus.totalBytes.toFloat()
              if (curDownloadProgress.isNaN()) {
                curDownloadProgress = 0f
              }
              fontSize = 9.sp
            }
            // Status for unzipping.
            else if (downloadStatus.status == ModelDownloadStatusType.UNZIPPING) {
              sizeLabel = "Unzipping..."
            }
          }

          Column(
            horizontalAlignment = if (isExpanded) Alignment.CenterHorizontally else Alignment.Start
          ) {
            for ((index, line) in sizeLabel.split("\n").withIndex()) {
              Text(
                line,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                style = labelSmallNarrow.copy(fontSize = fontSize, lineHeight = 10.sp),
                textAlign = if (isExpanded) TextAlign.Center else TextAlign.Start,
                overflow = TextOverflow.Visible,
                modifier =
                  Modifier.offset(y = if (index == 0) 0.dp else (-1).dp)
                    .sharedElement(
                      rememberSharedContentState(key = "status_label_${index}"),
                      animatedVisibilityScope = animatedVisibilityScope,
                    ),
              )
            }
          }
        }
      }

      // Download progress bar.
      if (inProgress || isPartiallyDownloaded) {
        val animatedProgress = remember { Animatable(0f) }
        LinearProgressIndicator(
          progress = { animatedProgress.value },
          color = getTaskIconColor(task = task),
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          modifier =
            Modifier.padding(top = 2.dp)
              .sharedElement(
                rememberSharedContentState(key = "download_progress_bar"),
                animatedVisibilityScope = animatedVisibilityScope,
              ),
        )
        LaunchedEffect(curDownloadProgress) {
          animatedProgress.animateTo(curDownloadProgress, animationSpec = tween(150))
        }
      }
      // Unzipping progress.
      else if (downloadStatus?.status == ModelDownloadStatusType.UNZIPPING) {
        LinearProgressIndicator(
          color = getTaskIconColor(task = task),
          trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          modifier =
            Modifier.padding(top = 2.dp)
              .sharedElement(
                rememberSharedContentState(key = "unzip_progress_bar"),
                animatedVisibilityScope = animatedVisibilityScope,
              ),
        )
      }
    }
  }
}
