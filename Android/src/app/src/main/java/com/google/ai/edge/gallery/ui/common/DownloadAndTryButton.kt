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

import android.app.ActivityManager
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.TokenRequestResultType
import com.google.ai.edge.gallery.ui.modelmanager.TokenStatus
import java.net.HttpURLConnection
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGDownloadAndTryButton"

// TODO:
// - replace the download button in chat view page with this one, and add a flag to not "onclick"
//   just download

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
  needToDownloadFirst: Boolean,
  modelManagerViewModel: ModelManagerViewModel,
  onClicked: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var checkingToken by remember { mutableStateOf(false) }
  var showAgreementAckSheet by remember { mutableStateOf(false) }
  var showErrorDialog by remember { mutableStateOf(false) }
  var showMemoryWarning by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState()

  // A launcher for requesting notification permission.
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      modelManagerViewModel.downloadModel(task = task, model = model)
    }

  // Function to kick off download.
  val startDownload: (accessToken: String?) -> Unit = { accessToken ->
    model.accessToken = accessToken
    onClicked()
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
            }

            TokenRequestResultType.USER_CANCELLED -> {
              Log.d(TAG, "User cancelled. Do nothing")
              checkingToken = false
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

  Button(
    onClick = {
      if (!enabled || checkingToken) {
        return@Button
      }

      // Launches a coroutine to handle the initial check and potential authentication flow
      // before downloading the model. It checks if the model needs to be downloaded first,
      // handles HuggingFace URLs by verifying the need for authentication, and initiates
      // the token exchange process if required or proceeds with the download if no auth is needed
      // or a valid token is available.
      scope.launch(Dispatchers.IO) {
        if (needToDownloadFirst) {
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
        } else {
          withContext(Dispatchers.Main) {
            val activityManager =
              context.getSystemService(android.app.Activity.ACTIVITY_SERVICE) as? ActivityManager
            val estimatedPeakMemoryInBytes = model.estimatedPeakMemoryInBytes

            val isMemoryLow =
              if (activityManager != null && estimatedPeakMemoryInBytes != null) {
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                Log.d(
                  TAG,
                  "AvailMem: ${memoryInfo.availMem}. TotalMem: ${memoryInfo.totalMem}. Estimated peak memory: ${estimatedPeakMemoryInBytes}.",
                )

                // The device should be able to run the model if `availMem` is larger than the
                // estimated peak memory. Android also has a mechanism to kill background apps to
                // free up memory for the foreground app. We believe that if half of the total
                // memory on the device is larger than the estimated peak memory, it can run the
                // model fine with this mechanism. For example, a phone with 12GB memory can have
                // very few `availMem` but will have no problem running most models.
                max(memoryInfo.availMem, memoryInfo.totalMem / 2) < estimatedPeakMemoryInBytes
              } else {
                false
              }

            if (isMemoryLow) {
              showMemoryWarning = true
            } else {
              onClicked()
            }
          }
        }
      }
    }
  ) {
    Icon(
      Icons.AutoMirrored.Rounded.ArrowForward,
      contentDescription = "",
      modifier = Modifier.padding(end = 4.dp),
    )

    val textColor = MaterialTheme.colorScheme.onPrimary
    if (checkingToken) {
      BasicText(
        text = "Checking access...",
        maxLines = 1,
        color = { textColor },
        style = MaterialTheme.typography.bodyMedium,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp, stepSize = 1.sp),
      )
    } else {
      if (needToDownloadFirst) {
        BasicText(
          text = "Download & Try",
          maxLines = 1,
          color = { textColor },
          style = MaterialTheme.typography.bodyMedium,
          autoSize =
            TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 14.sp, stepSize = 1.sp),
        )
      } else {
        Text("Try it", maxLines = 1)
      }
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
        Icon(Icons.Rounded.Error, contentDescription = "", tint = MaterialTheme.colorScheme.error)
      },
      title = { Text("Unknown network error") },
      text = { Text("Please check your internet connection.") },
      onDismissRequest = { showErrorDialog = false },
      confirmButton = { TextButton(onClick = { showErrorDialog = false }) { Text("Close") } },
    )
  }

  if (showMemoryWarning) {
    AlertDialog(
      title = { Text("Memory Warning") },
      text = {
        Text(
          "This model might need more memory than your device has available. " +
            "Running it could cause the app to crash."
        )
      },
      onDismissRequest = { showMemoryWarning = false },
      confirmButton = {
        TextButton(
          onClick = {
            onClicked()
            showMemoryWarning = false
          }
        ) {
          Text("Continue")
        }
      },
      dismissButton = { TextButton(onClick = { showMemoryWarning = false }) { Text("Cancel") } },
    )
  }
}
