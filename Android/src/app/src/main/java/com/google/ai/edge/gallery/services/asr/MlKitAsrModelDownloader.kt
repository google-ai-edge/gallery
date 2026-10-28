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

import android.util.Log
import com.google.ai.edge.gallery.data.LanguagePackDownloader
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelDownloader
import com.google.ai.edge.gallery.data.ModelUnavailability
import com.google.ai.edge.gallery.data.ModelUnavailabilityReason
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.supplementary.AiCoreVersion
import com.google.ai.edge.gallery.data.supplementary.SpeechServicesVersion
import dagger.Lazy
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AGMlKitAsrModelDownloader"

/**
 * [ModelDownloader] and [LanguagePackDownloader] strategy for ML Kit ASR supplementary models
 * (`RuntimeType.MLKIT_ASR`), delegating availability checks, model downloads, and language pack
 * downloads to the [AsrService] that serves the model's [AsrModelIdentifier].
 *
 * Speech Services and AICore own the downloaded ML Kit assets, so downloads cannot be cancelled and
 * models cannot be deleted from the app.
 */
@Singleton
class MlKitAsrModelDownloader(
  private val asrServiceProviderLazy: Lazy<AsrServiceProvider>,
  ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelDownloader, LanguagePackDownloader {

  @Inject
  constructor(
    asrServiceProviderLazy: Lazy<AsrServiceProvider>
  ) : this(asrServiceProviderLazy = asrServiceProviderLazy, ioDispatcher = Dispatchers.IO)

  private val downloaderScope = CoroutineScope(SupervisorJob() + ioDispatcher)

  private val asrServiceProvider: AsrServiceProvider
    get() = asrServiceProviderLazy.get()

  private val activeDownloadJobs = ConcurrentHashMap<String, Job>()

  override val supportsCancel: Boolean
    get() = false

  /** Returns the engine type serving [model], or `null` if its model ID is not an ASR engine. */
  private fun engineTypeFor(model: Model): AsrEngineType? {
    val engineType = AsrModelRegistry.engineTypeForModelId(model.modelId)
    if (engineType == null) {
      Log.w(TAG, "Model '${model.name}' has unknown ASR model ID '${model.modelId}'")
    }
    return engineType
  }

  /**
   * `true` for [AsrEngineType.MLKIT_BASIC], which installs a pack per language, and `false` for
   * [AsrEngineType.MLKIT_ADVANCED], whose AICore model bundles every supported language. An unknown
   * model ID also returns `false`, since it has no language packs to install.
   */
  override fun installsLanguagesSeparately(model: Model): Boolean =
    AsrModelRegistry.installsLanguagePacks(model.modelId)

  override suspend fun queryStatus(model: Model): ModelDownloadStatus {
    val engineType =
      engineTypeFor(model)
        ?: return ModelDownloadStatus(
          status = ModelDownloadStatusType.UNAVAILABLE,
          unavailability = ModelUnavailability(reason = ModelUnavailabilityReason.UNSUPPORTED_MODEL),
        )
    val asrService = asrServiceProvider.service(engineType)
    return when (val asrStatus = asrService.checkStatus()) {
      is AsrServiceStatus.Ready,
      is AsrServiceStatus.Downloaded ->
        ModelDownloadStatus(
          status = ModelDownloadStatusType.SUCCEEDED,
          receivedBytes = model.downloadInfo.totalBytes,
          totalBytes = model.downloadInfo.totalBytes,
        )
      is AsrServiceStatus.Downloading ->
        ModelDownloadStatus(
          status = ModelDownloadStatusType.IN_PROGRESS,
          receivedBytes = (model.downloadInfo.totalBytes * asrStatus.progress).toLong(),
          totalBytes = model.downloadInfo.totalBytes,
        )
      is AsrServiceStatus.Unavailable -> {
        val unavailability =
          when (asrService.unavailabilityReason) {
            AsrUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING ->
              ModelUnavailability(
                reason = ModelUnavailabilityReason.SPEECH_SERVICES_OUTDATED_OR_MISSING,
                guideUrl = SpeechServicesVersion.PLAY_STORE_URL,
              )
            AsrUnavailabilityReason.AICORE_OUTDATED_OR_MISSING ->
              ModelUnavailability(
                reason = ModelUnavailabilityReason.AICORE_OUTDATED_OR_MISSING,
                guideUrl = AiCoreVersion.PLAY_STORE_URL,
              )
            AsrUnavailabilityReason.AICORE_UNAVAILABLE ->
              ModelUnavailability(
                reason = ModelUnavailabilityReason.AICORE_UNAVAILABLE,
                guideUrl = MlKitAsrService.AICORE_DEV_PREVIEW_GUIDE_URL,
              )
            AsrUnavailabilityReason.UNSUPPORTED_DEVICE,
            null -> ModelUnavailability(reason = ModelUnavailabilityReason.UNSUPPORTED_MODEL)
          }
        ModelDownloadStatus(
          status = ModelDownloadStatusType.UNAVAILABLE,
          unavailability = unavailability,
        )
      }
      else -> ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
    }
  }

  override suspend fun queryLanguagePackStatus(
    model: Model,
    languageCode: String,
  ): ModelDownloadStatus {
    val engineType =
      engineTypeFor(model)
        ?: return ModelDownloadStatus(status = ModelDownloadStatusType.UNAVAILABLE)
    val service = asrServiceProvider.service(engineType)
    return try {
      when (val asrStatus = service.checkLanguageStatus(languageCode)) {
        is AsrServiceStatus.Ready,
        is AsrServiceStatus.Downloaded ->
          ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
        is AsrServiceStatus.NotDownloaded ->
          ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
        is AsrServiceStatus.Downloading ->
          ModelDownloadStatus(
            status = ModelDownloadStatusType.IN_PROGRESS,
            receivedBytes = asrStatus.bytesDownloaded,
            totalBytes = asrStatus.totalBytes,
          )
        is AsrServiceStatus.Unavailable ->
          ModelDownloadStatus(status = ModelDownloadStatusType.UNAVAILABLE)
        else -> ModelDownloadStatus(status = ModelDownloadStatusType.FAILED)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Failed to query ASR language pack '$languageCode' for '${model.name}'", e)
      ModelDownloadStatus(
        status = ModelDownloadStatusType.FAILED,
        errorMessage = e.localizedMessage.orEmpty(),
      )
    }
  }

  override fun downloadLanguagePack(
    model: Model,
    languageCode: String,
    onStatusUpdated: (ModelDownloadStatus) -> Unit,
  ) {
    val engineType = engineTypeFor(model)
    if (engineType == null) {
      onStatusUpdated(ModelDownloadStatus(status = ModelDownloadStatusType.FAILED))
      return
    }
    val jobKey = "${model.modelId}/$languageCode"
    if (activeDownloadJobs[jobKey]?.isActive == true) {
      return
    }
    val service = asrServiceProvider.service(engineType)
    val job =
      downloaderScope.launch(start = CoroutineStart.LAZY) {
        val currentJob = coroutineContext[Job]
        try {
          onStatusUpdated(ModelDownloadStatus(status = ModelDownloadStatusType.IN_PROGRESS))
          service.downloadLanguage(languageCode).collect { asrStatus ->
            asrStatus.toDownloadStatus()?.let { status ->
              onStatusUpdated(status)
            }
          }
        } catch (e: CancellationException) {
          onStatusUpdated(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED))
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to download ASR language pack '$languageCode' for '${model.name}'", e)
          onStatusUpdated(
            ModelDownloadStatus(
              status = ModelDownloadStatusType.FAILED,
              errorMessage = e.localizedMessage.orEmpty(),
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
    val engineType = engineTypeFor(model)
    if (engineType == null) {
      onStatusUpdated(model, ModelDownloadStatus(status = ModelDownloadStatusType.FAILED))
      return
    }
    val jobKey = model.modelId
    if (activeDownloadJobs[jobKey]?.isActive == true) {
      // The engine keeps downloading once started, so let the running job keep reporting.
      return
    }
    val service = asrServiceProvider.service(engineType)
    val job =
      downloaderScope.launch(start = CoroutineStart.LAZY) {
        val currentJob = coroutineContext[Job]
        try {
          onStatusUpdated(
            model,
            ModelDownloadStatus(
              status = ModelDownloadStatusType.IN_PROGRESS,
              receivedBytes = 0L,
              totalBytes = model.downloadInfo.totalBytes,
            ),
          )
          service.download().collect { asrStatus ->
            asrStatus.toDownloadStatus()?.let { status ->
              onStatusUpdated(model, status)
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.e(TAG, "Failed to download ASR model '${model.name}'", e)
          onStatusUpdated(
            model,
            ModelDownloadStatus(
              status = ModelDownloadStatusType.FAILED,
              errorMessage = e.localizedMessage.orEmpty(),
            ),
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

  /** Unsupported: Speech Services and AICore keep downloading once started ([supportsCancel]). */
  override fun cancel(model: Model) {
    throw UnsupportedOperationException("ML Kit ASR downloads cannot be cancelled")
  }

  /**
   * Unsupported: Speech Services and AICore own the ML Kit ASR assets. The UI hides Delete for
   * these models because `RuntimeType.MLKIT_ASR.supportsDelete` is `false`.
   */
  override suspend fun delete(model: Model) {
    throw UnsupportedOperationException("ML Kit ASR models cannot be deleted from the app")
  }

  /** Maps an engine download status to a [ModelDownloadStatus], or `null` if it reports nothing. */
  private fun AsrServiceStatus.toDownloadStatus(): ModelDownloadStatus? =
    when (this) {
      is AsrServiceStatus.Downloading ->
        ModelDownloadStatus(
          status = ModelDownloadStatusType.IN_PROGRESS,
          receivedBytes = bytesDownloaded,
          totalBytes = totalBytes,
        )
      is AsrServiceStatus.Downloaded,
      is AsrServiceStatus.Ready -> ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
      is AsrServiceStatus.Failed,
      is AsrServiceStatus.Unavailable ->
        ModelDownloadStatus(status = ModelDownloadStatusType.FAILED)
      else -> null
    }
}
