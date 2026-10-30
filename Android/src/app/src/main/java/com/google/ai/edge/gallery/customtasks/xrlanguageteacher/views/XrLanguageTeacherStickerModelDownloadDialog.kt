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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.views

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherStickerModelDownloadReason
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherStickerModelDownloadState
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import com.google.ai.edge.gallery.ui.common.modelitem.calculateDownloadProgress

/**
 * The dialog that downloads the sticker segmentation model ("Magic touch") before an action that
 * needs it: it asks the user to download the model, then shows the download's progress and, once it
 * is downloaded, the progress of turning the saved photos into stickers. It offers to retry a
 * failed download. Shows nothing while [state] is
 * [XrLanguageTeacherStickerModelDownloadState.Hidden].
 *
 * [onConfirm] starts the download, [onRetry] starts it again after it failed, [onCancel] hides the
 * dialog from its Cancel buttons (cancelling the download, or stopping the segmentation, while it
 * runs), and [onAbandon] hides it on the system Back (a running download keeps running). Taps
 * outside the dialog are ignored, so that nothing is decided by accident.
 *
 * For a study game, [onPlayWithoutStickers] starts the game with the saved photos: "Play without
 * stickers" while asking (declining the model), downloading (cancelling the download), or after a
 * failed download, and "Play now" while segmenting (with the stickers cut out so far). No other
 * button starts the game.
 */
@Composable
fun XrLanguageTeacherStickerModelDownloadDialog(
  state: XrLanguageTeacherStickerModelDownloadState,
  onConfirm: () -> Unit,
  onRetry: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  onAbandon: () -> Unit = onCancel,
  onPlayWithoutStickers: () -> Unit = {},
) {
  val isGame = state.isForGame
  when (state) {
    XrLanguageTeacherStickerModelDownloadState.Hidden -> Unit
    is XrLanguageTeacherStickerModelDownloadState.Confirming ->
      AlertDialog(
        onDismissRequest = onAbandon,
        modifier = modifier,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.xrteacher_sticker_model_download_title)) },
        text = {
          Text(stringResource(state.reason.messageResId, state.sizeInBytes.humanReadableSize()))
        },
        confirmButton = {
          StickerModelDialogButtons {
            CancelButton(onCancel)
            if (isGame) PlayWithoutStickersButton(onPlayWithoutStickers)
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.download)) }
          }
        },
      )
    is XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation ->
      AlertDialog(
        onDismissRequest = onAbandon,
        modifier = modifier,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.xrteacher_sticker_segment_confirm_title)) },
        text = {
          Text(
            pluralStringResource(
              R.plurals.xrteacher_sticker_segment_confirm_message,
              state.unsegmentedCount,
              state.unsegmentedCount,
            )
          )
        },
        confirmButton = {
          StickerModelDialogButtons {
            CancelButton(onCancel)
            TextButton(onClick = onConfirm) {
              Text(stringResource(R.string.xrteacher_sticker_segment_confirm_action))
            }
          }
        },
      )
    is XrLanguageTeacherStickerModelDownloadState.Downloading ->
      AlertDialog(
        onDismissRequest = onAbandon,
        modifier = modifier,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.xrteacher_sticker_model_downloading_title)) },
        text = { StickerModelDownloadProgress(state) },
        confirmButton = {
          StickerModelDialogButtons {
            if (isGame) PlayWithoutStickersButton(onPlayWithoutStickers)
            TextButton(onClick = onCancel) {
              Text(stringResource(R.string.xrteacher_sticker_model_cancel_download))
            }
          }
        },
      )
    is XrLanguageTeacherStickerModelDownloadState.Segmenting ->
      AlertDialog(
        onDismissRequest = onAbandon,
        modifier = modifier,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.xrteacher_sticker_segmenting_title)) },
        text = { StickerSegmentationProgress(state) },
        confirmButton = {
          if (isGame) {
            TextButton(onClick = onPlayWithoutStickers) {
              Text(stringResource(R.string.xrteacher_sticker_segmenting_play_now))
            }
          } else {
            CancelButton(onCancel)
          }
        },
      )
    is XrLanguageTeacherStickerModelDownloadState.Failed ->
      AlertDialog(
        onDismissRequest = onAbandon,
        modifier = modifier,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.xrteacher_sticker_model_download_failed_title)) },
        text = {
          Text(
            // The raw error message (logged) isn't localized, so the dialog doesn't show it.
            if (state.isStalled) {
              stringResource(R.string.xrteacher_sticker_model_download_stalled_message)
            } else {
              stringResource(R.string.xrteacher_sticker_model_download_failed_message)
            }
          )
        },
        confirmButton = {
          StickerModelDialogButtons {
            CancelButton(onCancel)
            if (isGame) PlayWithoutStickersButton(onPlayWithoutStickers)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
          }
        },
      )
  }
}

