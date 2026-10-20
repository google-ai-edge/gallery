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
import com.google.ai.edge.gallery.common.isPixelDevice
import com.google.gson.annotations.SerializedName

private const val TAG = "AGModelAllowlist"

/**
 * Returns the base file name of a file [path] relative to a model repo root, e.g.
 * "subfolder/model.litertlm" -> "model.litertlm". Downloaded files are stored on device under their
 * base name, regardless of the sub-folder they live in within the repo.
 */
fun getBaseFileName(path: String): String = path.substringAfterLast('/')

data class DefaultConfig(
  @SerializedName("topK") val topK: Int?,
  @SerializedName("topP") val topP: Float?,
  @SerializedName("temperature") val temperature: Float?,
  @SerializedName("accelerators") val accelerators: String?,
  @SerializedName("visionAccelerator") val visionAccelerator: String?,
  @SerializedName("audioAccelerator") val audioAccelerator: String? = null,
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
@Suppress("AvoidNullableCollections")
data class AllowedModel(
  val name: String,
  val modelId: String,
  val modelFile: String,
  val commitHash: String,
  val description: String,
  val sizeInBytes: Long,
  val defaultConfig: DefaultConfig? = null,
  val taskTypes: List<String>? = null,
  val disabled: Boolean? = null,
  val llmSupportImage: Boolean? = null,
  val llmSupportAudio: Boolean? = null,
  val llmSupportTinyGarden: Boolean? = null,
  val llmSupportMobileActions: Boolean? = null,
  val capabilities: List<ModelCapability>? = null,
  val minDeviceMemoryInGb: Int? = null,
  val bestForTaskTypes: List<String>? = null,
  val localModelFilePathOverride: String? = null,
  val url: String? = null,
  val socToModelFiles: Map<String, SocModelFile>? = null,
  val runtimeType: RuntimeType? = null,
  val aicoreReleaseStage: AICoreModelReleaseStage? = null,
  val aicorePreference: AICoreModelPreference? = null,
  val parentModelName: String? = null,
  val variantLabel: String? = null,
  val capabilityToTaskTypes: Map<ModelCapability, List<String>>? = null,
  val updatableModelFiles: List<ModelFile>? = null,
  val updateInfo: String? = null,
  val extraDataFiles: List<ModelDataFile>? = null,
  val metadata: ModelMetadata? = null,
  val modelType: ModelType? = null,
  val learnMoreUrl: String? = null,
  /**
   * Name of the device-requirement set this model needs, a key of
   * [ModelAllowlist.deviceRequirements]. AICore models without one use
   * [DeviceRequirementSets.AICORE_LLM].
   */
  val deviceRequirement: String? = null,
) {
  /**
   * The device-requirement set this model needs, falling back to [DeviceRequirementSets.AICORE_LLM]
   * for AICore models that do not specify a set. Returns null if the model can run on any device.
   */
  val requiredDeviceRequirementSet: String?
    get() =
      deviceRequirement
        ?: DeviceRequirementSets.AICORE_LLM.takeIf { runtimeType == RuntimeType.AICORE }

  fun toModel(): Model? {
    val effectiveModelType = modelType ?: ModelType.TASK
    val resolvedRuntime = runtimeType ?: RuntimeType.LITERT_LM

    // Construct HF download url. `modelFile` may be a path relative to the repo root (e.g.
    // "subfolder/model.litertlm"): the full path is used in the download url, while only the base
    // file name is used for the file stored on device.
    var version = commitHash
    var downloadedFileName = getBaseFileName(modelFile)
    var downloadUrl =
      url ?: "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
    var sizeInBytes = sizeInBytes

    // Handle per-soc model files.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (socToModelFiles?.isNotEmpty() == true) {
        socToModelFiles.get(SOC)?.let { info ->
          Log.d(TAG, "Found soc-specific model files for model $name: $info")
          version = info.commitHash ?: "-"
          downloadedFileName = info.modelFile?.let { getBaseFileName(it) } ?: "-"
          downloadUrl =
            info.url
              ?: "https://huggingface.co/$modelId/resolve/${info.commitHash}/${info.modelFile}?download=true"
          sizeInBytes = info.sizeInBytes ?: -1
        }
      }
    }

    // Config.
    val safeTaskTypes = taskTypes.orEmpty()
    val isLlmModel =
      effectiveModelType == ModelType.TASK &&
        (BuiltInTaskId.LLM_CHAT in safeTaskTypes ||
          BuiltInTaskId.LLM_PROMPT_LAB in safeTaskTypes ||
          BuiltInTaskId.LLM_ASK_AUDIO in safeTaskTypes ||
          BuiltInTaskId.LLM_ASK_IMAGE in safeTaskTypes ||
          BuiltInTaskId.LLM_MOBILE_ACTIONS in safeTaskTypes ||
          BuiltInTaskId.LLM_TINY_GARDEN in safeTaskTypes)
    var configs: MutableList<Config> = mutableListOf()
    var llmMaxToken = DEFAULT_MAX_TOKEN
    var accelerators: List<Accelerator> = DEFAULT_ACCELERATORS
    var visionAccelerator: Accelerator = DEFAULT_VISION_ACCELERATOR

    var finalDescription = description
    var acceleratorsStr = defaultConfig?.accelerators

    if (isPixelDevice()) {
      finalDescription = description.replace(Regex("\\bNPU\\b"), "TPU")
      acceleratorsStr = acceleratorsStr?.replace(Regex("\\bnpu\\b"), "tpu")
    }

    if (acceleratorsStr != null) {
      val items = acceleratorsStr.split(",")
      val parsedAccelerators = mutableListOf<Accelerator>()
      for (item in items) {
        Accelerator.fromLabel(item.trim())?.let { parsedAccelerators.add(it) }
      }
      // Remove GPU from pixel 10 devices for LLM models.
      if (isPixel10() && isLlmModel) {
        parsedAccelerators.remove(Accelerator.GPU)
      }
      if (parsedAccelerators.isNotEmpty()) {
        accelerators = parsedAccelerators
      }
    }

    Accelerator.fromLabel(defaultConfig?.visionAccelerator)?.let { visionAccelerator = it }
    val audioAccelerator = Accelerator.fromLabel(defaultConfig?.audioAccelerator)

    if (isLlmModel) {
      val defaultTopK: Int = defaultConfig?.topK ?: DEFAULT_TOPK
      val defaultTopP: Float = defaultConfig?.topP ?: DEFAULT_TOPP
      val defaultTemperature: Float = defaultConfig?.temperature ?: DEFAULT_TEMPERATURE
      llmMaxToken = defaultConfig?.maxTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKEN
      val llmMaxContextLength = defaultConfig?.maxContextLength
      val npuOnly =
        accelerators.size == 1 &&
          (accelerators[0] == Accelerator.NPU || accelerators[0] == Accelerator.TPU)
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
              supportThinking = capabilities?.contains(ModelCapability.LLM_THINKING) == true,
              supportSpeculativeDecoding =
                capabilities?.contains(ModelCapability.SPECULATIVE_DECODING) == true,
            )
          })
          .toMutableList()
    }

    var resolvedLearnMoreUrl =
      this.learnMoreUrl ?: if (modelId.isEmpty()) "" else "https://huggingface.co/${modelId}"

    if (!resolvedRuntime.downloadsViaRepository) {
      downloadUrl = ""
    }
    if (resolvedRuntime == RuntimeType.AICORE && this.learnMoreUrl == null) {
      resolvedLearnMoreUrl = "https://developers.google.com/ml-kit/terms"
    }

    // Misc.
    var showRunAgainButton = true
    if (isLlmModel) {
      showRunAgainButton = false
    }
    val downloadInfo =
      ModelDownloadInfo(
        url = downloadUrl,
        sizeInBytes = sizeInBytes,
        downloadFileName = downloadedFileName,
        version = version,
        extraDataFiles = extraDataFiles ?: emptyList(),
        localModelFilePathOverride = localModelFilePathOverride ?: "",
        updatableModelFiles =
          updatableModelFiles?.map { it.copy(fileName = getBaseFileName(it.fileName)) }
            ?: emptyList(),
        updateInfo = updateInfo ?: "",
      )
    val llmProfile =
      if (isLlmModel) {
        LlmProfile(
          supportTinyGarden = llmSupportTinyGarden == true,
          supportMobileActions = llmSupportMobileActions == true,
          maxTokens = llmMaxToken,
        )
      } else {
        null
      }
    return Model(
      name = name,
      modelId = modelId,
      info = finalDescription,
      minDeviceMemoryInGb = minDeviceMemoryInGb,
      configs = configs,
      showRunAgainButton = showRunAgainButton,
      learnMoreUrl = resolvedLearnMoreUrl,
      downloadInfo = downloadInfo,
      backendSpec =
        BackendSpec(
          runtimeType = resolvedRuntime,
          aicoreReleaseStage = aicoreReleaseStage,
          aicorePreference = aicorePreference,
          accelerators = accelerators,
          visionAccelerator = visionAccelerator,
          audioAccelerator = audioAccelerator,
        ),
      hierarchy = ModelHierarchy(parentModelName = parentModelName, variantLabel = variantLabel),
      llmProfile = llmProfile,
      declaredModelType = effectiveModelType,
      supportImage = llmSupportImage == true,
      supportAudio = llmSupportAudio == true,
      capabilities = capabilities ?: emptyList(),
      capabilityToTaskTypes = capabilityToTaskTypes ?: emptyMap(),
      bestForTaskIds = bestForTaskTypes ?: listOf(),
      metadata = metadata ?: ModelMetadata(),
    )
  }

  override fun toString(): String {
    return "$modelId/$modelFile"
  }
}

