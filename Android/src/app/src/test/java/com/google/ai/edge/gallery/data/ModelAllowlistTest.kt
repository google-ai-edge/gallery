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

package com.google.ai.edge.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ModelAllowlistTest {
  @Test
  fun toModel_success() {
    val modelName = "test_model"
    val modelId = "test_model_id"
    val modelFile = "test_model_file"
    val description = "test description"
    val sizeInBytes = 100L
    val version = "20250623"
    val topK = 10
    val topP = 0.5f
    val temperature = 0.1f
    val maxTokens = 1000
    val accelerators = "gpu,cpu"
    val taskTypes = listOf("llm_chat", "ask_image")
    val estimatedPeakMemoryInBytes = 300L

    val allowedModel =
      AllowedModel(
        name = modelName,
        modelId = modelId,
        modelFile = modelFile,
        description = description,
        sizeInBytes = sizeInBytes,
        version = version,
        defaultConfig =
          DefaultConfig(
            topK = topK,
            topP = topP,
            temperature = temperature,
            maxTokens = maxTokens,
            accelerators = accelerators,
          ),
        taskTypes = taskTypes,
        llmSupportImage = true,
        llmSupportAudio = true,
        estimatedPeakMemoryInBytes = estimatedPeakMemoryInBytes,
      )
    val model = allowedModel.toModel()

    // Check that basic fields are set correctly.
    assertEquals(model.name, modelName)
    assertEquals(model.version, version)
    assertEquals(model.info, description)
    assertEquals(
      model.url,
      "https://huggingface.co/test_model_id/resolve/main/test_model_file?download=true",
    )
    assertEquals(model.sizeInBytes, sizeInBytes)
    assertEquals(model.estimatedPeakMemoryInBytes, estimatedPeakMemoryInBytes)
    assertEquals(model.downloadFileName, modelFile)
    assertFalse(model.showBenchmarkButton)
    assertFalse(model.showRunAgainButton)
    assertTrue(model.llmSupportImage)
    assertTrue(model.llmSupportAudio)

    // Check that configs are set correctly.
    assertEquals(model.configs.size, 5)

    // A label for showing max tokens (non-changeable).
    assertTrue(model.configs[0] is LabelConfig)
    assertEquals((model.configs[0] as LabelConfig).defaultValue, "$maxTokens")

    // A slider for topK.
    assertTrue(model.configs[1] is NumberSliderConfig)
    assertEquals((model.configs[1] as NumberSliderConfig).defaultValue, topK.toFloat())

    // A slider for topP.
    assertTrue(model.configs[2] is NumberSliderConfig)
    assertEquals((model.configs[2] as NumberSliderConfig).defaultValue, topP)

    // A slider for temperature.
    assertTrue(model.configs[3] is NumberSliderConfig)
    assertEquals((model.configs[3] as NumberSliderConfig).defaultValue, temperature)

    // A segmented button for accelerators.
    assertTrue(model.configs[4] is SegmentedButtonConfig)
    assertEquals((model.configs[4] as SegmentedButtonConfig).defaultValue, "GPU")
    assertEquals((model.configs[4] as SegmentedButtonConfig).options, listOf("GPU", "CPU"))
  }
}
