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

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.VocabCardData
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherStickerImages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherVocabImageCache
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.findUnsegmentedStickerEntries
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.loadSavedVocabPhoto
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.persistVocabSticker
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.revertVocabStickerToPhoto
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.StickerCutoutResult
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.makeSticker
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.proto.DictionaryEntry
import com.google.ai.edge.gallery.proto.Language
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STICKER_CONTROLLER_TAG = "AGTeacherStickerCtrl"

/** A captured frame and the prepared (cropped and scaled) copy that replaces it. */
internal class PreparedFrameCopy(val original: Bitmap, val prepared: Bitmap)

/** Returns the key that a card's edited sticker and pending sticker state are stored under. */
internal fun stickerKey(word: String): String = word.trim().lowercase()

/**
 * Returns the sticker cutout to show and save for [card] of the current session: the one the user
 * edited for it, none when the user chose its original photo, or else the session's automatic
 * cutout, or `null` while there is none.
 */
internal fun XrLanguageTeacherUiState.stickerFor(card: VocabCardData): Bitmap? =
  stickerForKey(stickerKey(card.word))

/** Returns the sticker cutout for the card whose [stickerKey] is [key] (see [stickerFor]). */
internal fun XrLanguageTeacherUiState.stickerForKey(key: String): Bitmap? =
  sessionStickerForKey(
    key = key,
    autoSticker = stickerBitmap,
    editedStickers = editedStickerBitmaps,
  )

/**
 * Returns the sticker cutout of the session card whose [stickerKey] is [key]: its entry in
 * [editedStickers] (`null` when the user chose the card's original photo), or else [autoSticker],
 * the session's automatic cutout.
 */
internal fun sessionStickerForKey(
  key: String,
  autoSticker: Bitmap?,
  editedStickers: Map<String, Bitmap?>,
): Bitmap? = if (key in editedStickers) editedStickers[key] else autoSticker

/**
 * Returns whether the user chose the original photo instead of a sticker cutout for the session
 * card whose [stickerKey] is [key].
 */
internal fun XrLanguageTeacherUiState.usesOriginalPhoto(key: String): Boolean =
  key in editedStickerBitmaps && editedStickerBitmaps[key] == null

/**
 * Returns the model that cuts stickers out: the segmentation model registered to the Scrapbook task
 * ([BuiltInTaskId.MP_SCRAPBOOK]) in the model manager's [tasks] ("Magic touch"), or `null` when
 * there is none. Its download status is keyed by its name, so a download from Scrapbook or from
 * this feature shows in both.
 */
internal fun findStickerSegmentationModel(tasks: List<Task>): Model? =
  tasks.firstOrNull { it.id == BuiltInTaskId.MP_SCRAPBOOK }?.models?.firstOrNull()

/**
 * Updates sticker segmentation for the segmentation [model] and its download [status]: segments
 * with the model file at [modelPath] of the model once it is downloaded. Otherwise (no model, not
 * downloaded, downloading, or failed) segmentation is unavailable and the segmenter is released,
 * and cards show their photos. The model is only downloaded when the user agrees to it (see
 * [XrLanguageTeacherStickerModelDownloadController]).
 */
fun XrLanguageTeacherViewModel.syncStickerSegmentationModel(
  model: Model?,
  status: ModelDownloadStatusType?,
  modelPath: (Model) -> String,
) {
  setStickerSegmentationModelPath(
    if (model != null && status == ModelDownloadStatusType.SUCCEEDED) modelPath(model) else null
  )
}

/**
 * Sets the downloaded segmentation model file that sticker cutouts are segmented with, or `null`
 * while it is not downloaded (which releases the segmenter and closes the cutout editor), and
 * updates [XrLanguageTeacherViewModel.isStickerModelReady]. Warms the segmenter up in the
 * background and cuts out the current frame's sticker if it was captured before the model was
 * ready.
 */
