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

package com.google.ai.edge.gallery.ui.llmchat

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.LabelConfig
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.SegmentedButtonConfig
import com.google.ai.edge.gallery.data.ValueType

const val DEFAULT_MAX_TOKEN = 1024
const val DEFAULT_TOPK = 40
const val DEFAULT_TOPP = 0.9f
const val DEFAULT_TEMPERATURE = 1.0f
val DEFAULT_ACCELERATORS = listOf(Accelerator.GPU)

fun createLlmChatConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTopP: Float = DEFAULT_TOPP,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
): List<Config> {
  return listOf(
    LabelConfig(
      key = ConfigKey.MAX_TOKENS,
      defaultValue = "$defaultMaxToken",
    ),
    NumberSliderConfig(
      key = ConfigKey.TOPK,
      sliderMin = 5f,
      sliderMax = 40f,
      defaultValue = defaultTopK.toFloat(),
      valueType = ValueType.INT
    ),
    NumberSliderConfig(
      key = ConfigKey.TOPP,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = defaultTopP,
      valueType = ValueType.FLOAT
    ),
    NumberSliderConfig(
      key = ConfigKey.TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 2.0f,
      defaultValue = defaultTemperature,
      valueType = ValueType.FLOAT
    ),
    SegmentedButtonConfig(
      key = ConfigKey.ACCELERATOR,
      defaultValue = accelerators[0].label,
      options = accelerators.map { it.label }
    )
  )
}
