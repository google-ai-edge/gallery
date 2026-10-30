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
import com.google.ai.edge.gallery.customtasks.common.segmentation.SEGMENTATION_MASK_OVERLAY_COLOR
import com.google.ai.edge.gallery.customtasks.common.segmentation.SegmentationBrushMode
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.AUTO_CUTOUT_STROKE
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CutoutStroke
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.NormalizedPoint
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.StickerMaskPolarity
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherStickerCutout
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherStickerImages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.StickerSegmentationResult
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services.XrLanguageTeacherStickerMaker
import com.google.ai.edge.gallery.proto.Language
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The vocabulary card whose sticker cutout the editor edits. */
sealed interface CutoutEditTarget {
  /** The vocabulary word of the card. */
  val word: String

  /** A card of the active camera lesson, which may not be saved yet. */
  data class SessionCard(override val word: String) : CutoutEditTarget

  /** A word saved for [learnLanguage]. */
  data class SavedEntry(override val word: String, val learnLanguage: Language) : CutoutEditTarget
}

/** A problem the cutout editor tells the user about. */
enum class CutoutEditorMessage {
  /** The original photo of the card could not be loaded. */
  PHOTO_UNAVAILABLE,
  /** The segmentation model is not downloaded yet or can't run on this device. */
  SEGMENTER_UNAVAILABLE,
  /** The strokes could not be segmented. */
  SEGMENTATION_FAILED,
  /** The selection is too small or covers almost the whole photo to make a sticker. */
  SELECTION_REJECTED,
  /** The new sticker could not be saved. */
  SAVE_FAILED,
}

/**
 * The state of the cutout editor.
 *
 * @property target The card being edited, or `null` while the editor is closed.
 * @property photo The card's original photo that the strokes are drawn on.
 * @property strokes Every stroke drawn, including undone ones after [activeStrokeCount].
 * @property activeStrokeCount How many of [strokes] are applied; undo and redo move it.
 * @property brushMode The brush that new strokes are drawn with.
 * @property maskOverlay The scrim over the parts of [photo] outside the current selection.
 * @property hasSelection Whether the current selection contains any pixel.
 * @property isSegmenting Whether the selection is being recomputed for the current strokes.
 * @property isApplying Whether the sticker is being regenerated and saved after Done.
 * @property message A problem to tell the user about, or `null`.
 */
data class CutoutEditorUiState(
  val target: CutoutEditTarget? = null,
  val photo: Bitmap? = null,
  val strokes: List<CutoutStroke> = emptyList(),
  val activeStrokeCount: Int = 0,
  val brushMode: SegmentationBrushMode = SegmentationBrushMode.ADD,
  val maskOverlay: Bitmap? = null,
  val hasSelection: Boolean = false,
  val isSegmenting: Boolean = false,
  val isApplying: Boolean = false,
  val message: CutoutEditorMessage? = null,
) {
  /** Whether the editor is shown. */
  val isOpen: Boolean
    get() = target != null

  /** The strokes that are applied (not undone). */
  val activeStrokes: List<CutoutStroke>
    get() = strokes.take(activeStrokeCount)

  /** Whether there is an applied stroke to undo. */
  val canUndo: Boolean
    get() = activeStrokeCount > 0

  /** Whether there is an undone stroke to redo. */
  val canRedo: Boolean
    get() = activeStrokeCount < strokes.size

  /** Whether there are strokes to clear. */
  val canClear: Boolean
    get() = strokes.isNotEmpty()

  /** Whether Done can regenerate the sticker from the current selection. */
  val canApply: Boolean
    get() = hasSelection && !isSegmenting && !isApplying
}

/**
 * Holds the cutout editor state: the brush strokes drawn on a card's original photo (in normalized
 * photo coordinates) with undo and redo, and the selection [stickerMaker] segments for them.
 *
 * The editor opens on the automatic cutout ([AUTO_CUTOUT_STROKE]); every stroke change segments the
 * applied strokes again, dropping results that a later change superseded.
 *
 * @param scope The scope segmentations run in (the ViewModel scope, on the main thread).
 * @param imageDispatcher The dispatcher that builds mask overlays and stickers.
 */
