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

package com.google.ai.edge.gallery.customtasks.examplecustomtask

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ExampleCustomTaskViewModelTest {

  private lateinit var viewModel: ExampleCustomTaskViewModel

  @Before
  fun setUp() {
    viewModel = ExampleCustomTaskViewModel()
  }

  // ---------------------------------------------------------------------------
  // Initial state
  // ---------------------------------------------------------------------------

  @Test
  fun `initial uiState has textColor set to Black`() {
    assertEquals(Color.Black, viewModel.uiState.value.textColor)
  }

  // ---------------------------------------------------------------------------
  // updateTextColor
  // ---------------------------------------------------------------------------

  @Test
  fun `updateTextColor changes textColor in uiState`() {
    viewModel.updateTextColor(Color.Red)
    assertEquals(Color.Red, viewModel.uiState.value.textColor)
  }

  @Test
  fun `updateTextColor to Blue is reflected in uiState`() {
    viewModel.updateTextColor(Color.Blue)
    assertEquals(Color.Blue, viewModel.uiState.value.textColor)
  }

  @Test
  fun `updateTextColor can be called multiple times and reflects the latest value`() {
    viewModel.updateTextColor(Color.Green)
    viewModel.updateTextColor(Color.Yellow)
    viewModel.updateTextColor(Color.Cyan)

    assertEquals(Color.Cyan, viewModel.uiState.value.textColor)
  }

  @Test
  fun `updateTextColor with same color is idempotent`() {
    viewModel.updateTextColor(Color.Red)
    viewModel.updateTextColor(Color.Red)

    assertEquals(Color.Red, viewModel.uiState.value.textColor)
  }

  @Test
  fun `updateTextColor with White updates state correctly`() {
    viewModel.updateTextColor(Color.White)
    assertEquals(Color.White, viewModel.uiState.value.textColor)
  }

  // ---------------------------------------------------------------------------
  // State immutability — each update creates a new UiState copy
  // ---------------------------------------------------------------------------

  @Test
  fun `each updateTextColor call produces a new uiState instance`() {
    val stateBeforeUpdate = viewModel.uiState.value

    viewModel.updateTextColor(Color.Magenta)

    val stateAfterUpdate = viewModel.uiState.value
    // The new state should be a different object
    assert(stateBeforeUpdate !== stateAfterUpdate)
    assertEquals(Color.Magenta, stateAfterUpdate.textColor)
  }

  @Test
  fun `uiState before update is not modified by subsequent update`() {
    val capturedState = viewModel.uiState.value.copy()

    viewModel.updateTextColor(Color.Gray)

    // The previously captured copy still has the original color
    assertEquals(Color.Black, capturedState.textColor)
    assertEquals(Color.Gray, viewModel.uiState.value.textColor)
  }
}
