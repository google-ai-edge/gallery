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

import com.google.gson.annotations.SerializedName

data class DefaultConfig(
  @SerializedName("topK") val topK: Int?,
  @SerializedName("topP") val topP: Float?,
  @SerializedName("temperature") val temperature: Float?,
  @SerializedName("accelerators") val accelerators: String?,
  @SerializedName("maxTokens") val maxTokens: Int?,
)

/** A model in the model allowlist. */
data class AllowedModel(
  val name: String,
  val modelId: String,
  val modelFile: String,
  val description: String,
  val sizeInBytes: Long,
  val commitHash: String,
  val defaultConfig: DefaultConfig,
  val taskTypes: List<String>,
  val disabled: Boolean? = null,
  val llmSupportImage: Boolean? = null,
  val llmSupportAudio: Boolean? = null,
  val llmSupportAgents: Boolean? = null,
  val minDeviceMemoryInGb: Int? = null,
  val bestForTaskTypes: List<String>? = null,
  val localModelFilePathOverride: String? = null,
  val url: String? = null,
) {
  fun toModel(): Model {
    // Construct HF download url.
    val downloadUrl =
      url ?: "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"

    // Config.
    val isLlmModel =
      taskTypes.contains(BuiltInTaskId.LLM_CHAT) ||
        taskTypes.contains(BuiltInTaskId.LLM_PROMPT_LAB) ||
        taskTypes.contains(BuiltInTaskId.LLM_ASK_AUDIO) ||
        taskTypes.contains(BuiltInTaskId.LLM_ASK_IMAGE) ||
        taskTypes.contains(BuiltInTaskId.LLM_VOICE_TO_ACTION) ||
        taskTypes.contains(BuiltInTaskId.LLM_TINY_GARDEN)
    var configs: List<Config> = listOf()
    if (isLlmModel) {
      val defaultTopK: Int = defaultConfig.topK ?: DEFAULT_TOPK
      val defaultTopP: Float = defaultConfig.topP ?: DEFAULT_TOPP
      val defaultTemperature: Float = defaultConfig.temperature ?: DEFAULT_TEMPERATURE
      val defaultMaxToken = defaultConfig.maxTokens ?: 1024
      var accelerators: List<Accelerator> = DEFAULT_ACCELERATORS
      if (defaultConfig.accelerators != null) {
        val items = defaultConfig.accelerators.split(",")
        accelerators = mutableListOf()
        for (item in items) {
          if (item == "cpu") {
            accelerators.add(Accelerator.CPU)
          } else if (item == "gpu") {
            accelerators.add(Accelerator.GPU)
          }
        }
      }
      configs =
        createLlmChatConfigs(
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
      version = commitHash,
      info = description,
      url = downloadUrl,
      sizeInBytes = sizeInBytes,
      minDeviceMemoryInGb = minDeviceMemoryInGb,
      configs = configs,
      downloadFileName = modelFile,
      showBenchmarkButton = showBenchmarkButton,
      showRunAgainButton = showRunAgainButton,
      learnMoreUrl = "https://huggingface.co/${modelId}",
      llmSupportImage = llmSupportImage == true,
      llmSupportAudio = llmSupportAudio == true,
      llmSupportAgents = llmSupportAgents == true,
      bestForTaskIds = bestForTaskTypes ?: listOf(),
      localModelFilePathOverride = localModelFilePathOverride ?: "",
    )
  }

  override fun toString(): String {
    return "$modelId/$modelFile"
  }
}

/** The model allowlist. */
data class ModelAllowlist(val models: List<AllowedModel>)
