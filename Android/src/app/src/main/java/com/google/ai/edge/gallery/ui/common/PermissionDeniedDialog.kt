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
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.google.ai.edge.gallery.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "AGPermissionDeniedDialog"

/** System permission category surfaced by [PermissionDeniedDialog]. */
enum class PermissionAccessKind(
  val manifestPermission: String,
  @StringRes val titleResId: Int,
  @StringRes val messageResId: Int,
) {
  /** Microphone (`android.permission.RECORD_AUDIO`) access. */
  MICROPHONE(
    manifestPermission = Manifest.permission.RECORD_AUDIO,
    titleResId = R.string.microphone_access_denied_title,
    messageResId = R.string.microphone_access_denied_message,
  ),

  /** Camera (`android.permission.CAMERA`) access. */
  CAMERA(
    manifestPermission = Manifest.permission.CAMERA,
    titleResId = R.string.camera_access_denied_title,
    messageResId = R.string.camera_access_denied_message,
  ),
}

/**
 * Tracks which runtime permissions have been requested or granted at least once so the UI can
 * distinguish "never asked yet" (normal appearance; first tap opens the system permission prompt)
 * from "denied or revoked" (greyed-out appearance; tap prompts to open system Settings).
 */
object PermissionAccessTracker {
  private const val PREFS_NAME = "permission_access_prefs"
  private const val KEY_REQUESTED_PERMISSIONS = "requested_permissions"

  private val requestedPermissionsFlow = MutableStateFlow<Set<String>>(emptySet())
  private val stateVersionFlow = MutableStateFlow(0)
  private val isLoaded = AtomicBoolean(false)

  fun observeStateVersion(context: Context): StateFlow<Int> {
    ensureLoaded(context)
    return stateVersionFlow
  }

  fun ensureLoaded(context: Context) {
    if (isLoaded.compareAndSet(false, true)) {
      val saved =
        context.applicationContext
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getStringSet(KEY_REQUESTED_PERMISSIONS, emptySet())
          .orEmpty()
          .toSet()
      if (saved.isNotEmpty()) {
        requestedPermissionsFlow.update { current -> current + saved }
      }
    }
  }

  fun markPermissionRequested(context: Context, permission: String) {
    ensureLoaded(context)
    var updatedSet: Set<String>? = null
    requestedPermissionsFlow.update { current ->
      if (permission in current) {
        current
      } else {
        (current + permission).also { updatedSet = it }
      }
    }
    if (updatedSet != null) {
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
        putStringSet(KEY_REQUESTED_PERMISSIONS, updatedSet)
      }
    }
    stateVersionFlow.update { it + 1 }
  }

  fun onPermissionResult(context: Context, permission: String, granted: Boolean) {
    markPermissionRequested(context, permission)
  }

  fun isPermissionGranted(context: Context, kind: PermissionAccessKind): Boolean {
    val granted =
      ContextCompat.checkSelfPermission(context, kind.manifestPermission) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) {
      markPermissionRequested(context, kind.manifestPermission)
    }
    return granted
  }

  fun isPermissionDenied(context: Context, kind: PermissionAccessKind): Boolean {
    ensureLoaded(context)
    if (isPermissionGranted(context, kind)) {
      return false
    }
    val permission = kind.manifestPermission
    val hasBeenRequested = permission in requestedPermissionsFlow.value
    val shouldShowRationale =
      (context as? Activity)?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
      } == true
    if (shouldShowRationale && !hasBeenRequested) {
      markPermissionRequested(context, permission)
    }
    return hasBeenRequested || shouldShowRationale
  }

  @VisibleForTesting
  fun resetForTesting(context: Context? = null) {
    isLoaded.set(false)
    requestedPermissionsFlow.value = emptySet()
    context
      ?.applicationContext
      ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      ?.edit()
      ?.clear()
      ?.commit()
    stateVersionFlow.update { it + 1 }
  }
}

/**
 * Reactive permission state and launcher for [kind], rendering [PermissionDeniedDialog] when access
 * is denied.
 */
@Stable
class PermissionAccessState(
  val kind: PermissionAccessKind,
  val isGranted: Boolean,
  val isDenied: Boolean,
  private val onRequestOrRun: (() -> Unit) -> Unit,
  private val onShowDeniedDialog: () -> Unit,
) {
  /**
   * Runs [onGranted] immediately if [isGranted] is true; shows [PermissionDeniedDialog] if
   * [isDenied] is already true; otherwise launches the system permission prompt.
   */
  fun requestOrRun(onGranted: () -> Unit) {
    onRequestOrRun(onGranted)
  }

  /** Opens [PermissionDeniedDialog] for [kind] directly. */
  fun showDeniedDialog() {
    onShowDeniedDialog()
  }
}

