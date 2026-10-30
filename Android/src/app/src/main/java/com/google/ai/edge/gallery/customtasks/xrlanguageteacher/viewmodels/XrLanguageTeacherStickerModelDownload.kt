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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels

import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val STICKER_MODEL_DOWNLOAD_TAG = "AGTeacherStickerDownload"

/**
 * How long a download the dialog shows may make no progress before it counts as stalled, in
 * milliseconds.
 */
internal const val STICKER_MODEL_DOWNLOAD_STALL_TIMEOUT_MS = 25_000L

/** What the user wants to do that needs the sticker segmentation model to be downloaded. */
enum class XrLanguageTeacherStickerModelDownloadReason {
  /** Editing a vocabulary card's sticker cutout. */
  CUTOUT_EDITOR,
  /**
   * Starting a study game, whose targets and obstacles look best as sticker cutouts. The game also
   * starts without the model, with the saved photos.
   */
  GAME,
  /** Turning the saved photos into sticker cutouts from the Study tab. */
  CONVERT_STICKERS,
}

/** The state of the dialog that downloads the sticker segmentation model when it is needed. */
sealed interface XrLanguageTeacherStickerModelDownloadState {
  /** The dialog is hidden. */
  data object Hidden : XrLanguageTeacherStickerModelDownloadState

  /** The dialog asks the user to download the model, which is [sizeInBytes] large. */
  data class Confirming(
    val reason: XrLanguageTeacherStickerModelDownloadReason,
    val sizeInBytes: Long,
  ) : XrLanguageTeacherStickerModelDownloadState

  /**
   * The model is already downloaded; the dialog asks the user to confirm cutting stickers out of
   * the [unsegmentedCount] saved vocabulary photos that were never segmented.
   */
  data class ConfirmingSegmentation(val unsegmentedCount: Int) :
    XrLanguageTeacherStickerModelDownloadState

  /**
   * The dialog shows the download's progress. [downloadStatus] is `null` until the download reports
   * its first progress; [sizeInBytes] is the model's size from the allowlist.
   */
  data class Downloading(
    val reason: XrLanguageTeacherStickerModelDownloadReason,
    val downloadStatus: ModelDownloadStatus?,
    val sizeInBytes: Long,
  ) : XrLanguageTeacherStickerModelDownloadState

  /**
   * The dialog shows the progress of cutting stickers out of the saved words' photos (of every
   * learn language) that were never segmented: [current] is the 1-based index of the word being
   * segmented and [total] the number of words to segment.
   */
  data class Segmenting(
    val reason: XrLanguageTeacherStickerModelDownloadReason,
    val current: Int,
    val total: Int,
  ) : XrLanguageTeacherStickerModelDownloadState {
    /** The fraction of the words segmented so far, in `[0, 1]`, for the progress bar. */
    val fraction: Float
      get() = if (total > 0) (current - 1).coerceIn(0, total).toFloat() / total else 0f
  }

  /**
   * The download failed with [errorMessage] (empty when unknown; logged, while the dialog shows a
   * localized message), or [isStalled] because it made no progress for a while and was cancelled;
   * the user can retry it.
   */
  data class Failed(
    val reason: XrLanguageTeacherStickerModelDownloadReason,
    val errorMessage: String,
    val isStalled: Boolean = false,
  ) : XrLanguageTeacherStickerModelDownloadState
}

/** What is known about the sticker segmentation model of the allowlist. */
enum class XrLanguageTeacherStickerModelAvailability {
  /** Not known yet: the allowlist wasn't reported yet or is (re)loading. */
  UNKNOWN,
  /** The loaded allowlist has no segmentation model, so stickers can't be cut out or edited. */
  MISSING,
  /** The allowlist has the model, but it isn't downloaded yet (or is downloading). */
  NOT_DOWNLOADED,
  /** The model is downloaded. */
  DOWNLOADED,
}

/** Starts and cancels downloads of the sticker segmentation model (the model manager). */
interface XrLanguageTeacherStickerModelDownloader {
  fun download(model: Model)