fun XrLanguageTeacherViewModel.setStickerSegmentationModelPath(modelPath: String?) {
  if (stickerModelPath == modelPath) return
  stickerModelPath = modelPath
  stickerMaker.setModelPath(modelPath)
  _isStickerModelReady.value = modelPath != null
  if (modelPath == null) {
    // The model was deleted (e.g. in the model manager): the editor can't segment anymore.
    cutoutEditor.close()
    return
  }
  viewModelScope.launch { stickerMaker.prepare() }
  val state = _uiState.value
  val frame = state.capturedBitmap
  if (frame != null && state.stickerBitmap == null) {
    scheduleStickerCutout(frame)
  }
}

/**
 * Cuts the sticker out of every new captured frame (mirroring iOS `capturedFrame.didSet`). A
 * different capture, or the end of the session, drops the previous capture's stickers and pending
 * saves; swapping the frame for its prepared copy keeps them.
 */
internal fun XrLanguageTeacherViewModel.observeCapturedFrameForStickers() {
  viewModelScope.launch {
    _uiState
      .map { it.capturedBitmap }
      .distinctUntilChanged { old, new -> old === new }
      .collect { frame -> onCapturedFrameChanged(frame) }
  }
}

/**
 * Updates the sticker state for the new captured [frame] (see [observeCapturedFrameForStickers]).
 */
internal fun XrLanguageTeacherViewModel.onCapturedFrameChanged(frame: Bitmap?) {
  val previous = stickerSourceFrame
  stickerSourceFrame = frame
  val preparedCopy = lastPreparedFrameCopy
  if (
    frame != null &&
      previous != null &&
      preparedCopy != null &&
      preparedCopy.original === previous &&
      preparedCopy.prepared === frame
  ) {
    // Same photo: keep the finished or in-flight cutout and the session's edits.
    return
  }
  stickerCaptureId.incrementAndGet()
  sessionSavedWordKeys.clear()
  sessionWordsAwaitingSticker.clear()
  _uiState.update { it.copy(stickerBitmap = null, editedStickerBitmaps = emptyMap()) }
  scheduleStickerCutout(frame)
}

/** Segments the sticker cutout of [frame] in the background, replacing any cutout still running. */
internal fun XrLanguageTeacherViewModel.scheduleStickerCutout(frame: Bitmap?) {
  val epoch = stickerEpoch.incrementAndGet()
  stickerJob?.cancel()
  stickerJob = null
  if (frame == null || !stickerMaker.isAvailable) return
  val captureId = stickerCaptureId.get()
  stickerJob = viewModelScope.launch {
    val result = stickerMaker.makeSticker(source = frame, cutoutDispatcher = inferenceDispatcher)
    if (
      stickerEpoch.get() != epoch ||
        stickerCaptureId.get() != captureId ||
        result !is StickerCutoutResult.Success
    ) {
      return@launch
    }
    withContext(inferenceDispatcher) {
      val unused = XrLanguageTeacherStickerImages.getOrPrepareOutlinedSticker(result.sticker)
    }
    if (stickerEpoch.get() != epoch || stickerCaptureId.get() != captureId) {
      return@launch
    }
    _uiState.update { it.copy(stickerBitmap = result.sticker) }
    persistPendingSessionStickers(captureId)
  }
}

/**
 * Saves the current sticker of every word saved from capture [captureId] before its sticker was
 * ready. A word whose save hasn't landed yet stays pending, and its save picks the sticker up (see
 * [persistLatestSessionSticker]).
 */
