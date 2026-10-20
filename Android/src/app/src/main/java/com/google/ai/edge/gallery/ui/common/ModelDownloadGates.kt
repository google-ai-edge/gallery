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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DownloadAuthError
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.modelmanager.DownloadClickAction
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.TokenRequestResultType

private const val TAG = "AGModelDownloadGates"

/**
 * The checks that run before a model download starts: the low-memory warning, Hugging Face
 * authentication (including the gated-repo user agreement), and the notification permission.
 *
 * Shared by [DownloadAndTryButton], which runs them for one model, and by screens that download
 * several models at once, which run them one after another with [runAll]. Create it with
 * [rememberModelDownloadGates] and render its dialogs with [ModelDownloadGatesHost].
 */
@Stable
class ModelDownloadGates
internal constructor(
  private val context: Context,
  private val modelManagerViewModel: ModelManagerViewModel,
) {
  /** The low-memory warning waiting for the user's answer, or `null` if none is shown. */
  internal var memoryWarning by mutableStateOf<MemoryWarningRequest?>(null)
    private set

  /** The gated model whose user agreement sheet is shown, or `null` if none is shown. */
  internal var agreementSheetModel by mutableStateOf<Model?>(null)
    private set

  internal var launchers: Launchers? = null

  /** See [ModelDownloadGatesFallback]. */
  internal var fallback: ModelDownloadGatesFallback? = null

  private var accessRequest: AccessRequest? = null
  private var onNotificationPermissionResult: (() -> Unit)? = null

  /**
   * Shows the low-memory warning if the device has less memory than [model] needs. Calls
   * [onProceed] right away when memory is enough or once the user proceeds anyway, and [onCancel]
   * if the user cancels.
   */
  fun checkMemory(model: Model, onProceed: () -> Unit, onCancel: () -> Unit = {}) {
    if (isMemoryLow(context = context, model = model)) {
      memoryWarning = MemoryWarningRequest(onProceed = onProceed, onCancel = onCancel)
    } else {
      onProceed()
    }
  }

  /**
   * Decides what starting [model]'s download does (see
   * [ModelManagerViewModel.downloadClickAction]):
   * - Calls [onAccessible] right away with the stored access token (`null` when none is needed) if
   *   no prior download attempt was refused, or once the user completes OAuth or the gated-repo
   *   agreement.
   * - Calls [onAborted] when the user cancels OAuth or the agreement sheet.
   */
  fun checkAccess(
    model: Model,
    onAccessible: (accessToken: String?, afterSignIn: Boolean) -> Unit,
    onAborted: () -> Unit,
  ) {
    when (val action = modelManagerViewModel.downloadClickAction(model)) {
      is DownloadClickAction.Download -> onAccessible(action.accessToken, false)
      is DownloadClickAction.ResolveAuth ->
        resolveAuthError(
          model = model,
          authError = action.authError,
          onAccessible = onAccessible,
          onAborted = onAborted,
        )
    }
  }

  /**
   * Prompts the user for the missing access reported by a refused Hugging Face download
   * ([authError] from [ModelManagerViewModel.takeAuthErrorToPrompt]), and calls [onAccessible] once
   * the user signs in or closes the license agreement tab.
   */
  fun resolveAuthError(
    model: Model,
    authError: DownloadAuthError,
    onAccessible: (accessToken: String?, afterSignIn: Boolean) -> Unit,
    onAborted: () -> Unit = {},
  ) {
    accessRequest = AccessRequest(model = model, onAccessible = onAccessible, onAborted = onAborted)
    when (authError) {
      DownloadAuthError.NEEDS_SIGN_IN -> {
        Log.d(TAG, "Model '${model.name}' needs a Hugging Face token. Initiating OAuth exchange...")
        startTokenExchange()
      }
      DownloadAuthError.NEEDS_LICENSE_ACCEPTANCE -> {
        Log.d(TAG, "Model '${model.name}' is gated. Displaying license acknowledgment sheet.")
        agreementSheetModel = model
      }
    }
  }

  /**
   * Requests the notification permission, used for download progress notifications, if it isn't
   * granted yet.
   *
   * Calls [onDone] once the permission is granted or the user answers the request, whatever the
   * answer. When the permission can't be requested (below Android 13) and isn't granted, calls
   * [onUnavailable] instead.
   */
  fun requestNotificationPermission(onDone: () -> Unit, onUnavailable: () -> Unit = {}) {
    when {
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED -> onDone()
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
        onNotificationPermissionResult = onDone
        launchers?.permission?.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
      else -> onUnavailable()
    }
  }

  /**
   * Runs the gates for downloading several models together, one after another: the low-memory
   * warning for [memoryCheckModel] (if any), the access check for each of [modelsToDownload] (which
   * stores the access token on the model and records the user-started download), and then the
   * notification permission once.
   *
   * Calls [onPassed] when every gate passes, or [onAborted] as soon as one fails or the user
   * cancels.
   */
  fun runAll(
    memoryCheckModel: Model?,
    modelsToDownload: List<Model>,
    onPassed: () -> Unit,
    onAborted: () -> Unit,
  ) {
    fun checkAccessFrom(index: Int) {
      if (index >= modelsToDownload.size) {
        if (modelsToDownload.isEmpty()) {
          onPassed()
        } else {
          requestNotificationPermission(onDone = onPassed, onUnavailable = onPassed)
        }
        return
      }
      val model = modelsToDownload[index]
      checkAccess(
        model = model,
        onAccessible = { accessToken, afterSignIn ->
          model.downloadInfo.accessToken = accessToken?.takeIf { it.isNotEmpty() }
          modelManagerViewModel.onUserStartedDownload(model = model, afterSignIn = afterSignIn)
          checkAccessFrom(index + 1)
        },
        onAborted = onAborted,
      )
    }

    if (memoryCheckModel != null) {
      checkMemory(
        model = memoryCheckModel,
        onProceed = { checkAccessFrom(0) },
        onCancel = onAborted,
      )
    } else {
      checkAccessFrom(0)
    }
  }

  internal fun onMemoryWarningProceeded() {
    val request = memoryWarning ?: return
    memoryWarning = null
    request.onProceed()
  }

  internal fun onMemoryWarningDismissed() {
    val request = memoryWarning ?: return
    memoryWarning = null
    request.onCancel()
  }

  internal fun onNotificationPermissionResult() {
    val callback = onNotificationPermissionResult ?: fallback?.onNotificationPermissionResult
    onNotificationPermissionResult = null
    callback?.invoke()
  }

  /** Handles the result of the authentication flow, then continues with the new token. */
  internal fun onAuthResult(result: ActivityResult) {
    modelManagerViewModel.handleAuthResult(
      result,
      onTokenRequested = { tokenRequestResult ->
        when (tokenRequestResult.status) {
          TokenRequestResultType.SUCCEEDED -> {
            Log.d(TAG, "Token request succeeded. Restarting the download.")
            val request = accessRequest ?: fallback?.toAccessRequest() ?: return@handleAuthResult
            accessRequest = null
            request.onAccessible(modelManagerViewModel.curAccessToken, true)
          }

          TokenRequestResultType.FAILED -> {
            Log.d(
              TAG,
              "Token request done. Error message: ${tokenRequestResult.errorMessage ?: ""}",
            )
            abortAccessRequest()
          }

          TokenRequestResultType.USER_CANCELLED -> {
            Log.d(TAG, "User cancelled. Do nothing")
            abortAccessRequest()
          }
        }
      },
    )
  }

  /** Continues the download once the user closes the user agreement tab. */
  internal fun onAgreementTabClosed() {
    Log.d(TAG, "User closes the browser tab. Restarting the download.")
    // The token may have expired while the tab was open; sign in again rather than retry with it.
    val token = modelManagerViewModel.validStoredAccessToken()
    if (token == null) {
      startTokenExchange()
    } else {
      val request = accessRequest ?: fallback?.toAccessRequest() ?: return
      accessRequest = null
      request.onAccessible(token, false)
    }
  }

  /** Opens [model]'s user agreement in a custom tab and dismisses the agreement sheet. */
  internal fun openUserAgreement(model: Model) {
    // Get agreement url from model url.
    val index = model.downloadInfo.url.indexOf("/resolve/")
    // Show it in a tab.
    val launcher = launchers?.agreement
    if (index >= 0 && launcher != null) {
      val agreementUrl = model.downloadInfo.url.substring(0, index)
      val customTabsIntent = CustomTabsIntent.Builder().build()
      customTabsIntent.intent.setData(agreementUrl.toUri())
      launcher.launch(customTabsIntent.intent)
      agreementSheetModel = null
    } else {
      agreementSheetModel = null
      abortAccessRequest()
    }
  }

  internal fun onAgreementSheetDismissed() {
    agreementSheetModel = null
    abortAccessRequest()
  }

  /** Kicks off the authentication and token exchange flow. */
  private fun startTokenExchange() {
    val authRequest = modelManagerViewModel.getAuthorizationRequest()
    val authIntent = modelManagerViewModel.authService.getAuthorizationRequestIntent(authRequest)
    launchers?.auth?.launch(authIntent)
  }

  private fun abortAccessRequest() {
    val request = accessRequest ?: fallback?.toAccessRequest() ?: return
    accessRequest = null
    request.onAborted()
  }

  private fun ModelDownloadGatesFallback.toAccessRequest(): AccessRequest =
    AccessRequest(model = model, onAccessible = onAccessible, onAborted = onAborted)

  /** A low-memory warning waiting for the user's answer. */
  internal class MemoryWarningRequest(val onProceed: () -> Unit, val onCancel: () -> Unit)

  /** Activity result launchers registered by [rememberModelDownloadGates]. */
  internal class Launchers(
    val permission: ActivityResultLauncher<String>,
    val auth: ActivityResultLauncher<Intent>,
    val agreement: ActivityResultLauncher<Intent>,
  )

  private class AccessRequest(
    val model: Model,
    val onAccessible: (accessToken: String?, afterSignIn: Boolean) -> Unit,
    val onAborted: () -> Unit,
  )
}

