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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.isForTestOnly
import com.google.ai.edge.gallery.ui.common.tos.GemmaTermsOfUseDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

private const val SYSTEM_RESERVED_MEMORY_IN_BYTES = 3 * (1L shl 30)

private val MODEL_NAMES_TO_SHOW_GEMMA_LICENSES =
  setOf("Gemma-3n-E2B-it", "Gemma-3n-E4B-it", "Gemma3-1B-IT", "Gemma3-1B-IT NPU")

/**
 * Handles the "Download & Try it" button click, managing the model download process based on
 * various conditions.
 *
 * If the button is enabled, it initiates the download logic.
 *
 * For models requiring download first, the download starts directly. For HuggingFace URLs, it
 * passes the stored access token if one exists and hasn't expired. The URL isn't probed first:
 * Hugging Face counts every request to a model file, including HEAD, as a download.
 *
 * If Hugging Face refuses the download, the download status carries a `DownloadAuthError`:
 * - `DownloadAuthError.NEEDS_SIGN_IN`: a token exchange flow is initiated, and the download is
 *   retried with the new token. If that token is refused too, the button doesn't sign in again
 *   until the next click.
 * - `DownloadAuthError.NEEDS_LICENSE_ACCEPTANCE`: a modal bottom sheet is displayed, prompting the
 *   user to acknowledge the user agreement by opening it in a custom tab. Upon closing the tab, the
 *   download is retried.
 *
 * A refused download that wasn't started from a download button (e.g. an update) is handled on the
 * next click. If the user signed in since the refusal, that click downloads with the new token.
 *
 * If the model doesn't need to be downloaded first, the provided `onClicked` callback is executed.
 *
 * The composable also manages UI states for displaying the agreement acknowledgement sheet, and it
 * handles requesting notification permissions before initiating the actual download. These checks
 * are implemented by [ModelDownloadGates].
 */