private fun XrLanguageTeacherViewModel.persistPendingSessionStickers(captureId: Long) {
  if (sessionWordsAwaitingSticker.isEmpty()) return
  val language = learnLanguage.value
  viewModelScope.launch(ioDispatcher) {
    for (key in sessionWordsAwaitingSticker.toList()) {
      if (stickerCaptureId.get() != captureId) return@launch
      val sticker = _uiState.value.stickerForKey(key) ?: continue
      val pngBytes = XrLanguageTeacherStickerImages.encodePng(sticker) ?: continue
      val saved =
        persistVocabSticker(word = key, learnLanguage = language, stickerBytes = pngBytes) {
          // Not if the user edited the sticker or chose the photo while it was encoded.
          stickerCaptureId.get() == captureId &&
            key in sessionWordsAwaitingSticker &&
            _uiState.value.stickerForKey(key) === sticker
        }
      if (saved) {
        sessionWordsAwaitingSticker.remove(key)
      }
    }
  }
}

/**
 * Called once the word of [card], newly saved from capture [captureId] with [savedSticker] (or
 * none), is written: saves the card's current sticker if it changed meanwhile, because the
 * automatic cutout finished or the user edited it before the save landed, or deletes the saved
 * sticker if the user chose the original photo meanwhile. Call on the IO dispatcher.
 */
internal suspend fun XrLanguageTeacherViewModel.persistLatestSessionSticker(
  card: VocabCardData,
  learnLanguage: Language,
  captureId: Long,
  savedSticker: Bitmap?,
) {
  if (stickerCaptureId.get() != captureId) return
  val key = stickerKey(card.word)
  if (_uiState.value.usesOriginalPhoto(key)) {
    // The user chose the original photo before the save landed: drop any sticker it saved.
    sessionWordsAwaitingSticker.remove(key)
    val unused = revertVocabStickerToPhoto(word = card.word, learnLanguage = learnLanguage)
    return
  }
  val latest = _uiState.value.stickerFor(card)
  if (latest == null) return
  if (latest === savedSticker) {
    sessionWordsAwaitingSticker.remove(key)
    return
  }
  val pngBytes = XrLanguageTeacherStickerImages.encodePng(latest) ?: return
  val saved =
    persistVocabSticker(word = card.word, learnLanguage = learnLanguage, stickerBytes = pngBytes) {
      // Not if the user edited the sticker or chose the photo while it was encoded.
      stickerCaptureId.get() == captureId && _uiState.value.stickerFor(card) === latest
    }
  if (saved) {
    sessionWordsAwaitingSticker.remove(key)
  }
}

/**
 * Segments the photos of the words saved for [language] (or for every learn language when it is
 * `null`) that were never segmented into a sticker cutout, saving each cutout next to its photo and
 * marking the word as attempted (also when no foreground object was isolated, so it is not
 * segmented again). [onProgress] is called with the 1-based index of the word being segmented and
 * the number of words to segment.
 *
 * Stops early, leaving the remaining words for the next call, when segmentation is unavailable (for
 * example while the model downloads). A photo the segmenter throws on is skipped and left
 * unattempted, so it doesn't stop the words after it; after [MAX_STICKER_SEGMENTATION_ERRORS] such
 * errors in this ViewModel it is marked as attempted, so it isn't retried forever. A sticker that
 * can't be encoded is also left for the next call. Returns the number of stickers saved.
 */
/**
 * How many times [ensureStickersSegmented] tries a photo that the segmenter throws on before it
 * marks the photo as attempted.
 */
internal const val MAX_STICKER_SEGMENTATION_ERRORS = 3

