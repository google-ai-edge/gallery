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

/**
 * The types of configuration editors available.
 *
 * This enum defines the different UI components used to edit configuration values.
 * Each type corresponds to a specific editor widget, such as a slider or a switch.
 */
enum class ConfigEditorType {
  LABEL,
  NUMBER_SLIDER,
  BOOLEAN_SWITCH,
  DROPDOWN,
}

/**
 * The data types of configuration values.
 */
enum class ValueType {
  INT,
  FLOAT,
  DOUBLE,
  STRING,
  BOOLEAN,
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

/**
 * Configuration setting for a label.
 */
class LabelConfig(
  override val key: ConfigKey,
  override val defaultValue: String = "",
) : Config(
  type = ConfigEditorType.LABEL,
  key = key,
  defaultValue = defaultValue,
  valueType = ValueType.STRING
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

/**
 * Configuration setting for a boolean switch.
 */
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

/**
 * Configuration setting for a dropdown.
 */
class SegmentedButtonConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<String>,
  val allowMultiple: Boolean = false,
) : Config(
  type = ConfigEditorType.DROPDOWN,
  key = key,
  defaultValue = defaultValue,
  // The emitted value will be comma-separated labels when allowMultiple=true.
  valueType = ValueType.STRING,
)