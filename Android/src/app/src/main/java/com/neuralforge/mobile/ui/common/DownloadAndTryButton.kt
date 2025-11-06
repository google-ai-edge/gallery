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

package com.neuralforge.mobile.ui.common

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.neuralforge.mobile.R
import com.neuralforge.mobile.data.Model
import com.neuralforge.mobile.data.ModelDownloadStatus
import com.neuralforge.mobile.data.ModelDownloadStatusType
import com.neuralforge.mobile.data.Task
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel
import com.neuralforge.mobile.ui.modelmanager.TokenRequestResultType
import com.neuralforge.mobile.ui.modelmanager.TokenStatus
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadAndTryButton"
private const val SYSTEM_RESERVED_MEMORY_IN_BYTES = 3 * (1L shl 30)

/**
 * Handles the "Download & Try it" button click, managing the model download process based on
 * various conditions.
 *
 * If the button is enabled and not currently checking the token, it initiates a coroutine to handle
 * the download logic.
 *
 * For models requiring download first, it specifically addresses HuggingFace URLs by first checking
 * if authentication is necessary. If no authentication is needed, the download starts directly.
 * Otherwise, it checks the current token status; if the token is invalid or expired, a token
 * exchange flow is initiated. If a valid token exists, it attempts to access the download URL. If
 * access is granted, the download begins; if not, a new token is requested.
 *
 * For non-HuggingFace URLs that need downloading, the download starts directly.
 *
 * If the model doesn't need to be downloaded first, the provided `onClicked` callback is executed.
 *
 * Additionally, for gated HuggingFace models, if accessing the model after token exchange results
 * in a forbidden error, a modal bottom sheet is displayed, prompting the user to acknowledge the
 * user agreement by opening it in a custom tab. Upon closing the tab, the download process is
 * retried.
 *
 * The composable also manages UI states for indicating token checking and displaying the agreement
 * acknowledgement sheet, and it handles requesting notification permissions before initiating the
 * actual download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadAndTryButton(
  task: Task,
  model: Model,
  enabled: Boolean,
  downloadStatus: ModelDownloadStatus?,
  modelManagerViewModel: ModelManagerViewModel,
  onClicked: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  canShowTryIt: Boolean = true,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var checkingToken by remember { mutableStateOf(false) }
  var showAgreementAckSheet by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var showMemoryWarning by remember { mutableStateOf(false) }
  var downloadStarted by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState()

  val needToDownloadFirst =
    (downloadStatus?.status == ModelDownloadStatusType.NOT_DOWNLOADED ||
      downloadStatus?.status == ModelDownloadStatusType.FAILED) &&
      model.localFileRelativeDirPathOverride.isEmpty()
  val inProgress = downloadStatus?.status == ModelDownloadStatusType.IN_PROGRESS
  val downloadSucceeded = downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
  val isPartiallyDownloaded = downloadStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED
  val showDownloadProgress =
    !downloadSucceeded && (downloadStarted || checkingToken || inProgress || isPartiallyDownloaded)
  var curDownloadProgress: Float

  // A launcher for requesting notification permission.
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      modelManagerViewModel.downloadModel(task = task, model = model)
    }

  // Function to kick off download.
  val startDownload: (accessToken: String?) -> Unit = { accessToken ->
    model.accessToken = accessToken
    checkNotificationPermissionAndStartDownload(
      context = context,
      launcher = permissionLauncher,
      modelManagerViewModel = modelManagerViewModel,
      task = task,
      model = model,
    )
    checkingToken = false
  }

  // A launcher for opening the custom tabs intent for requesting user agreement ack.
  // Once the tab is closed, try starting the download process.
  val agreementAckLauncher: ActivityResultLauncher<Intent> =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      Log.d(TAG, "User closes the browser tab. Try to start downloading.")
      startDownload(modelManagerViewModel.curAccessToken)
    }

  // A launcher for handling the authentication flow.
  // It processes the result of the authentication activity and then checks if a user agreement
  // acknowledgement is needed before proceeding with the model download.
  val authResultLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      modelManagerViewModel.handleAuthResult(
        result,
        onTokenRequested = { tokenRequestResult ->
          when (tokenRequestResult.status) {
            TokenRequestResultType.SUCCEEDED -> {
              Log.d(TAG, "Token request succeeded. Checking if we need user to ack user agreement")
              scope.launch(Dispatchers.IO) {
                // Check if we can use the current token to access model. If not, we might need to
                // acknowledge the user agreement.
                if (
                  modelManagerViewModel.getModelUrlResponse(
                    model = model,
                    accessToken = modelManagerViewModel.curAccessToken,
                  ) == HttpURLConnection.HTTP_FORBIDDEN
                ) {
                  Log.d(TAG, "Model '${model.name}' needs user agreement ack.")
                  showAgreementAckSheet = true
                } else {
                  Log.d(
                    TAG,
                    "Model '${model.name}' does NOT need user agreement ack. Start downloading...",
                  )
                  withContext(Dispatchers.Main) {
                    startDownload(modelManagerViewModel.curAccessToken)
                  }
                }
              }
            }

            TokenRequestResultType.FAILED -> {
              Log.d(
                TAG,
                "Token request done. Error message: ${tokenRequestResult.errorMessage ?: ""}",
              )
              checkingToken = false
              downloadStarted = false
            }

            TokenRequestResultType.USER_CANCELLED -> {
              Log.d(TAG, "User cancelled. Do nothing")
              checkingToken = false
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

  // Launches a coroutine to handle the initial check and potential authentication flow
  // before downloading the model. It checks if the model needs to be downloaded first,
  // handles HuggingFace URLs by verifying the need for authentication, and initiates
  // the token exchange process if required or proceeds with the download if no auth is needed
  // or a valid token is available.
  val handleClickButton = {
    scope.launch(Dispatchers.IO) {
      if (needToDownloadFirst) {
        downloadStarted = true
        // For HuggingFace urls
        if (model.url.startsWith("https://huggingface.co")) {
          checkingToken = true

          // Check if the url needs auth.
          Log.d(
            TAG,
            "Model '${model.name}' is from HuggingFace. Checking if the url needs auth to download",
          )
          val firstResponseCode = modelManagerViewModel.getModelUrlResponse(model = model)
          if (firstResponseCode == HttpURLConnection.HTTP_OK) {
            Log.d(TAG, "Model '${model.name}' doesn't need auth. Start downloading the model...")
            withContext(Dispatchers.Main) { startDownload(null) }
            return@launch
          } else if (firstResponseCode < 0) {
            checkingToken = false
            downloadStarted = false
            Log.e(TAG, "Unknown network error")
            showErrorDialog = true
            return@launch
          }
          Log.d(TAG, "Model '${model.name}' needs auth. Start token exchange process...")

          // Get current token status
          val tokenStatusAndData = modelManagerViewModel.getTokenStatusAndData()

          when (tokenStatusAndData.status) {
            // If token is not stored or expired, log in and request a new token.
            TokenStatus.NOT_STORED,
            TokenStatus.EXPIRED -> {
              withContext(Dispatchers.Main) { startTokenExchange() }
            }

            // If token is still valid...
            TokenStatus.NOT_EXPIRED -> {
              // Use the current token to check the download url.
              Log.d(TAG, "Checking the download url '${model.url}' with the current token...")
              val responseCode =
                modelManagerViewModel.getModelUrlResponse(
                  model = model,
                  accessToken = tokenStatusAndData.data!!.accessToken,
                )
              if (responseCode == HttpURLConnection.HTTP_OK) {
                // Download url is accessible. Download the model.
                Log.d(TAG, "Download url is accessible with the current token.")

                withContext(Dispatchers.Main) {
                  startDownload(tokenStatusAndData.data!!.accessToken)
                }
              }
              // Download url is NOT accessible. Request a new token.
              else {
                Log.d(
                  TAG,
                  "Download url is NOT accessible. Response code: ${responseCode}. Trying to request a new token.",
                )

                withContext(Dispatchers.Main) { startTokenExchange() }
              }
            }
          }
        }
        // For other urls, just download the model.
        else {
          Log.d(
            TAG,
            "Model '${model.name}' is not from huggingface. Start downloading the model...",
          )
          withContext(Dispatchers.Main) { startDownload(null) }
        }
      }
      // No need to download. Directly open the model.
      else {
        withContext(Dispatchers.Main) { onClicked() }
      }
    }
  }

  if (!showDownloadProgress) {
    var buttonModifier: Modifier = modifier.height(42.dp)
    if (!compact) {
      buttonModifier = buttonModifier.fillMaxWidth()
    }
    Button(
      modifier = buttonModifier,
      colors =
        ButtonDefaults.buttonColors(
          containerColor =
            if (
              (!downloadSucceeded || !canShowTryIt) &&
                model.localFileRelativeDirPathOverride.isEmpty()
            )
              MaterialTheme.colorScheme.surfaceContainer
            else getTaskBgGradientColors(task = task)[1]
        ),
      contentPadding = PaddingValues(horizontal = 12.dp),
      onClick = {
        if (!enabled || checkingToken) {
          return@Button
        }

        if (isMemoryLow(context = context, model = model)) {
          showMemoryWarning = true
        } else {
          handleClickButton()
        }
      },
    ) {
      val textColor =
        if (!downloadSucceeded && model.localFileRelativeDirPathOverride.isEmpty())
          MaterialTheme.colorScheme.onSurface
        else Color.White
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          if (needToDownloadFirst) Icons.Outlined.FileDownload
          else Icons.AutoMirrored.Rounded.ArrowForward,
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
              stringResource(R.string.try_it),
              color = textColor,
              style = MaterialTheme.typography.titleMedium,
            )
          }
        }
      }
    }
  }
  // Download progress.
  else {
    curDownloadProgress =
      downloadStatus!!.receivedBytes.toFloat() / downloadStatus.totalBytes.toFloat()
    if (curDownloadProgress.isNaN()) {
      curDownloadProgress = 0f
    }
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
      if (checkingToken) {
        Text(
          stringResource(R.string.checking_access),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
          modifier = if (!compact) Modifier.fillMaxWidth() else Modifier.padding(horizontal = 4.dp),
        )
      } else {
        Text(
          "${(curDownloadProgress * 100).toInt()}%",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(start = 12.dp).width(if (compact) 32.dp else 44.dp),
        )
        if (!compact) {
          LinearProgressIndicator(
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            progress = { animatedProgress.value },
            color = getTaskBgGradientColors(task = task)[1],
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          )
        }
        val cbStop = stringResource(R.string.cd_stop_icon)
        IconButton(
          onClick = {
            downloadStarted = false
            modelManagerViewModel.cancelDownloadModel(task = task, model = model)
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
    }
    LaunchedEffect(curDownloadProgress) {
      animatedProgress.animateTo(curDownloadProgress, animationSpec = tween(150))
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
        checkingToken = false
      },
      sheetState = sheetState,
      modifier = Modifier.wrapContentHeight(),
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 16.dp),
      ) {
        Text("Acknowledge user agreement", style = MaterialTheme.typography.titleLarge)
        Text(
          "This is a gated model. Please click the button below to view and agree to the user agreement. After accepting, simply close that tab to proceed with the model download.",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(
          onClick = {
            // Get agreement url from model url.
            val index = model.url.indexOf("/resolve/")
            // Show it in a tab.
            if (index >= 0) {
              val agreementUrl = model.url.substring(0, index)

              val customTabsIntent = CustomTabsIntent.Builder().build()
              customTabsIntent.intent.setData(agreementUrl.toUri())
              agreementAckLauncher.launch(customTabsIntent.intent)
            }
            // Dismiss the sheet.
            showAgreementAckSheet = false
          }
        ) {
          Text("Open user agreement")
        }
      }
    }
  }

  if (showErrorDialog) {
    AlertDialog(
      icon = {
        Icon(
          Icons.Rounded.Error,
          contentDescription = stringResource(R.string.cd_error),
          tint = MaterialTheme.colorScheme.error,
        )
      },
      title = { Text("Unknown network error") },
      text = { Text("Please check your internet connection.") },
      onDismissRequest = { showErrorDialog = false },
      confirmButton = { TextButton(onClick = { showErrorDialog = false }) { Text("Close") } },
    )
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
}
