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

package com.google.ai.edge.gallery.data

import com.google.ai.edge.gallery.huggingface.HfUrlInfo
import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.ai.edge.gallery.huggingface.extractHfUrlInfo
import com.google.ai.edge.gallery.proto.LlmConfig
import com.google.ai.edge.gallery.proto.llmConfig

/** Routing action resolved for an incoming model deep link. */
sealed interface ModelDeepLinkResolution {
  /** Opens Global Model Manager at its default top state (missing or invalid target URL). */
  data object OpenModelManagerDefault : ModelDeepLinkResolution

  /**
   * Opens Global Model Manager and triggers the model import flow for [importUrl] (either a direct
   * model file not yet in the catalog, or a Hugging Face repository URL for the file picker).
   */
  data class OpenImportFlow(val importUrl: String) : ModelDeepLinkResolution

  /**
   * Opens Global Model Manager centered and highlighted on [modelName], optionally presenting the
   * "Try it with ... task" selector bottom sheet immediately when [openTryIt] is `true`.
   */
  data class FocusModelInManager(val modelName: String, val openTryIt: Boolean) :
    ModelDeepLinkResolution

  /**
   * Navigates directly into [taskId] with [modelName] selected, keeping Global Model Manager on the
   * back stack.
   */
  data class NavigateToTask(val taskId: String, val modelName: String) : ModelDeepLinkResolution
}

/** Whether this model is restricted to the "Test it" task. */
val Model.isForTestOnly: Boolean
  get() = downloadInfo.imported && ModelUtils.isImportedUrlForTestOnly(downloadInfo.url)

/**
 * Returns the set of task IDs that this imported [Model] should be registered under based on its
 * capabilities and whether it is restricted to the "Test it" task ([isForTestOnly]).
 */
fun Model.getTargetTaskIdsForImportedModel(): Set<String> {
  if (isForTestOnly) {
    return setOf(BuiltInTaskId.LLM_TEST)
  }
  return buildSet {
    add(BuiltInTaskId.LLM_CHAT)
    add(BuiltInTaskId.LLM_PROMPT_LAB)
    add(BuiltInTaskId.LLM_AGENT_CHAT)
    if (supportImage) {
      add(BuiltInTaskId.LLM_ASK_IMAGE)
    }
    if (supportAudio) {
      add(BuiltInTaskId.LLM_ASK_AUDIO)
    }
    if (llmProfile?.supportTinyGarden == true) {
      add(BuiltInTaskId.LLM_TINY_GARDEN)
    }
    if (llmProfile?.supportMobileActions == true) {
      add(BuiltInTaskId.LLM_MOBILE_ACTIONS)
    }
  }
}

/** Domain utilities for imported models and task routing. */
object ModelUtils {
  /**
   * Returns whether an imported model with the given source [url] is restricted to the "Test it"
   * task.
   */
  fun isImportedUrlForTestOnly(url: String): Boolean =
    false
    && HuggingFaceApiClient.isHuggingFaceUrl(url)

