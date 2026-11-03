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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DownloadStorageManager
import com.google.ai.edge.gallery.data.DownloadStorageManager.Companion.isZipFileName
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadFailureReason
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.NetworkStateProvider
import com.google.ai.edge.gallery.data.PendingDownload
import com.google.ai.edge.gallery.data.RepositoryDownloadSize
import com.google.ai.edge.gallery.data.Task
import java.util.UUID

private const val TAG = "AGModelDownloadPlanner"

/** How [ModelManagerViewModel.downloadModel] downloads a model's files. */
internal class ModelDownloadPlan(
  /** Whether the download includes the model's extra data files. */
  val includeExtraDataFiles: Boolean,
  /** Whether the download resumes from the partial files on disk. */
  val resumesPartialDownload: Boolean,
  val downloadSize: RepositoryDownloadSize,
) {
  /**
   * Returns the free storage the download needs, before it deletes the files it can't resume: the
   * bytes it still has to fetch, plus the room to unzip its zip files.
   */
  fun initialRequiredBytes(): Long = remainingBytes() + downloadSize.unzipBytes

  /** Returns the bytes the download still has to fetch. */
  fun remainingBytes(): Long =
    if (resumesPartialDownload) downloadSize.remainingBytes() else downloadSize.totalBytes
}

/**
 * How [ModelManagerViewModel.downloadExtraDataFiles] downloads the extra data files of a model
 * family.
 */
internal class ExtraDataDownloadPlan(
  /** The models of the family, which all get the download's status. */
  val family: List<Model>,
  /** The model whose dir the extra data files are downloaded to. */
  val targetModel: Model,
  /** The total size of the extra data files. */
  val totalBytes: Long,
  /** Returns how many bytes of the extra data files are still to download. */
  val remainingBytes: () -> Long,
  /** Returns how much free storage the download still needs, unzipping included. */
  val requiredBytes: () -> Long,
) {
  val familyNames: Set<String> = family.map { it.name }.toSet()
}

/**
 * How [ModelManagerViewModel.downloadModel] downloads several models together, as sized for a
 * storage or mobile-data check.
 */
internal data class BatchDownloadPlan(
  /** The names of the models. */
  val modelNames: Set<String>,
  /** The bytes the downloads still have to fetch. */
  val remainingBytes: Long,
  /** The free storage the downloads need, before they delete the files they can't resume. */
  val requiredBytes: Long,
)

/** A WorkManager download matched to its current allowlist or supplementary [model] and [task]. */
internal data class ObservedPendingDownload(
  val pendingDownload: PendingDownload,
  val task: Task?,
  val model: Model,
)

/** A partially downloaded model that should be automatically resumed at startup. */
internal data class AutoResumablePartialModel(
  val task: Task?,
  val model: Model,
  val includeExtraDataFiles: Boolean,
)

/** The pending downloads to attach to and the stale downloads to cancel at startup. */
internal data class PendingDownloadsPlan(
  val toObserve: List<ObservedPendingDownload>,
  val staleToCancel: List<PendingDownload>,
)

/**
 * Plans single-model, extra-data, and batch model downloads, computes in-flight storage
 * reservations, storage shortfalls, and mobile-data download sizes, and filters pending and
 * auto-resumable downloads at startup.
 */
