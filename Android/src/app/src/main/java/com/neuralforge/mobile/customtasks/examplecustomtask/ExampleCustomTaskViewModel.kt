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

package com.neuralforge.mobile.customtasks.examplecustomtask

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The UI state of the example custom task screen.
 *
 * It tracks the current text color.
 */
data class ExampleCustomTaskUiState(val textColor: Color)

/** The ViewModel of the example custom task screen. */
@HiltViewModel
class ExampleCustomTaskViewModel @Inject constructor() : ViewModel() {
  protected val _uiState = MutableStateFlow(ExampleCustomTaskUiState(textColor = Color.Black))
  val uiState = _uiState.asStateFlow()

  fun updateTextColor(color: Color) {
    val newUiState = uiState.value.copy(textColor = color)
    _uiState.update { newUiState }
  }
}
