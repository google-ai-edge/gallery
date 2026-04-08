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

import android.os.Build
import android.util.Log
import com.google.ai.edge.gallery.common.isPixel10
import com.google.gson.annotations.SerializedName

private const val TAG = "AGModelAllowlist"

data class DefaultConfig(
  @SerializedName("topK") val topK: Int?,
  @SerializedName("topP") val topP: Float?,
  @SerializedName("temperature") val temperature: Float?,
  @SerializedName("accelerators") val accelerators: String?,
  @SerializedName("visionAccelerator") val visionAccelerator: String?,
  @SerializedName("maxContextLength") val maxContextLength: Int?,
  @SerializedName("maxTokens") val maxTokens: Int?,
)

/** A model file on HF for a specific SOC. */
data class SocModelFile(
  @SerializedName("modelFile") val modelFile: String?,
  @SerializedName("url") val url: String?,
  @SerializedName("commitHash") val commitHash: String?,
  @SerializedName("sizeInBytes") val sizeInBytes: Long?,
)

/** A model in the model allowlist. */
data class AllowedModel(
  val name: String,
  val modelId: String,
  val modelFile: String,
  val commitHash: String,
  val description: String,
  val sizeInBytes: Long,
  val defaultConfig: DefaultConfig,
  val taskTypes: List<String>,
  val disabled: Boolean? = null,
  val llmSupportImage: Boolean? = null,
  val llmSupportAudio: Boolean? = null,
  val llmSupportTinyGarden: Boolean? = null,
  val llmSupportMobileActions: Boolean? = null,
  val llmSupportThinking: Boolean? = null,
  val minDeviceMemoryInGb: Int? = null,
  val bestForTaskTypes: List<String>? = null,
  val localModelFilePathOverride: String? = null,
  val url: String? = null,
  val socToModelFiles: Map<String, SocModelFile>? = null,
  val runtimeType: RuntimeType? = null,
  val aicoreReleaseStage: AICoreModelReleaseStage? = null,
  val aicorePreference: AICoreModelPreference? = null,
) {
  fun toModel(): Model {
    // Construct HF download url.
    var version = commitHash
    var downloadedFileName = modelFile
    var downloadUrl =
      url ?: "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
    var sizeInBytes = sizeInBytes

    // Handle per-soc model files.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (socToModelFiles?.isNotEmpty() == true) {
        socToModelFiles.get(SOC)?.let { info ->
          Log.d(TAG, "Found soc-specific model files for model $name: $info")
          version = info.commitHash ?: "-"
          downloadedFileName = info.modelFile ?: "-"
          downloadUrl =
            info.url
              ?: "https://huggingface.co/$modelId/resolve/${info.commitHash}/${info.modelFile}?download=true"
          sizeInBytes = info.sizeInBytes ?: -1
        }
      }
    }

    // Config.
    val isLlmModel =
      taskTypes.contains(BuiltInTaskId.LLM_CHAT) ||
        taskTypes.contains(BuiltInTaskId.LLM_PROMPT_LAB) ||
        taskTypes.contains(BuiltInTaskId.LLM_ASK_AUDIO) ||
        taskTypes.contains(BuiltInTaskId.LLM_ASK_IMAGE) ||
        taskTypes.contains(BuiltInTaskId.LLM_MOBILE_ACTIONS) ||
        taskTypes.contains(BuiltInTaskId.LLM_TINY_GARDEN)
    var configs: MutableList<Config> = mutableListOf()
    var llmMaxToken = 1024
    var llmMaxContextLength: Int? = null
    var accelerators: List<Accelerator> = DEFAULT_ACCELERATORS
    var visionAccelerator: Accelerator = DEFAULT_VISION_ACCELERATOR
    if (isLlmModel) {
      val defaultTopK: Int = defaultConfig.topK ?: DEFAULT_TOPK
      val defaultTopP: Float = defaultConfig.topP ?: DEFAULT_TOPP
      val defaultTemperature: Float = defaultConfig.temperature ?: DEFAULT_TEMPERATURE
      llmMaxToken = defaultConfig.maxTokens ?: 1024
      llmMaxContextLength = defaultConfig.maxContextLength
      if (defaultConfig.accelerators != null) {
        val items = defaultConfig.accelerators.split(",")
        accelerators = mutableListOf()
        for (item in items) {
          if (item == "cpu") {
            accelerators.add(Accelerator.CPU)
          } else if (item == "gpu") {
            accelerators.add(Accelerator.GPU)
          } else if (item == "npu") {
            accelerators.add(Accelerator.NPU)
          }
        }
        // Remove GPU from pixel 10 devices.
        if (isPixel10()) {
          accelerators.remove(Accelerator.GPU)
        }
      }
      if (defaultConfig.visionAccelerator != null) {
        val accelerator = defaultConfig.visionAccelerator
        if (accelerator == "cpu") {
          visionAccelerator = Accelerator.CPU
        } else if (accelerator == "gpu") {
          visionAccelerator = Accelerator.GPU
        } else if (accelerator == "npu") {
          visionAccelerator = Accelerator.NPU
        }
      }
      val npuOnly = accelerators.size == 1 && accelerators[0] == Accelerator.NPU
      configs =
        (if (runtimeType == RuntimeType.AICORE) {
            createAICoreConfigs(
              defaultTopK = defaultTopK,
              defaultTemperature = if (defaultTemperature > 1.0f) 1.0f else defaultTemperature,
              defaultMaxToken = llmMaxToken,
              accelerators = accelerators,
            )
          } else if (npuOnly) {
            createLlmChatConfigsForNpuModel(
              defaultMaxToken = llmMaxToken,
              accelerators = accelerators,
            )
          } else {
            createLlmChatConfigs(
              defaultTopK = defaultTopK,
              defaultTopP = defaultTopP,
              defaultTemperature = defaultTemperature,
              defaultMaxToken = llmMaxToken,
              defaultMaxContextLength = llmMaxContextLength,
              accelerators = accelerators,
              supportThinking = llmSupportThinking == true,
            )
          })
          .toMutableList()
    }

    var learnMoreUrl = "https://huggingface.co/${modelId}"

    if (runtimeType == RuntimeType.AICORE) {
      downloadUrl = ""
      learnMoreUrl = "https://developers.google.com/ml-kit/terms"
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
      minDeviceMemoryInGb = minDeviceMemoryInGb,
      configs = configs,
      downloadFileName = downloadedFileName,
      showBenchmarkButton = showBenchmarkButton,
      showRunAgainButton = showRunAgainButton,
      learnMoreUrl = learnMoreUrl,
      llmSupportImage = llmSupportImage == true,
      llmSupportAudio = llmSupportAudio == true,
      llmSupportTinyGarden = llmSupportTinyGarden == true,
      llmSupportMobileActions = llmSupportMobileActions == true,
      llmSupportThinking = llmSupportThinking == true,
      llmMaxToken = llmMaxToken,
      accelerators = accelerators,
      visionAccelerator = visionAccelerator,
      bestForTaskIds = bestForTaskTypes ?: listOf(),
      localModelFilePathOverride = localModelFilePathOverride ?: "",
      isLlm = isLlmModel,
      runtimeType = runtimeType ?: RuntimeType.LITERT_LM,
      aicoreReleaseStage = aicoreReleaseStage,
      aicorePreference = aicorePreference,
    )
  }

  override fun toString(): String {
    return "$modelId/$modelFile"
  }
}

/** Specific device requirements grouped by a descriptive name. */
data class NamedDeviceGroup(
  @SerializedName("groupName") val groupName: String,
  @SerializedName("description") val description: String? = null,
  @SerializedName("deviceModels") val deviceModels: List<String>,
)

/** Hardware-based constraints for model deployment. */
data class DeviceRequirements(
  @SerializedName("allowedDeviceGroups") val allowedDeviceGroups: List<NamedDeviceGroup>? = null
)

/** The model allowlist. */
data class ModelAllowlist(
  val models: List<AllowedModel>,
  @SerializedName("aicoreRequirements") val aicoreRequirements: DeviceRequirements? = null,
)