  /**
   * Builds an [LlmConfig] proto from the user-edited configuration [values] map in the model import
   * dialog.
   *
   * Config keys that are omitted when `isForTestOnly` is enabled (such as `SUPPORT_TINY_GARDEN` and
   * `SUPPORT_MOBILE_ACTIONS`) default to `false`.
   */
  fun createImportedLlmConfig(values: Map<String, Any>): LlmConfig {
    val supportedAccelerators =
      (convertValueToTargetType(
          value = values[ConfigKeys.COMPATIBLE_ACCELERATORS.label] ?: "",
          valueType = ValueType.STRING,
        )
          as String)
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val defaultMaxTokens =
      convertValueToTargetType(
        value = values[ConfigKeys.DEFAULT_MAX_TOKENS.label] ?: DEFAULT_MAX_TOKEN,
        valueType = ValueType.INT,
      )
        as Int
    val defaultTopk =
      convertValueToTargetType(
        value = values[ConfigKeys.DEFAULT_TOPK.label] ?: DEFAULT_TOPK,
        valueType = ValueType.INT,
      )
        as Int
    val defaultTopp =
      convertValueToTargetType(
        value = values[ConfigKeys.DEFAULT_TOPP.label] ?: DEFAULT_TOPP,
        valueType = ValueType.FLOAT,
      )
        as Float
    val defaultTemperature =
      convertValueToTargetType(
        value = values[ConfigKeys.DEFAULT_TEMPERATURE.label] ?: DEFAULT_TEMPERATURE,
        valueType = ValueType.FLOAT,
      )
        as Float
    val supportImage =
      convertValueToTargetType(
        value = values[ConfigKeys.SUPPORT_IMAGE.label] ?: false,
        valueType = ValueType.BOOLEAN,
      )
        as Boolean
    val supportAudio =
      convertValueToTargetType(
        value = values[ConfigKeys.SUPPORT_AUDIO.label] ?: false,
        valueType = ValueType.BOOLEAN,
      )
        as Boolean
    val supportTinyGarden =
      convertValueToTargetType(
        value = values[ConfigKeys.SUPPORT_TINY_GARDEN.label] ?: false,
        valueType = ValueType.BOOLEAN,
      )
        as Boolean
    val supportMobileActions =
      convertValueToTargetType(
        value = values[ConfigKeys.SUPPORT_MOBILE_ACTIONS.label] ?: false,
        valueType = ValueType.BOOLEAN,
      )
        as Boolean
    val supportThinking =
      convertValueToTargetType(
        value = values[ConfigKeys.SUPPORT_THINKING.label] ?: false,
        valueType = ValueType.BOOLEAN,
      )
        as Boolean
    val supportSpeculativeDecoding =
      convertValueToTargetType(
        value = values[ConfigKeys.SUPPORT_SPECULATIVE_DECODING.label] ?: false,
        valueType = ValueType.BOOLEAN,
      )
        as Boolean
    return llmConfig {
      compatibleAccelerators += supportedAccelerators
      this.defaultMaxTokens = defaultMaxTokens
      this.defaultTopk = defaultTopk
      this.defaultTopp = defaultTopp
      this.defaultTemperature = defaultTemperature
      this.supportImage = supportImage
      this.supportAudio = supportAudio
      this.supportMobileActions = supportMobileActions
      this.supportThinking = supportThinking
      this.supportTinyGarden = supportTinyGarden
      this.supportSpeculativeDecoding = supportSpeculativeDecoding
    }
  }

  /** Builds the [ModelCapability] to target task IDs mapping for an imported LLM model. */
  fun buildImportedModelCapabilityToTaskTypes(
    supportThinking: Boolean,
    supportSpeculativeDecoding: Boolean,
    isForTestOnly: Boolean,
  ): Map<ModelCapability, List<String>> {
    return buildMap {
      if (supportThinking) {
        put(
          ModelCapability.LLM_THINKING,
          if (isForTestOnly) {
            listOf(BuiltInTaskId.LLM_TEST)
          } else {
            listOf(
              BuiltInTaskId.LLM_CHAT,
              BuiltInTaskId.LLM_ASK_IMAGE,
              BuiltInTaskId.LLM_ASK_AUDIO,
            )
          },
        )
      }
      if (supportSpeculativeDecoding) {
        put(
          ModelCapability.SPECULATIVE_DECODING,
          if (isForTestOnly) {
            listOf(BuiltInTaskId.LLM_TEST)
          } else {
            listOf(
              BuiltInTaskId.LLM_CHAT,
              BuiltInTaskId.LLM_ASK_IMAGE,
              BuiltInTaskId.LLM_ASK_AUDIO,
              BuiltInTaskId.LLM_PROMPT_LAB,
            )
          },
        )
      }
    }
  }

  /**
   * Filters and sorts raw vision signature token counts against the model's max budget ceiling.
   *
   * Keeps strictly positive entries (`> 0`) and, when `ceiling > 0`, entries `<= ceiling`. A
   * non-positive `ceiling` (such as `-1`) means no upper bound is defined on the model. Returns an
   * empty list if [rawOptions] is `null` or no valid options remain.
   */
  fun resolveVisualTokenBudgetOptions(rawOptions: IntArray?, ceiling: Int): List<Int> {
    if (rawOptions == null) return emptyList()
    val usable = rawOptions.filter { it > 0 && (ceiling <= 0 || it <= ceiling) }
    if (usable.isEmpty()) return emptyList()
    return usable.sorted()
  }