internal class ModelDownloadPlanner(
  private val context: Context,
  private val storageManager: DownloadStorageManager,
  private val networkStateProvider: NetworkStateProvider,
  private val uiStateProvider: () -> ModelManagerUiState,
  private val availableStorageBytesProvider: () -> Long? =
    storageManager::defaultAvailableStorageBytes,
) {
  /**
   * Returns all models belonging to [model]'s hierarchy family across [model], [modelVariants], and
   * the tasks in [ModelManagerUiState.tasks].
   */
  fun getModelFamily(model: Model, modelVariants: List<Model> = emptyList()): List<Model> {
    val rootName = model.hierarchy.parentModelName ?: model.name
    val taskModels =
      uiStateProvider()
        .tasks
        .flatMap { it.models }
        .distinct()
        .sortedBy { it.displayName.ifEmpty { it.name } }
    val allModels = (listOf(model) + modelVariants + taskModels).distinctBy { it.name }
    val family = allModels.filter {
      it.name == rootName || it.hierarchy.parentModelName == rootName
    }
    return family.ifEmpty { listOf(model) }
  }

  /** Whether an extra data download is currently running or unzipping for [model]'s family. */
  fun isExtraDataDownloading(model: Model, modelVariants: List<Model> = emptyList()): Boolean {
    val state = uiStateProvider()
    return getModelFamily(model, modelVariants).any {
      val extraDataStatus = state.extraDataDownloadStatus[it.name]?.status
      extraDataStatus == ModelDownloadStatusType.IN_PROGRESS ||
        extraDataStatus == ModelDownloadStatusType.UNZIPPING
    }
  }

  /**
   * Whether cancelling [model]'s download should keep its extra data files because an extra data
   * download of its family is running or already succeeded.
   */
  fun keepsExtraDataFilesOnCancel(model: Model): Boolean {
    val state = uiStateProvider()
    val extraDataStatuses =
      getModelFamily(model).map { state.extraDataDownloadStatus[it.name]?.status }
    return ModelDownloadStatusType.IN_PROGRESS in extraDataStatuses ||
      ModelDownloadStatusType.UNZIPPING in extraDataStatuses ||
      ModelDownloadStatusType.SUCCEEDED in extraDataStatuses
  }

  /**
   * Returns how [ModelManagerViewModel.downloadModel] downloads [model] for [task], with its extra
   * data files if [includeExtraDataFiles] and they can be.
   */
  fun modelDownloadPlan(
    task: Task?,
    model: Model,
    includeExtraDataFiles: Boolean =
      uiStateProvider().isDownloadOptionalComponentsEnabled(model.name),
  ): ModelDownloadPlan {
    val state = uiStateProvider()
    // Keep the partial files of an interrupted download of this model so that it resumes rather
    // than starting over.
    val family = getModelFamily(model)
    // An extra data download that is still running writes the extra data files itself, so this
    // download must neither include nor resume them.
    val canResumeExtraDataFiles = includeExtraDataFiles && !isExtraDataDownloading(model)
    val resumesPartialDownload =
      model.downloadsViaRepository &&
        storageManager.hasResumablePartialDownload(
          model = model,
          taskId = task?.id,
          includeExtraDataFiles = canResumeExtraDataFiles,
        )

    val isExtraDataAlreadyDownloaded =
      model.downloadsViaRepository &&
        (family.any {
          state.extraDataDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
        } || family.any { storageManager.isExtraDataPresentOnDisk(it, task?.id) })
    val actualIncludeExtraDataFiles = canResumeExtraDataFiles && !isExtraDataAlreadyDownloaded

    return ModelDownloadPlan(
      includeExtraDataFiles = actualIncludeExtraDataFiles,
      resumesPartialDownload = resumesPartialDownload,
      downloadSize =
        storageManager.repositoryDownloadSize(
          model,
          task?.id,
          includeExtraDataFiles = actualIncludeExtraDataFiles,
        ),
    )
  }

  /**
   * Returns how [ModelManagerViewModel.downloadExtraDataFiles] would download the extra data files
   * of [model]'s family for [task], or `null` if the family has none.
   */
  fun extraDataDownloadPlan(
    task: Task?,
    model: Model,
    modelVariants: List<Model> = emptyList(),
  ): ExtraDataDownloadPlan? {
    val state = uiStateProvider()
    val family = getModelFamily(model, modelVariants)
    val downloadedModels = family.filter {
      state.modelDownloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
    val targetModel = downloadedModels.firstOrNull() ?: model
    val extraFiles = targetModel.downloadInfo.extraDataFiles(task?.id)
    if (extraFiles.isEmpty()) {
      return null
    }
    val totalBytes = extraFiles.sumOf { it.sizeInBytes }
    // Zip files count twice, for the download and the unzipped files (see RepositoryDownloadSize).
    val unzipBytes =
      extraFiles.filter { isZipFileName(it.downloadFileName) }.sumOf { it.sizeInBytes }
    val fileNames = extraFiles.map { it.downloadFileName }
    val remainingBytes = {
      (totalBytes - storageManager.bytesOnDisk(targetModel, fileNames)).coerceAtLeast(0L)
    }
    return ExtraDataDownloadPlan(
      family = family,
      targetModel = targetModel,
      totalBytes = totalBytes,
      remainingBytes = remainingBytes,
      requiredBytes = { remainingBytes() + unzipBytes },
    )
  }

  /**
   * Returns how [ModelManagerViewModel.downloadModel] would download [downloads], as (task, model)
   * pairs, each with the extra data files the user enabled. The bytes on disk are read once.
   */
  fun batchDownloadPlan(downloads: List<Pair<Task?, Model>>): BatchDownloadPlan {
    val state = uiStateProvider()
    val plans = downloads.map { (task, model) ->
      modelDownloadPlan(task, model, state.isDownloadOptionalComponentsEnabled(model.name))
    }
    val remainingBytes = plans.sumOf { it.remainingBytes() }
    return BatchDownloadPlan(
      modelNames = downloads.map { (_, model) -> model.name }.toSet(),
      remainingBytes = remainingBytes,
      requiredBytes = remainingBytes + plans.sumOf { it.downloadSize.unzipBytes },
    )
  }

  /**
   * Returns the bytes that the running model downloads, other than those of [excludedModelNames],
   * and the running extra data downloads, other than those of [excludedExtraDataModelNames], still
   * have to write.
   */
  fun inFlightRemainingBytes(
    excludedModelNames: Set<String> = emptySet(),
    excludedExtraDataModelNames: Set<String> = emptySet(),
  ): Long {
    val state = uiStateProvider()
    fun remainingBytes(status: ModelDownloadStatus): Long =
      if (status.status == ModelDownloadStatusType.IN_PROGRESS) {
        (status.totalBytes - status.receivedBytes).coerceAtLeast(0L)
      } else {
        0L
      }
    val modelBytes =
      state.modelDownloadStatus
        .filterKeys { it !in excludedModelNames }
        .values
        .sumOf { remainingBytes(it) }
    val familyByModelName =
      (state.tasks.asSequence().flatMap { it.models.asSequence() } +
          state.supplementaryModels.asSequence())
        .associate { model -> model.name to (model.hierarchy.parentModelName ?: model.name) }
    // An extra data download reports its status to every model of its family, so count each
    // model family once.
    val extraDataBytes =
      state.extraDataDownloadStatus
        .filterKeys { it !in excludedExtraDataModelNames }
        .entries
        .distinctBy { (modelName, _) -> familyByModelName[modelName] ?: modelName }
        .sumOf { (_, status) -> remainingBytes(status) }
    return modelBytes + extraDataBytes
  }

  /**
   * Returns how many bytes of storage are missing for [requiredBytes] more bytes, or `null` if they
   * fit or the free storage is unknown. See [storageShortfallStatus] for the parameters.
   */
  fun storageShortfallBytes(
    requiredBytes: Long,
    excludedModelNames: Set<String> = emptySet(),
    excludedExtraDataModelNames: Set<String> = emptySet(),
  ): Long? {
    if (requiredBytes <= 0L) {
      return null
    }
    val freeBytes = availableStorageBytesProvider() ?: return null
    val availableBytes =
      freeBytes - inFlightRemainingBytes(excludedModelNames, excludedExtraDataModelNames)
    if (availableBytes >= requiredBytes) {
      return null
    }
    Log.w(TAG, "Not enough storage: $requiredBytes bytes needed, $availableBytes bytes available")
    return requiredBytes - availableBytes.coerceAtLeast(0L)
  }

  /**
   * Returns the failed status to show if [requiredBytes] more bytes don't fit in the free storage,
   * or `null` if they fit or the free storage is unknown.
   *
   * The bytes that other running downloads still have to write are kept free for them, so that two
   * downloads started one after another can't both pass the check and then run out of storage
   * partway through. Downloads managed by system services count too, since they share the data
   * partition. Callers count the space needed to unzip their own zip files in [requiredBytes] (see
   * [RepositoryDownloadSize.requiredStorageBytes]); running downloads only count the bytes they
   * still have to write.
   *
   * @param excludedModelNames The models whose own download this check is for.
   * @param excludedExtraDataModelNames The models whose own extra data download this check is for.
   */
  fun storageShortfallStatus(
    requiredBytes: Long,
    excludedModelNames: Set<String> = emptySet(),
    excludedExtraDataModelNames: Set<String> = emptySet(),
  ): ModelDownloadStatus? {
    val shortfallBytes =
      storageShortfallBytes(
        requiredBytes = requiredBytes,
        excludedModelNames = excludedModelNames,
        excludedExtraDataModelNames = excludedExtraDataModelNames,
      ) ?: return null
    val shortfall = Formatter.formatShortFileSize(context, shortfallBytes)
    return ModelDownloadStatus(
      status = ModelDownloadStatusType.FAILED,
      errorMessage = context.getString(R.string.model_download_error_storage_shortfall, shortfall),
      failureReason = ModelDownloadFailureReason.OUT_OF_STORAGE,
    )
  }

  /**
   * Returns [status], with the storage shortfall in its message if the download failed because the
   * device ran out of storage.
   *
   * @param requiredBytes Returns how many more bytes the download needs to write.
   */
  fun withStorageShortfall(
    status: ModelDownloadStatus,
    requiredBytes: () -> Long,
    excludedModelNames: Set<String> = emptySet(),
    excludedExtraDataModelNames: Set<String> = emptySet(),
  ): ModelDownloadStatus {
    if (
      status.status != ModelDownloadStatusType.FAILED ||
        status.failureReason != ModelDownloadFailureReason.OUT_OF_STORAGE
    ) {
      return status
    }
    val shortfallStatus =
      storageShortfallStatus(
        requiredBytes = requiredBytes(),
        excludedModelNames = excludedModelNames,
        excludedExtraDataModelNames = excludedExtraDataModelNames,
      ) ?: return status
    return status.copy(errorMessage = shortfallStatus.errorMessage)
  }

  /**
   * Returns how many bytes of storage are missing to download [batch], or `null` if it fits or the
   * free storage is unknown.
   */
  fun batchStorageShortfallBytes(batch: BatchDownloadPlan): Long? =
    storageShortfallBytes(
      requiredBytes = batch.requiredBytes,
      // These models' own downloads, if running, are replaced by the new ones.
      excludedModelNames = batch.modelNames,
    )

  /**
   * Returns the message to show if the models in [downloads], as (task, model) pairs, don't all fit
   * in the free storage, or `null` if they fit or the free storage is unknown. Each model is sized
   * as [ModelManagerViewModel.downloadModel] would download it, with the extra data files the user
   * enabled, and the bytes that other running downloads still have to write are kept free for them.
   *
   * Only models downloaded by the app are counted, and only batches of at least two of them are
   * checked: [ModelManagerViewModel.downloadModel] checks a single model itself and shows the
   * shortfall on its card.
   */
  fun batchDownloadStorageShortfallMessage(downloads: List<Pair<Task?, Model>>): String? {
    val repositoryDownloads = downloads.filter { (_, model) -> model.downloadsViaRepository }
    if (repositoryDownloads.size < 2) {
      return null
    }
    val shortfallBytes =
      batchStorageShortfallBytes(batchDownloadPlan(repositoryDownloads)) ?: return null
    return context.getString(
      R.string.model_download_error_batch_storage_shortfall,
      Formatter.formatShortFileSize(context, shortfallBytes),
    )
  }

  /**
   * Returns the status of a download that didn't start because the device isn't connected: paused
   * with the bytes on disk if there are some, or failed otherwise.
   *
   * @param totalBytes The total size of the download's files.
   * @param remainingBytes The bytes of the download's files that aren't on disk yet.
   */
  fun noConnectionStatus(totalBytes: Long, remainingBytes: Long): ModelDownloadStatus {
    val receivedBytes = (totalBytes - remainingBytes).coerceAtLeast(0L)
    return ModelDownloadStatus(
      status =
        if (receivedBytes > 0L) {
          ModelDownloadStatusType.PARTIALLY_DOWNLOADED
        } else {
          ModelDownloadStatusType.FAILED
        },
      totalBytes = totalBytes,
      receivedBytes = receivedBytes,
      errorMessage = context.getString(R.string.model_download_error_no_internet),
    )
  }

  /**
   * Returns the message to show when downloads can't start because the device isn't connected to
   * the internet, or `null` if it is.
   */
  fun noConnectionMessage(): String? =
    if (networkStateProvider.isConnected()) {
      null
    } else {
      context.getString(R.string.model_download_error_no_internet)
    }

  /**
   * Returns how many bytes of [downloads], as (task, model) pairs, the user must agree to download
   * over mobile data first, or `null` if no confirmation is needed: the device isn't on mobile
   * data, nothing is left to download, or the device isn't connected or the files don't fit in the
   * free storage, in which case downloading shows that error instead without downloading anything.
   *
   * Each model is sized as [ModelManagerViewModel.downloadModel] downloads it, without the bytes
   * already on disk, and checked for storage as [ModelManagerViewModel.downloadModel] checks it, so
   * that a download never starts over mobile data without asking. Several models are checked
   * together, as [batchDownloadStorageShortfallMessage] checks them. Only models downloaded by the
   * app count: system services download the other ones themselves.
   */
  fun mobileDataDownloadBytes(downloads: List<Pair<Task?, Model>>): Long? {
    val repositoryDownloads = downloads.filter { (_, model) -> model.downloadsViaRepository }
    if (
      repositoryDownloads.isEmpty() ||
        !networkStateProvider.isConnected() ||
        !networkStateProvider.isOnMobileData()
    ) {
      return null
    }
    val batch = batchDownloadPlan(repositoryDownloads)
    if (batchStorageShortfallBytes(batch) != null) {
      return null
    }
    return batch.remainingBytes.takeIf { it > 0L }
  }

  /**
   * Returns how many bytes of the extra data files of [model]'s family the user must agree to
   * download over mobile data first, or `null` if no confirmation is needed. See
   * [mobileDataDownloadBytes] and [ModelManagerViewModel.downloadExtraDataFiles].
   */
  fun extraDataMobileDataDownloadBytes(
    task: Task?,
    model: Model,
    modelVariants: List<Model> = emptyList(),
  ): Long? {
    if (!networkStateProvider.isConnected() || !networkStateProvider.isOnMobileData()) {
      return null
    }
    val plan = extraDataDownloadPlan(task, model, modelVariants) ?: return null
    val shortfallBytes =
      storageShortfallBytes(
        requiredBytes = plan.requiredBytes(),
        excludedExtraDataModelNames = plan.familyNames,
      )
    if (shortfallBytes != null) {
      return null
    }
    return plan.remainingBytes().takeIf { it > 0L }
  }

  /**
   * Matches [pendingDownloads] to the models in [ModelManagerUiState.tasks] and
   * [supplementaryModels] whose current version matches the download's version tag, and returns
   * both the downloads to observe and the stale downloads to cancel.
   */
  fun planPendingDownloads(
    pendingDownloads: List<PendingDownload>,
    supplementaryModels: List<Model> = emptyList(),
  ): PendingDownloadsPlan {
    val tasks = uiStateProvider().tasks
    val pendingByModelName = pendingDownloads.groupBy { it.modelName }
    val observedWorkerIds = mutableSetOf<UUID>()
    val toObserve = mutableListOf<ObservedPendingDownload>()

    for ((task, model) in tasksAndModels(tasks, supplementaryModels)) {
      // Downloads of another version of the model are left to be cancelled below: they would
      // store files where this version of the model isn't looked for.
      val matchingForModel =
        pendingByModelName[model.name].orEmpty().filter { it.version == model.downloadInfo.version }
      for (pendingDownload in matchingForModel) {
        // Prefer the task the download was started from: it decides the extra data files and
        // where the download's notification leads.
        val downloadTask =
          when (pendingDownload.taskId) {
            null -> task
            // Started from the global model manager, outside any task.
            "" -> null
            else -> tasks.find { it.id == pendingDownload.taskId } ?: task
          }
        toObserve.add(
          ObservedPendingDownload(
            pendingDownload = pendingDownload,
            task = downloadTask,
            model = model,
          )
        )
        observedWorkerIds.add(pendingDownload.workerId)
      }
    }

    val staleToCancel = pendingDownloads.filter { it.workerId !in observedWorkerIds }
    return PendingDownloadsPlan(toObserve = toObserve, staleToCancel = staleToCancel)
  }

  /**
   * Returns the partially downloaded models from [ModelManagerUiState.tasks] and
   * [supplementaryModels] that should be automatically resumed at startup.
   *
   * Models are only auto-resumed when the device has an unmetered internet connection, the model's
   * status is [ModelDownloadStatusType.PARTIALLY_DOWNLOADED] without a pause reason
   * (`errorMessage.isEmpty()`), and no model download of the current version is already pending.
   */
  fun autoResumablePartialModels(
    pendingDownloads: List<PendingDownload>,
    supplementaryModels: List<Model> = emptyList(),
  ): List<AutoResumablePartialModel> {
    val state = uiStateProvider()
    val pendingByModelName = pendingDownloads.groupBy { it.modelName }
    // Downloads the user didn't start aren't resumed without a connection, nor over mobile data,
    // which can cost money: they stay paused until the user resumes them, which asks first on
    // mobile data.
    val canAutoResume = networkStateProvider.isConnected() && !networkStateProvider.isOnMobileData()
    val result = mutableListOf<AutoResumablePartialModel>()

    for ((task, model) in tasksAndModels(state.tasks, supplementaryModels)) {
      val pendingForModel =
        pendingByModelName[model.name].orEmpty().filter { it.version == model.downloadInfo.version }
      // Restart downloads of partially downloaded models that nothing is downloading, unless
      // the download was explicitly paused with an error message for the user.
      val curDownloadStatus = state.modelDownloadStatus[model.name]
      val shouldAutoResume =
        curDownloadStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED &&
          curDownloadStatus.errorMessage.isEmpty()
      val isModelDownloadPending = pendingForModel.any { !it.isExtraDataOnly }
      if (!shouldAutoResume || isModelDownloadPending) {
        continue
      }
      if (!canAutoResume) {
        Log.d(TAG, "Not resuming '${model.name}' without a connection or on mobile data")
        continue
      }
      if (task != null || model.downloadsViaRepository) {
        result.add(
          AutoResumablePartialModel(
            task = task,
            model = model,
            // A pending extra data download already writes the extra data files.
            includeExtraDataFiles = pendingForModel.none { it.isExtraDataOnly },
          )
        )
      }
    }
    return result
  }

  private fun tasksAndModels(
    tasks: List<Task>,
    supplementaryModels: List<Model>,
  ): Sequence<Pair<Task?, Model>> =
    (tasks.asSequence().flatMap { task ->
        task.models.asSequence().map { model -> task to model }
      } + supplementaryModels.asSequence().map { model -> null to model })
      .distinctBy { (_, model) -> model.name }
}