  fun cancelDownload(model: Model)
}

/**
 * Asks the user to download the sticker segmentation model ("Magic touch") before an action that
 * needs it, and turns the saved words' photos into stickers once it is downloaded.
 *
 * The model is never downloaded without the user agreeing to it. The download itself and its status
 * are the model manager's, so a download started from the model manager shows here too. The task
 * screen sets the [downloader] and reports the model and its download status through
 * [onModelStatusChanged] (or [onAllowlistLoading] while the allowlist loads).
 *
 * Once a download the user agreed to finishes, the same dialog shows the progress of cutting
 * stickers out of every saved photo that was never segmented, then closes. Editing a cutout only
 * opens the editor when the model was already downloaded: after a download, the user sees the new
 * stickers on their cards instead. A study game
 * ([XrLanguageTeacherStickerModelDownloadReason.GAME]) starts after the download and the
 * segmentation, or right away with the saved photos when the user picks "Play without stickers".
 * That choice is remembered, so later games start right away with the photos until the model is
 * downloaded (e.g. through the edit button or "Convert images to stickers"). Cancel buttons
 * ([cancel]) and the system Back ([abandon]) never start a game nor decline the model.
 *
 * A download the dialog shows that makes no progress for [stallTimeoutMs] is cancelled and shown as
 * failed ([XrLanguageTeacherStickerModelDownloadState.Failed.isStalled]), so the user can retry it
 * instead of waiting forever.
 *
 * @param scope the scope that stickers are segmented and stalled downloads are detected in (the
 *   ViewModel scope).
 * @param segmentStickers cuts stickers out of the photos of the saved words (of every learn
 *   language) that were never segmented, reporting the 1-based index of the word being segmented
 *   and the number of words to segment. It reports nothing when there are none.
 * @param countUnsegmentedStickers returns how many saved vocabulary photos (across every learn
 *   language) were never segmented into stickers, so [requireModel] for
 *   [XrLanguageTeacherStickerModelDownloadReason.CONVERT_STICKERS] can ask the user to confirm
 *   segmenting them when the model is already downloaded.
 * @param stallTimeoutMs how long a download may make no progress before it counts as stalled.
 * @param isGameDownloadDeclined returns whether the user chose to play games without stickers.
 * @param setGameDownloadDeclined persists whether the user chose to play games without stickers.
 */