suspend fun XrLanguageTeacherViewModel.ensureStickersSegmented(
  language: Language?,
  onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
): Int {
  if (!stickerMaker.isAvailable) return 0
  val pendingEntries = findUnsegmentedStickerEntries(language)
  var savedCount = 0
  for ((index, entry) in pendingEntries.withIndex()) {
    currentCoroutineContext().ensureActive()
    onProgress(index + 1, pendingEntries.size)
    val photo = loadSavedVocabPhoto(entry.word, entry.learnLanguage)
    if (photo == null) {
      // The photo can't be decoded; don't try it again on every launch.
      val unused = persistVocabSticker(word = entry.word, learnLanguage = entry.learnLanguage, null)
      continue
    }
    when (
      val result = stickerMaker.makeSticker(source = photo, cutoutDispatcher = inferenceDispatcher)
    ) {
      is StickerCutoutResult.Success -> {
        val pngBytes =
          withContext(ioDispatcher) { XrLanguageTeacherStickerImages.encodePng(result.sticker) }
            ?: continue
        if (persistVocabSticker(entry.word, entry.learnLanguage, pngBytes)) {
          savedCount++
        }
      }
      StickerCutoutResult.Error -> {
        val entryKey = XrLanguageTeacherVocabImageCache.entryKey(entry.word, entry.learnLanguage)
        val errorCount =
          stickerSegmentationErrorCounts.merge(entryKey, 1) { count, added -> count + added } ?: 1
        if (errorCount >= MAX_STICKER_SEGMENTATION_ERRORS) {
          Log.w(STICKER_CONTROLLER_TAG, "Giving up on a photo the segmenter keeps failing on")
          stickerSegmentationErrorCounts.remove(entryKey)
          val unused =
            persistVocabSticker(word = entry.word, learnLanguage = entry.learnLanguage, null)
        }
      }
      StickerCutoutResult.NoForeground -> {
        val unused =
          persistVocabSticker(word = entry.word, learnLanguage = entry.learnLanguage, null)
      }
      StickerCutoutResult.Unavailable -> {
        Log.w(
          STICKER_CONTROLLER_TAG,
          "Sticker segmentation became unavailable; resuming on the next launch",
        )
        break
      }
    }
  }
  return savedCount
}

// MARK: - Cutout editor

/**
 * Opens the cutout editor for [card] of the current camera session on the session's frozen frame
 * when the segmentation model is downloaded. Otherwise asks to download it, which turns the saved
 * photos into stickers instead of opening the editor (see
 * [XrLanguageTeacherStickerModelDownloadController.requireModel]).
 */
fun XrLanguageTeacherViewModel.openCutoutEditor(card: VocabCardData) {
  stickerModelDownload.requireModel(XrLanguageTeacherStickerModelDownloadReason.CUTOUT_EDITOR) {
    val photo = _uiState.value.capturedBitmap?.takeUnless { it.isRecycled }
    cutoutEditor.open(target = CutoutEditTarget.SessionCard(card.word), photo = photo)
  }
}

/**
 * Opens the cutout editor for the saved [entry] on its original photo when the segmentation model
 * is downloaded. Otherwise asks to download it, which turns the saved photos into stickers instead
 * of opening the editor (see [XrLanguageTeacherStickerModelDownloadController.requireModel]).
 */
fun XrLanguageTeacherViewModel.openCutoutEditor(entry: DictionaryEntry) {
  stickerModelDownload.requireModel(XrLanguageTeacherStickerModelDownloadReason.CUTOUT_EDITOR) {
    viewModelScope.launch {
      val photo = loadSavedVocabPhoto(entry.word, entry.learnLanguage)
      cutoutEditor.open(
        target =
          CutoutEditTarget.SavedEntry(word = entry.word, learnLanguage = entry.learnLanguage),
        photo = photo,
      )
    }
  }
}

/** Closes the cutout editor without changing the sticker. */
fun XrLanguageTeacherViewModel.cancelCutoutEdit() {
  cutoutEditor.close()
}

/**
 * Regenerates the edited card's sticker from the editor's selection through the cutout pipeline and
 * closes the editor.
 *
 * A saved word's sticker is saved to disk. A card of the current session keeps the sticker in
 * memory, so it is saved with the word when the user taps Save; when the word was already saved
 * from this capture, it is saved right away (or by that save, if it hasn't landed yet).
 */
