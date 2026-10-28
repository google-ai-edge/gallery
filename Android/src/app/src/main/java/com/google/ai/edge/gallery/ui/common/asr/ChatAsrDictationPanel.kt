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

package com.google.ai.edge.gallery.ui.common.asr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

/**
 * Chat input bar content shown while voice input is active: the live transcript above an
 * [AsrRecordingPill] with cancel, stop, and send buttons.
 *
 * @param onCancel Discards the transcript.
 * @param onStop Stops listening and lets the user edit the transcript in the text field.
 * @param onSend Stops listening and sends the transcript as the prompt.
 */
@Composable
fun ChatAsrDictationPanel(
  uiState: ChatAsrDictationUiState,
  onCancel: () -> Unit,
  onStop: () -> Unit,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val transcriptScrollState = rememberScrollState()
  LaunchedEffect(uiState.transcript) {
    transcriptScrollState.scrollTo(transcriptScrollState.maxValue)
  }

  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // Live transcript (auto-scrolls as speech streams in; manual scroll disabled).
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .heightIn(max = 72.dp)
          .verticalScroll(state = transcriptScrollState, enabled = false),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = uiState.transcript.ifBlank { stringResource(R.string.listening) },
        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        color =
          if (uiState.transcript.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
          else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier =
          Modifier.fillMaxWidth().padding(horizontal = 8.dp).semantics {
            liveRegion = LiveRegionMode.Polite
          },
      )
    }

    AsrRecordingPill(
      audioLevel = uiState.audioLevel,
      cancelContentDescription = stringResource(R.string.asr_dictation_cancel_desc),
      sendContentDescription = stringResource(R.string.asr_dictation_send_desc),
      stopContentDescription = stringResource(R.string.asr_dictation_stop_desc),
      onCancel = onCancel,
      onStop = onStop,
      onSend = onSend,
      showGlow = true,
    )
  }
}
