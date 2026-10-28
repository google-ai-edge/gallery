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

import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelSelectionConfig
import com.google.ai.edge.gallery.data.ModelSelectionSection
import com.google.ai.edge.gallery.data.ModelSelectionTarget
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.services.asr.AsrModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier

/** Id of the XR Language Teacher's main model section. */
const val XLT_MAIN_MODEL_SECTION_ID = "main"

/** Id of the XR Language Teacher's speech recognition section. */
const val XLT_ASR_MODEL_SECTION_ID = "asr"

/** Id of the XR Language Teacher's text-to-speech section. */
const val XLT_TTS_MODEL_SECTION_ID = "tts"

/**
 * Whether [model] is a speech recognition model XR Language Teacher can use. The System speech
 * recognizer is never offered.
 */
internal fun isXrLanguageTeacherAsrModel(model: Model): Boolean =
  model.modelType == ModelType.ASR && !AsrModelIdentifier.isSystemEngine(model.modelId)

/**
 * Whether [model] is a text-to-speech model XR Language Teacher can use. The Android System voice
 * is never offered, and a TTS model must support more than one language so that there is at least
 * one native language and one target language to learn.
 */
internal fun isXrLanguageTeacherTtsModel(model: Model): Boolean {
  if (model.modelType != ModelType.TTS) {
    return false
  }
  val engineId = model.ttsProfile?.engineModelId?.ifEmpty { null } ?: model.name
  if (TtsModelIdentifier.isSystemEngine(engineId)) {
    return false
  }
  val languages = model.ttsProfile?.languages.orEmpty()
  return languages.isEmpty() || languages.size > 1
}

/**
 * The XR Language Teacher's model selection screen: one main model, one speech recognition model
 * and one text-to-speech model. Device gating is already applied by the model manager, which drops
 * the models this device can't run.
 */
val XR_LANGUAGE_TEACHER_MODEL_SELECTION_CONFIG =
  ModelSelectionConfig(
    sections =
      listOf(
        ModelSelectionSection(
          id = XLT_MAIN_MODEL_SECTION_ID,
          titleResId = R.string.model_selection_main_models_title,
          availableCountResId = R.plurals.model_selection_main_models_available,
          selectedCountResId = R.plurals.model_selection_main_models_selected,
          selectedShortCountResId = R.plurals.model_selection_main_models_selected_short,
          emptyMessageResId = R.string.model_selection_no_main_models,
          target = ModelSelectionTarget.MAIN_MODEL,
          infoResId = R.string.xrteacher_model_selection_main_info,
        ),
        ModelSelectionSection(
          id = XLT_ASR_MODEL_SECTION_ID,
          titleResId = R.string.model_selection_asr_models_title,
          availableCountResId = R.plurals.model_selection_asr_models_available,
          selectedCountResId = R.plurals.model_selection_asr_models_selected,
          selectedShortCountResId = R.plurals.model_selection_asr_models_selected_short,
          emptyMessageResId = R.string.model_selection_no_asr_models,
          target = ModelSelectionTarget.ASR_MODEL,
          infoResId = R.string.xrteacher_model_selection_asr_info,
          modelFilter = ::isXrLanguageTeacherAsrModel,
        ),
        ModelSelectionSection(
          id = XLT_TTS_MODEL_SECTION_ID,
          titleResId = R.string.model_selection_tts_models_title,
          availableCountResId = R.plurals.model_selection_tts_models_available,
          selectedCountResId = R.plurals.model_selection_tts_models_selected,
          selectedShortCountResId = R.plurals.model_selection_tts_models_selected_short,
          emptyMessageResId = R.string.model_selection_no_tts_models,
          target = ModelSelectionTarget.TTS_MODEL,
          infoResId = R.string.xrteacher_model_selection_tts_info,
          modelFilter = ::isXrLanguageTeacherTtsModel,
        ),
      ),
    hideSystemSpeechDefaults = true,
  )
