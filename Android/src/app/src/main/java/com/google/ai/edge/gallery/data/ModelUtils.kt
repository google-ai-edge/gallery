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

import com.google.ai.edge.gallery.huggingface.HuggingFaceApiClient
import com.google.ai.edge.gallery.proto.LlmConfig
import com.google.ai.edge.gallery.proto.llmConfig

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
}