/**
 * Specific device requirements grouped by a descriptive name.
 *
 * A device belongs to the group if its `Build.MODEL` is in [deviceModels] or its `Build.SOC_MODEL`
 * is in [socModels], compared case-insensitively.
 *
 * All properties have defaults so that Gson uses the generated no-arg constructor and keeps the
 * defaults for fields missing from the JSON.
 */
data class NamedDeviceGroup(
  @SerializedName("groupName") val groupName: String = "",
  @SerializedName("description") val description: String? = null,
  @SerializedName("deviceModels") val deviceModels: List<String> = emptyList(),
  @SerializedName("socModels") val socModels: List<String> = emptyList(),
)

/** Hardware-based constraints for model deployment. */
data class DeviceRequirements(
  @SerializedName("allowedDeviceGroups") val allowedDeviceGroups: List<NamedDeviceGroup>? = null
)

/** The model allowlist. */
data class ModelAllowlist(
  val models: List<AllowedModel> = emptyList(),
  /**
   * Device list for AICore models. Read as the [DeviceRequirementSets.AICORE_LLM] set; see
   * [DeviceRequirementRegistry].
   */
  @SerializedName("aicoreRequirements") val aicoreRequirements: DeviceRequirements? = null,
  /**
   * Named device lists that models reference through [AllowedModel.deviceRequirement]. Nullable
   * because Gson writes null for an explicit JSON `null`.
   */
  @Suppress("AvoidNullableCollections")
  @SerializedName("deviceRequirements")
  val deviceRequirements: Map<String, DeviceRequirements>? = null,
  /** Flag overrides configured remotely via the allowlist. */
  @SerializedName("flags") val flags: Map<String, Boolean>? = null,
)
