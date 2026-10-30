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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services

import android.util.Log
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.resolveEngineLocaleTagForLanguage
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.toLocale
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.TAG
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels.XrLanguageTeacherViewModel
import com.google.ai.edge.gallery.proto.Language
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrModelIdentifier
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.services.asr.AsrPreparationConfig
import com.google.ai.edge.gallery.services.asr.AsrService
import com.google.ai.edge.gallery.services.asr.AsrSessionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * Returns the ASR engine that recognizes [learnLanguage] in the study games: the user's selected
 * engine when it supports [learnLanguage] and is ready for it (by the lesson's model and language
 * pack checks), or else the system speech recognizer, so the games work in every learn language.
 * Returns `null` when no engine is registered.
 *
 * Preparing the engine for [learnLanguage] changes the language of the shared engine, so the game
 * ViewModels have the lesson prepare it for the host language again after the game.
 */
internal suspend fun XrLanguageTeacherViewModel.resolveGameAsrTarget(
  learnLanguage: Language
): XrLanguageTeacherGameAsrTarget? {
  val engineType = selectedAsrEngineType
  val modelId = selectedAsrModelID.value
  if (engineType != null && !AsrModelRegistry.isSystemEngine(engineType)) {
    val localeTag =
      resolveEngineLocaleTagForLanguage(learnLanguage, getSupportedLocaleTagsForAsrModel(modelId))
    val service = asrServiceProvider.serviceOrNull(engineType)
    if (localeTag != null && service != null && isLanguageReady(engineType, service, localeTag)) {
      val config =
        AsrSessionSettings(modelId = modelId, languageCode = localeTag, showInterimResults = true)
          .preparationConfig()
      return gameAsrTarget(engineType, config)
    }
  }
  val systemConfig =
    AsrSessionSettings(
        modelId = AsrModelIdentifier.SYSTEM_DEFAULT,
        languageCode = learnLanguage.toLocale().toLanguageTag(),
        showInterimResults = true,
      )
      .preparationConfig()
  return gameAsrTarget(AsrEngineType.SYSTEM_DEFAULT, systemConfig)
}

/**
 * Whether the selected [engineType], served by [service], can recognize [localeTag] now, by the
 * same model and language pack checks as the lesson (see [selectedAsrEngineErrorCause]).
 */
private suspend fun XrLanguageTeacherViewModel.isLanguageReady(
  engineType: AsrEngineType,
  service: AsrService,
  localeTag: String,
): Boolean =
  try {
    selectedAsrEngineErrorCause(engineType, service, localeTag) == null
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    Log.w(TAG, "Failed to check whether the game ASR engine is ready for '$localeTag'", e)
    false
  }

private fun XrLanguageTeacherViewModel.gameAsrTarget(
  engineType: AsrEngineType,
  config: AsrPreparationConfig,
): XrLanguageTeacherGameAsrTarget? {
  val service = asrServiceProvider.serviceOrNull(engineType) ?: return null
  return XrLanguageTeacherGameAsrTarget(
    service = service,
    prepare = {
      checkNotNull(asrServiceProvider.prepareServiceOrNull(engineType, config)) {
        "No ASR engine registered for $engineType"
      }
    },
  )
}

/**
 * Creates the continuous learn-language speech recognition of a study game round, delivering its
 * events to [listener] on [scope].
 *
 * Android's `AsrRecordingOptions` has no contextual phrases, so unlike iOS the deck words are not
 * passed to the recognizer as biasing phrases; the fuzzy speech matcher covers near misses instead.
 */
internal fun XrLanguageTeacherViewModel.createGameSpeechRecognizer(
  scope: CoroutineScope,
  learnLanguage: Language,
  listener: XrLanguageTeacherGameSpeechListener,
  hasMicPermission: () -> Boolean,
): XrLanguageTeacherGameSpeechRecognizer =
  XrLanguageTeacherGameSpeechSession(
    scope = scope,
    hasMicPermission = hasMicPermission,
    resolveTarget = { resolveGameAsrTarget(learnLanguage) },
    listener = listener,
  )
