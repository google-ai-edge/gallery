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

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.annotation.StringRes
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TtsVoice

/** Modality filter chips at the top of the Model Manager's Supplementary tab. */
enum class SupplementaryModelFilter {
  ALL,
  TTS,
  ASR,
}

/**
 * Everything the Model Manager's Supplementary tab renders.
 *
 * @property filter The selected modality filter chip.
 * @property cards The visible model cards that match [filter], in allowlist order.
 * @property visibleModelCount Number of visible (not hidden) supplementary models across all
 *   filters, shown in the tab title.
 */
data class SupplementaryModelsUiState(
  val filter: SupplementaryModelFilter = SupplementaryModelFilter.ALL,
  val cards: List<SupplementaryModelCardUiState> = emptyList(),
  val visibleModelCount: Int = 0,
)

/**
 * One supplementary model card.
 *
 * @property model The allowlist model the card shows.
 * @property isBestOverall Whether the card shows the "Best overall" badge for its modality.
 * @property usesTopLevelDownload Whether the model is downloaded as a file with the card's regular
 *   download button and progress, instead of being managed by a system service.
 * @property statusLine The line under the model name, or `null` to show the card's regular download
 *   status.
 * @property notice A panel explaining why the model can't be used yet, with an optional action.
 * @property defaultToggle The "Default to this model" checkbox, or `null` while the model isn't
 *   ready to be the default.
 * @property voices The voice profiles of a ready TTS model, or `null` when not shown.
 * @property languages The "Supported languages" list, or `null` when not shown.
 */
data class SupplementaryModelCardUiState(
  val model: Model,
  val isBestOverall: Boolean = false,
  val usesTopLevelDownload: Boolean = false,
  val statusLine: SupplementaryStatusLine? = null,
  val notice: SupplementaryCardNotice? = null,
  val defaultToggle: SupplementaryDefaultToggle? = null,
  val voices: SupplementaryVoicesUiState? = null,
  val languages: SupplementaryLanguagesUiState? = null,
)

/** The line under a supplementary model's name. */
sealed interface SupplementaryStatusLine {
  /** The model's availability is being checked. */
  data object Checking : SupplementaryStatusLine

  /** A fixed message; [isError] shows it in the error color. */
  data class Message(@StringRes val textRes: Int, val isError: Boolean = false) :
    SupplementaryStatusLine

  /**
   * The model is downloading; [totalBytes] is `0` when the size is unknown. [continuesInBackground]
   * is `true` when the download can't be stopped and keeps running if the user leaves.
   */
  data class Downloading(
    val receivedBytes: Long,
    val totalBytes: Long,
    val continuesInBackground: Boolean,
  ) : SupplementaryStatusLine

  /** [installed] of the model's [total] languages are installed. */
  data class LanguagesInstalled(val installed: Int, val total: Int) : SupplementaryStatusLine
}

/** A panel with a message and an optional action, shown in an expanded card. */
data class SupplementaryCardNotice(
  @StringRes val messageRes: Int,
  val action: SupplementaryCardAction? = null,
)

/** The action button of a [SupplementaryCardNotice]. */
sealed interface SupplementaryCardAction {
  /** Opens [url] in the browser or the Play Store; [labelRes] is the button label. */
  data class OpenUrl(@StringRes val labelRes: Int, val url: String) : SupplementaryCardAction

  /** Checks the model's availability again. */
  data object Retry : SupplementaryCardAction

  /** Starts downloading the model. */
  data object Download : SupplementaryCardAction
}

/** What a supplementary model is the default for. */
enum class SupplementaryDefaultUsage {
  READ_ALOUD,
  SPEECH_INPUT,
}

/** The "Default to this model" checkbox of a ready model. */
data class SupplementaryDefaultToggle(val usage: SupplementaryDefaultUsage, val isDefault: Boolean)

/**
 * The voice profiles of a ready TTS model.
 *
 * @property engineId Identifier of the engine that speaks the previews.
 * @property canSelect Whether the voices are radio buttons, which is only while the model is the
 *   read-aloud default.
 * @property speakableLanguageCodes Languages the engine can speak right away, for the preview.
 * @property appliesToAllLanguages Whether the model's voice profiles apply across all supported
 *   languages (shown in the Voice profiles header).
 */
data class SupplementaryVoicesUiState(
  val engineId: String,
  val voices: List<TtsVoice>,
  val selectedVoiceId: String,
  val canSelect: Boolean,
  val speakableLanguageCodes: List<String>,
  val appliesToAllLanguages: Boolean = false,
)
