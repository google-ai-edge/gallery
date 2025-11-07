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

import androidx.hilt.navigation.compose.hiltViewModel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuralforge.mobile.data.ConfigKey
import com.neuralforge.mobile.data.NumberSliderConfig
import com.neuralforge.mobile.data.ValueType
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel

data class ExampleCustomTaskModelInstance(val content: String)

/**
 * Configuration keys for the `ExampleCustomTask`.
 *
 * These keys are used to uniquely identify and retrieve values for configurable parameters within a
 * model.
 */
val EXAMPLE_CUSTOM_TASK_CONFIG_KEY_FONT_SIZE = ConfigKey(id = "font_size", label = "Font size")
val EXAMPLE_CUSTOM_TASK_CONFIG_KEY_MAX_CHAR_COUNT =
  ConfigKey(id = "max_char_count", label = "Max character count")

/**
 * A list of configurable parameters for the `ExampleCustomTask`'s models.
 *
 * This list defines two user-adjustable settings that appear in the model configuration dialog:
 * 1. Font size: A `NumberSliderConfig` that allows the user to change the text font size.
 *    `needReinitialization = false` indicates that changing this value **does not** require the
 *    model to be reloaded, as it's a simple UI change.
 * 2. Max character count: A `NumberSliderConfig` to cap the amount of text displayed.
 *    `needReinitialization = true` indicates that changing this value **does** require the
 *    `initializeModelFn` to be called again to re-read and truncate the model file content.
 */
val EXAMPLE_CUSTOM_TASK_CONFIGS =
  listOf(
    NumberSliderConfig(
      key = EXAMPLE_CUSTOM_TASK_CONFIG_KEY_FONT_SIZE,
      sliderMin = 8f,
      sliderMax = 24f,
      defaultValue = 14f,
      valueType = ValueType.INT,
      needReinitialization = false,
    ),
    NumberSliderConfig(
      key = EXAMPLE_CUSTOM_TASK_CONFIG_KEY_MAX_CHAR_COUNT,
      sliderMin = 100f,
      sliderMax = 2000f,
      defaultValue = 2000f,
      valueType = ValueType.INT,
      needReinitialization = true,
    ),
  )

/** The main screen of the example custom task. */
@Composable
fun ExampleCustomTaskScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: ExampleCustomTaskViewModel = hiltViewModel(),
) {
  val colors = listOf(MaterialTheme.colorScheme.onSurface, Color.Red, Color.Green, Color.Blue)
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val model = modelManagerUiState.selectedModel
  val uiState by viewModel.uiState.collectAsState()
  val textColor = uiState.textColor

  // Get the current font size value from config.
  //
  // `modelManagerUiState.configValuesUpdateTrigger` will be updated and trigger a recomposition
  // when a config value is updated. Use it as the key here to read the font size from the config
  // whenever it is changed.
  var fontSize by
    remember(modelManagerUiState.configValuesUpdateTrigger) {
      mutableIntStateOf(model.getIntConfigValue(EXAMPLE_CUSTOM_TASK_CONFIG_KEY_FONT_SIZE))
    }

  // Set initial text color.
  LaunchedEffect(Unit) { viewModel.updateTextColor(color = colors[0]) }

  if (modelManagerUiState.isModelInitialized(model = model)) {
    val instance = model.instance as ExampleCustomTaskModelInstance
    Column {
      // A list of colors user can click to set the text color.
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(16.dp),
      ) {
        Text("Text color: ")
        for (color in colors) {
          Box(
            modifier =
              Modifier.size(16.dp).clip(CircleShape).background(color = color).clickable {
                viewModel.updateTextColor(color = color)
              },
            contentAlignment = Alignment.Center,
          ) {
            if (color == textColor) {
              Icon(
                Icons.Outlined.Check,
                tint = MaterialTheme.colorScheme.onPrimary,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
              )
            }
          }
        }
      }

      HorizontalDivider()

      // Content.
      Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text(
          instance.content,
          color = textColor,
          modifier = Modifier.padding(16.dp),
          style =
            MaterialTheme.typography.bodyMedium.copy(
              fontSize = fontSize.sp,
              lineHeight = (fontSize * 1.3).sp,
            ),
        )
      }
    }
  }
  // Loading spinner.
  else {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
      CircularProgressIndicator(
        modifier = Modifier.size(24.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        strokeWidth = 3.dp,
      )
    }
  }
}