  /**
   * Resolves the active visual token budget for [model] and updates [Model.configs] and
   * [Model.configValues] accordingly.
   *
   * When [shouldEnableImage] is `true` and [resolveVisualTokenBudgetOptions] returns more than one
   * option, registers (or updates) a [DropdownConfig] for [ConfigKeys.VISUAL_TOKEN_BUDGET] on
   * [model] with default value equal to the maximum option, and returns the currently selected
   * valid budget. Otherwise removes [ConfigKeys.VISUAL_TOKEN_BUDGET] from [model]'s configs and
   * returns `null`.
   */
  fun configureVisualTokenBudgetForModel(
    model: Model,
    shouldEnableImage: Boolean,
    rawOptions: IntArray?,
    ceiling: Int,
  ): Int? {
    val budgetOptions =
      if (shouldEnableImage) {
        resolveVisualTokenBudgetOptions(rawOptions = rawOptions, ceiling = ceiling)
      } else {
        emptyList()
      }
    if (budgetOptions.size <= 1) {
      if (model.configs.any { it.key == ConfigKeys.VISUAL_TOKEN_BUDGET }) {
        model.configs = model.configs.filterNot { it.key == ConfigKeys.VISUAL_TOKEN_BUDGET }
      }
      if (model.configValues.containsKey(ConfigKeys.VISUAL_TOKEN_BUDGET.label)) {
        model.configValues =
          model.configValues.toMutableMap().apply { remove(ConfigKeys.VISUAL_TOKEN_BUDGET.label) }
      }
      return null
    }

    val defaultBudget = budgetOptions.last()
    val configuredBudget =
      if (model.configValues.containsKey(ConfigKeys.VISUAL_TOKEN_BUDGET.label)) {
        model.getIntConfigValue(key = ConfigKeys.VISUAL_TOKEN_BUDGET, defaultValue = defaultBudget)
      } else {
        defaultBudget
      }
    val selectedBudget = if (configuredBudget in budgetOptions) configuredBudget else defaultBudget

    val dropdownConfig =
      DropdownConfig(
        key = ConfigKeys.VISUAL_TOKEN_BUDGET,
        defaultValue = "$defaultBudget",
        options = budgetOptions.map { "$it" },
      )
    val updatedConfigs = model.configs.toMutableList()
    val existingIndex = updatedConfigs.indexOfFirst { it.key == ConfigKeys.VISUAL_TOKEN_BUDGET }
    if (existingIndex >= 0) {
      updatedConfigs[existingIndex] = dropdownConfig
    } else {
      val temperatureIndex = updatedConfigs.indexOfFirst { it.key == ConfigKeys.TEMPERATURE }
      val acceleratorIndex = updatedConfigs.indexOfFirst { it.key == ConfigKeys.ACCELERATOR }
      val insertIndex =
        when {
          temperatureIndex >= 0 -> temperatureIndex + 1
          acceleratorIndex >= 0 -> acceleratorIndex
          else -> updatedConfigs.size
        }
      updatedConfigs.add(insertIndex, dropdownConfig)
    }
    model.configs = updatedConfigs

    val updatedValues = model.configValues.toMutableMap()
    updatedValues[ConfigKeys.VISUAL_TOKEN_BUDGET.label] = "$selectedBudget"
    model.configValues = updatedValues

    return selectedBudget
  }

  private val GENERIC_MODEL_STEMS = setOf("model", "weights", "checkpoint", "adapter")
  private val SUPPORTED_MODEL_EXTENSIONS = listOf(".litertlm", ".task")
  private const val MAX_STORAGE_FILE_NAME_LENGTH = 96

