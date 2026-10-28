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

import android.util.Log
import com.google.ai.edge.gallery.data.DeviceSupportChecker
import com.google.ai.edge.gallery.data.LanguagePackDownloader
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadFailureReason
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelDownloader
import com.google.ai.edge.gallery.data.ModelUnavailability
import com.google.ai.edge.gallery.data.ModelUnavailabilityReason
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.supplementary.SpeechAvailability
import com.google.ai.edge.gallery.data.supplementary.SpeechServicesVersion
import com.google.ai.edge.gallery.data.supplementary.SpeechUnavailabilityReason
import com.google.ai.edge.gallery.di.IoDispatcher
import dagger.Lazy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

private const val TAG = "AGTtsModelDownloader"

/**
 * [ModelDownloader] strategy for TTS supplementary models (`RuntimeType.MLKIT_SPEECH`), delegating
 * availability checks, byte-level downloads, and deletion to the concrete [TtsService] resolved by
 * [TtsServiceProvider] via [Model.ttsProfile] and synchronizing user selection state in
 * [TtsSettingsRepository]. Also serves per-language pack status and downloads for runtimes that
 * install languages separately.
 */
@Singleton
class TtsModelDownloader
internal constructor(
  private val ttsServiceProviderLazy: Lazy<TtsServiceProvider>,
  private val ttsSettingsRepository: TtsSettingsRepository,
  private val downloaderScope: CoroutineScope,
) : ModelDownloader, LanguagePackDownloader, DeviceSupportChecker {

  /**
   * [TtsServiceProvider] is injected lazily so that registering this downloader does not
   * instantiate the TTS engines until a TTS model is actually queried, downloaded, or deleted.
   */
  @Inject
  constructor(
    ttsServiceProviderLazy: Lazy<TtsServiceProvider>,
    ttsSettingsRepository: TtsSettingsRepository,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
  ) : this(
    ttsServiceProviderLazy = ttsServiceProviderLazy,
    ttsSettingsRepository = ttsSettingsRepository,
    downloaderScope = CoroutineScope(SupervisorJob() + ioDispatcher),
  )

  private val ttsServiceProvider: TtsServiceProvider
    get() = ttsServiceProviderLazy.get()

  private val activeDownloadJobs = ConcurrentHashMap<String, Job>()

  override suspend fun queryStatus(model: Model): ModelDownloadStatus {
    val engineId = model.ttsProfile?.engineModelId
    if (engineId.isNullOrEmpty()) {
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.UNAVAILABLE,
        unavailability = ModelUnavailability(reason = ModelUnavailabilityReason.UNSUPPORTED_MODEL),
      )
    }

    val availability =
      try {
        ttsServiceProvider.service(forModelId = engineId).checkModelAvailability(engineId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to check availability of TTS model '${model.name}'", e)
        return ModelDownloadStatus(
          status = ModelDownloadStatusType.FAILED,
          errorMessage = e.localizedMessage.orEmpty(),
        )
      }

    return when (availability) {
      is TtsModelAvailability.Downloaded -> {
        ttsSettingsRepository.ensureValidVoiceForDownloadedModel(model)
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = model.downloadInfo.totalBytes,
          totalBytes = model.downloadInfo.totalBytes,
        )
      }
      is TtsModelAvailability.NotDownloaded ->
        ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
      is TtsModelAvailability.Unavailable ->
        ModelDownloadStatus(
          status = ModelDownloadStatusType.UNAVAILABLE,
          unavailability =
            ModelUnavailability(
              reason = availability.reason.toModelUnavailabilityReason(),
              guideUrl = availability.guideUrl ?: model.learnMoreUrl.ifEmpty { null },
            ),
        )
    }
  }

  override suspend fun isSupportedOnDevice(model: Model): Boolean {
    val engineId = model.ttsProfile?.engineModelId
    if (engineId.isNullOrEmpty()) {
      return true
    }
    return ttsServiceProvider.service(forModelId = engineId).isSupportedOnDevice()
  }

  override suspend fun queryLanguagePackStatus(
    model: Model,
    languageCode: String,
  ): ModelDownloadStatus {
    val engineId = model.ttsProfile?.engineModelId
    if (engineId.isNullOrEmpty()) {
      return ModelDownloadStatus(
        status = ModelDownloadStatusType.UNAVAILABLE,
        unavailability = ModelUnavailability(reason = ModelUnavailabilityReason.UNSUPPORTED_MODEL),
      )
    }
    val availability =
      try {
        ttsServiceProvider
          .service(forModelId = engineId)
          .checkLanguageAvailability(engineId, languageCode)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to check '$languageCode' availability of TTS model '${model.name}'", e)
        return ModelDownloadStatus(
          status = ModelDownloadStatusType.FAILED,
          errorMessage = e.localizedMessage.orEmpty(),
        )
      }
    return when (availability) {
      is SpeechAvailability.Available ->
        ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
      is SpeechAvailability.NeedsDownload ->
        ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
      is SpeechAvailability.Downloading ->
        if (activeDownloadJobs[languagePackJobKey(engineId, languageCode)]?.isActive == true) {
          ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS)
        } else {
          // Speech Services lists the voice as downloading, but no download in the app is receiving
          // its progress, so the user may need to request it again.
          ModelDownloadStatus(
            status = ModelDownloadStatusType.FAILED,
            receivedBytes = availability.receivedBytes,
            totalBytes = availability.totalBytes,
            failureReason = ModelDownloadFailureReason.STALLED,
          )
        }
      is SpeechAvailability.Unavailable ->
        ModelDownloadStatus(
          status = ModelDownloadStatusType.UNAVAILABLE,
          unavailability =
            ModelUnavailability(
              reason =
                if (availability.reason == SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED) {
                  ModelUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING
                } else {
                  ModelUnavailabilityReason.UNSUPPORTED_MODEL
                },
              guideUrl =
                if (availability.reason == SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED) {
                  SpeechServicesVersion.PLAY_STORE_URL
                } else {
                  model.learnMoreUrl.ifEmpty { null }
                },
            ),
        )
      is SpeechAvailability.ProbeFailed ->
        ModelDownloadStatus(
          status = ModelDownloadStatusType.FAILED,
          errorMessage = availability.cause.localizedMessage.orEmpty(),
        )
    }
  }

  override fun downloadLanguagePack(
    model: Model,
    languageCode: String,
    onStatusUpdated: (ModelDownloadStatus) -> Unit,
  ) {
    val engineId = model.ttsProfile?.engineModelId
    if (engineId.isNullOrEmpty()) {
      Log.e(
        TAG,
        "Cannot download '$languageCode' for TTS model '${model.name}' without an engine ID",
      )
      onStatusUpdated(ModelDownloadStatus(status = ModelDownloadStatusType.FAILED))
      return
    }
    val jobKey = languagePackJobKey(engineId, languageCode)
    if (activeDownloadJobs[jobKey]?.isActive == true) {
      return
    }
    // Start lazily so the job is registered before it can run (and remove itself) on another
    // thread.
    val job =
      downloaderScope.launch(start = CoroutineStart.LAZY) {
        val currentJob = coroutineContext[Job]
        var lastProgress: TtsDownloadProgress? = null
        try {
          onStatusUpdated(ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS))
          ttsServiceProvider
            .service(forModelId = engineId)
            .downloadLanguage(engineId, languageCode)
            .collect { progress ->
              lastProgress = progress
              onStatusUpdated(
                ModelDownloadStatus(
                  status = ModelDownloadStatusType.IN_PROGRESS,
                  receivedBytes = progress.downloadedBytes,
                  totalBytes = progress.totalBytes,
                )
              )
            }
          onStatusUpdated(ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED))
        } catch (e: CancellationException) {
          onStatusUpdated(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED))
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to download '$languageCode' for TTS model '${model.name}'", e)
          // Unregister first so that a status query made after this update doesn't see this job as
          // still running.
          if (currentJob != null) {
            activeDownloadJobs.remove(jobKey, currentJob)
          }
          onStatusUpdated(
            ModelDownloadStatus(
              status = ModelDownloadStatusType.FAILED,
              receivedBytes = lastProgress?.downloadedBytes ?: 0L,
              totalBytes = lastProgress?.totalBytes ?: 0L,
              errorMessage = e.localizedMessage.orEmpty(),
              failureReason = e.toDownloadFailureReason(),
            )
          )
        } finally {
          if (currentJob != null) {
            activeDownloadJobs.remove(jobKey, currentJob)
          }
        }
      }
    activeDownloadJobs[jobKey] = job
    job.start()
  }

  override fun download(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean,
    onStatusUpdated: (Model, ModelDownloadStatus) -> Unit,
  ) {
    val engineId = model.ttsProfile?.engineModelId
    if (engineId.isNullOrEmpty()) {
      Log.e(TAG, "Cannot download TTS model '${model.name}' without an engine model ID")
      onStatusUpdated(model, ModelDownloadStatus(status = ModelDownloadStatusType.FAILED))
      return
    }

    activeDownloadJobs.remove(engineId)?.cancel()

    val expectedTotalBytes = model.downloadInfo.totalBytes
    // Start lazily so the job is registered before it can run (and remove itself) on another
    // thread.
    val job =
      downloaderScope.launch(start = CoroutineStart.LAZY) {
        val currentJob = coroutineContext[Job]
        try {
          onStatusUpdated(
            model,
            ModelDownloadStatus(
              status = ModelDownloadStatusType.IN_PROGRESS,
              receivedBytes = 0L,
              totalBytes = expectedTotalBytes,
            ),
          )
          var lastResolvedTotal = expectedTotalBytes
          var lastDownloadedBytes = 0L
          ttsServiceProvider
            .service(forModelId = engineId)
            .downloadModel(engineId, expectedTotalBytes)
            .collect { progress ->
              val resolvedTotal =
                if (progress.totalBytes > 0L) progress.totalBytes else expectedTotalBytes
              lastResolvedTotal = resolvedTotal
              lastDownloadedBytes = progress.downloadedBytes
              onStatusUpdated(
                model,
                ModelDownloadStatus(
                  status = ModelDownloadStatusType.IN_PROGRESS,
                  receivedBytes = progress.downloadedBytes,
                  totalBytes = resolvedTotal,
                ),
              )
            }
          val finalTotal = if (lastResolvedTotal > 0L) lastResolvedTotal else lastDownloadedBytes
          ttsSettingsRepository.onModelDownloaded(model)
          onStatusUpdated(
            model,
            ModelDownloadStatus(
              status = ModelDownloadStatusType.SUCCEEDED,
              receivedBytes = finalTotal,
              totalBytes = finalTotal,
            ),
          )
        } catch (e: CancellationException) {
          // Skip the update when a newer download for the same engine has replaced this job, so a
          // stale NOT_DOWNLOADED doesn't overwrite the replacement's progress.
          val activeJob = activeDownloadJobs[engineId]
          if (activeJob == null || activeJob == currentJob) {
            onStatusUpdated(
              model,
              ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
            )
          }
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to download TTS model '${model.name}'", e)
          onStatusUpdated(
            model,
            ModelDownloadStatus(
              status = ModelDownloadStatusType.FAILED,
              errorMessage = e.localizedMessage.orEmpty(),
              failureReason = e.toDownloadFailureReason(),
            ),
          )
        } finally {
          if (currentJob != null) {
            activeDownloadJobs.remove(engineId, currentJob)
          }
        }
      }
    activeDownloadJobs[engineId] = job
    job.start()
  }

  override fun cancel(model: Model) {
    val engineId = model.ttsProfile?.engineModelId ?: return
    activeDownloadJobs.remove(engineId)?.cancel()
  }

  override suspend fun delete(model: Model) {
    val engineId = model.ttsProfile?.engineModelId ?: return
    // Wait for any in-flight download to finish cancelling before deleting its files.
    activeDownloadJobs.remove(engineId)?.cancelAndJoin()
    ttsServiceProvider.service(forModelId = engineId).deleteModel(engineId)
    ttsSettingsRepository.onModelDeleted(model)
  }

  private fun TtsUnavailabilityReason.toModelUnavailabilityReason(): ModelUnavailabilityReason =
    when (this) {
      TtsUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING ->
        ModelUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING
      TtsUnavailabilityReason.AICORE_UNAVAILABLE -> ModelUnavailabilityReason.AICORE_UNAVAILABLE
      TtsUnavailabilityReason.UNSUPPORTED_MODEL -> ModelUnavailabilityReason.UNSUPPORTED_MODEL
    }

  /** Key of the [activeDownloadJobs] entry of a language pack download. */
  private fun languagePackJobKey(engineId: String, languageCode: String) = "$engineId/$languageCode"

  /** Returns the reason the UI shows for a download that failed with this exception, if any. */
  private fun Exception.toDownloadFailureReason(): ModelDownloadFailureReason? =
    when (this) {
      is TtsServiceError.DownloadStalled -> ModelDownloadFailureReason.STALLED
      else -> null
    }
}

/**
 * [ModelDownloader] for `RuntimeType.MLKIT_SPEECH` models. Delegates to [TtsModelDownloader] but
 * reports that downloads cannot be cancelled: Speech Services runs the voice download itself and
 * keeps it going after the app stops collecting progress.
 */
class MlKitTtsModelDownloader @Inject constructor(delegate: TtsModelDownloader) :
  ModelDownloader by delegate,
  LanguagePackDownloader by delegate,
  DeviceSupportChecker by delegate {
  override val supportsCancel: Boolean
    get() = false
}
