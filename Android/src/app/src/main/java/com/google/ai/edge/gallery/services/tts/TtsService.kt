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

import com.google.ai.edge.gallery.data.supplementary.SpeechAvailability
import com.google.ai.edge.gallery.data.supplementary.SpeechAvailabilityProbe
import com.google.ai.edge.gallery.data.supplementary.SpeechUnavailabilityReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Common contract implemented by every text-to-speech backend vended by [TtsServiceProvider].
 *
 * ## Lifecycle
 *
 * The expected call order is `prepare` → `synthesize`* → `stop`/`cancel` → `destroy`:
 * 1. [prepare] acquires the engine's resources. It must complete successfully before [synthesize]
 *    is called. While it runs, [status] is [TtsServiceStatus.Initializing] (or
 *    [TtsServiceStatus.Downloading] when the backing model has to be fetched). On success [status]
 *    becomes [TtsServiceStatus.Ready]; on failure it becomes [TtsServiceStatus.Failed] and the
 *    exception is rethrown.
 * 2. [synthesize] may be called any number of times once the engine is ready. It returns
 *    immediately with a cold [TtsOutput]; when the returned flow is collected, [status] becomes
 *    [TtsServiceStatus.Synthesizing] and synthesis starts. [status] returns to
 *    [TtsServiceStatus.Ready] when the flow terminates, whether it completes, fails, or is
 *    cancelled.
 * 3. [stop] and [cancel] halt any in-flight utterance and return [status] to
 *    [TtsServiceStatus.Ready]. They are safe to call when nothing is playing. The two are currently
 *    synonyms in every implementation; [cancel] exists so callers can express intent.
 * 4. [destroy] releases all native resources. It is terminal: the instance must be re-[prepare]d
 *    before it can synthesize again.
 *
 * ## Changing models
 *
 * [TtsServiceProvider] resolves which backend serves a given [TtsConfig.modelId]. Callers must call
 * [prepare] on the resolved engine (or [TtsServiceProvider.prepareService]) before calling
 * [synthesize]; otherwise [synthesize] returns a [TtsOutput] that fails with
 * [TtsServiceError.EngineUnavailable]. The non-suspending [synthesize] signature deliberately does
 * not prepare implicitly, so that the (potentially slow, potentially downloading) preparation stays
 * under the caller's control.
 *
 * Implementations are expected to be safe to call from any thread.
 */
interface TtsService : SpeechAvailabilityProbe {
  /** Current lifecycle state of the engine. Hot; replays the latest value to new collectors. */
  val status: StateFlow<TtsServiceStatus>

  /**
   * Models this engine can serve right now.
   *
   * Some engines can only describe their models after [prepare] has run, so this may be empty
   * before preparation.
   */
  val availableModels: List<TtsModelMetadata>

  /**
   * Model identifiers (see [TtsModelIdentifier]) this engine serves, used by [TtsServiceProvider]
   * to route a [TtsConfig.modelId] to its engine.
   *
   * Must be static: it is read before [prepare] and must not require IPC.
   */
  val servedModelIds: Set<String>

  /**
   * Whether this engine can speak at the [TtsConfig.speed] callers ask for.
   *
   * Engines that return [TtsOutput.AudioStream] get this for free: the caller plays the PCM and
   * retimes it. Engines that return [TtsOutput.DirectPlayback] play the audio themselves, so they
   * support speed only if their own API takes a speaking rate. Must be static: it is read before
   * [prepare] to decide whether to offer a speed control.
   */
  val supportsSpeedControl: Boolean
    get() = true

  /**
   * Whether this engine loads model weights into this app's process (native memory or GPU
   * delegates) when prepared.
   *
   * Background memory trims ([TtsMemoryTrimCallbacks]) only release engines that do: releasing an
   * engine whose model runs in another process frees nothing here and only makes the next utterance
   * pay for a cold client. Must be static.
   */
  val holdsNativeWeights: Boolean
    get() = false

  /**
   * How long to wait before each retry, one retry per entry, when synthesizing an utterance failed
   * with [error] before any of its audio played. A retry synthesizes the same text again without
   * preparing. Callers count the retries of each utterance with [PreStartRetries].
   *
   * An empty list, the default, means that the engine has no retry policy for [error]; callers then
   * handle it as they handle any other failure.
   */
  fun preStartRetryDelaysMs(error: TtsServiceError): List<Long> = emptyList()

