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

import com.google.ai.edge.gallery.data.supplementary.SpeechAvailability
import com.google.ai.edge.gallery.data.supplementary.SpeechAvailabilityProbe
import com.google.ai.edge.gallery.data.supplementary.SpeechUnavailabilityReason
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Contract for Automatic Speech Recognition (ASR) engines. */
interface AsrService : SpeechAvailabilityProbe {
  /** Which [AsrEngineType] this service represents. */
  val engineType: AsrEngineType

  /** Reactive state representing the current lifecycle status of the ASR engine. */
  val status: StateFlow<AsrServiceStatus>

  /**
   * Normalized audio input amplitude in `[0f, 1f]` during active recording, or `0f` when idle.
   *
   * Allows UI callers to drive waveform/volume animations uniformly across engines without passing
   * side-effect callbacks into [startRecording].
   */
  val currentAmplitude: StateFlow<Float>
    get() = DEFAULT_AMPLITUDE_FLOW

  /** Metadata for the model currently prepared on this service, or `null` before [prepare]. */
  val activeModel: AsrModelMetadata?
    get() = null

  /**
   * Models this service can prepare on the current device.
   *
   * Multi-engine providers aggregate the lists of their backing engines.
   */
  val availableModels: List<AsrModelMetadata>
    get() = emptyList()

  /**
   * Specific reason why this service most recently reported [AsrServiceStatus.Unavailable], or
   * `null` if available or unspecified.
   */
  val unavailabilityReason: AsrUnavailabilityReason?
    get() = null

  /**
   * Whether this engine manages its own asset downloads directly (exposing them via [download]),
   * independent of the generic supplementary model repository.
   *
   * For current engines, this is the inverse of [AsrModelMetadata.requiresRepositoryModel]: an
   * engine either pulls its own assets (e.g. `SPEECH_PROCESSOR`) or relies on a repository-managed
   * model file when downloadable.
   */
  val managesOwnDownloads: Boolean
    get() = false

  /**
   * Queries whether the engine and its required language/model assets are available on the device.
   *
   * Non-downloadable engines (such as System Speech) return [AsrServiceStatus.Ready] by default.
   */
  suspend fun checkStatus(): AsrServiceStatus = AsrServiceStatus.Ready

  /**
   * Queries whether the language pack or model asset for [languageCode] is installed on the device.
   *
   * Single-bundle engines delegate to [checkStatus] by default.
   */
  suspend fun checkLanguageStatus(languageCode: String): AsrServiceStatus = checkStatus()

  override suspend fun checkAvailability(modelId: String): SpeechAvailability =
    when (checkStatus()) {
      is AsrServiceStatus.Ready,
      is AsrServiceStatus.Downloaded,
      is AsrServiceStatus.Initializing,
      is AsrServiceStatus.Recognizing,
      is AsrServiceStatus.Processing -> SpeechAvailability.Available
      is AsrServiceStatus.NotDownloaded -> SpeechAvailability.NeedsDownload()
      is AsrServiceStatus.Downloading -> SpeechAvailability.Downloading(0L, 0L)
      is AsrServiceStatus.Unavailable ->
        SpeechAvailability.Unavailable(SpeechUnavailabilityReason.DEVICE_NOT_SERVED)
      is AsrServiceStatus.Failed,
      is AsrServiceStatus.Unknown ->
        SpeechAvailability.ProbeFailed(
          IllegalStateException("ASR status check failed or is unknown")
        )
    }

  override suspend fun checkLanguageAvailability(
    modelId: String,
    languageCode: String,
  ): SpeechAvailability =
    when (checkLanguageStatus(languageCode)) {
      is AsrServiceStatus.Ready,
      is AsrServiceStatus.Downloaded,
      is AsrServiceStatus.Initializing,
      is AsrServiceStatus.Recognizing,
      is AsrServiceStatus.Processing -> SpeechAvailability.Available
      is AsrServiceStatus.NotDownloaded -> SpeechAvailability.NeedsDownload()
      is AsrServiceStatus.Downloading -> SpeechAvailability.Downloading(0L, 0L)
      is AsrServiceStatus.Unavailable ->
        SpeechAvailability.Unavailable(SpeechUnavailabilityReason.LOCALE_NOT_OFFERED)
      is AsrServiceStatus.Failed,
      is AsrServiceStatus.Unknown ->
        SpeechAvailability.ProbeFailed(
          IllegalStateException("ASR language status check failed or is unknown")
        )
    }

  /**
   * Initiates an asynchronous download of required ASR model assets, emitting progress updates.
   *
   * Non-downloadable engines emit nothing by default.
   */
  fun download(): Flow<AsrServiceStatus> = emptyFlow()

  /**
   * Initiates an asynchronous download of the language pack for [languageCode].
   *
   * Single-bundle engines delegate to [download] by default.
   */
  fun downloadLanguage(languageCode: String): Flow<AsrServiceStatus> = download()

  /**
   * Warms up and initializes the underlying speech recognizer or model in memory using [config].
   *
   * @throws AsrServiceError if initialization fails or the required model is not downloaded.
   */
  suspend fun prepare(config: AsrPreparationConfig = AsrPreparationConfig()) {}

  /**
   * Starts a live audio recognition session and returns a cold [Flow] of cumulative transcript
   * updates.
   *
   * Each emission in the returned [Flow] is the cumulative transcript recognized so far in the
   * current session. Normal completion of the [Flow] signals that the recording session has ended
   * and emitted its final transcript. Failures throw an [AsrServiceError] into the [Flow].
   *
   * @param options Per-session options such as interim-result visibility.
   */
  fun startRecording(options: AsrRecordingOptions = AsrRecordingOptions.DEFAULT): Flow<String>

  /**
   * Gracefully stops microphone capture and flushes any remaining buffered audio to produce a final
   * transcription result before completing the active [startRecording] flow.
   */
  suspend fun stopRecording()

  /** Immediately aborts the active recognition session and discards any pending results. */
  suspend fun cancelRecording()

  /**
   * Releases all native and system resources held by the ASR engine, canceling any active recording
   * session and resetting engine status (to [AsrServiceStatus.Ready] for reusable system/cloud
   * engines or [AsrServiceStatus.Unknown] until re-prepared).
   */
  fun release()

  companion object {
    private val DEFAULT_AMPLITUDE_FLOW: StateFlow<Float> = MutableStateFlow(0f).asStateFlow()
  }
}

/**
 * Collects live transcript emissions from [AsrService.startRecording] alongside concurrent
 * amplitude updates from [AsrService.currentAmplitude] within a structured [coroutineScope],
 * automatically canceling amplitude collection as soon as the recording stream terminates.
 */
suspend fun AsrService.collectRecordingWithAmplitude(
  options: AsrRecordingOptions = AsrRecordingOptions.DEFAULT,
  onAmplitudeChanged: (Float) -> Unit,
  onTranscriptChanged: (String) -> Unit,
) {
  coroutineScope {
    val amplitudeJob = launch { currentAmplitude.collect(onAmplitudeChanged) }
    try {
      startRecording(options).collect(onTranscriptChanged)
    } finally {
      amplitudeJob.cancel()
    }
  }
}
