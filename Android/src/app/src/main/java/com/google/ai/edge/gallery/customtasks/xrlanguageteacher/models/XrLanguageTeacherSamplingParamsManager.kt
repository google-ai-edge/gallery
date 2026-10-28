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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages task-specific sampling parameters (`TopK`) in memory when entering or leaving the XR
 * Language Teacher (`LLM_XR_LANGUAGE_TEACHER`) task, enforcing greedy decoding (`TopK = 1`) by
 * default while preserving manual user customizations and restoring general-chat sampling settings
 * on exit.
 */
object XrLanguageTeacherSamplingParamsManager {
  /**
   * Tracks model names where the user explicitly customized `TopK` via the configuration dialog
   * inside XR Language Teacher, preventing automatic overrides back to `TopK = 1`.
   */
  private val adjustedModelSet: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

  /**
   * Stores the model's general `TopK` value prior to entering XR Language Teacher so it can be
   * restored when exiting the task.
   */
  private val preTaskTopKMap: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

  /**
   * Stores the custom `TopK` value configured by the user specifically inside XR Language Teacher.
   */
  private val taskTopKMap: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

  fun isAdjusted(modelName: String): Boolean = modelName in adjustedModelSet

  fun setAdjusted(modelName: String, adjusted: Boolean) {
    if (adjusted) {
      adjustedModelSet.add(modelName)
    } else {
      adjustedModelSet.remove(modelName)
    }
  }

  fun getPreTopK(modelName: String): Any? = preTaskTopKMap[modelName]

  fun setPreTopK(modelName: String, topK: Any?) {
    if (topK == null) {
      preTaskTopKMap.remove(modelName)
    } else {
      preTaskTopKMap[modelName] = topK
    }
  }

  fun getTaskTopK(modelName: String): Any? = taskTopKMap[modelName]

  fun setTaskTopK(modelName: String, topK: Any?) {
    if (topK == null) {
      taskTopKMap.remove(modelName)
    } else {
      taskTopKMap[modelName] = topK
    }
  }

  fun clear(modelName: String) {
    adjustedModelSet.remove(modelName)
    preTaskTopKMap.remove(modelName)
    taskTopKMap.remove(modelName)
  }
}

var Model.xrTeacherTopKAdjusted: Boolean
  get() = XrLanguageTeacherSamplingParamsManager.isAdjusted(this.name)
  set(value) = XrLanguageTeacherSamplingParamsManager.setAdjusted(this.name, value)

var Model.preXrTeacherTopK: Any?
  get() = XrLanguageTeacherSamplingParamsManager.getPreTopK(this.name)
  set(value) = XrLanguageTeacherSamplingParamsManager.setPreTopK(this.name, value)

var Model.xrTeacherTopK: Any?
  get() = XrLanguageTeacherSamplingParamsManager.getTaskTopK(this.name)
  set(value) = XrLanguageTeacherSamplingParamsManager.setTaskTopK(this.name, value)

/**
 * Configures [Model]'s `TopK` setting when entering XR Language Teacher.
 *
 * Captures the model's prior `TopK` into `preXrTeacherTopK` if not already stored, and sets `TopK`
 * to `1f` (greedy decoding) unless the user manually customized `TopK` for XR Language Teacher.
 *
 * @return `true` if `configValues` changed, `false` otherwise.
 */
@Synchronized
fun Model.setupXrLanguageTeacherTopK(): Boolean {
  if (this.preXrTeacherTopK == null) {
    this.preXrTeacherTopK = this.configValues[ConfigKeys.TOPK.label]
  }
  var configValuesChanged = false
  val newConfigValues = this.configValues.toMutableMap()
  if (!this.xrTeacherTopKAdjusted) {
    when {
      newConfigValues[ConfigKeys.TOPK.label] != 1f -> {
        newConfigValues[ConfigKeys.TOPK.label] = 1f
        this.xrTeacherTopK = 1f
        this.configValues = newConfigValues
        configValuesChanged = true
      }
      this.xrTeacherTopK != 1f -> this.xrTeacherTopK = 1f
    }
  } else {
    val currentTaskTopK = this.xrTeacherTopK
    if (currentTaskTopK != null && newConfigValues[ConfigKeys.TOPK.label] != currentTaskTopK) {
      newConfigValues[ConfigKeys.TOPK.label] = currentTaskTopK
      this.configValues = newConfigValues
      configValuesChanged = true
    }
  }
  return configValuesChanged
}

/**
 * Restores [Model]'s original `TopK` when exiting XR Language Teacher.
 *
 * @return `true` if `configValues` changed, `false` otherwise.
 */
@Synchronized
fun Model.cleanupXrLanguageTeacherTopK(): Boolean {
  val previousTopK = this.preXrTeacherTopK ?: return false
  this.preXrTeacherTopK = null
  if (this.configValues[ConfigKeys.TOPK.label] != previousTopK) {
    val restoreValues = this.configValues.toMutableMap()
    restoreValues[ConfigKeys.TOPK.label] = previousTopK
    this.configValues = restoreValues
    return true
  }
  return false
}