  /**
   * Whether this device can run the engine at all, as opposed to merely needing setup (for example
   * an app update or a download, which [checkModelAvailability] reports).
   *
   * Models whose engine reports `false` are hidden from the model list. Implementations must fail
   * open: return `true` when support cannot be determined.
   */
  suspend fun isSupportedOnDevice(): Boolean = true

  /**
   * Queries whether the model identified by [modelId] is downloaded, downloadable, or unavailable
   * on the current device.
   */
  suspend fun checkModelAvailability(modelId: String = ""): TtsModelAvailability =
    TtsModelAvailability.Downloaded

  override suspend fun checkAvailability(modelId: String): SpeechAvailability =
    when (checkModelAvailability(modelId)) {
      is TtsModelAvailability.Downloaded -> SpeechAvailability.Available
      is TtsModelAvailability.NotDownloaded -> SpeechAvailability.NeedsDownload()
      is TtsModelAvailability.Unavailable ->
        SpeechAvailability.Unavailable(SpeechUnavailabilityReason.DEVICE_NOT_SERVED)
    }

  /**
   * Queries whether the language pack for [languageCode] (a BCP-47 tag such as `"ja-JP"`) is
   * installed for the model identified by [modelId].
   *
   * Engines that ship every language with the model report the model's own availability.
   */
  override suspend fun checkLanguageAvailability(
    modelId: String,
    languageCode: String,
  ): SpeechAvailability = checkAvailability(modelId)

  /**
   * Downloads (and prepares) the model identified by [modelId], streaming byte-level progress
   * updates without synthetic progress floors.
   */
  fun downloadModel(
    modelId: String = "",
    expectedTotalBytes: Long = 0L,
  ): Flow<TtsDownloadProgress> =
    flowOf(
      TtsDownloadProgress(downloadedBytes = expectedTotalBytes, totalBytes = expectedTotalBytes)
    )

  /** Deletes any downloaded files or cached engine state for [modelId]. */
  suspend fun deleteModel(modelId: String = "") {}

  /**
   * Downloads the language pack for [languageCode] for the model identified by [modelId], streaming
   * byte-level progress updates.
   *
   * Engines that ship every language with the model download the model itself.
   */
  fun downloadLanguage(
    modelId: String,
    languageCode: String,
    expectedTotalBytes: Long = 0L,
  ): Flow<TtsDownloadProgress> = downloadModel(modelId, expectedTotalBytes)

  /**
   * Acquires the resources needed to synthesize with [config], downloading the backing model if
   * required. Returns once the engine is fully ready to synthesize with [config]; engines that need
   * a cold-start or per-voice warm-up perform it inside [prepare].
   *
   * @throws Exception if the engine cannot be initialized; [status] is left as
   *   [TtsServiceStatus.Failed].
   */
  suspend fun prepare(config: TtsConfig)

  /**
   * Acquires the resources needed to synthesize with [config], invoking [progressListener] with
   * download progress in `[0f, 1f]` when the engine supports progress callbacks during preparation.
   */
  suspend fun prepare(config: TtsConfig, progressListener: ((Float) -> Unit)?) {
    prepare(config)
  }

  /**
   * Starts an utterance for [text], overriding the prepared configuration with [config] where the
   * engine supports it.
   *
   * Returns a cold [TtsOutput]; nothing happens until its flow is collected, and cancelling the
   * collector cancels the utterance.
   */
  fun synthesize(text: String, config: TtsConfig? = null): TtsOutput

  /** Pauses speech playback for the active utterance. */
  fun pause()

  /** Resumes speech playback from the paused position. */
  fun resume()

  /** Halts the current utterance and discards any queued audio. */
  fun stop()

  /** Halts the current utterance. Equivalent to [stop] in all current implementations. */
  fun cancel()

  /**
   * Cancels any in-flight preparation or synthesis, awaits active background workers, and releases
   * all native resources. Terminal: call [prepare] again before reusing this instance.
   */
  suspend fun release() {
    stop()
    destroy()
  }

  /** Releases all resources. Terminal: call [prepare] again before reusing this instance. */
  fun destroy()
}
