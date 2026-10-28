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

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import com.google.ai.edge.gallery.ui.theme.staticGreen60

/**
 * Returns the line shown under a language pack's name for [status]: the download error, the
 * download progress when its size is known, or `null` when there is nothing to report.
 */
@Composable
fun languagePackStatusText(status: LanguagePackUiStatus): String? =
  when {
    status is LanguagePackUiStatus.Failed ->
      stringResource(R.string.tts_language_pack_download_failed)
    status is LanguagePackUiStatus.Downloading && status.totalBytes > 0L ->
      stringResource(
        R.string.modelitem_optional_components_progress_format,
        status.receivedBytes.humanReadableSize(),
        status.totalBytes.humanReadableSize(),
      )
    else -> null
  }

/**
 * The trailing download control of a language pack or speech model row: a small neutral spinner
 * while the status is being checked, so that a cold start doesn't look like every pack is
 * downloading, a downloading badge while the pack is downloading, a download button (which also
 * retries a failed download) when the pack can be downloaded, a retry button that requests a
 * stalled download again ([LanguagePackUiStatus.Downloading.stalled]), and an optional green
 * installed badge when [showInstalledBadge] is `true`.
 *
 * Shows nothing for [LanguagePackUiStatus.Unavailable], or for [LanguagePackUiStatus.Installed]
 * when [showInstalledBadge] is `false`.
 *
 * @param languageDisplayName Localized language or model name, used in the download button's
 *   content description.
 * @param showInstalledBadge Whether to render [DownloadedBadge] for
 *   [LanguagePackUiStatus.Installed].
 */
@Composable
fun LanguagePackStatusControl(
  status: LanguagePackUiStatus,
  languageDisplayName: String,
  onDownload: () -> Unit,
  modifier: Modifier = Modifier,
  showInstalledBadge: Boolean = false,
) {
  when (status) {
    LanguagePackUiStatus.Checking ->
      LanguagePackTrailingSlot(modifier = modifier) {
        val checkingDescription = stringResource(R.string.supplementary_status_checking)
        CircularProgressIndicator(
          modifier =
            Modifier.size(CHECKING_SPINNER_SIZE).semantics {
              contentDescription = checkingDescription
            },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          strokeWidth = 2.dp,
          // A faint full ring, so that the spinner reads as one even in a still frame.
          trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
      }
    is LanguagePackUiStatus.Downloading ->
      LanguagePackTrailingSlot(modifier = modifier) {
        if (status.stalled) {
          LanguagePackRetryDownloadButton(
            languageDisplayName = languageDisplayName,
            onRetryDownload = onDownload,
          )
        } else {
          Box(
            modifier =
              Modifier.size(DOWNLOAD_BUTTON_SIZE)
                .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Rounded.Downloading,
              contentDescription = stringResource(R.string.cd_downloading_icon),
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.size(DOWNLOAD_ICON_SIZE),
            )
          }
        }
      }
    LanguagePackUiStatus.Downloadable,
    LanguagePackUiStatus.Failed ->
      LanguagePackTrailingSlot(modifier = modifier) {
        FilledIconButton(onClick = onDownload, modifier = Modifier.size(DOWNLOAD_BUTTON_SIZE)) {
          Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription =
              stringResource(R.string.cd_language_pack_download, languageDisplayName),
            modifier = Modifier.size(DOWNLOAD_ICON_SIZE),
          )
        }
      }
    LanguagePackUiStatus.Installed ->
      if (showInstalledBadge) {
        LanguagePackTrailingSlot(modifier = modifier) { DownloadedBadge() }
      }
    LanguagePackUiStatus.Unavailable -> Unit
  }
}

/**
 * Icon button that requests a language pack download again when nothing in the app is receiving its
 * progress ([LanguagePackUiStatus.Downloading.stalled]).
 *
 * @param languageDisplayName Localized language name, used in the content description.
 */
@Composable
fun LanguagePackRetryDownloadButton(
  languageDisplayName: String,
  onRetryDownload: () -> Unit,
  modifier: Modifier = Modifier,
) {
  IconButton(onClick = onRetryDownload, modifier = modifier) {
    Icon(
      imageVector = Icons.Rounded.Refresh,
      contentDescription =
        stringResource(R.string.cd_language_pack_retry_download, languageDisplayName),
      tint = MaterialTheme.colorScheme.primary,
    )
  }
}

/**
 * Green circular badge with a white download-done checkmark indicating that a model or language
 * pack is already installed on the device.
 */
@Composable
fun DownloadedBadge(modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier.size(DOWNLOADED_BADGE_SIZE).background(color = staticGreen60, shape = CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Rounded.DownloadDone,
      contentDescription = stringResource(R.string.cd_downloaded_icon),
      tint = Color.White,
      modifier = Modifier.size(DOWNLOADED_BADGE_ICON_SIZE),
    )
  }
}

/**
 * Trailing tonal Play/Loading/Stop icon button that previews a TTS voice profile in
 * `VoiceProfilesPanel` and `TtsVoiceDropdownSection`.
 */
@Composable
fun VoicePreviewButton(
  voiceDisplayName: String,
  status: VoicePreviewStatus,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  LanguagePackTrailingSlot(modifier = modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(DOWNLOAD_BUTTON_SIZE)) {
      when (status) {
        VoicePreviewStatus.LOADING -> {
          CircularProgressIndicator(
            modifier = Modifier.size(VOICE_PREVIEW_SPINNER_SIZE),
            strokeWidth = 2.dp,
          )
        }
        VoicePreviewStatus.PLAYING -> {
          Icon(
            imageVector = Icons.Rounded.Stop,
            contentDescription =
              stringResource(R.string.cd_tts_stop_preview_voice, voiceDisplayName),
            modifier = Modifier.size(DOWNLOAD_ICON_SIZE),
          )
        }
        VoicePreviewStatus.IDLE -> {
          Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.cd_tts_preview_voice, voiceDisplayName),
            modifier = Modifier.size(DOWNLOAD_ICON_SIZE),
          )
        }
      }
    }
  }
}

/**
 * Centers a language pack row's trailing control in a fixed slot so controls line up across rows.
 */
@Composable
fun LanguagePackTrailingSlot(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(
    modifier = modifier.size(LANGUAGE_PACK_TRAILING_SLOT_SIZE),
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

/** Size of [LanguagePackTrailingSlot], matching the Material minimum touch target. */
private val LANGUAGE_PACK_TRAILING_SLOT_SIZE = 48.dp

/** Size of [DownloadedBadge]. */
private val DOWNLOADED_BADGE_SIZE = 32.dp

/** Size of the download-done icon inside [DownloadedBadge]. */
private val DOWNLOADED_BADGE_ICON_SIZE = 18.dp

/** Size of the round download button in [LanguagePackStatusControl]. */
private val DOWNLOAD_BUTTON_SIZE = 32.dp

/** Size of the download icon inside the download button. */
private val DOWNLOAD_ICON_SIZE = 18.dp

/** Size of the spinner [LanguagePackStatusControl] shows while a pack's status is being checked. */
private val CHECKING_SPINNER_SIZE = 20.dp

/** Size of the loading spinner inside [VoicePreviewButton]. */
private val VOICE_PREVIEW_SPINNER_SIZE = 16.dp
