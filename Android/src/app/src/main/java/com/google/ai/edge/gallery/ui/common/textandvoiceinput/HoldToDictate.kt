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
package com.google.ai.edge.gallery.ui.common.textandvoiceinput

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "AGHoldToDictate"

/**
 * A Composable that provides a "Hold to Dictate" functionality.
 *
 * This composable requests RECORD_AUDIO permission and, once granted, displays a button. The user
 * can press and hold the button to start speech recognition. Releasing the button stops the
 * recognition. Moving the finger off the button while holding will cancel the recognition.
 */
@Composable
fun HoldToDictate(
  task: Task,
  viewModel: HoldToDictateViewModel,
  onDone: (String) -> Unit,
  onAmplitudeChanged: (Int) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsState()
  var recordAudioPermissionGranted by remember { mutableStateOf(false) }
  val context = LocalContext.current

  val recordAudioPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      permissionGranted ->
      if (permissionGranted) {
        recordAudioPermissionGranted = true
      }
    }

  LaunchedEffect(Unit) {
    // Check permission
    when (PackageManager.PERMISSION_GRANTED) {
      // Already got permission.
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
        recordAudioPermissionGranted = true
      }

      // Otherwise, ask for permission
      else -> {
        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  if (recordAudioPermissionGranted) {
    Box(
      modifier =
        modifier
          .then(
            if (enabled) {
              Modifier.pointerInput(Unit) {
                detectTapGestures(
                  onPress = {
                    viewModel.startSpeechRecognition(
                      onDone = onDone,
                      onAmplitudeChanged = onAmplitudeChanged,
                    )
                    try {
                      awaitRelease()
                    } catch (e: CancellationException) {
                      // Move out of the button to cancel it.
                      viewModel.cancelSpeechRecognition()
                      return@detectTapGestures
                    }

                    // Release to stop recognition.
                    viewModel.stopSpeechRecognition()
                  }
                )
              }
            } else {
              Modifier
            }
          )
          .clip(CircleShape)
          .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
          .background(getTaskBgGradientColors(task = task)[1])
          .height(48.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        stringResource(if (uiState.recognizing) R.string.listening else R.string.hold_down_to_talk),
        color = Color.White,
      )
    }
  }
}
