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

/** A speech engine a task needs. */
enum class SpeechEngineKind {
  /** The speech recognition (ASR) engine. */
  SPEECH_RECOGNITION,

  /** The text-to-speech engine: the task's voice. */
  VOICE;

  /** The model selection section target that picks this engine. */
  val selectionTarget: ModelSelectionTarget
    get() =
      when (this) {
        SPEECH_RECOGNITION -> ModelSelectionTarget.ASR_MODEL
        VOICE -> ModelSelectionTarget.TTS_MODEL
      }
}

/** Why a speech engine couldn't start. */
enum class SpeechEngineErrorCause {
  /** Speech Services by Google is missing or too old to serve the engine. */
  SPEECH_SERVICES_OUTDATED,

  /** A language the task uses isn't installed for the engine. */
  LANGUAGE_NOT_INSTALLED,

  /** The selected model has no engine the task can run yet. */
  NOT_SUPPORTED,

  /** Anything else, for example a missing model or a failed initialization. */
  OTHER,
}

/** What the user can do about a [SpeechEngineError]. */
enum class SpeechEngineErrorAction {
  /** Open the Play Store listing of Speech Services by Google. */
  UPDATE,

  /** Open the task's language settings. */
  CHOOSE_LANGUAGE,

  /** Go back to the model selection screen with the engine's section expanded. */
  CHOOSE_ANOTHER_MODEL,

  /** Try to start the engine again. */
  RETRY,
}

/** A speech engine of [kind] that couldn't start because of [cause]. */
data class SpeechEngineError(val kind: SpeechEngineKind, val cause: SpeechEngineErrorCause) {
  /**
   * The actions offered for this error, the primary one first. Errors are never retried
   * automatically.
   */
  val actions: List<SpeechEngineErrorAction>
    get() =
      when (cause) {
        // Retry lets the user try again after updating, since nothing retries automatically.
        SpeechEngineErrorCause.SPEECH_SERVICES_OUTDATED ->
          listOf(SpeechEngineErrorAction.UPDATE, SpeechEngineErrorAction.RETRY)
        SpeechEngineErrorCause.LANGUAGE_NOT_INSTALLED ->
          listOf(SpeechEngineErrorAction.CHOOSE_LANGUAGE)
        SpeechEngineErrorCause.NOT_SUPPORTED -> listOf(SpeechEngineErrorAction.CHOOSE_ANOTHER_MODEL)
        SpeechEngineErrorCause.OTHER ->
          listOf(SpeechEngineErrorAction.RETRY, SpeechEngineErrorAction.CHOOSE_ANOTHER_MODEL)
      }
}
