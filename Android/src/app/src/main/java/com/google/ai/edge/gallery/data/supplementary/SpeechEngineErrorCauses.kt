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

package com.google.ai.edge.gallery.data.supplementary

import com.google.ai.edge.gallery.data.SpeechEngineErrorCause

/**
 * Why a task that needs this model can't start it, or `null` if the model is ready.
 *
 * Tasks that can't use a substitute engine block on the returned cause, so the user sees the
 * actions that fix it:
 * - A missing language ([SpeechModelState.NeedsLanguagePack]) asks to choose a language.
 * - An outdated Speech Services ([SpeechModelState.NeedsUpdate]) asks to update it.
 * - A failed or pending check, a recoverable runtime failure, and a model that still has to be
 *   downloaded offer Retry (and another model).
 * - A model the device can't use right now asks to choose another model.
 */
fun SpeechModelState.toSpeechEngineErrorCause(): SpeechEngineErrorCause? =
  when (this) {
    is SpeechModelState.Ready -> null
    is SpeechModelState.NeedsLanguagePack -> SpeechEngineErrorCause.LANGUAGE_NOT_INSTALLED
    is SpeechModelState.NeedsUpdate ->
      when (component) {
        UpdatableComponent.SPEECH_SERVICES -> SpeechEngineErrorCause.SPEECH_SERVICES_OUTDATED
        UpdatableComponent.AICORE -> SpeechEngineErrorCause.OTHER
      }
    is SpeechModelState.EngineError ->
      if (recoverable) SpeechEngineErrorCause.OTHER else SpeechEngineErrorCause.NOT_SUPPORTED
    is SpeechModelState.Hidden,
    is SpeechModelState.NeedsOptIn,
    is SpeechModelState.Unavailable -> SpeechEngineErrorCause.NOT_SUPPORTED
    SpeechModelState.Checking,
    is SpeechModelState.CheckFailed,
    is SpeechModelState.Downloadable,
    is SpeechModelState.Downloading,
    is SpeechModelState.Queued -> SpeechEngineErrorCause.OTHER
  }

/**
 * Why a task can't use a language whose pack is in this state, or `null` if it is installed. A
 * failed or pending check offers Retry; every other state asks to choose another language.
 */
fun LanguageState.toSpeechEngineErrorCause(): SpeechEngineErrorCause? =
  when (this) {
    is LanguageState.Installed -> null
    is LanguageState.Downloadable,
    is LanguageState.Downloading,
    is LanguageState.Queued,
    is LanguageState.Failed,
    is LanguageState.NotOffered -> SpeechEngineErrorCause.LANGUAGE_NOT_INSTALLED
    is LanguageState.Checking,
    is LanguageState.CheckFailed -> SpeechEngineErrorCause.OTHER
  }