class XrLanguageTeacherCutoutEditorController(
  private val scope: CoroutineScope,
  private val stickerMaker: XrLanguageTeacherStickerMaker,
  private val imageDispatcher: CoroutineDispatcher,
  private val overlayColor: Int = SEGMENTATION_MASK_OVERLAY_COLOR,
) {
  private val _uiState = MutableStateFlow(CutoutEditorUiState())
  val uiState: StateFlow<CutoutEditorUiState> = _uiState.asStateFlow()

  private var segmentationJob: Job? = null
  private var segmentationRequest = 0L
  /** The segmentation of the applied strokes, once it finished. */
  private var latestSegmentation: StickerSegmentationResult.Success? = null
  /** Whether a drag stroke is being drawn, so that points can be added to it. */
  private var isDrawingStroke = false

  /**
   * Opens the editor for [target] on its original [photo] (or `null` when it couldn't be loaded),
   * starting from the automatic cutout.
   */
  fun open(target: CutoutEditTarget, photo: Bitmap?) {
    cancelSegmentation()
    isDrawingStroke = false
    updateKeepingOverlay(null) {
      CutoutEditorUiState(
        target = target,
        photo = photo,
        strokes = listOf(AUTO_CUTOUT_STROKE),
        activeStrokeCount = 1,
        message = if (photo == null) CutoutEditorMessage.PHOTO_UNAVAILABLE else null,
      )
    }
    segmentActiveStrokes()
  }

  /** Closes the editor and drops its state. */
  fun close() {
    cancelSegmentation()
    isDrawingStroke = false
    updateKeepingOverlay(null) { CutoutEditorUiState() }
  }

  /** Selects the brush that new strokes are drawn with. */
  fun setBrushMode(brushMode: SegmentationBrushMode) {
    _uiState.update { it.copy(brushMode = brushMode) }
  }

  /**
   * Starts a stroke at [point] with the current brush, discarding the undone strokes that redo
   * could have restored.
   */
  fun startStroke(point: NormalizedPoint) {
    if (!_uiState.value.isOpen || _uiState.value.photo == null) return
    isDrawingStroke = true
    _uiState.update { state ->
      val strokes =
        state.activeStrokes + CutoutStroke(points = listOf(point), brushMode = state.brushMode)
      state.copy(strokes = strokes, activeStrokeCount = strokes.size)
    }
  }

  /** Adds [point] to the stroke being drawn. */
  fun addStrokePoint(point: NormalizedPoint) {
    if (!isDrawingStroke) return
    _uiState.update { state ->
      val lastStroke = state.strokes.lastOrNull() ?: return@update state
      state.copy(
        strokes = state.strokes.dropLast(1) + lastStroke.copy(points = lastStroke.points + point)
      )
    }
  }

  /** Finishes the stroke being drawn and segments the strokes again. */
  fun finishStroke() {
    if (!isDrawingStroke) return
    isDrawingStroke = false
    segmentActiveStrokes()
  }

  /** Adds a single-point stroke at [point] and segments the strokes again. */
  fun tap(point: NormalizedPoint) {
    startStroke(point)
    finishStroke()
  }

  /** Undoes the last applied stroke. */
  fun undo() {
    if (!_uiState.value.canUndo) return
    isDrawingStroke = false
    _uiState.update { it.copy(activeStrokeCount = it.activeStrokeCount - 1) }
    segmentActiveStrokes()
  }

  /** Redoes the last undone stroke. */
  fun redo() {
    if (!_uiState.value.canRedo) return
    isDrawingStroke = false
    _uiState.update { it.copy(activeStrokeCount = it.activeStrokeCount + 1) }
    segmentActiveStrokes()
  }

  /** Removes every stroke, clearing the selection. */
  fun clear() {
    isDrawingStroke = false
    _uiState.update { it.copy(strokes = emptyList(), activeStrokeCount = 0) }
    segmentActiveStrokes()
  }

  /** Goes back to the automatic cutout, dropping every stroke the user drew. */
  fun resetToAuto() {
    isDrawingStroke = false
    _uiState.update { it.copy(strokes = listOf(AUTO_CUTOUT_STROKE), activeStrokeCount = 1) }
    segmentActiveStrokes()
  }

  /** Hides the current message. */
  fun dismissMessage() {
    _uiState.update { it.copy(message = null) }
  }

  /** Marks the sticker as being regenerated after Done, which disables the editor's controls. */
  fun setApplying(isApplying: Boolean, message: CutoutEditorMessage? = null) {
    _uiState.update { it.copy(isApplying = isApplying, message = message ?: it.message) }
  }

  /**
   * Cuts the sticker out of the photo for the current selection, or returns `null` when there is no
   * finished selection or it is too small or too large to make a sticker.
   */
  suspend fun buildSticker(): Bitmap? {
    val segmentation = latestSegmentation ?: return null
    return withContext(imageDispatcher) {
      XrLanguageTeacherStickerImages.cutOut(
          source = segmentation.image,
          mask = segmentation.mask,
          polarity = StickerMaskPolarity.HIGH_IS_FOREGROUND,
        )
        ?.also {
          // Pre-bakes the outline so the card shows the edited sticker outlined right away.
          val unused = XrLanguageTeacherStickerImages.getOrPrepareOutlinedSticker(it)
        }
    }
  }

  private fun cancelSegmentation() {
    segmentationJob?.cancel()
    segmentationJob = null
    segmentationRequest++
    latestSegmentation = null
  }

  /**
   * Applies [transform] with [maskOverlay] as the new overlay, then recycles the replaced overlay
   * (as Scrapbook does), which the UI no longer draws.
   */
  private fun updateKeepingOverlay(
    maskOverlay: Bitmap?,
    transform: (CutoutEditorUiState) -> CutoutEditorUiState,
  ) {
    val previous = _uiState.value.maskOverlay
    _uiState.update { transform(it).copy(maskOverlay = maskOverlay) }
    if (previous != null && previous !== maskOverlay) {
      previous.recycle()
    }
  }

  /** Segments the applied strokes, replacing any segmentation still running. */
  private fun segmentActiveStrokes() {
    cancelSegmentation()
    val state = _uiState.value
    val photo = state.photo
    val strokes = state.activeStrokes.filter { it.points.isNotEmpty() }
    if (photo == null || strokes.isEmpty()) {
      updateKeepingOverlay(null) { it.copy(hasSelection = false, isSegmenting = false) }
      return
    }
    val request = segmentationRequest
    _uiState.update { it.copy(isSegmenting = true) }
    segmentationJob = scope.launch {
      val result = stickerMaker.segment(source = photo, strokes = strokes)
      // Builds the overlay and scans the mask off the main thread.
      val overlayAndSelection =
        if (result is StickerSegmentationResult.Success) {
          withContext(imageDispatcher) {
            XrLanguageTeacherStickerImages.buildMaskOverlay(result.mask, overlayColor) to
              result.mask.values.any { it >= XrLanguageTeacherStickerCutout.CONFIDENCE_THRESHOLD }
          }
        } else {
          null
        }
      if (request != segmentationRequest) {
        overlayAndSelection?.first?.recycle()
        return@launch
      }
      when (result) {
        is StickerSegmentationResult.Success -> {
          latestSegmentation = result
          val (overlay, hasSelection) = checkNotNull(overlayAndSelection)
          updateKeepingOverlay(overlay) {
            it.copy(
              hasSelection = hasSelection,
              isSegmenting = false,
              message =
                it.message.takeUnless { message ->
                  message == CutoutEditorMessage.SEGMENTER_UNAVAILABLE ||
                    message == CutoutEditorMessage.SEGMENTATION_FAILED ||
                    message == CutoutEditorMessage.SELECTION_REJECTED
                },
            )
          }
        }
        StickerSegmentationResult.Unavailable ->
          updateKeepingOverlay(null) {
            it.copy(
              hasSelection = false,
              isSegmenting = false,
              message = CutoutEditorMessage.SEGMENTER_UNAVAILABLE,
            )
          }
        StickerSegmentationResult.Failed,
        StickerSegmentationResult.Error ->
          updateKeepingOverlay(null) {
            it.copy(
              hasSelection = false,
              isSegmenting = false,
              message = CutoutEditorMessage.SEGMENTATION_FAILED,
            )
          }
      }
    }
  }
}
