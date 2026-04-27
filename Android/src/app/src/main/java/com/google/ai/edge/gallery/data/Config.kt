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

import androidx.annotation.StringRes
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
  SEGMENTED_BUTTON,
  BOTTOMSHEET_SELECTOR,
}

/** The data types of configuration values. */
enum class ValueType {
  INT,
  FLOAT,
  DOUBLE,
  STRING,
  BOOLEAN,
}

data class ConfigKey(val id: String, val label: String)

object ConfigKeys {
  val MAX_TOKENS = ConfigKey("max_tokens", "最大 Token")
  val TOPK = ConfigKey("topk", "TopK")
  val TOPP = ConfigKey("topp", "TopP")
  val TEMPERATURE = ConfigKey("temperature", "温度")
  val DEFAULT_MAX_TOKENS = ConfigKey("default_max_tokens", "默认最大 Token")
  val DEFAULT_TOPK = ConfigKey("default_topk", "默认 TopK")
  val DEFAULT_TOPP = ConfigKey("default_topp", "默认 TopP")
  val DEFAULT_TEMPERATURE = ConfigKey("default_temperature", "默认温度")
  val SUPPORT_IMAGE = ConfigKey("support_image", "支持图片")
  val SUPPORT_AUDIO = ConfigKey("support_audio", "支持音频")
  val SUPPORT_TINY_GARDEN = ConfigKey("support_tiny_garden", "支持微型花园")
  val SUPPORT_MOBILE_ACTIONS = ConfigKey("support_mobile_actions", "支持移动操作")
  val SUPPORT_THINKING = ConfigKey("support_thinking", "支持思考")
  val ENABLE_THINKING = ConfigKey("enable_thinking", "启用思考")
  val MAX_RESULT_COUNT = ConfigKey("max_result_count", "最大结果数")
  val USE_GPU = ConfigKey("use_gpu", "使用 GPU")
  val ACCELERATOR = ConfigKey("accelerator", "加速器")
  val VISION_ACCELERATOR = ConfigKey("vision_accelerator", "视觉加速器")
  val COMPATIBLE_ACCELERATORS = ConfigKey("compatible_accelerators", "兼容加速器")
  val WARM_UP_ITERATIONS = ConfigKey("warm_up_iterations", "预热迭代")
  val BENCHMARK_ITERATIONS = ConfigKey("benchmark_iterations", "基准测试迭代")
  val ITERATIONS = ConfigKey("iterations", "迭代次数")
  val THEME = ConfigKey("theme", "主题")
  val NAME = ConfigKey("name", "名称")
  val MODEL_TYPE = ConfigKey("model_type", "模型类型")
  val MODEL = ConfigKey("model", "模型")
  val RESET_CONVERSATION_TURN_COUNT =
    ConfigKey("reset_conversation_turn_count", "对话重置前的轮次数")
  val PREFILL_TOKENS = ConfigKey("prefill_tokens", "预填充 Token")
  val DECODE_TOKENS = ConfigKey("decode_tokens", "解码 Token")
  val NUMBER_OF_RUNS = ConfigKey("number_of_runs", "运行次数")
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
  // Changes on any configs with this field set to true will automatically trigger a model
  // re-initialization.
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
) : Config(
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
) : Config(
  type = ConfigEditorType.BOOLEAN_SWITCH,
  key = key,
  defaultValue = defaultValue,
  valueType = ValueType.BOOLEAN,
)

/** Configuration setting for a segmented button. */
class SegmentedButtonConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<String>,
  val allowMultiple: Boolean = false,
) : Config(
  type = ConfigEditorType.SEGMENTED_BUTTON,
  key = key,
  defaultValue = defaultValue,
  // The emitted value will be comma-separated labels when allowMultiple=true.
  valueType = ValueType.STRING,
)

/** Configuration setting for a bottom sheet selector. */
class BottomSheetSelectorConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<BottomSheetSelectorItem>,
  @StringRes val bottomSheetTitleResId: Int? = null,
) : Config(
  type = ConfigEditorType.BOTTOMSHEET_SELECTOR,
  key = key,
  defaultValue = defaultValue,
  valueType = ValueType.STRING,
)

data class BottomSheetSelectorItem(val label: String)

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
  defaultMaxContextLength: Int? = null,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTopP: Float = DEFAULT_TOPP,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
  supportThinking: Boolean = false,
): List<Config> {
  var maxTokensConfig: Config =
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken")
  if (defaultMaxContextLength != null) {
    maxTokensConfig =
      NumberSliderConfig(
        key = ConfigKeys.MAX_TOKENS,
        sliderMin = 2000f,
        sliderMax = defaultMaxContextLength.toFloat(),
        defaultValue = defaultMaxToken.toFloat(),
        valueType = ValueType.INT,
      )
  }
  val configs =
    listOf(
        maxTokensConfig,
        NumberSliderConfig(
          key = ConfigKeys.TOPK,
          sliderMin = 5f,
          sliderMax = 100f,
          defaultValue = defaultTopK.toFloat(),
          valueType = ValueType.INT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TOPP,
          sliderMin = 0.0f,
          sliderMax = 1.0f,
          defaultValue = defaultTopP,
          valueType = ValueType.FLOAT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TEMPERATURE,
          sliderMin = 0.0f,
          sliderMax = 2.0f,
          defaultValue = defaultTemperature,
          valueType = ValueType.FLOAT,
        ),
        SegmentedButtonConfig(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = accelerators[0].label,
          options = accelerators.map { it.label },
        ),
      )
      .toMutableList()
  if (supportThinking) {
    configs.add(
      BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
    )
  }
  return configs
}

/**
 * Creates the configuration settings for an LLM model that only supports NPU.
 *
 * For now NPU models don't support setting topK, topP, and temperature.
 */
fun createLlmChatConfigsForNpuModel(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
): List<Config> {
  return listOf(
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken"),
    SegmentedButtonConfig(
      key = ConfigKeys.ACCELERATOR,
      defaultValue = accelerators[0].label,
      options = accelerators.map { it.label },
    ),
  )
}

/**
 * Creates the configuration settings for an AICore model.
 *
 * AICore models support setting topK and temperature (clamped between 0.0 and 1.0), but not topP.
 */
fun createAICoreConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
): List<Config> {
  return listOf(
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken"),
    NumberSliderConfig(
      key = ConfigKeys.TOPK,
      sliderMin = 5f,
      sliderMax = 100f,
      defaultValue = defaultTopK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = defaultTemperature,
      valueType = ValueType.FLOAT,
    ),
    SegmentedButtonConfig(
      key = ConfigKeys.ACCELERATOR,
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