  /**
   * Derives the default UI display name for a model being imported from [rawUriOrUrl], using the
   * official Hugging Face file or repository name (or local filename stem) and appending `" (2)"`,
   * `" (3)"`, etc. when [existingDisplayNames] already contains a case-insensitive match.
   */
  fun deriveDefaultImportedDisplayName(
    rawUriOrUrl: String,
    fallbackFileName: String,
    existingDisplayNames: Set<String> = emptySet(),
    defaultFallbackName: String = "Imported model",
  ): String {
    val baseName =
      if (HuggingFaceUrls.isHuggingFaceUrl(rawUriOrUrl)) {
        val info = extractHfUrlInfo(rawUriOrUrl)
        val modelId = info.modelId
        val infoFileName = info.fileName
        val rawFile = (infoFileName ?: fallbackFileName).substringAfterLast('/')
        val stem = stripModelExtension(rawFile)
        when {
          stem.isNotEmpty() && stem.lowercase() !in GENERIC_MODEL_STEMS -> stem
          !modelId.isNullOrEmpty() -> {
            val repoName = modelId.substringAfterLast('/')
            val parentDir =
              infoFileName
                ?.substringBeforeLast('/', missingDelimiterValue = "")
                ?.substringAfterLast('/')
                .orEmpty()
            if (parentDir.isNotEmpty() && !parentDir.equals(repoName, ignoreCase = true)) {
              "$repoName ($parentDir)"
            } else {
              repoName
            }
          }
          else -> stem.ifEmpty { fallbackFileName }.ifEmpty { defaultFallbackName }
        }
      } else {
        stripModelExtension(fallbackFileName.substringAfterLast('/')).ifEmpty {
          defaultFallbackName
        }
      }

    val lowerExisting = existingDisplayNames.map { it.lowercase() }.toSet()
    if (baseName.lowercase() !in lowerExisting) {
      return baseName
    }
    var suffix = 2
    while ("$baseName ($suffix)".lowercase() in lowerExisting) {
      suffix++
    }
    return "$baseName ($suffix)"
  }

  /**
   * Derives a unique, `/`-free on-disk filename (and [Model.name] identifier) strictly from the
   * model's source [rawUriOrUrl] and [fallbackFileName]—never from user-edited display text.
   *
   * Preserves the `.litertlm` or `.task` extension and appends `_2`, `_3`, etc. if [existingNames]
   * already contains a case-insensitive collision.
   */
  fun deriveUniqueImportedFileName(
    rawUriOrUrl: String,
    fallbackFileName: String,
    existingNames: Set<String> = emptySet(),
  ): String {
    val info =
      if (HuggingFaceUrls.isHuggingFaceUrl(rawUriOrUrl)) extractHfUrlInfo(rawUriOrUrl) else null
    val extSource = info?.fileName ?: fallbackFileName
    val extension = extractModelExtension(extSource)

    val modelId = info?.modelId
    val infoFileName = info?.fileName
    val rawStem =
      if (modelId != null && infoFileName != null) {
          val repoSlug = sanitizeStorageSegment(modelId.replace("/", "__"))
          val fileSlug =
            sanitizeStorageSegment(stripModelExtension(infoFileName).replace("/", "__"))
          "${repoSlug}__${fileSlug}"
        } else {
          sanitizeStorageSegment(stripModelExtension(fallbackFileName.substringAfterLast('/')))
        }
        .ifEmpty { "imported_model" }

    val maxStemLen = (MAX_STORAGE_FILE_NAME_LENGTH - extension.length).coerceAtLeast(16)
    val truncatedStem = rawStem.take(maxStemLen).trimEnd('_', '.', '-')
    val candidate = "$truncatedStem$extension"

    val lowerExisting = existingNames.map { it.lowercase() }.toSet()
    if (candidate.lowercase() !in lowerExisting) {
      return candidate
    }
    var suffix = 2
    while (true) {
      val suffixStr = "_$suffix"
      val stemLimit = (maxStemLen - suffixStr.length).coerceAtLeast(8)
      val numbered = "${truncatedStem.take(stemLimit).trimEnd('_', '.', '-')}$suffixStr$extension"
      if (numbered.lowercase() !in lowerExisting) {
        return numbered
      }
      suffix++
    }
  }

