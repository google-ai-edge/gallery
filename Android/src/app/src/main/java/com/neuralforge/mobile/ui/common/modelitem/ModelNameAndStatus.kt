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

package com.neuralforge.mobile.ui.common.modelitem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neuralforge.mobile.R
import com.neuralforge.mobile.data.MODEL_INFO_ICON_SIZE
import com.neuralforge.mobile.data.Model
import com.neuralforge.mobile.data.ModelDownloadStatus
import com.neuralforge.mobile.data.ModelDownloadStatusType
import com.neuralforge.mobile.data.Task
import com.neuralforge.mobile.ui.common.ClickableLink
import com.neuralforge.mobile.ui.common.humanReadableSize
import com.neuralforge.mobile.ui.theme.customColors
import com.neuralforge.mobile.ui.theme.labelSmallNarrow

/**
 * Composable function to display the model name and its download status information.
 *
 * This function renders the model's name and its current download status, including:
 * - Model name.
 * - Failure message (if download failed).
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
    Column(modifier = modifier) {
      // Show "best overall" only for the first model if it is indeed the best for this task.
      if (model.bestForTaskIds.contains(task.id) && task.models[0] == model) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier =
            Modifier.padding(bottom = 6.dp)
              .sharedElement(
                rememberSharedContentState(key = "best_overall"),
                animatedVisibilityScope = animatedVisibilityScope,
              ),
        ) {
          Icon(
            Icons.Filled.Star,
            tint = Color(0xFFFCC934),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Text(
            stringResource(R.string.best_overall),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.6f),
          )
        }
      }

      // Model name and action buttons.
      Text(
        model.displayName.ifEmpty { model.name },
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        style = MaterialTheme.typography.titleMedium,
        modifier =
          Modifier.sharedElement(
            rememberSharedContentState(key = "model_name"),
            animatedVisibilityScope = animatedVisibilityScope,
          ),
      )

      // Status icon + size + download progress details.
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        // Status icon.
        StatusIcon(
          task = task,
          model = model,
          downloadStatus = downloadStatus,
          modifier =
            Modifier.padding(end = 4.dp)
              .sharedElement(
                rememberSharedContentState(key = "download_status_icon"),
                animatedVisibilityScope = animatedVisibilityScope,
              ),
        )

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
          if (model.localFileRelativeDirPathOverride.isNotEmpty()) {
            sizeLabel = "{ext_files_dir}/${model.localFileRelativeDirPathOverride}"
          }

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
                // if (downloadStatus.remainingMs >= 0) {
                //   sizeLabel =
                //     "$sizeLabel\n${downloadStatus.remainingMs.formatToHourMinSecond()} left"
                // }
              }
              if (isPartiallyDownloaded) {
                sizeLabel = "$sizeLabel (resuming...)"
              }
              curDownloadProgress =
                downloadStatus.receivedBytes.toFloat() / downloadStatus.totalBytes.toFloat()
              if (curDownloadProgress.isNaN()) {
                curDownloadProgress = 0f
              }
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
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

      // Learn more url.
      if (!model.imported && model.learnMoreUrl.isNotEmpty()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.sharedElement(
              rememberSharedContentState(key = "learn_more"),
              animatedVisibilityScope = animatedVisibilityScope,
            ),
        ) {
          Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            tint = MaterialTheme.customColors.modelInfoIconColor,
            contentDescription = null,
            modifier = Modifier.size(MODEL_INFO_ICON_SIZE).offset(y = 1.dp),
          )
          ClickableLink(model.learnMoreUrl, linkText = stringResource(R.string.learn_more))
        }
      }
    }
  }
}