/**
 * What [ModelDownloadGates] does with an activity result (notification permission, authentication,
 * or user agreement tab) that arrives without a pending request, which happens when the activity
 * was recreated while the result activity was showing. Single-model callers such as
 * [DownloadAndTryButton] pass it so the download still continues in that case.
 *
 * @property model The model being downloaded.
 * @property onAccessible Called with the access token and whether it came from a fresh sign-in.
 * @property onAborted Called when the user cancels OAuth or the agreement sheet.
 * @property onNotificationPermissionResult Called once the user answers the permission request.
 */
class ModelDownloadGatesFallback(
  val model: Model,
  val onAccessible: (accessToken: String?, afterSignIn: Boolean) -> Unit,
  val onAborted: () -> Unit,
  val onNotificationPermissionResult: () -> Unit,
)

/**
 * Creates and remembers [ModelDownloadGates], registering the activity result launchers it needs.
 *
 * @param fallback See [ModelDownloadGatesFallback].
 */
@Composable
fun rememberModelDownloadGates(
  modelManagerViewModel: ModelManagerViewModel,
  fallback: ModelDownloadGatesFallback? = null,
): ModelDownloadGates {
  val context = LocalContext.current
  val gates =
    remember(modelManagerViewModel) {
      ModelDownloadGates(context = context, modelManagerViewModel = modelManagerViewModel)
    }

  // A launcher for requesting notification permission.
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      gates.onNotificationPermissionResult()
    }
  // A launcher for handling the authentication flow.
  val authResultLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      gates.onAuthResult(result)
    }
  // A launcher for opening the custom tabs intent for requesting user agreement ack.
  val agreementAckLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      gates.onAgreementTabClosed()
    }
  SideEffect {
    gates.fallback = fallback
    gates.launchers =
      ModelDownloadGates.Launchers(
        permission = permissionLauncher,
        auth = authResultLauncher,
        agreement = agreementAckLauncher,
      )
  }
  return gates
}

/**
 * Renders the dialogs of [gates]: the gated-repo user agreement sheet and the low-memory warning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadGatesHost(gates: ModelDownloadGates) {
  // The agreement sheet is short, so it opens fully expanded without a partially expanded state.
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  // A ModalBottomSheet composable that displays information about the user agreement
  // for a gated model and provides a button to open the agreement in a custom tab.
  val agreementSheetModel = gates.agreementSheetModel
  if (agreementSheetModel != null) {
    ModalBottomSheet(
      onDismissRequest = { gates.onAgreementSheetDismissed() },
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
        Button(onClick = { gates.openUserAgreement(agreementSheetModel) }) {
          Text(stringResource(R.string.open_user_agreement))
        }
      }
    }
  }

  if (gates.memoryWarning != null) {
    MemoryWarningAlert(
      onProceeded = { gates.onMemoryWarningProceeded() },
      onDismissed = { gates.onMemoryWarningDismissed() },
    )
  }
}
