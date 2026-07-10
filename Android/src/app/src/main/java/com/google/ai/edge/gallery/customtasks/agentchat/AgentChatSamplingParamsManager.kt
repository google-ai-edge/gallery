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

package com.google.ai.edge.gallery.customtasks.agentchat

import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages task-specific sampling parameters (such as `TopK`) in memory when entering or leaving the
 * Agent Skills (`LLM_AGENT_CHAT`) task, specifically enforcing greedy decoding (`TopK = 1`) by
 * default while preserving user customizations and original general-chat sampling configurations.
 */
object AgentChatSamplingParamsManager {
  /**
   * Tracks model names where the user explicitly customized `TopK` via the configuration dialog
   * inside Agent Skills, preventing future automatic overrides with the initial default greedy
   * (`TopK = 1`) assignment.
   */
  private val agentSkillTopKAdjustedMap: MutableSet<String> =
    Collections.newSetFromMap(ConcurrentHashMap())

  /**
   * Stores the model's general chat (`LLM_CHAT`) `TopK` value prior to entering the Agent Skills
   * task, used to restore the original parameter when exiting Agent Skills. Keyed by model name.
   */
  private val preAgentSkillTopKMap: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

  /**
   * Stores the custom `TopK` value specifically configured by the user inside Agent Skills
   * (`LLM_AGENT_CHAT`). Keyed by model name.
   */
  private val agentSkillTopKMap: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

  fun isAdjusted(modelName: String): Boolean = agentSkillTopKAdjustedMap.contains(modelName)

  fun setAdjusted(modelName: String, adjusted: Boolean) {
    if (adjusted) {
      agentSkillTopKAdjustedMap.add(modelName)
    } else {
      agentSkillTopKAdjustedMap.remove(modelName)
    }
  }

  fun getPreTopK(modelName: String): Any? = preAgentSkillTopKMap[modelName]

  fun setPreTopK(modelName: String, topK: Any?) {
    if (topK == null) {
      preAgentSkillTopKMap.remove(modelName)
    } else {
      preAgentSkillTopKMap[modelName] = topK
    }
  }

  fun getSkillTopK(modelName: String): Any? = agentSkillTopKMap[modelName]

  fun setSkillTopK(modelName: String, topK: Any?) {
    if (topK == null) {
      agentSkillTopKMap.remove(modelName)
    } else {
      agentSkillTopKMap[modelName] = topK
    }
  }

  fun clear(modelName: String) {
    agentSkillTopKAdjustedMap.remove(modelName)
    preAgentSkillTopKMap.remove(modelName)
    agentSkillTopKMap.remove(modelName)
  }
}

var Model.agentSkillTopKAdjusted: Boolean
  get() = AgentChatSamplingParamsManager.isAdjusted(this.name)
  set(value) = AgentChatSamplingParamsManager.setAdjusted(this.name, value)

var Model.preAgentSkillTopK: Any?
  get() = AgentChatSamplingParamsManager.getPreTopK(this.name)
  set(value) = AgentChatSamplingParamsManager.setPreTopK(this.name, value)

var Model.agentSkillTopK: Any?
  get() = AgentChatSamplingParamsManager.getSkillTopK(this.name)
  set(value) = AgentChatSamplingParamsManager.setSkillTopK(this.name, value)

/**
 * Configures [Model]'s `TopK` setting when entering the Agent Skills task.
 *
 * Captures current general chat `TopK` into `preAgentSkillTopK` if not already stored, and sets
 * `TopK` to 1 (greedy decoding) unless the user manually customized `TopK` for Agent Skills.
 *
 * @return `true` if `configValues` changed, `false` otherwise.
 */
@Synchronized
fun Model.setupAgentSkillTopK(): Boolean {
  if (this.preAgentSkillTopK == null) {
    this.preAgentSkillTopK = this.configValues[ConfigKeys.TOPK.label]
  }
  // Refers to `configValues` in the `Model` class.
  var configValuesChanged = false
  val newConfigValues = this.configValues.toMutableMap()
  if (!this.agentSkillTopKAdjusted) {
    if (newConfigValues[ConfigKeys.TOPK.label] != 1f) {
      newConfigValues[ConfigKeys.TOPK.label] = 1f
      this.agentSkillTopK = 1f
      this.configValues = newConfigValues
      configValuesChanged = true
    } else if (this.agentSkillTopK != 1f) {
      // Handles the edge case where configValues[TOPK] is already 1f on entry, but
      // agentSkillTopK in memory (agentSkillTopKMap) is not yet initialized to 1f. For example,
      // this can happen when entering Agent Chat (setupAgentSkillTopK()) on a model whose
      // general TopK is already 1f.
      this.agentSkillTopK = 1f
    }
  } else {
    val currentSkillTopK = this.agentSkillTopK
    if (currentSkillTopK != null && newConfigValues[ConfigKeys.TOPK.label] != currentSkillTopK) {
      newConfigValues[ConfigKeys.TOPK.label] = currentSkillTopK
      this.configValues = newConfigValues
      configValuesChanged = true
    }
  }
  return configValuesChanged
}

/**
 * Restores [Model]'s original general chat `TopK` when exiting the Agent Skills task.
 *
 * @return `true` if `configValues` changed, `false` otherwise.
 */
@Synchronized
fun Model.cleanupAgentSkillTopK(): Boolean {
  val previousTopK = this.preAgentSkillTopK ?: return false
  this.preAgentSkillTopK = null
  if (this.configValues[ConfigKeys.TOPK.label] != previousTopK) {
    val restoreValues = this.configValues.toMutableMap()
    restoreValues[ConfigKeys.TOPK.label] = previousTopK
    this.configValues = restoreValues
    return true
  }
  return false
}
