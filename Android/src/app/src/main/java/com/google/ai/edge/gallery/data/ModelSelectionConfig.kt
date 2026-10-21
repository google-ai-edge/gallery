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

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * What a [ModelSelectionSection] picks, which decides where its candidates come from and where the
 * choice is saved when the task launches.
 */
enum class ModelSelectionTarget {
  /** The model the task runs. Candidates are the task's own (non-imported) models. */
  MAIN_MODEL,

  /** The speech recognition model. Candidates are supplementary models. */
  ASR_MODEL,

  /** The text-to-speech model. Candidates are supplementary models. */
  TTS_MODEL,
}

/**
 * One section of a sectioned model selection screen.
 *
 * @property id Stable identifier of the section, unique within its [ModelSelectionConfig].
 * @property titleResId Section header, for example "Main models".
 * @property availableCountResId Plural shown in the screen header, for example "4 main models". It
 *   takes the number of models the section offers as its only argument.
 * @property selectedCountResId Plural shown in the summary sheet, for example "1 main model
 *   selected". It takes the number of selected models as its only argument.
 * @property selectedShortCountResId Short plural joined with the other sections' in the collapsed
 *   summary sheet, for example "1 main" in "Selected: 1 main, 1 ASR, and 0 TTS". It takes the
 *   number of selected models as its only argument.
 * @property emptyMessageResId Message shown when the device has no candidate for this section.
 * @property target What the section picks. See [ModelSelectionTarget].
 * @property infoResId Optional explanation of what the section's model does in the task, shown in a
 *   tooltip next to the section header.
 * @property modelFilter Keeps only the candidates that belong to this section.
 * @property requiredCount How many models must be selected before the task can launch.
 */
data class ModelSelectionSection(
  val id: String,
  @StringRes val titleResId: Int,
  @PluralsRes val availableCountResId: Int,
  @PluralsRes val selectedCountResId: Int,
  @PluralsRes val selectedShortCountResId: Int,
  @StringRes val emptyMessageResId: Int,
  val target: ModelSelectionTarget,
  @StringRes val infoResId: Int? = null,
  val modelFilter: (Model) -> Boolean = { true },
  val requiredCount: Int = 1,
)

/**
 * Describes a task's sectioned model selection screen, shown in place of the regular model list.
 *
 * @property sections The sections, in display order.
 * @property hideSystemSpeechDefaults Whether to hide the System speech recognition and
 *   text-to-speech defaults from the task's settings.
 */
data class ModelSelectionConfig(
  val sections: List<ModelSelectionSection>,
  val hideSystemSpeechDefaults: Boolean = false,
)
