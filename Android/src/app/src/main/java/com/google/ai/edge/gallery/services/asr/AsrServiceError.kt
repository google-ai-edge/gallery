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

package com.google.ai.edge.gallery.services.asr

/**
 * Domain-specific exceptions thrown during [AsrService.prepare] or emitted by the
 * [AsrService.startRecording] flow.
 */
sealed class AsrServiceError(override val message: String, override val cause: Throwable? = null) :
  Exception(message, cause) {

  class NetworkError(cause: Throwable? = null) :
    AsrServiceError("Network error during speech recognition.", cause) {
    override fun equals(other: Any?): Boolean = this === other || other is NetworkError

    override fun hashCode(): Int = NetworkError::class.hashCode()
  }

  class AudioPermissionDenied(cause: Throwable? = null) :
    AsrServiceError("Audio recording permission denied.", cause) {
    override fun equals(other: Any?): Boolean = this === other || other is AudioPermissionDenied

    override fun hashCode(): Int = AudioPermissionDenied::class.hashCode()
  }

  class AudioRecordingFailed(cause: Throwable? = null) :
    AsrServiceError("Audio recording failed.", cause) {
    override fun equals(other: Any?): Boolean = this === other || other is AudioRecordingFailed

    override fun hashCode(): Int = AudioRecordingFailed::class.hashCode()
  }

  class ModelNotDownloaded(cause: Throwable? = null) :
    AsrServiceError("ASR model is not downloaded.", cause) {
    override fun equals(other: Any?): Boolean = this === other || other is ModelNotDownloaded

    override fun hashCode(): Int = ModelNotDownloaded::class.hashCode()
  }

  class ModelInitializationFailed(cause: Throwable? = null) :
    AsrServiceError("Failed to initialize ASR model.", cause) {
    override fun equals(other: Any?): Boolean = this === other || other is ModelInitializationFailed

    override fun hashCode(): Int = ModelInitializationFailed::class.hashCode()
  }

  class HardwareNotSupported(cause: Throwable? = null) :
    AsrServiceError("ASR hardware or feature is not supported on this device.", cause) {
    override fun equals(other: Any?): Boolean = this === other || other is HardwareNotSupported

    override fun hashCode(): Int = HardwareNotSupported::class.hashCode()
  }

  class NoSpeechDetected(cause: Throwable? = null) : AsrServiceError("No speech detected.", cause) {
    override fun equals(other: Any?): Boolean = this === other || other is NoSpeechDetected

    override fun hashCode(): Int = NoSpeechDetected::class.hashCode()
  }

  class RecognizerBusy(cause: Throwable? = null) :
    AsrServiceError("Speech recognizer is busy.", cause) {
    override fun equals(other: Any?): Boolean = this === other || other is RecognizerBusy

    override fun hashCode(): Int = RecognizerBusy::class.hashCode()
  }

  data class Unknown(
    override val message: String = "Unknown ASR error.",
    override val cause: Throwable? = null,
  ) : AsrServiceError(message, cause)
}
