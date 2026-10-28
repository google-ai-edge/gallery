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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.xr

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.ai.edge.gallery.common.PermissionsResultListener
import com.google.ai.edge.gallery.common.VirtualDevicePermissionsHost

/**
 * Projected glasses virtual device ID and any ungranted runtime permissions that must be requested
 * on that virtual device before starting glasses camera or microphone capture.
 *
 * @property deviceId The virtual device ID from `ProjectedContext.createProjectedDeviceContext`.
 * @property missingPermissions Runtime permission names not yet granted on [deviceId].
 */
internal data class GlassesPermissionRequest(
  val deviceId: Int,
  val missingPermissions: List<String>,
)

/**
 * Helpers and constants for checking and requesting device-aware runtime permissions on the
 * connected glasses virtual device.
 */
internal object GlassesPermissions {
  /**
   * Request code for the device-aware camera and microphone grant on the glasses virtual device.
   */
  const val GLASSES_PERMISSION_REQUEST_CODE = 1002

  /**
   * Runtime permissions required on the glasses virtual device to stream the glasses camera preview
   * and capture speech from the glasses microphone.
   */
  val REQUIRED_GLASSES_PERMISSIONS: List<String> =
    listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

  /**
   * Checks whether the connected glasses virtual device is missing any
   * [REQUIRED_GLASSES_PERMISSIONS] and, on Android 15+ (`VANILLA_ICE_CREAM`), launches the system
   * device-aware permission dialog on [activity].
   *
   * Why this uses the platform 4-argument [Activity.requestPermissions] API on `VANILLA_ICE_CREAM`:
   * - Starting in Android 15 (API 35), `CAMERA` and `RECORD_AUDIO` are device-aware runtime
   *   permissions (`VirtualDeviceManager`): once an activity of this app runs on the glasses
   *   virtual device, sensor capture is attributed to the glasses `deviceId` and requires its own
   *   grant.
   * - In `androidx.xr.projected`, `ProjectedPermissionsResultContract` is deprecated and
   *   `ProjectedActivityCompat.requestPermissions` requires an `Activity` already running on the
   *   projected glasses display (throwing `IllegalArgumentException` if called from the phone's
   *   host `MainActivity`). Requesting via `Activity.requestPermissions(permissions, requestCode,
   *   deviceId)` on the host activity prompts for the glasses grant before switching into XR mode.
   *
   * @param activity Host phone activity used to launch the device-aware permission prompt.
   * @param checkMissingPermissions Callback that queries [XrGlassesManager] for any ungranted
   *   permissions on the connected glasses virtual device.
   * @return `true` if any required glasses permissions are missing (launching the runtime
   *   permission dialog when [activity] is non-null) so the caller waits for the grant result, or
   *   `false` if all permissions are already granted (or the device runs below API 35) so the
   *   caller can enter XR mode immediately.
   */
  fun requestMissingGlassesPermissions(
    activity: Activity?,
    checkMissingPermissions: (List<String>) -> GlassesPermissionRequest?,
  ): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      val permRequest = checkMissingPermissions(REQUIRED_GLASSES_PERMISSIONS)
      if (permRequest != null) {
        activity?.requestPermissions(
          permRequest.missingPermissions.toTypedArray(),
          GLASSES_PERMISSION_REQUEST_CODE,
          permRequest.deviceId,
        )
        return true
      }
    }
    return false
  }
}

/**
 * Remembers a callback that verifies (and, when needed on Android 15+, requests) the device-aware
 * camera and microphone permissions on the connected glasses virtual device before invoking
 * [onPermissionsGranted].
 *
 * Registers a [PermissionsResultListener] with the host [VirtualDevicePermissionsHost] activity for
 * the lifetime of the composition so that the result of the 4-argument
 * [Activity.requestPermissions] call routes to [onPermissionsGranted] or [onPermissionsDenied].
 *
 * @param checkMissingPermissions Queries [XrGlassesManager] for missing glasses permissions.
 * @param onPermissionsGranted Invoked immediately if all glasses permissions are already granted,
 *   or once the user grants all requested permissions in the dialog.
 * @param onPermissionsDenied Invoked if the user denies one or more glasses permissions.
 * @return A function to invoke when the user requests switching to XR glasses mode.
 */
@Composable
internal fun rememberGlassesPermissionRequester(
  checkMissingPermissions: (List<String>) -> GlassesPermissionRequest?,
  onPermissionsGranted: () -> Unit,
  onPermissionsDenied: () -> Unit,
): () -> Unit {
  val context = LocalContext.current
  val currentCheckMissing by rememberUpdatedState(checkMissingPermissions)
  val currentOnGranted by rememberUpdatedState(onPermissionsGranted)
  val currentOnDenied by rememberUpdatedState(onPermissionsDenied)

  DisposableEffect(context) {
    val hostActivity = context as? VirtualDevicePermissionsHost
    val listener = PermissionsResultListener { requestCode, _, grantResults, _ ->
      if (requestCode == GlassesPermissions.GLASSES_PERMISSION_REQUEST_CODE) {
        if (
          grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
          currentOnGranted()
        } else {
          currentOnDenied()
        }
      }
    }
    hostActivity?.registerPermissionsResultListener(listener)
    onDispose { hostActivity?.unregisterPermissionsResultListener(listener) }
  }

  return remember(context) {
    {
      val missingPermissions =
        GlassesPermissions.requestMissingGlassesPermissions(
          activity = context as? Activity,
          checkMissingPermissions = currentCheckMissing,
        )
      if (!missingPermissions) {
        currentOnGranted()
      }
    }
  }
}
