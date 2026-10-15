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

import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DownloadAuthError
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.isForTestOnly
import com.google.ai.edge.gallery.ui.common.tos.GemmaTermsOfUseDialog
import com.google.ai.edge.gallery.ui.common.tos.TosViewModel
import com.google.ai.edge.gallery.ui.modelmanager.DownloadClickAction
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.TokenRequestResultType

private const val TAG = "AGDownloadAndTryButton"
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
 * If Hugging Face refuses the download, the download status carries a [DownloadAuthError]:
 * - [DownloadAuthError.NEEDS_SIGN_IN]: a token exchange flow is initiated, and the download is
 *   retried with the new token. If that token is refused too, the button doesn't sign in again
 *   until the next click.
 * - [DownloadAuthError.NEEDS_LICENSE_ACCEPTANCE]: a modal bottom sheet is displayed, prompting the
 *   user to acknowledge the user agreement by opening it in a custom tab. Upon closing the tab, the
 *   download is retried.
 *
 * A refused download that wasn't started from a download button (e.g. an update) is handled on the
 * next click. If the user signed in since the refusal, that click downloads with the new token.
 *
 * If the model doesn't need to be downloaded first, the provided `onClicked` callback is executed.
 *
 * The composable also manages UI states for displaying the agreement acknowledgement sheet, and it
 * handles requesting notification permissions before initiating the actual download.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
  val context = LocalContext.current
  var showAgreementAckSheet by remember(model.name) { mutableStateOf(false) }
  var showMemoryWarning by remember { mutableStateOf(false) }
  var showGemmaTermsOfUseDialog by remember { mutableStateOf(false) }
  var downloadStarted by remember(model.name) { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsStateWithLifecycle()
  // Set when Hugging Face refused the last download of this model (HTTP 401 or 403).
  val authError = modelManagerUiState.modelDownloadStatus[model.name]?.authError

  // Unavailable models can neither be downloaded nor opened, so render the button disabled.
  val isUnavailable = downloadStatus == ModelDownloadStatusType.UNAVAILABLE
  val buttonEnabled = enabled && !isUnavailable
  val needToDownloadFirst =
    (downloadStatus == ModelDownloadStatusType.NOT_DOWNLOADED ||
      downloadStatus == ModelDownloadStatusType.FAILED) &&
      model.downloadInfo.localRelativeDirPathOverride.isEmpty() &&
      !model.autoDownloadsOnStartup
  val inProgress = downloadStatus == ModelDownloadStatusType.IN_PROGRESS
  val downloadSucceeded = downloadStatus == ModelDownloadStatusType.SUCCEEDED
  val isPartiallyDownloaded = downloadStatus == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
  val showDownloadProgress =
    !downloadSucceeded && (downloadStarted || inProgress || isPartiallyDownloaded)

  val view = LocalView.current
  val completedCd = stringResource(R.string.cd_download_completed)
  LaunchedEffect(downloadSucceeded) {
    if (downloadSucceeded && downloadStarted) {
      downloadStarted = false
      view.announceForAccessibility(completedCd)
    }
  }

  // A launcher for requesting notification permission.
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      modelManagerViewModel.downloadModel(task = task, model = model)
    }

  // Function to kick off download. `afterSignIn` marks a retry right after a Hugging Face sign-in,
  // so
  // a second sign-in refusal doesn't start the OAuth flow again.
  fun startDownload(accessToken: String?, afterSignIn: Boolean = false) {
    downloadStarted = true
    model.downloadInfo.accessToken = accessToken?.takeIf { it.isNotEmpty() }
    modelManagerViewModel.onUserStartedDownload(model = model, afterSignIn = afterSignIn)
    checkNotificationPermissionAndStartDownload(
      context = context,
      launcher = permissionLauncher,
      modelManagerViewModel = modelManagerViewModel,
      task = task,
      model = model,
    )
  }

  // A launcher for handling the authentication flow.
  // Once the user signs in, it restarts the download with the new token. If the model is gated and
  // its license isn't accepted yet, that download fails with NEEDS_LICENSE_ACCEPTANCE, which shows
  // the license sheet.
  val authResultLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      modelManagerViewModel.handleAuthResult(
        result,
        onTokenRequested = { tokenRequestResult ->
          when (tokenRequestResult.status) {
            TokenRequestResultType.SUCCEEDED -> {
              Log.d(TAG, "Token request succeeded. Restarting the download.")
              startDownload(modelManagerViewModel.curAccessToken, afterSignIn = true)
            }

            TokenRequestResultType.FAILED -> {
              Log.d(
                TAG,
                "Token request done. Error message: ${tokenRequestResult.errorMessage ?: ""}",
              )
              downloadStarted = false
            }

            TokenRequestResultType.USER_CANCELLED -> {
              Log.d(TAG, "User cancelled. Do nothing")
              downloadStarted = false
            }
          }
        },
      )
    }

  // Function to kick off the authentication and token exchange flow.
  val startTokenExchange = {
    val authRequest = modelManagerViewModel.getAuthorizationRequest()
    val authIntent = modelManagerViewModel.authService.getAuthorizationRequestIntent(authRequest)
    authResultLauncher.launch(authIntent)
  }

  // A launcher for opening the custom tabs intent for requesting user agreement ack.
  // Once the tab is closed, restart the download. If the license still isn't accepted, the
  // download fails again and the license sheet comes back.
  val agreementAckLauncher: ActivityResultLauncher<Intent> =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
      Log.d(TAG, "User closes the browser tab. Restarting the download.")
      // The token may have expired while the tab was open; sign in again rather than retry with it.
      val token = modelManagerViewModel.validStoredAccessToken()
      if (token == null) {
        startTokenExchange()
      } else {
        startDownload(token)
      }
    }

  // Runs the flow that a download refused by Hugging Face asks for.
  val handleAuthError: (DownloadAuthError) -> Unit = { error ->
    when (error) {
      DownloadAuthError.NEEDS_SIGN_IN -> {
        Log.d(TAG, "Model '${model.name}' needs a Hugging Face token. Initiating OAuth exchange...")
        startTokenExchange()
      }
      DownloadAuthError.NEEDS_LICENSE_ACCEPTANCE -> {
        Log.d(TAG, "Model '${model.name}' is gated. Displaying license acknowledgment sheet.")
        showAgreementAckSheet = true
      }
    }
  }

  // When Hugging Face refuses a download the user started, ask for the missing access. The
  // ViewModel tracks which downloads the user started, so this works even if this button was
  // recomposed or replaced while the download ran, and only one button asks per refusal.
  LaunchedEffect(model.name, authError) {
    if (authError != null) {
      downloadStarted = false
      modelManagerViewModel.takeAuthErrorToPrompt(model)?.let(handleAuthError)
    }
  }

  // Starts the download, or the sign-in or license flow if the last attempt was refused (see
  // ModelManagerViewModel.downloadClickAction).
  val handleClickButton = {
    if (needToDownloadFirst) {
      when (val action = modelManagerViewModel.downloadClickAction(model)) {
        is DownloadClickAction.Download -> startDownload(action.accessToken)
        is DownloadClickAction.ResolveAuth -> handleAuthError(action.authError)
      }
    }
    // No need to download. Directly open the model.
    else {
      onClicked()
    }
  }

  val checkMemoryAndClickDownloadButton = {
    if (isMemoryLow(context = context, model = model)) {
      showMemoryWarning = true
    } else {
      handleClickButton()
    }
  }

  if (!showDownloadProgress) {
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

        // Check TOS before downloading.
        if (
          model.downloadInfo.url.startsWith("https://dl.google.com/google-ai-edge-gallery/") &&
            MODEL_NAMES_TO_SHOW_GEMMA_LICENSES.contains(model.name) &&
            !tosViewModel.getIsGemmaTermsOfUseAccepted()
        ) {
          showGemmaTermsOfUseDialog = true
        } else {
          checkMemoryAndClickDownloadButton()
        }
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
          if (needToDownloadFirst) {
            Text(
              stringResource(R.string.download),
              color = textColor,
              style = MaterialTheme.typography.titleMedium,
            )
          } else if (canShowTryIt) {
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
  // Download progress.
  else {
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
          downloadStarted = false
          modelManagerViewModel.cancelDownloadModel(model = model)
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

  // A ModalBottomSheet composable that displays information about the user agreement
  // for a gated model and provides a button to open the agreement in a custom tab.
  // Upon clicking the button, it constructs the agreement URL, launches it using a
  // custom tab, and then dismisses the bottom sheet.
  if (showAgreementAckSheet) {
    ModalBottomSheet(
      onDismissRequest = {
        showAgreementAckSheet = false
        downloadStarted = false
      },
      sheetState = sheetState,
      modifier = Modifier.wrapContentHeight(),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 16.dp),
      ) {
        Text(
          stringResource(R.string.acknowledge_user_agreement),
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          stringResource(R.string.gated_model_agreement_help),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(
          onClick = {
            // Get agreement url from model url.
            val index = model.downloadInfo.url.indexOf("/resolve/")
            // Show it in a tab.
            if (index >= 0) {
              val agreementUrl = model.downloadInfo.url.substring(0, index)

              val customTabsIntent = CustomTabsIntent.Builder().build()
              customTabsIntent.intent.setData(agreementUrl.toUri())
              agreementAckLauncher.launch(customTabsIntent.intent)
            }
            // Dismiss the sheet.
            showAgreementAckSheet = false
            downloadStarted = false
          }
        ) {
          Text(stringResource(R.string.open_user_agreement))
        }
      }
    }
  }

  if (showMemoryWarning) {
    MemoryWarningAlert(
      onProceeded = {
        handleClickButton()
        showMemoryWarning = false
      },
      onDismissed = { showMemoryWarning = false },
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
