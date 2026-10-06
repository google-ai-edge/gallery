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

package com.google.ai.edge.gallery.runtime.aicore

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.ModelDownloader
import com.google.ai.edge.gallery.data.ModelUnavailability
import com.google.ai.edge.gallery.data.ModelUnavailabilityReason
import com.google.ai.edge.gallery.data.Task
import com.google.mlkit.genai.common.FeatureStatus
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [ModelDownloader] implementation for system-managed AICore models (`RuntimeType.AICORE`),
 * delegating status checks and downloads to [AICoreModelHelper].
 */
class AICoreModelDownloader(
  private val context: Context,
  private val coroutineScope: CoroutineScope? = null,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelDownloader {

  // checkStatus() and close() make Binder calls to the AICore service, so keep them off the
  // caller's (typically main) dispatcher.
  override suspend fun queryStatus(model: Model): ModelDownloadStatus =
    withContext(ioDispatcher) { queryAICoreStatus(model) }

  private suspend fun queryAICoreStatus(model: Model): ModelDownloadStatus {
    val generativeModel = AICoreModelHelper.generativeModelProvider(model)
    return try {
      when (generativeModel.checkStatus()) {
        FeatureStatus.AVAILABLE ->
          ModelDownloadStatus(
            status = ModelDownloadStatusType.SUCCEEDED,
            receivedBytes = model.downloadInfo.sizeInBytes,
            totalBytes = model.downloadInfo.sizeInBytes,
          )
        FeatureStatus.DOWNLOADABLE,
        FeatureStatus.DOWNLOADING ->
          ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)
        else ->
          ModelDownloadStatus(
            status = ModelDownloadStatusType.UNAVAILABLE,
            unavailability =
              ModelUnavailability(
                reason = ModelUnavailabilityReason.AICORE_UNAVAILABLE,
                guideUrl = AICORE_ACCESS_GUIDE_URL,
              ),
          )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Throwable) {
      ModelDownloadStatus(
        status = ModelDownloadStatusType.UNAVAILABLE,
        unavailability =
          ModelUnavailability(
            reason = ModelUnavailabilityReason.AICORE_UNAVAILABLE,
            guideUrl = AICORE_ACCESS_GUIDE_URL,
          ),
      )
    } finally {
      generativeModel.close()
    }
  }

  override fun download(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean,
    onStatusUpdated: (Model, ModelDownloadStatus) -> Unit,
  ) {
    AICoreModelHelper.downloadModel(
      context = context,
      coroutineScope = coroutineScope,
      model = model,
      onProgress = { downloaded: Long, total: Long ->
        onStatusUpdated(
          model,
          ModelDownloadStatus(
            status = ModelDownloadStatusType.IN_PROGRESS,
            receivedBytes = downloaded,
            totalBytes = total,
          ),
        )
      },
      onDone = {
        onStatusUpdated(
          model,
          ModelDownloadStatus(
            status = ModelDownloadStatusType.SUCCEEDED,
            receivedBytes = model.downloadInfo.sizeInBytes,
            totalBytes = model.downloadInfo.sizeInBytes,
          ),
        )
      },
      onError = { error: String ->
        onStatusUpdated(
          model,
          ModelDownloadStatus(status = ModelDownloadStatusType.FAILED, errorMessage = error),
        )
      },
    )
  }

  // AICore downloads are managed by the system service and cannot be cancelled from the app.
  override val supportsCancel: Boolean
    get() = false

  override fun cancel(model: Model) {}

  override suspend fun delete(model: Model) {
    // AICore models are managed by the system service and cannot be deleted from the app.
  }

  companion object {
    const val AICORE_ACCESS_GUIDE_URL =
      "https://developers.google.com/ml-kit/genai/aicore-dev-preview"
  }
}