/**
 * Remembers a [PermissionAccessState] for [kind] that refreshes automatically on
 * [Lifecycle.Event.ON_RESUME] and across permission results in the process, and hosts
 * [PermissionDeniedDialog] when access is denied.
 */
@Composable
fun rememberPermissionAccessState(kind: PermissionAccessKind): PermissionAccessState {
  val context = LocalContext.current
  val trackerVersion by PermissionAccessTracker.observeStateVersion(context).collectAsState()
  var resumeTick by remember { mutableIntStateOf(0) }
  var isDialogVisible by remember { mutableStateOf(false) }
  var pendingOnGranted by remember { mutableStateOf<(() -> Unit)?>(null) }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }

  val isGranted =
    remember(context, kind, trackerVersion, resumeTick) {
      PermissionAccessTracker.isPermissionGranted(context, kind)
    }
  val isDenied =
    remember(context, kind, isGranted, trackerVersion, resumeTick) {
      !isGranted && PermissionAccessTracker.isPermissionDenied(context, kind)
    }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      PermissionAccessTracker.onPermissionResult(context, kind.manifestPermission, granted)
      val callback = pendingOnGranted
      pendingOnGranted = null
      if (granted) {
        callback?.invoke()
      } else {
        isDialogVisible = true
      }
    }

  if (isDialogVisible) {
    PermissionDeniedDialog(kind = kind, onDismiss = { isDialogVisible = false })
  }

  val latestContext by rememberUpdatedState(context)
  val requestOrRunFn: (() -> Unit) -> Unit =
    remember(kind, permissionLauncher) {
      { onGranted ->
        val ctx = latestContext
        when {
          PermissionAccessTracker.isPermissionGranted(ctx, kind) -> onGranted()
          PermissionAccessTracker.isPermissionDenied(ctx, kind) -> isDialogVisible = true
          else -> {
            pendingOnGranted = onGranted
            permissionLauncher.launch(kind.manifestPermission)
          }
        }
      }
    }
  val showDeniedDialogFn: () -> Unit = remember { { isDialogVisible = true } }

  return remember(kind, isGranted, isDenied, requestOrRunFn, showDeniedDialogFn) {
    PermissionAccessState(
      kind = kind,
      isGranted = isGranted,
      isDenied = isDenied,
      onRequestOrRun = requestOrRunFn,
      onShowDeniedDialog = showDeniedDialogFn,
    )
  }
}

/**
 * Dialog shown when microphone or camera permission is denied, explaining how to re-enable access
 * in the app's system Settings page.
 */
@Composable
fun PermissionDeniedDialog(
  kind: PermissionAccessKind,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenSettings: (() -> Unit)? = null,
) {
  val defaultOpenSettings = rememberOpenAppPermissionSettings()
  val effectiveOpenSettings = onOpenSettings ?: defaultOpenSettings
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title = { Text(stringResource(kind.titleResId)) },
    text = { Text(stringResource(kind.messageResId)) },
    confirmButton = {
      TextButton(
        onClick = {
          onDismiss()
          effectiveOpenSettings()
        }
      ) {
        Text(stringResource(R.string.open_settings))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}

/**
 * Overlay shown inside a camera surface when `android.permission.CAMERA` is denied, offering a
 * direct button to open the app's system Settings page.
 */
@Composable
fun CameraAccessDeniedOverlay(modifier: Modifier = Modifier, onOpenSettings: (() -> Unit)? = null) {
  val defaultOpenSettings = rememberOpenAppPermissionSettings()
  val effectiveOpenSettings = onOpenSettings ?: defaultOpenSettings
  Column(
    modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = Icons.Rounded.VideocamOff,
      contentDescription = null,
      tint = Color.White.copy(alpha = 0.85f),
      modifier = Modifier.size(36.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = stringResource(R.string.camera_access_denied_title),
      style = MaterialTheme.typography.titleMedium,
      color = Color.White,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = stringResource(R.string.camera_access_denied_message),
      style = MaterialTheme.typography.bodyMedium,
      color = Color.White.copy(alpha = 0.8f),
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    FilledTonalButton(onClick = effectiveOpenSettings) {
      Text(stringResource(R.string.open_settings))
    }
  }
}

/** Returns a callback that opens the system App Info / permissions page for the current app. */
@Composable
fun rememberOpenAppPermissionSettings(): () -> Unit {
  val context = LocalContext.current
  return remember(context) { { AppPermissionSettings.open(context) } }
}

/** Builds and launches the system App Info intent where the user can toggle app permissions. */
internal object AppPermissionSettings {
  fun createIntent(packageName: String): Intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", packageName, null),
    )

  fun open(context: Context) {
    val intent =
      createIntent(context.packageName).apply {
        if (context !is Activity) {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      }
    runCatching { context.startActivity(intent) }
      .onFailure { e -> Log.w(TAG, "Failed to open application details settings", e) }
  }
}
