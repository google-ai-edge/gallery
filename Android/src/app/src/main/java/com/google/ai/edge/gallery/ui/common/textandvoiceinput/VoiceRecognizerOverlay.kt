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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.AudioAnimation
import com.google.ai.edge.gallery.ui.common.getTaskBgGradientColors

private const val TAG = "AGVROverlay"

/**
 * A Composable that displays the UI after user holding down on the "Hold to Dictate" button.
 *
 * It shows the recognized text, an audio level animation, and instructions for the user.
 */
@Composable
fun VoiceRecognizerOverlay(
  task: Task,
  viewModel: HoldToDictateViewModel,
  bottomPadding: Dp,
  curAmplitude: Int,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsState()

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
    // Audio level animation.
    AudioAnimation(bgColor = Color.Black.copy(alpha = 0.8f), amplitude = curAmplitude)

    // Recognized text.
    Text(
      uiState.recognizedText.ifEmpty { stringResource(R.string.listening) },
      modifier =
        Modifier.padding(horizontal = 16.dp)
          .padding(bottom = (48.dp + bottomPadding) / 2)
          .align(Alignment.Center),
      color = Color.White,
    )

    Column(
      modifier =
        Modifier.padding(bottom = bottomPadding).padding(horizontal = 16.dp).fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Instructions
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          stringResource(R.string.release_to_send),
          color = Color.Black,
          style = MaterialTheme.typography.labelMedium,
        )
        Text(
          stringResource(R.string.slide_up_to_cancel),
          color = Color.Black,
          style = MaterialTheme.typography.labelMedium,
        )
      }

      // A button that covers the HoldToDictate button.
      Box(
        modifier =
          modifier
            .pointerInput(Unit) {}
            .clip(CircleShape)
            .background(getTaskBgGradientColors(task = task)[1])
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(stringResource(R.string.listening), color = Color.White)
      }
    }
  }
}
