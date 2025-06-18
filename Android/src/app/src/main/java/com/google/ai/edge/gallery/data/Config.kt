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

import kotlin.math.abs

/**
 * The types of configuration editors available.
 *
 * This enum defines the different UI components used to edit configuration values. Each type
 * corresponds to a specific editor widget, such as a slider or a switch.
 */
enum class ConfigEditorType {
  LABEL,
  NUMBER_SLIDER,
  BOOLEAN_SWITCH,
  DROPDOWN,
}

/** The data types of configuration values. */
enum class ValueType {
  INT,
  FLOAT,
  DOUBLE,
  STRING,
  BOOLEAN,
}

enum class ConfigKey(val label: String) {
  MAX_TOKENS("Max tokens"),
  TOPK("TopK"),
  TOPP("TopP"),
  TEMPERATURE("Temperature"),
  DEFAULT_MAX_TOKENS("Default max tokens"),
  DEFAULT_TOPK("Default TopK"),
  DEFAULT_TOPP("Default TopP"),
  DEFAULT_TEMPERATURE("Default temperature"),
  SUPPORT_IMAGE("Support image"),
  SUPPORT_AUDIO("Support audio"),
  MAX_RESULT_COUNT("Max result count"),
  USE_GPU("Use GPU"),
  ACCELERATOR("Choose accelerator"),
  COMPATIBLE_ACCELERATORS("Compatible accelerators"),
  WARM_UP_ITERATIONS("Warm up iterations"),
  BENCHMARK_ITERATIONS("Benchmark iterations"),
  ITERATIONS("Iterations"),
  THEME("Theme"),
  NAME("Name"),
  MODEL_TYPE("Model type"),
}

/**
 * Base class for configuration settings.
 *
 * @param type The type of configuration editor.
 * @param key The unique key for the configuration setting.
 * @param defaultValue The default value for the configuration setting.
 * @param valueType The data type of the configuration value.
 * @param needReinitialization Indicates whether the model needs to be reinitialized after changing
 *   this config.
 */
open class Config(
  val type: ConfigEditorType,
  open val key: ConfigKey,
  open val defaultValue: Any,
  open val valueType: ValueType,
  open val needReinitialization: Boolean = true,
)

/** Configuration setting for a label. */
class LabelConfig(override val key: ConfigKey, override val defaultValue: String = "") :
  Config(
    type = ConfigEditorType.LABEL,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

/**
 * Configuration setting for a number slider.
 *
 * @param sliderMin The minimum value of the slider.
 * @param sliderMax The maximum value of the slider.
 */
class NumberSliderConfig(
  override val key: ConfigKey,
  val sliderMin: Float,
  val sliderMax: Float,
  override val defaultValue: Float,
  override val valueType: ValueType,
  override val needReinitialization: Boolean = true,
) :
  Config(
    type = ConfigEditorType.NUMBER_SLIDER,
    key = key,
    defaultValue = defaultValue,
    valueType = valueType,
  )

/** Configuration setting for a boolean switch. */
class BooleanSwitchConfig(
  override val key: ConfigKey,
  override val defaultValue: Boolean,
  override val needReinitialization: Boolean = true,
) :
  Config(
    type = ConfigEditorType.BOOLEAN_SWITCH,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.BOOLEAN,
  )

/** Configuration setting for a dropdown. */
class SegmentedButtonConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<String>,
  val allowMultiple: Boolean = false,
) :
  Config(
    type = ConfigEditorType.DROPDOWN,
    key = key,
    defaultValue = defaultValue,
    // The emitted value will be comma-separated labels when allowMultiple=true.
    valueType = ValueType.STRING,
  )

fun convertValueToTargetType(value: Any, valueType: ValueType): Any {
  return when (valueType) {
    ValueType.INT ->
      when (value) {
        is Int -> value
        is Float -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull() ?: ""
        is Boolean -> if (value) 1 else 0
        else -> ""
      }

    ValueType.FLOAT ->
      when (value) {
        is Int -> value.toFloat()
        is Float -> value
        is Double -> value.toFloat()
        is String -> value.toFloatOrNull() ?: ""
        is Boolean -> if (value) 1f else 0f
        else -> ""
      }

    ValueType.DOUBLE ->
      when (value) {
        is Int -> value.toDouble()
        is Float -> value.toDouble()
        is Double -> value
        is String -> value.toDoubleOrNull() ?: ""
        is Boolean -> if (value) 1.0 else 0.0
        else -> ""
      }

    ValueType.BOOLEAN ->
      when (value) {
        is Int -> value == 0
        is Boolean -> value
        is Float -> abs(value) > 1e-6
        is Double -> abs(value) > 1e-6
        is String -> value.isNotEmpty()
        else -> false
      }

    ValueType.STRING -> value.toString()
  }
}

fun createLlmChatConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTopP: Float = DEFAULT_TOPP,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
): List<Config> {
  return listOf(
    LabelConfig(key = ConfigKey.MAX_TOKENS, defaultValue = "$defaultMaxToken"),
    NumberSliderConfig(
      key = ConfigKey.TOPK,
      sliderMin = 5f,
      sliderMax = 40f,
      defaultValue = defaultTopK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKey.TOPP,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = defaultTopP,
      valueType = ValueType.FLOAT,
    ),
    NumberSliderConfig(
      key = ConfigKey.TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 2.0f,
      defaultValue = defaultTemperature,
      valueType = ValueType.FLOAT,
    ),
    SegmentedButtonConfig(
      key = ConfigKey.ACCELERATOR,
      defaultValue = accelerators[0].label,
      options = accelerators.map { it.label },
    ),
  )
}

fun getConfigValueString(value: Any, config: Config): String {
  var strNewValue = "$value"
  if (config.valueType == ValueType.FLOAT) {
    strNewValue = "%.2f".format(value)
  }
  return strNewValue
}
