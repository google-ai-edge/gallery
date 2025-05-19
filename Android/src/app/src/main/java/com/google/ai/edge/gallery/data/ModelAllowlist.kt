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

import com.google.ai.edge.gallery.ui.llmchat.DEFAULT_ACCELERATORS
import com.google.ai.edge.gallery.ui.llmchat.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.ui.llmchat.DEFAULT_TOPK
import com.google.ai.edge.gallery.ui.llmchat.DEFAULT_TOPP
import com.google.ai.edge.gallery.ui.llmchat.createLlmChatConfigs
import kotlinx.serialization.Serializable

/** A model in the model allowlist. */
@Serializable
data class AllowedModel(
  val name: String,
  val modelId: String,
  val modelFile: String,
  val description: String,
  val sizeInBytes: Long,
  val version: String,
  val defaultConfig: Map<String, ConfigValue>,
  val taskTypes: List<String>,
  val disabled: Boolean? = null,
  val llmSupportImage: Boolean? = null,
) {
  fun toModel(): Model {
    // Construct HF download url.
    val downloadUrl = "https://huggingface.co/$modelId/resolve/main/$modelFile?download=true"

    // Config.
    val isLlmModel =
      taskTypes.contains(TASK_LLM_CHAT.type.id) || taskTypes.contains(TASK_LLM_PROMPT_LAB.type.id)
    var configs: List<Config> = listOf()
    if (isLlmModel) {
      var defaultTopK: Int = DEFAULT_TOPK
      var defaultTopP: Float = DEFAULT_TOPP
      var defaultTemperature: Float = DEFAULT_TEMPERATURE
      var defaultMaxToken = 1024
      var accelerators: List<Accelerator> = DEFAULT_ACCELERATORS
      if (defaultConfig.containsKey("topK")) {
        defaultTopK = getIntConfigValue(defaultConfig["topK"], defaultTopK)
      }
      if (defaultConfig.containsKey("topP")) {
        defaultTopP = getFloatConfigValue(defaultConfig["topP"], defaultTopP)
      }
      if (defaultConfig.containsKey("temperature")) {
        defaultTemperature = getFloatConfigValue(defaultConfig["temperature"], defaultTemperature)
      }
      if (defaultConfig.containsKey("maxTokens")) {
        defaultMaxToken = getIntConfigValue(defaultConfig["maxTokens"], defaultMaxToken)
      }
      if (defaultConfig.containsKey("accelerators")) {
        val items = getStringConfigValue(defaultConfig["accelerators"], "gpu").split(",")
        accelerators = mutableListOf()
        for (item in items) {
          if (item == "cpu") {
            accelerators.add(Accelerator.CPU)
          } else if (item == "gpu") {
            accelerators.add(Accelerator.GPU)
          }
        }
      }
      configs = createLlmChatConfigs(
        defaultTopK = defaultTopK,
        defaultTopP = defaultTopP,
        defaultTemperature = defaultTemperature,
        defaultMaxToken = defaultMaxToken,
        accelerators = accelerators,
      )
    }

    // Misc.
    var showBenchmarkButton = true
    var showRunAgainButton = true
    if (isLlmModel) {
      showBenchmarkButton = false
      showRunAgainButton = false
    }

    return Model(
      name = name,
      version = version,
      info = description,
      url = downloadUrl,
      sizeInBytes = sizeInBytes,
      configs = configs,
      downloadFileName = modelFile,
      showBenchmarkButton = showBenchmarkButton,
      showRunAgainButton = showRunAgainButton,
      learnMoreUrl = "https://huggingface.co/${modelId}",
      llmSupportImage = llmSupportImage == true,
    )
  }

  override fun toString(): String {
    return "$modelId/$modelFile"
  }
}

/** The model allowlist. */
@Serializable
data class ModelAllowlist(
  val models: List<AllowedModel>,
)