class XrLanguageTeacherStickerModelDownloadController(
  private val scope: CoroutineScope,
  private val segmentStickers: suspend (onProgress: (current: Int, total: Int) -> Unit) -> Unit,
  private val countUnsegmentedStickers: suspend () -> Int = { 0 },
  private val stallTimeoutMs: Long = STICKER_MODEL_DOWNLOAD_STALL_TIMEOUT_MS,
  private val isGameDownloadDeclined: () -> Boolean = { false },
  private val setGameDownloadDeclined: (Boolean) -> Unit = {},
) {
  private val _state =
    MutableStateFlow<XrLanguageTeacherStickerModelDownloadState>(
      XrLanguageTeacherStickerModelDownloadState.Hidden
    )
  /** The state of the download dialog. */
  val state: StateFlow<XrLanguageTeacherStickerModelDownloadState> = _state.asStateFlow()

  private val _availability = MutableStateFlow(XrLanguageTeacherStickerModelAvailability.UNKNOWN)
  /**
   * What is known about the segmentation model, e.g. so that screens hide the cutout edit buttons
   * when the allowlist has no segmentation model, and the Study tab offers to convert the saved
   * photos into stickers while it isn't downloaded.
   */
  val availability: StateFlow<XrLanguageTeacherStickerModelAvailability> =
    _availability.asStateFlow()

  /** Starts and cancels the model's download, or `null` while no screen is shown. */
  var downloader: XrLanguageTeacherStickerModelDownloader? = null

  private var model: Model? = null
  private var downloadStatus: ModelDownloadStatus? = null
  private var isAllowlistLoading = false
  private var pendingAction: (() -> Unit)? = null
  /** The reason [pendingAction] was requested for. */
  private var pendingReason: XrLanguageTeacherStickerModelDownloadReason? = null
  /** A request made while the allowlist was loading, handled once it has loaded. */
  private var deferredRequest: Pair<XrLanguageTeacherStickerModelDownloadReason, () -> Unit>? = null
  private var segmentationJob: Job? = null
  /** Cancels the download shown in the dialog once it makes no progress for [stallTimeoutMs]. */
  private var stallWatchdogJob: Job? = null
  /** The bytes the download shown in the dialog had received when it last made progress. */
  private var lastProgressBytes: Long? = null
  /**
   * Whether the dialog started the download it shows and no progress has been reported for it yet,
   * so a status left over from before (e.g. an earlier failure) is still being reported.
   */
  private var isAwaitingFirstProgress = false
  /**
   * Whether the dialog started the download it shows (rather than showing one started elsewhere).
   */
  private var isDownloadStartedByDialog = false

  /**
   * Reports that the model allowlist is (re)loading, so the segmentation model is not known yet.
   * Requests made meanwhile wait until [onModelStatusChanged] reports the loaded model.
   */
  fun onAllowlistLoading() {
    isAllowlistLoading = true
    _availability.value = XrLanguageTeacherStickerModelAvailability.UNKNOWN
  }

  /**
   * Updates the segmentation [model] (`null` when the loaded allowlist has none) and its [status]
   * in the model manager. Segments the saved photos once a download the dialog shows succeeds, and
   * closes the dialog if the model went away (e.g. the allowlist reloaded without it).
   */
  fun onModelStatusChanged(model: Model?, status: ModelDownloadStatus?) {
    this.model = model
    downloadStatus = status
    _availability.value =
      when {
        model == null -> XrLanguageTeacherStickerModelAvailability.MISSING
        status?.status == ModelDownloadStatusType.SUCCEEDED ->
          XrLanguageTeacherStickerModelAvailability.DOWNLOADED
        else -> XrLanguageTeacherStickerModelAvailability.NOT_DOWNLOADED
      }
    if (
      _availability.value == XrLanguageTeacherStickerModelAvailability.DOWNLOADED &&
        isGameDownloadDeclined()
    ) {
      // Games ask again if the model is deleted later.
      setGameDownloadDeclined(false)
    }
    if (isAllowlistLoading) {
      isAllowlistLoading = false
      val request = deferredRequest
      deferredRequest = null
      if (request != null) {
        requireModel(reason = request.first, action = request.second)
        return
      }
    }
    if (model == null) {
      // The model went away while the dialog asks for it or downloads it: nothing can finish.
      // A game starts with the photos, without the user having declined the download.
      val state = _state.value
      if (state.isAskingOrDownloading) {
        finish(runPendingAction = state.reason == XrLanguageTeacherStickerModelDownloadReason.GAME)
      }
      return
    }
    when (val state = _state.value) {
      XrLanguageTeacherStickerModelDownloadState.Hidden,
      is XrLanguageTeacherStickerModelDownloadState.Segmenting -> Unit
      is XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation ->
        if (status?.status != ModelDownloadStatusType.SUCCEEDED) finish(runPendingAction = false)
      // Downloaded elsewhere (e.g. the model manager) while the dialog was shown.
      is XrLanguageTeacherStickerModelDownloadState.Confirming ->
        if (status?.status == ModelDownloadStatusType.SUCCEEDED) onDownloaded(state.reason)
      is XrLanguageTeacherStickerModelDownloadState.Failed ->
        if (status?.status == ModelDownloadStatusType.SUCCEEDED) onDownloaded(state.reason)
      is XrLanguageTeacherStickerModelDownloadState.Downloading ->
        onDownloadStatusChanged(state, status)
    }
  }

  /** Updates the [state] of the download the dialog shows for its new [status]. */
  private fun onDownloadStatusChanged(
    state: XrLanguageTeacherStickerModelDownloadState.Downloading,
    status: ModelDownloadStatus?,
  ) {
    when (status?.status) {
      ModelDownloadStatusType.SUCCEEDED -> onDownloaded(state.reason)
      // Until the download the dialog just started reports progress, a failure or a missing
      // download may be left over from before it; the stall watchdog (armed when it started) fails
      // the download if it never reports progress.
      ModelDownloadStatusType.FAILED ->
        if (!isAwaitingFirstProgress) {
          stopStallWatchdog()
          Log.w(
            STICKER_MODEL_DOWNLOAD_TAG,
            "The sticker model download failed: ${status.errorMessage}",
          )
          _state.value =
            XrLanguageTeacherStickerModelDownloadState.Failed(
              reason = state.reason,
              errorMessage = status.errorMessage,
            )
        }
      // The download was cancelled or the model deleted, e.g. from the model manager. A game
      // starts with the photos.
      ModelDownloadStatusType.NOT_DOWNLOADED ->
        if (!isAwaitingFirstProgress) {
          finish(
            runPendingAction = state.reason == XrLanguageTeacherStickerModelDownloadReason.GAME
          )
        }
      ModelDownloadStatusType.UNAVAILABLE ->
        finish(runPendingAction = state.reason == XrLanguageTeacherStickerModelDownloadReason.GAME)
      ModelDownloadStatusType.IN_PROGRESS -> {
        isAwaitingFirstProgress = false
        if (status.receivedBytes != lastProgressBytes) {
          lastProgressBytes = status.receivedBytes
          rearmStallWatchdog()
        } else {
          armStallWatchdogIfIdle()
        }
        _state.value = state.copy(downloadStatus = status)
      }
      // Unzipping reports no byte progress, so it isn't watched.
      ModelDownloadStatusType.UNZIPPING -> {
        isAwaitingFirstProgress = false
        stopStallWatchdog()
        _state.value = state.copy(downloadStatus = status)
      }
      else -> _state.value = state.copy(downloadStatus = status)
    }
  }

  /**
   * Runs [action] for [reason] once the segmentation model is downloaded. When it is, a
   * [XrLanguageTeacherStickerModelDownloadReason.CUTOUT_EDITOR] action runs right away, while a
   * game first has the saved photos that were never segmented cut out (showing the progress), and
   * [XrLanguageTeacherStickerModelDownloadReason.CONVERT_STICKERS] only does that. Otherwise asks
   * the user to download the model (or shows the download that is already running), and after the
   * download turns the saved photos into stickers; only a game's [action] runs then. A game whose
   * download the user declined before starts right away, with the saved photos.
   *
   * Without a segmentation model in the allowlist, only a game's [action] runs (with the photos),
   * so an action that needs the model never runs without it. Does nothing while the dialog is
   * already shown or the saved photos are being segmented. A request made while the allowlist loads
   * is handled once it has loaded; [cancelPendingAction] drops it.
   */
  fun requireModel(reason: XrLanguageTeacherStickerModelDownloadReason, action: () -> Unit) {
    if (_state.value != XrLanguageTeacherStickerModelDownloadState.Hidden) return
    if (segmentationJob?.isActive == true) return
    if (isAllowlistLoading) {
      deferredRequest = reason to action
      return
    }
    val model = model
    val status = downloadStatus?.status
    val isGame = reason == XrLanguageTeacherStickerModelDownloadReason.GAME
    when {
      model == null ->
        if (isGame) {
          action()
        } else {
          Log.w(STICKER_MODEL_DOWNLOAD_TAG, "The allowlist has no sticker segmentation model")
        }
      status == ModelDownloadStatusType.SUCCEEDED ->
        when (reason) {
          XrLanguageTeacherStickerModelDownloadReason.CUTOUT_EDITOR -> action()
          XrLanguageTeacherStickerModelDownloadReason.GAME -> {
            setPendingAction(reason, action)
            segmentStickersThenFinish(reason, runPendingAction = true)
          }
          XrLanguageTeacherStickerModelDownloadReason.CONVERT_STICKERS ->
            confirmSegmentationIfNeeded()
        }
      isGame && isGameDownloadDeclined() -> action()
      else -> {
        setPendingAction(reason, action)
        _state.value =
          if (status.isDownloadRunning()) {
            // A download started elsewhere (e.g. the model manager): watch it from now on.
            isAwaitingFirstProgress = false
            lastProgressBytes = downloadStatus?.receivedBytes
            if (status == ModelDownloadStatusType.IN_PROGRESS) rearmStallWatchdog()
            XrLanguageTeacherStickerModelDownloadState.Downloading(
              reason = reason,
              downloadStatus = downloadStatus,
              sizeInBytes = model.downloadInfo.sizeInBytes,
            )
          } else {
            XrLanguageTeacherStickerModelDownloadState.Confirming(
              reason = reason,
              sizeInBytes = model.downloadInfo.sizeInBytes,
            )
          }
      }
    }
  }

  /**
   * Starts the download or the batch sticker segmentation that the user agreed to in the
   * confirmation dialog.
   */
  fun confirm() {
    when (val state = _state.value) {
      is XrLanguageTeacherStickerModelDownloadState.Confirming -> startDownload(state.reason)
      is XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation ->
        segmentStickersThenFinish(
          reason = XrLanguageTeacherStickerModelDownloadReason.CONVERT_STICKERS,
          runPendingAction = false,
        )
      else -> Unit
    }
  }

  /** Starts the download again after it failed. */
  fun retry() {
    val reason =
      (_state.value as? XrLanguageTeacherStickerModelDownloadState.Failed)?.reason ?: return
    startDownload(reason)
  }

  /**
   * Starts the study game right away with the saved photos: "Play without stickers" while asking,
   * downloading, or after a failed download, and "Play now" while segmenting. While asking or
   * downloading, this declines the model: a download the dialog started is cancelled (one started
   * elsewhere, e.g. in Model Manager, keeps running), and later games start right away without
   * asking (until the model is downloaded). While segmenting, it stops and the game uses the
   * stickers cut out so far. Does nothing for actions other than a game, and only hides the dialog
   * when no game is waiting for it anymore (see [cancelPendingAction]).
   */
  fun playWithoutStickers() {
    val state = _state.value
    if (state.reason != XrLanguageTeacherStickerModelDownloadReason.GAME) return
    if (pendingAction == null) {
      // No game is waiting: don't decline the model or cancel the download for nothing.
      abandon()
      return
    }
    val isDownloaded = downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
    when (state) {
      is XrLanguageTeacherStickerModelDownloadState.Confirming -> setGameDownloadDeclined(true)
      is XrLanguageTeacherStickerModelDownloadState.Downloading ->
        if (!isDownloaded) {
          if (isDownloadStartedByDialog) cancelRunningDownload()
          setGameDownloadDeclined(true)
        }
      else -> Unit
    }
    segmentationJob?.cancel()
    segmentationJob = null
    finish(runPendingAction = true)
  }

  /**
   * Hides the dialog from its Cancel buttons without running the pending action: cancels the
   * download if it is running (a download the model manager can't cancel keeps running in the
   * background), or stops segmenting the saved photos (the rest are segmented the next time). It
   * doesn't decline the model, so a later game asks again.
   */
  fun cancel() {
    if (_state.value is XrLanguageTeacherStickerModelDownloadState.Downloading) {
      cancelRunningDownload()
    }
    segmentationJob?.cancel()
    segmentationJob = null
    finish(runPendingAction = false)
  }

  /**
   * Cancels the download the dialog shows if it is running, or was just started and hasn't reported
   * progress yet.
   */
  private fun cancelRunningDownload() {
    val model = model ?: return
    if (isAwaitingFirstProgress || downloadStatus?.status.isDownloadRunning()) {
      downloader?.cancelDownload(model)
    }
  }

  /**
   * Drops the pending action of [reason], e.g. because the screen that asked for the model went
   * away: a request waiting for the allowlist is forgotten, a confirmation or failed download for
   * it is closed (without declining the download), and a dialog showing the download or the
   * segmentation for it is abandoned like the system Back does ([abandon]: the download keeps
   * running, segmentation stops), so its buttons can't act on a game that no longer exists.
   */
  fun cancelPendingAction(reason: XrLanguageTeacherStickerModelDownloadReason) {
    if (deferredRequest?.first == reason) deferredRequest = null
    if (pendingReason == reason) {
      pendingAction = null
      pendingReason = null
    }
    val state = _state.value
    if (state.reason != reason) return
    when (state) {
      is XrLanguageTeacherStickerModelDownloadState.Confirming,
      is XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation,
      is XrLanguageTeacherStickerModelDownloadState.Failed -> finish(runPendingAction = false)
      is XrLanguageTeacherStickerModelDownloadState.Downloading,
      is XrLanguageTeacherStickerModelDownloadState.Segmenting -> abandon()
      XrLanguageTeacherStickerModelDownloadState.Hidden -> Unit
    }
  }

  private fun setPendingAction(
    reason: XrLanguageTeacherStickerModelDownloadReason,
    action: () -> Unit,
  ) {
    pendingAction = action
    pendingReason = reason
  }

  /**
   * Abandons the dialog (the system Back): hides it without running the pending action. A running
   * download keeps running in the background (and then just finishes), and segmenting the saved
   * photos stops (the rest are segmented the next time).
   */
  fun abandon() {
    segmentationJob?.cancel()
    segmentationJob = null
    finish(runPendingAction = false)
  }

  private fun startDownload(reason: XrLanguageTeacherStickerModelDownloadReason) {
    val model = model
    val downloader = downloader
    if (model == null || downloader == null) {
      // The model went away (e.g. the allowlist reloaded without it), or the screen is gone. A game
      // starts without the model, with the photos.
      finish(
        runPendingAction =
          model == null && reason == XrLanguageTeacherStickerModelDownloadReason.GAME
      )
      return
    }
    stopStallWatchdog()
    isAwaitingFirstProgress = true
    isDownloadStartedByDialog = true
    lastProgressBytes = null
    _state.value =
      XrLanguageTeacherStickerModelDownloadState.Downloading(
        reason = reason,
        downloadStatus = null,
        sizeInBytes = model.downloadInfo.sizeInBytes,
      )
    downloader.download(model)
    // Fails the download if it never reports progress, e.g. when no status arrives at all.
    if (_state.value is XrLanguageTeacherStickerModelDownloadState.Downloading) {
      rearmStallWatchdog()
    }
  }

  /**
   * Turns the saved photos into stickers once the model the dialog asked for is downloaded, then
   * starts the game that asked for it.
   */
  private fun onDownloaded(reason: XrLanguageTeacherStickerModelDownloadReason) {
    stopStallWatchdog()
    segmentStickersThenFinish(
      reason,
      runPendingAction = reason == XrLanguageTeacherStickerModelDownloadReason.GAME,
    )
  }

  /** (Re)starts the stall watchdog, e.g. because the download made progress. */
  private fun rearmStallWatchdog() {
    stallWatchdogJob?.cancel()
    stallWatchdogJob = scope.launch {
      delay(stallTimeoutMs)
      stallWatchdogJob = null
      onDownloadStalled()
    }
  }

  /** Starts the stall watchdog unless it is already running. */
  private fun armStallWatchdogIfIdle() {
    if (stallWatchdogJob?.isActive != true) rearmStallWatchdog()
  }

  private fun stopStallWatchdog() {
    stallWatchdogJob?.cancel()
    stallWatchdogJob = null
  }

  /**
   * Cancels the download the dialog shows, which made no progress for [stallTimeoutMs], and shows
   * it as failed so the user can retry it.
   */
  private fun onDownloadStalled() {
    val state = _state.value as? XrLanguageTeacherStickerModelDownloadState.Downloading ?: return
    Log.w(STICKER_MODEL_DOWNLOAD_TAG, "The sticker model download stalled; cancelling it")
    isAwaitingFirstProgress = false
    model?.let { downloader?.cancelDownload(it) }
    _state.value =
      XrLanguageTeacherStickerModelDownloadState.Failed(
        reason = state.reason,
        errorMessage = "",
        isStalled = true,
      )
  }

  /**
   * Checks how many saved vocabulary photos still need sticker cutouts and, when there are any,
   * asks the user to confirm segmenting them
   * ([XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation]).
   */
  private fun confirmSegmentationIfNeeded() {
    segmentationJob = scope.launch {
      val count =
        try {
          countUnsegmentedStickers()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(STICKER_MODEL_DOWNLOAD_TAG, "Failed to count unsegmented vocabulary photos", e)
          0
        }
      segmentationJob = null
      if (
        count > 0 &&
          _state.value == XrLanguageTeacherStickerModelDownloadState.Hidden &&
          downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
      ) {
        _state.value =
          XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation(
            unsegmentedCount = count
          )
      }
    }
  }

  /**
   * Cuts stickers out of the saved photos that were never segmented, showing
   * [XrLanguageTeacherStickerModelDownloadState.Segmenting] while there are any (the dialog keeps
   * its current state until then), then hides the dialog, running the pending action if
   * [runPendingAction]. Segmentation failures still finish.
   */
  private fun segmentStickersThenFinish(
    reason: XrLanguageTeacherStickerModelDownloadReason,
    runPendingAction: Boolean,
  ) {
    segmentationJob = scope.launch {
      try {
        segmentStickers { current, total ->
          _state.value =
            XrLanguageTeacherStickerModelDownloadState.Segmenting(
              reason = reason,
              current = current,
              total = total,
            )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(STICKER_MODEL_DOWNLOAD_TAG, "Failed to cut stickers out of the saved photos", e)
      }
      segmentationJob = null
      finish(runPendingAction)
    }
  }

  private fun finish(runPendingAction: Boolean) {
    isDownloadStartedByDialog = false
    stopStallWatchdog()
    isAwaitingFirstProgress = false
    val action = pendingAction
    pendingAction = null
    pendingReason = null
    _state.value = XrLanguageTeacherStickerModelDownloadState.Hidden
    if (runPendingAction) action?.invoke()
  }
}

/** Whether the dialog asks to download or segment, shows a download, or shows it failed. */
private val XrLanguageTeacherStickerModelDownloadState.isAskingOrDownloading: Boolean
  get() =
    this is XrLanguageTeacherStickerModelDownloadState.Confirming ||
      this is XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation ||
      this is XrLanguageTeacherStickerModelDownloadState.Downloading ||
      this is XrLanguageTeacherStickerModelDownloadState.Failed

/** The reason the dialog is shown for, or `null` while it is hidden. */
private val XrLanguageTeacherStickerModelDownloadState.reason:
  XrLanguageTeacherStickerModelDownloadReason?
  get() =
    when (this) {
      XrLanguageTeacherStickerModelDownloadState.Hidden -> null
      is XrLanguageTeacherStickerModelDownloadState.Confirming -> reason
      is XrLanguageTeacherStickerModelDownloadState.ConfirmingSegmentation ->
        XrLanguageTeacherStickerModelDownloadReason.CONVERT_STICKERS
      is XrLanguageTeacherStickerModelDownloadState.Downloading -> reason
      is XrLanguageTeacherStickerModelDownloadState.Segmenting -> reason
      is XrLanguageTeacherStickerModelDownloadState.Failed -> reason
    }

private fun ModelDownloadStatusType?.isDownloadRunning(): Boolean =
  this == ModelDownloadStatusType.IN_PROGRESS || this == ModelDownloadStatusType.UNZIPPING
