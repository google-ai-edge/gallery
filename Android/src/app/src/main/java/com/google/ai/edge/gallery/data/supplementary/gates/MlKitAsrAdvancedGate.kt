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

package com.google.ai.edge.gallery.data.supplementary.gates

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.supplementary.AiCoreVersion
import com.google.ai.edge.gallery.data.supplementary.DownloadProgress
import com.google.ai.edge.gallery.data.supplementary.HiddenReason
import com.google.ai.edge.gallery.data.supplementary.SpeechAvailability
import com.google.ai.edge.gallery.data.supplementary.SpeechModelGate
import com.google.ai.edge.gallery.data.supplementary.SpeechModelState
import com.google.ai.edge.gallery.data.supplementary.SpeechServicesVersion
import com.google.ai.edge.gallery.di.IoDispatcher
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrModelRegistry
import com.google.ai.edge.gallery.services.asr.AsrServiceProvider
import com.google.ai.edge.gallery.services.asr.MlKitAsrService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "AGMlKitAsrAdvancedGate"

/**
 * Gate evaluator for ML Kit GenAI Speech Recognition in Advanced mode.
 *
 * Order: Device (mlkit_asr_advanced set) -> Version (Speech Services + AICore) -> Opt-in (none) ->
 * Status.
 */
@Singleton
class MlKitAsrAdvancedGate
@VisibleForTesting
internal constructor(
  @ApplicationContext private val context: Context,
  private val asrServiceProvider: AsrServiceProvider,
  @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
  private val isSpeechServicesUpToDate: (Context) -> Boolean = {
    SpeechServicesVersion.isUpToDate(it)
  },
  private val isAiCoreUpToDate: (Context) -> Boolean = { AiCoreVersion.isUpToDate(it) },
) : SpeechModelGate {

  @Inject
  constructor(
    @ApplicationContext context: Context,
    asrServiceProvider: AsrServiceProvider,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
  ) : this(
    context = context,
    asrServiceProvider = asrServiceProvider,
    ioDispatcher = ioDispatcher,
    isSpeechServicesUpToDate = { SpeechServicesVersion.isUpToDate(it) },
    isAiCoreUpToDate = { AiCoreVersion.isUpToDate(it) },
  )

  override suspend fun checkGate(model: Model): SpeechModelState =
    withContext(ioDispatcher) {
      // Tier 1: Device: mlkit_asr_advanced set (validated upfront by allowlist loader).
      // Tier 2: Speech Services (`com.google.android.tts` >= 210665810) and AICore
      // (`com.google.android.aicore` >= 193575) version checks.
      if (!isSpeechServicesUpToDate(context)) {
        Log.i(
          TAG,
          "Speech Services is missing or older than ${SpeechServicesVersion.MIN_VERSION_CODE}",
        )
        return@withContext speechServicesNeedsUpdateState()
      }
      if (!isAiCoreUpToDate(context)) {
        Log.i(TAG, "AICore is missing or older than ${AiCoreVersion.MIN_VERSION_CODE}")
        return@withContext aiCoreNeedsUpdateState()
      }

      val service = asrServiceProvider.serviceOrNull(AsrEngineType.MLKIT_ADVANCED)
      if (service == null) {
        Log.w(TAG, "ML Kit ASR Advanced engine is not available in this build")
        return@withContext SpeechModelState.Hidden(HiddenReason.NO_ENGINE_IN_BUILD)
      }

      // Tier 3: Opt-in: none.
      // Tier 4: Status check with silent retries.
      val availability = retryProbe { service.checkAvailability(model.name) }

      when (availability) {
        is SpeechAvailability.Available -> SpeechModelState.Ready()
        is SpeechAvailability.NeedsDownload ->
          SpeechModelState.Downloadable(
            availability.totalBytes ?: AsrModelRegistry.MLKIT_ADVANCED_MODEL_SIZE_BYTES
          )
        is SpeechAvailability.Downloading ->
          SpeechModelState.Downloading(
            progress =
              DownloadProgress(
                bytesDownloaded = availability.receivedBytes,
                totalBytes = availability.totalBytes,
              ),
            cancellable = false,
            continuesInBackground = true,
          )
        is SpeechAvailability.Unavailable ->
          mapUnavailableToModelState(availability.reason, optInGuideUrl = OPT_IN_GUIDE_URL)
        is SpeechAvailability.ProbeFailed -> {
          Log.w(TAG, "ML Kit ASR Advanced probe failed: ${availability.cause.message}")
          SpeechModelState.CheckFailed("Couldn't check status")
        }
      }
    }

  companion object {
    const val OPT_IN_GUIDE_URL = MlKitAsrService.AICORE_DEV_PREVIEW_GUIDE_URL
  }
}