  private fun extractModelExtension(fileName: String): String {
    val lower = fileName.trim().lowercase()
    return SUPPORTED_MODEL_EXTENSIONS.firstOrNull { lower.endsWith(it) } ?: ".litertlm"
  }

  private fun stripModelExtension(fileName: String): String {
    val trimmed = fileName.trim()
    for (ext in SUPPORTED_MODEL_EXTENSIONS) {
      if (trimmed.endsWith(ext, ignoreCase = true)) {
        return trimmed.dropLast(ext.length)
      }
    }
    return trimmed
  }

  private fun sanitizeStorageSegment(segment: String): String =
    segment.replace(Regex("[^a-zA-Z0-9._-]"), "_").replace(Regex("_{3,}"), "__").trim('_')

  /**
   * Resolves an incoming model deep-link [rawImportUrl] (and optional [requestedTaskId]) into a
   * [ModelDeepLinkResolution] against all known [models], [tasks], and [downloadStatuses].
   */
  fun resolveModelDeepLink(
    rawImportUrl: String?,
    models: List<Model>,
    tasks: List<Task>,
    downloadStatuses: Map<String, ModelDownloadStatus>,
    requestedTaskId: String? = null,
  ): ModelDeepLinkResolution {
    val trimmedUrl = rawImportUrl?.trim().orEmpty()
    val canonicalTargetUrl = HfUrlInfo.canonicalizeModelUrl(trimmedUrl)
    if (canonicalTargetUrl.isEmpty()) {
      return ModelDeepLinkResolution.OpenModelManagerDefault
    }

    val urlInfo = extractHfUrlInfo(trimmedUrl)
    if (!urlInfo.isDirectModelFile) {
      return ModelDeepLinkResolution.OpenImportFlow(importUrl = trimmedUrl)
    }

    val prioritizedModels = models.sortedBy { it.downloadInfo.imported }
    val targetRepoId = urlInfo.modelId
    val targetFilePath = urlInfo.fileName
    val targetRevision = urlInfo.revision
    val isDefaultRevision =
      targetRevision == null || targetRevision.equals("main", ignoreCase = true)

    val matchedModel =
      prioritizedModels.firstOrNull { model ->
        val modelCanonicalUrl = HfUrlInfo.canonicalizeModelUrl(model.downloadInfo.url)
        modelCanonicalUrl.isNotEmpty() && modelCanonicalUrl == canonicalTargetUrl
      }
        ?: if (
          isDefaultRevision && !targetRepoId.isNullOrEmpty() && !targetFilePath.isNullOrEmpty()
        ) {
          val targetBaseFile = targetFilePath.substringAfterLast('/')
          prioritizedModels.firstOrNull { model ->
            !model.downloadInfo.imported &&
              model.modelId.equals(targetRepoId, ignoreCase = true) &&
              model.downloadInfo.downloadFileName.equals(targetBaseFile, ignoreCase = true)
          }
        } else {
          null
        }

    if (matchedModel == null) {
      return ModelDeepLinkResolution.OpenImportFlow(importUrl = trimmedUrl)
    }

    val isDownloaded =
      downloadStatuses[matchedModel.name]?.status == ModelDownloadStatusType.SUCCEEDED
    if (!isDownloaded) {
      return ModelDeepLinkResolution.FocusModelInManager(
        modelName = matchedModel.name,
        openTryIt = false,
      )
    }

    val cleanTaskId = requestedTaskId?.trim().orEmpty()
    val matchingTask =
      if (cleanTaskId.isNotEmpty()) {
        tasks.firstOrNull { task ->
          task.id == cleanTaskId && task.models.any { it.name == matchedModel.name }
        }
      } else {
        null
      }

    return if (matchingTask != null) {
      ModelDeepLinkResolution.NavigateToTask(
        taskId = matchingTask.id,
        modelName = matchedModel.name,
      )
    } else {
      ModelDeepLinkResolution.FocusModelInManager(modelName = matchedModel.name, openTryIt = true)
    }
  }
}