fun XrLanguageTeacherViewModel.applyCutoutEdit() {
  val editorState = cutoutEditor.uiState.value
  val target = editorState.target ?: return
  if (!editorState.canApply) return
  cutoutEditor.setApplying(true)
  viewModelScope.launch {
    val sticker = cutoutEditor.buildSticker()
    if (sticker == null) {
      cutoutEditor.setApplying(false, CutoutEditorMessage.SELECTION_REJECTED)
      return@launch
    }
    val saved =
      when (target) {
        is CutoutEditTarget.SessionCard -> {
          val key = stickerKey(target.word)
          if (_uiState.value.capturedBitmap != null) {
            _uiState.update {
              it.copy(editedStickerBitmaps = it.editedStickerBitmaps + (key to sticker))
            }
          }
          if (key in sessionSavedWordKeys) {
            // A save that hasn't landed yet picks the edit up instead, so this can't fail the edit.
            val unused =
              persistEditedSticker(target.word, learnLanguage.value, sticker) {
                _uiState.value.stickerForKey(key) === sticker
              }
          }
          true
        }
        is CutoutEditTarget.SavedEntry ->
          persistEditedSticker(target.word, target.learnLanguage, sticker)
      }
    if (saved) {
      cutoutEditor.close()
    } else {
      cutoutEditor.setApplying(false, CutoutEditorMessage.SAVE_FAILED)
    }
  }
}

/**
 * Shows the edited card's original photo instead of a sticker cutout and closes the editor.
 *
 * A saved word's sticker is deleted; the word stays marked as segmented, so it isn't cut out again
 * automatically, and "Reset to auto" in the editor brings a cutout back. A card of the current
 * session shows its photo and is saved without a sticker when the user taps Save; when the word was
 * already saved from this capture, its saved sticker is deleted right away (or once that save
 * lands, if it hasn't yet).
 */
fun XrLanguageTeacherViewModel.useOriginalPhotoForCutout() {
  val editorState = cutoutEditor.uiState.value
  val target = editorState.target ?: return
  if (editorState.isApplying) return
  cutoutEditor.setApplying(true)
  viewModelScope.launch {
    val reverted =
      when (target) {
        is CutoutEditTarget.SessionCard -> {
          val key = stickerKey(target.word)
          if (_uiState.value.capturedBitmap != null) {
            _uiState.update {
              it.copy(editedStickerBitmaps = it.editedStickerBitmaps + (key to null))
            }
          }
          sessionWordsAwaitingSticker.remove(key)
          if (key in sessionSavedWordKeys) {
            // A save that hasn't landed yet drops its sticker instead, so this can't fail.
            val unused = revertVocabStickerToPhoto(target.word, learnLanguage.value)
          }
          true
        }
        is CutoutEditTarget.SavedEntry ->
          revertVocabStickerToPhoto(target.word, target.learnLanguage)
      }
    if (reverted) {
      cutoutEditor.close()
    } else {
      cutoutEditor.setApplying(false, CutoutEditorMessage.SAVE_FAILED)
    }
  }
}

/**
 * Encodes and saves the edited [sticker] of the saved [word] if [shouldWrite] still allows it (see
 * [persistVocabSticker]), returning whether it was saved.
 */
private suspend fun XrLanguageTeacherViewModel.persistEditedSticker(
  word: String,
  learnLanguage: Language,
  sticker: Bitmap,
  shouldWrite: () -> Boolean = { true },
): Boolean =
  withContext(ioDispatcher) {
    val pngBytes = XrLanguageTeacherStickerImages.encodePng(sticker) ?: return@withContext false
    persistVocabSticker(
      word = word,
      learnLanguage = learnLanguage,
      stickerBytes = pngBytes,
      shouldWrite = shouldWrite,
    )
  }

/** Releases the sticker segmenter and closes the cutout editor when the ViewModel is cleared. */
internal fun XrLanguageTeacherViewModel.releaseStickerResources() {
  stickerJob?.cancel()
  stickerJob = null
  cutoutEditor.close()
  stickerMaker.close()
}
