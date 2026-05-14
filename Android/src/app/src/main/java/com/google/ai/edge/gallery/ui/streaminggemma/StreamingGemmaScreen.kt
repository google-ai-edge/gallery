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

package com.google.ai.edge.gallery.ui.streaminggemma

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
fun StreamingGemmaScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: StreamingGemmaViewModel = hiltViewModel(),
) {
  val streamingUiState by viewModel.streamingUiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val activeModel = modelManagerUiState.selectedModel

  LaunchedEffect(activeModel.name) { viewModel.initializeSession(activeModel) }

  Column(
    modifier = modifier.fillMaxSize().padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(text = "Status: ${streamingUiState.statusText}")
    Text(text = "Recognized: ${streamingUiState.recognizedText}")

    if (streamingUiState.responseText.isNotBlank()) {
      Text(
        text = "Response: ${streamingUiState.responseText}",
        modifier = Modifier.padding(top = 16.dp),
      )
    }

    Button(
      onClick = {
        if (streamingUiState.isListening) {
          viewModel.stopListening()
        } else {
          viewModel.startListening()
        }
      },
      modifier = Modifier.padding(top = 16.dp),
    ) {
      Text(text = if (streamingUiState.isListening) "Stop Listening" else "Start Listening")
    }
  }
}