/**
 * Lays the dialog's buttons out in a row aligned to the end, wrapping onto more rows when three of
 * them don't fit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickerModelDialogButtons(content: @Composable () -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { content() }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
  TextButton(onClick = onCancel) { Text(stringResource(R.string.xrteacher_cancel)) }
}

@Composable
private fun PlayWithoutStickersButton(onPlayWithoutStickers: () -> Unit) {
  TextButton(onClick = onPlayWithoutStickers) {
    Text(stringResource(R.string.xrteacher_sticker_model_download_play_without_stickers))
  }
}

/** Whether the dialog is shown before a study game. */
private val XrLanguageTeacherStickerModelDownloadState.isForGame: Boolean
  get() =
    when (this) {
      XrLanguageTeacherStickerModelDownloadState.Hidden,
      is XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation -> false
      is XrLanguageTeacherStickerModelDownloadState.Confirming -> reason.isGame
      is XrLanguageTeacherStickerModelDownloadState.Downloading -> reason.isGame
      is XrLanguageTeacherStickerModelDownloadState.Segmenting -> reason.isGame
      is XrLanguageTeacherStickerModelDownloadState.Failed -> reason.isGame
    }

private val XrLanguageTeacherStickerModelDownloadReason.isGame: Boolean
  get() = this == XrLanguageTeacherStickerModelDownloadReason.GAME

/** The progress bar and the downloaded and total sizes of the running download. */
@Composable
private fun StickerModelDownloadProgress(
  state: XrLanguageTeacherStickerModelDownloadState.Downloading
) {
  val downloadStatus = state.downloadStatus
  val totalBytes = downloadStatus?.totalBytes?.takeIf { it > 0L } ?: state.sizeInBytes
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (downloadStatus != null && downloadStatus.totalBytes > 0L) {
      LinearProgressIndicator(
        progress = { calculateDownloadProgress(downloadStatus) },
        modifier = Modifier.fillMaxWidth(),
      )
    } else {
      // Until the download reports its first progress.
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text(
      stringResource(
        R.string.modelitem_optional_components_progress_format,
        (downloadStatus?.receivedBytes ?: 0L).humanReadableSize(),
        totalBytes.humanReadableSize(),
      ),
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

/** The progress bar and the "photo N of M" label of turning the saved photos into stickers. */
@Composable
private fun StickerSegmentationProgress(
  state: XrLanguageTeacherStickerModelDownloadState.Segmenting
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
    Text(
      stringResource(
        R.string.xrteacher_sticker_segmenting_progress,
        state.current.coerceIn(1, state.total.coerceAtLeast(1)),
        state.total.coerceAtLeast(1),
      ),
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

/** The message that tells the user why the model is needed for the reason. */
private val XrLanguageTeacherStickerModelDownloadReason.messageResId: Int
  @StringRes
  get() =
    when (this) {
      XrLanguageTeacherStickerModelDownloadReason.CUTOUT_EDITOR ->
        R.string.xrteacher_sticker_model_download_cutout_message
      XrLanguageTeacherStickerModelDownloadReason.GAME ->
        R.string.xrteacher_sticker_model_download_game_message
      XrLanguageTeacherStickerModelDownloadReason.CONVERT_STICKERS ->
        R.string.xrteacher_sticker_model_download_convert_message
    }
