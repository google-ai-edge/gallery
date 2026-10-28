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

package com.google.ai.edge.gallery.services.tts

/** Defines domain-specific error types for Text-to-Speech (TTS) engine operations. */
sealed class TtsServiceError(message: String, cause: Throwable? = null) :
  Exception(message, cause) {
  /** The requested TTS model was not found or is not registered. */
  data class ModelNotFound(val modelId: String) :
    TtsServiceError("TTS model $modelId was not found.")

  /** The underlying TTS engine is unavailable on this device or platform. */
  data class EngineUnavailable(val reason: String) :
    TtsServiceError("TTS engine is unavailable: $reason")

  /**
   * Audio synthesis failed during generation.
   *
   * @property errorCode the engine's error code, such as `TextToSpeech.ERROR_SERVICE`, when the
   *   engine reported one, so callers can recover from specific codes.
   */
  data class SynthesisFailed(
    val msg: String,
    val errCause: Throwable? = null,
    val errorCode: Int? = null,
  ) : TtsServiceError("TTS synthesis failed: $msg", errCause)

  /** The requested operation or format is unsupported by the active engine. */
  data class UnsupportedOperation(val operation: String) :
    TtsServiceError("Unsupported TTS operation: $operation")

  /**
   * The speech service doesn't list the voice for [languageCode] yet, so nothing was spoken
   * (`TextToSpeech.ERROR_NOT_INSTALLED_YET`). Retrying may succeed.
   */
  data class VoiceNotInstalledYet(val languageCode: String) :
    TtsServiceError("TTS voice for $languageCode is not installed yet.")

  /**
   * The speech service that runs the engine stopped or was updated, so the engine's client must be
   * created again before it can speak.
   */
  data class ServiceDisconnected(val reason: String, val errCause: Throwable? = null) :
    TtsServiceError("TTS service disconnected: $reason", errCause)

  /**
   * The download of the voice for [languageCode] stopped reporting progress, so the app stopped
   * waiting for it. The speech service may still finish it later.
   */
  data class DownloadStalled(val languageCode: String) :
    TtsServiceError("The TTS voice download for $languageCode stopped reporting progress.")
}