@Composable
fun DownloadAndTryButton(
  task: Task?,
  model: Model,
  enabled: Boolean,
  downloadStatus: ModelDownloadStatusType?,
  downloadProgress: Float,
  modelManagerViewModel: ModelManagerViewModel,
  onClicked: () -> Unit,
  modifier: Modifier = Modifier,
  tosViewModel: TosViewModel = hiltViewModel(),
  modifierWhenExpanded: Modifier = Modifier,
  compact: Boolean = false,
  canShowTryIt: Boolean = true,
  downloadButtonBackgroundColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
  var showGemmaTermsOfUseDialog by remember { mutableStateOf(false) }
  // Shows the download as started while the download checks run, until the download really starts.
  var downloadStarted by remember(model.name) { mutableStateOf(false) }
  // Whether to announce that the download the user started has completed.
  var announceCompletion by remember(model.name) { mutableStateOf(false) }
  // The bytes downloaded so far, shown by the dialog that confirms stopping the download, if open.
  var stopDialogReceivedBytes by remember(model.name) { mutableStateOf<Long?>(null) }
  // The bytes downloaded so far, shown by the dialog that confirms discarding the paused download,
  // if open.
  var discardDialogReceivedBytes by remember(model.name) { mutableStateOf<Long?>(null) }
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
  val curDownloadStatus by
    remember(model.name) { derivedStateOf { modelManagerUiState.modelDownloadStatus[model.name] } }
  // Set when Hugging Face refused the last download of this model (HTTP 401 or 403). Read through
  // derivedStateOf so the progress updates of a download, which replace the UI state several times
  // a second, don't recompose the button of every model in the list.
  val authError = curDownloadStatus?.authError

  // Unavailable models can neither be downloaded nor opened, so render the button disabled.
  val isUnavailable = downloadStatus == ModelDownloadStatusType.UNAVAILABLE
  val buttonEnabled = enabled && !isUnavailable
  // A download that stopped, e.g. because the connection broke, and left files to resume. Only
  // downloads run by the app can be resumed or discarded.
  val isPaused =
    downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED && model.downloadsViaRepository
  val needToDownloadFirst =
    (downloadStatus == ModelDownloadStatusType.NOT_DOWNLOADED ||
      downloadStatus == ModelDownloadStatusType.FAILED ||
      isPaused) &&
      model.downloadInfo.localRelativeDirPathOverride.isEmpty() &&
      !model.autoDownloadsOnStartup
  val inProgress = downloadStatus == ModelDownloadStatusType.IN_PROGRESS
  val downloadSucceeded = downloadStatus == ModelDownloadStatusType.SUCCEEDED
  val isPartiallyDownloaded = downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
  val showDownloadProgress =
    !downloadSucceeded && !isPaused && (downloadStarted || inProgress || isPartiallyDownloaded)

  val view = LocalView.current
  val completedCd = stringResource(R.string.cd_download_completed)
  LaunchedEffect(downloadSucceeded) {
    if (downloadSucceeded && announceCompletion) {
      announceCompletion = false
      @Suppress("DEPRECATION") view.announceForAccessibility(completedCd)
    }
  }
  // A download that failed or paused no longer shows as started, and isn't announced if it
  // completes later.
  LaunchedEffect(downloadStatus) {
    if (
      downloadStatus == ModelDownloadStatusType.FAILED ||
        downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
    ) {
      downloadStarted = false
      announceCompletion = false
    }
  }
  // Close the confirmation dialogs once they no longer apply, e.g. when the download finished.
  LaunchedEffect(inProgress) {
    if (!inProgress) {
      stopDialogReceivedBytes = null
    }
  }
  LaunchedEffect(isPaused) {
    if (!isPaused) {
      discardDialogReceivedBytes = null
    }
  }

  // Starts the download once the notification permission has been handled, and the user agreed to
  // use mobile data if the device is on it.
  val downloadModel = {
    modelManagerViewModel.downloadModelAfterMobileDataCheck(
      task = task,
      model = model,
      onStarted = {
        // The download's status now shows whether it started, or why it couldn't (e.g. not enough
        // storage), so it no longer needs to show as started.
        downloadStarted = false
      },
      onCancelled = {
        downloadStarted = false
        announceCompletion = false
      },
    )
  }
  // startDownload captures gates before it's assigned, so it can't be a val even though kotlinc
  // suggests one (doing so fails with UNINITIALIZED_VARIABLE).
  @Suppress("CAN_BE_VAL_LATEINIT") lateinit var gates: ModelDownloadGates

  // Function to kick off download. `afterSignIn` marks a retry right after a Hugging Face sign-in,
  // so a second sign-in refusal doesn't start the OAuth flow again.
  fun startDownload(accessToken: String?, afterSignIn: Boolean = false) {
    downloadStarted = true
    announceCompletion = true
    model.downloadInfo.accessToken = accessToken?.takeIf { it.isNotEmpty() }
    modelManagerViewModel.onUserStartedDownload(model = model, afterSignIn = afterSignIn)
    gates.requestNotificationPermission(onDone = downloadModel)
  }
  val onAccessAborted = {
    downloadStarted = false
    announceCompletion = false
  }

  // The memory, Hugging Face access, and notification permission checks run before downloading.
  gates =
    rememberModelDownloadGates(
      modelManagerViewModel = modelManagerViewModel,
      fallback =
        ModelDownloadGatesFallback(
          model = model,
          onAccessible = ::startDownload,
          onAborted = onAccessAborted,
          onNotificationPermissionResult = downloadModel,
        ),
    )

  // When Hugging Face refuses a download the user started, ask for the missing access. The
  // ViewModel tracks which downloads the user started, so this works even if this button was
  // recomposed or replaced while the download ran, and only one button asks per refusal.
  LaunchedEffect(model.name, authError) {
    if (authError != null) {
      downloadStarted = false
      modelManagerViewModel.takeAuthErrorToPrompt(model)?.let { error ->
        gates.resolveAuthError(
          model = model,
          authError = error,
          onAccessible = ::startDownload,
          onAborted = onAccessAborted,
        )
      }
    }
  }

  // Starts the download, or the sign-in or license flow if the last attempt was refused (see
  // ModelManagerViewModel.downloadClickAction).
  val handleClickButton = {
    if (needToDownloadFirst) {
      gates.checkAccess(model = model, onAccessible = ::startDownload, onAborted = onAccessAborted)
    }
    // No need to download. Directly open the model.
    else {
      onClicked()
    }
  }

  val checkMemoryAndClickDownloadButton = {
    gates.checkMemory(model = model, onProceed = handleClickButton)
  }

  // Checks the Gemma terms of use, if needed, before the download checks.
  val onDownloadButtonClicked = {
    if (
      model.downloadInfo.url.startsWith("https://dl.google.com/google-ai-edge-gallery/") &&
        model.name in MODEL_NAMES_TO_SHOW_GEMMA_LICENSES &&
        !tosViewModel.getIsGemmaTermsOfUseAccepted()
    ) {
      showGemmaTermsOfUseDialog = true
    } else {
      checkMemoryAndClickDownloadButton()
    }
  }

  when {
    isPaused -> {
      PausedDownloadButtons(
        task = task,
        compact = compact,
        resumeEnabled = buttonEnabled,
        onResume = onDownloadButtonClicked,
        onDiscard = { discardDialogReceivedBytes = curDownloadStatus?.receivedBytes ?: 0L },
        modifier = if (compact) modifier else modifier.then(modifierWhenExpanded).fillMaxWidth(),
      )
    }
    !showDownloadProgress -> {
      var buttonModifier: Modifier = modifier.height(42.dp)
      if (!compact) {
        buttonModifier = buttonModifier.then(modifierWhenExpanded)
      }
      Button(
        modifier = buttonModifier,
        colors =
          ButtonDefaults.buttonColors(
            containerColor =
              when {
                (!downloadSucceeded || !canShowTryIt) &&
                  model.downloadInfo.localRelativeDirPathOverride.isEmpty() ->
                  downloadButtonBackgroundColor
                task != null -> getTaskBgGradientColors(task = task)[1]
                else -> MaterialTheme.colorScheme.primary
              }
          ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        onClick = {
          if (!buttonEnabled) {
            return@Button
          }
          onDownloadButtonClicked()
        },
      ) {
        val textColor =
          when {
            !buttonEnabled ->
              // Define the color for disabled button.
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            !downloadSucceeded && model.downloadInfo.localRelativeDirPathOverride.isEmpty() ->
              MaterialTheme.colorScheme.onSurface
            task != null -> Color.White
            else -> MaterialTheme.colorScheme.onPrimary
          }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            if (needToDownloadFirst) {
              Icons.Outlined.FileDownload
            } else {
              Icons.AutoMirrored.Rounded.ArrowForward
            },
            contentDescription = null,
            tint = textColor,
          )

          if (!compact) {
            when {
              needToDownloadFirst -> {
                Text(
                  stringResource(R.string.download),
                  color = textColor,
                  style = MaterialTheme.typography.titleMedium,
                )
              }
              canShowTryIt -> {
                Text(
                  stringResource(if (model.isForTestOnly) R.string.test_it else R.string.try_it),
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
    }
    // Download progress.
    else -> {
      val animatedProgress = remember { Animatable(0f) }

      var downloadProgressModifier: Modifier = modifier
      if (!compact) {
        downloadProgressModifier = downloadProgressModifier.fillMaxWidth()
      }
      downloadProgressModifier =
        downloadProgressModifier
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceContainer)
          .padding(horizontal = 8.dp)
          .height(42.dp)
      Row(modifier = downloadProgressModifier, verticalAlignment = Alignment.CenterVertically) {
        val progressPercent = (downloadProgress * 100).toInt()
        val progressCd = stringResource(R.string.cd_download_progress, progressPercent)
        val textSemanticsModifier =
          if (compact) {
            Modifier.semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(downloadProgress, 0f..1f)
                contentDescription = progressCd
              }
              .padding(start = 12.dp)
              .width(32.dp)
          } else {
            Modifier.clearAndSetSemantics {}.padding(start = 12.dp).width(44.dp)
          }
        Text(
          "$progressPercent%",
          style =
            MaterialTheme.typography.bodyMedium.copy(
              // This stops numbers from "jumping around" when being updated.
              fontFeatureSettings = "tnum"
            ),
          color = MaterialTheme.colorScheme.onSurface,
          modifier = textSemanticsModifier,
        )
        if (!compact) {
          val color =
            if (task != null) getTaskBgGradientColors(task = task)[1]
            else MaterialTheme.colorScheme.primary
          if (!model.downloadsViaRepository && downloadProgress <= 0f) {
            LinearProgressIndicator(
              modifier =
                Modifier.weight(1f).padding(horizontal = 4.dp).semantics {
                  contentDescription = progressCd
                },
              color = color,
              trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
          } else {
            LinearProgressIndicator(
              modifier =
                Modifier.weight(1f).padding(horizontal = 4.dp).semantics {
                  contentDescription = progressCd
                },
              progress = { animatedProgress.value },
              color = color,
              trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
          }
        }
        val cbStop = stringResource(R.string.cd_stop_icon)
        IconButton(
          onClick = {
            val receivedBytes = curDownloadStatus?.receivedBytes ?: 0L
            // Stopping deletes what's downloaded so far, so ask first when there is something.
            if (inProgress && model.downloadsViaRepository && receivedBytes > 0L) {
              stopDialogReceivedBytes = receivedBytes
            } else {
              downloadStarted = false
              modelManagerViewModel.cancelDownloadModel(model = model)
            }
          },
          colors =
            IconButtonDefaults.iconButtonColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
          modifier = Modifier.semantics { contentDescription = cbStop },
        ) {
          Icon(
            Icons.Outlined.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
      LaunchedEffect(downloadProgress) {
        animatedProgress.animateTo(downloadProgress, animationSpec = tween(150))
      }
    }
  }

  // The user agreement sheet and memory warning of the download checks.
  ModelDownloadGatesHost(gates = gates)

  stopDialogReceivedBytes?.let { receivedBytes ->
    StopDownloadDialog(
      receivedBytes = receivedBytes,
      onStopAndDelete = {
        stopDialogReceivedBytes = null
        // The download may have finished just before the tap: keep the downloaded model then.
        if (curDownloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS) {
          downloadStarted = false
          modelManagerViewModel.cancelDownloadModel(model = model)
        }
      },
      onDismiss = { stopDialogReceivedBytes = null },
    )
  }

  discardDialogReceivedBytes?.let { receivedBytes ->
    DiscardPartialDownloadDialog(
      receivedBytes = receivedBytes,
      onDelete = {
        discardDialogReceivedBytes = null
        modelManagerViewModel.discardPartialDownload(model = model)
      },
      onDismiss = { discardDialogReceivedBytes = null },
    )
  }

  if (showGemmaTermsOfUseDialog) {
    GemmaTermsOfUseDialog(
      onTosAccepted = {
        showGemmaTermsOfUseDialog = false
        tosViewModel.acceptGemmaTermsOfUse()
        checkMemoryAndClickDownloadButton()
      },
      onCancel = { showGemmaTermsOfUseDialog = false },
    )
  }
}
