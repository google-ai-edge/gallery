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
package com.google.ai.edge.gallery.customtasks.scrapbook

import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.ColorInt
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as GeoRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.Cutout
import com.google.ai.edge.gallery.proto.FillMode
import com.google.ai.edge.gallery.proto.Point
import com.google.ai.edge.gallery.proto.StrokePath as StrokePathProto
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeSet
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AGIISViewModel"
private const val FEATHER_KERNEL_SIZE = 2
private const val SAMPLE_TIME_MS = 20L
private const val GRID_PREVIEW_DEBOUNCE_TIME_MS = 70L

const val CUTOUT_COLLECTION_BASE_DIR = "___cutout_collection"

// Need to use non-data class because sometimes we update an instance of cutout info without
// updating its content (e.g. when applying edit with editing bitmap re-used).
class CutoutInfo(
  var cutout: Cutout,
  var originalBitmap: Bitmap? = null,
  var editingBitmap: Bitmap? = null,
  var bitmap: Bitmap? = null,
  var errorLoadingBitmap: Boolean = false,
  var lastEditing: Editing? = null,
  var borderBitmap: Bitmap? = null,
  var doodleBitmap: Bitmap? = null,
) {
  fun clone(cloneBitmaps: Boolean = false, genCutoutId: Boolean = false): CutoutInfo {
    return CutoutInfo(
      cutout =
        if (cloneBitmaps)
          cutout
            .toBuilder()
            .setId(if (genCutoutId) "${System.currentTimeMillis()}" else cutout.id)
            .build()
        else cutout,
      originalBitmap = if (cloneBitmaps) cloneBitmap(bitmap = originalBitmap) else originalBitmap,
      editingBitmap =
        if (cloneBitmaps) cloneBitmap(bitmap = editingBitmap, mutable = true) else editingBitmap,
      bitmap = if (cloneBitmaps) cloneBitmap(bitmap = bitmap) else bitmap,
      errorLoadingBitmap = errorLoadingBitmap,
      lastEditing = lastEditing,
      borderBitmap =
        if (cloneBitmaps) cloneBitmap(bitmap = borderBitmap, mutable = true) else borderBitmap,
      doodleBitmap =
        if (cloneBitmaps) cloneBitmap(bitmap = doodleBitmap, mutable = true) else doodleBitmap,
    )
  }

  fun cleanUp() {
    originalBitmap?.recycle()
    bitmap?.recycle()
    editingBitmap?.recycle()
    borderBitmap?.recycle()
    doodleBitmap?.recycle()
  }

  private fun cloneBitmap(bitmap: Bitmap?, mutable: Boolean = false): Bitmap? {
    if (bitmap == null) {
      return null
    }
    return bitmap.copy(Bitmap.Config.ARGB_8888, mutable)
  }
}

data class ItemTransformation(
  // Center X.
  val x: Float,
  // Center Y,
  val y: Float,
  // Scale.
  val scale: Float,
  // Rotation degree.
  val rotationDegree: Float,
)

// An item in collage editor.
data class CutoutCollageItem(
  val itemId: String,
  val cutoutInfo: CutoutInfo,
  val transform: ItemTransformation,
) {
  fun clone(): CutoutCollageItem {
    return CutoutCollageItem(itemId = itemId, cutoutInfo = cutoutInfo, transform = transform)
  }
}

data class SegmenterStroke(val points: List<Offset>, val brushMode: Stroke.BrushMode)

data class ScrapbookUiState(
  // The bitmap picked in the segmenter tab.
  val segmenterBitmap: Bitmap? = null,

  // The mask bitmap resulted from segmentation.
  val segmenterMaskBitmap: Bitmap? = null,

  // The user strokes in the segmenter tab.
  //
  // The inner list represents a stroke consisting of points, and the outer list represents all
  // strokes.
  val segmenterStrokes: List<SegmenterStroke> = listOf(),

  // The end index (exclusive) of the stroke to use for segmentation.
  val segmenterCurrentEndStrokeIndex: Int = 0,

  // The final bitmap from the collage editor tab.
  val collageEditorBitmap: Bitmap? = null,

  // Cutouts in "my cutouts".
  val cutoutInfos: List<CutoutInfo> = listOf(),

  // The temp cutout created after user taps on "create cutout" button on the segmentation page.
  val candidateCutoutInfo: CutoutInfo? = null,

  // Whether to show the candidate cutout editor.
  val showCandidateCutoutEditor: Boolean = false,

  // Whether in the process of creating the candidate cutout.
  val creatingCandidateCutout: Boolean = false,

  // Selected cutouts in cutout manager.
  val selectedCutoutIndices: TreeSet<Int> = TreeSet(),

  // Indicate whether a segmented object is being processed to be put into collection.
  val collectingCutout: Boolean = false,

  // Whether the editing is being applied in the editor.
  val applyingEdit: Boolean = false,

  // The bitmap for the grid preview.
  val gridPreview: Bitmap? = null,

  // Items added on top of the picture in the collage editor tab.
  val cutoutCollageItems: List<CutoutCollageItem> = listOf(),

  // The selected cutout item id.
  val selectedCutoutItemId: String = "",

  // Preview bitmap for brush.
  val brushPreviewBitmap: Bitmap? = null,
) {
  fun getCutoutInfoAtIndex(index: Int): CutoutInfo? {
    return when (index) {
      // -1 means the candidate cutout info from the cutout creation page.
      -1 -> candidateCutoutInfo
      else -> {
        if (index >= 0 && index < cutoutInfos.size) {
          cutoutInfos[index]
        } else {
          null
        }
      }
    }
  }
}

data class StrokePath(
  val points: MutableList<Offset>,
  val brushColor: Int,
  val brushSize: Float,
  val brushSoftness: Float,
  val blurType: BlurMaskFilter.Blur,
)

data class Editing(
  val index: Int,
  val rotationDegree: Int,
  val borderWidth: Int,
  val borderColor: Int,
  val fillColor: Int,
  val fillMode: FillMode,
  val strokePaths: List<StrokePath>,
  val boundingBoxColor: Int,
)

data class GridInfo(val columns: Int, val cellSize: Int, val cellPadding: Int, val type: GridType)

@HiltViewModel
class ScrapbookViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  val dataStoreRepository: DataStoreRepository,
) : ViewModel() {
  protected val _uiState = MutableStateFlow(ScrapbookUiState())
  val uiState = _uiState.asStateFlow()

  private val editingFlow = MutableSharedFlow<Editing>()
  private val gridPreviewFlow = MutableSharedFlow<GridInfo>()

  private var curMaskByteBuffer: ByteBuffer? = null
  private val loadingCutoutIds = mutableSetOf<String>()
  private val loadingCutoutMutex = Mutex()
  private val cutoutEditMutex = Mutex()

  init {
    // Load cutouts from data store.
    setCutouts(cutouts = dataStoreRepository.getAllCutouts())

    // Create base dir for cutout collection.
    val dir = File(context.getExternalFilesDir(null), CUTOUT_COLLECTION_BASE_DIR)
    if (!dir.exists()) {
      dir.mkdir()
    }

    // Collect from editing flow with sampling.
    viewModelScope.launch {
      editingFlow.sample(SAMPLE_TIME_MS).collect { editing -> applyCutoutEdits(editing = editing) }
    }

    // Collect from grid preview flow with debouncing.
    viewModelScope.launch {
      gridPreviewFlow.debounce(GRID_PREVIEW_DEBOUNCE_TIME_MS).collect { info ->
        createGridForSelectedCutouts(info = info)
      }
    }
  }

  fun resetSegmenterSession(model: Model, bitmap: Bitmap) {
    Log.d(TAG, "resetSegmenterSession...")
    viewModelScope.launch {
      val instance = model.instance
      if (instance != null) {
        withContext(Dispatchers.Default) {
          val segmenter = instance as InteractiveSegmenter
          val mpImage = BitmapImageBuilder(bitmap).build()
          segmenter.setImage(mpImage)
        }
      }
    }
  }

  fun segment(
    id: Int,
    model: Model,
    bitmap: Bitmap,
    picRect: GeoRect,
    @ColorInt overlayColor: Int,
    onResult: (id: Int, maskImage: Bitmap?) -> Unit,
  ) {
    Log.d(TAG, "Start segmenting...")
    viewModelScope.launch {
      // Get the relative offsets of the strokes within the picture.
      val relativeOffsets =
        _uiState.value.segmenterStrokes
          .subList(
            0,
            min(_uiState.value.segmenterCurrentEndStrokeIndex, _uiState.value.segmenterStrokes.size),
          )
          .map { (strokePoints, brushMode) ->
            val relativePoints =
              strokePoints
                .filter { picRect.contains(it) }
                .map { offset ->
                  val offsetXOnImage = offset.x - picRect.left
                  val offsetYOnImage = offset.y - picRect.top
                  val relativeX = offsetXOnImage / picRect.width
                  val relativeY = offsetYOnImage / picRect.height
                  NormalizedKeypoint.create(relativeX, relativeY)
                }
            Pair(relativePoints, brushMode)
          }

      if (relativeOffsets.isEmpty()) {
        Log.d(TAG, "No offsets given. Skip segmenting")
        onResult(id, null)
      } else {
        val instance = model.instance
        if (instance != null) {
          // Run segmenter around the given roi.
          val maskBitmap =
            withContext(Dispatchers.Default) {
              val segmenter = instance as InteractiveSegmenter

              val strokes =
                relativeOffsets
                  .filter { it.first.isNotEmpty() }
                  .mapIndexed { index, (points, brushMode) ->
                    Log.d(TAG, "Stroke[$index] - points: ${points.size}, brushMode: $brushMode")
                    Stroke.builder()
                      .setBrushMode(brushMode)
                      .setPoints(points)
                      .setCompleted(true)
                      .build()
                  }

              if (strokes.isEmpty()) {
                Log.d(TAG, "No valid strokes found to segment.")
                null
              } else {
                Log.d(TAG, "segment() called, strokes count: ${strokes.size}")
                val result = segmenter.segment(strokes)

                // Extract mask data.
                val buffer = ByteBufferExtractor.extract(result)
                val numPixels = bitmap.width * bitmap.height
                buffer.order(ByteOrder.nativeOrder())
                val floatBuffer = buffer.asFloatBuffer()

                // Bulk read into a primitive array for faster access.
                val floatArray = FloatArray(numPixels)
                floatBuffer.get(floatArray)

                val byteMaskArray = ByteArray(numPixels)
                val maskPixels = IntArray(numPixels)

                // Iterate over the arrays locally rather than directly accessing the direct buffer.
                for (i in maskPixels.indices) {
                  val isObject = floatArray[i] > 0.5f
                  byteMaskArray[i] = if (isObject) 1.toByte() else 0.toByte()
                  maskPixels[i] = if (isObject) Color.TRANSPARENT else overlayColor
                }

                // Bulk write the result back into a direct buffer.
                val byteMaskBuffer = ByteBuffer.allocateDirect(numPixels)
                byteMaskBuffer.put(byteMaskArray)
                byteMaskBuffer.rewind()
                curMaskByteBuffer = byteMaskBuffer
                val maskBitmap =
                  Bitmap.createBitmap(
                    maskPixels,
                    bitmap.width,
                    bitmap.height,
                    Bitmap.Config.ARGB_8888,
                  )
                maskBitmap
              }
            }
          Log.d(TAG, "Done segmenting")
          onResult(id, maskBitmap)
        }
      }
    }
  }

  fun addNewSegmenterStroke(brushMode: Stroke.BrushMode = Stroke.BrushMode.POSITIVE) {
    val newStrokes =
      _uiState.value.segmenterStrokes
        .toMutableList()
        // Truncate the stroke list.
        .subList(0, _uiState.value.segmenterCurrentEndStrokeIndex)
    newStrokes.add(SegmenterStroke(mutableListOf(), brushMode))
    _uiState.update {
      _uiState.value.copy(
        segmenterStrokes = newStrokes,
        segmenterCurrentEndStrokeIndex = newStrokes.size,
      )
    }
  }

  fun addSegmenterStrokePoint(offset: Offset) {
    val newStrokes = _uiState.value.segmenterStrokes.toMutableList()
    if (newStrokes.isNotEmpty()) {
      val (points, brushMode) = newStrokes.last()
      val newPoints = points.toMutableList()
      newPoints.add(offset)
      newStrokes[newStrokes.size - 1] = SegmenterStroke(newPoints, brushMode)
      _uiState.update { _uiState.value.copy(segmenterStrokes = newStrokes) }
    }
  }

  fun clearSegmenterStrokes() {
    _uiState.update {
      _uiState.value.copy(segmenterStrokes = listOf(), segmenterCurrentEndStrokeIndex = 0)
    }
  }

  fun setSegmenterMaskBitmap(maskBitmap: Bitmap?) {
    if (_uiState.value.segmenterMaskBitmap != maskBitmap) {
      _uiState.value.segmenterMaskBitmap?.recycle()
      _uiState.update { it.copy(segmenterMaskBitmap = maskBitmap) }
    }
  }

  fun undoStroke() {
    _uiState.update { currentState ->
      val newIndex = (currentState.segmenterCurrentEndStrokeIndex - 1).coerceAtLeast(0)
      currentState.copy(segmenterCurrentEndStrokeIndex = newIndex)
    }
  }

  fun redoStroke() {
    _uiState.update { currentState ->
      val newIndex =
        (currentState.segmenterCurrentEndStrokeIndex + 1).coerceAtMost(
          currentState.segmenterStrokes.size
        )
      currentState.copy(segmenterCurrentEndStrokeIndex = newIndex)
    }
  }

  fun saveSegmentedObjectToAlbum(bitmap: Bitmap, fileName: String, onDone: () -> Unit) {
    Log.d(TAG, "Start saving segmented object to album...")
    val maskBuffer = curMaskByteBuffer
    if (maskBuffer == null) {
      Log.d(TAG, "mask buffer is null")
      onDone()
      return
    }

    viewModelScope.launch {
      // Create the bitmap for the segmented object.
      val objectBitmap = extractSegmentedObject(originalBitmap = bitmap, maskBuffer = maskBuffer)

      // Save to album.
      saveBitmapToMediaStore(bitmap = objectBitmap, fileName = fileName)

      Log.d(TAG, "Done saving segmented object to album")
      onDone()
    }
  }

  fun saveBitmapToAlbum(bitmap: Bitmap, fileName: String, onDone: () -> Unit) {
    Log.d(TAG, "Start saving bitmap to album...")
    viewModelScope.launch {
      // Save to album.
      saveBitmapToMediaStore(bitmap = bitmap, fileName = fileName)

      Log.d(TAG, "Done saving bitmap to album")
      onDone()
    }
  }

  fun deleteCutouts(indices: Set<Int>, onDone: () -> Unit = {}) {
    Log.d(TAG, "Start deleting cutouts. Indices: $indices")
    viewModelScope.launch(Dispatchers.IO) {
      // Update state.
      val cutoutInfosToDelete = mutableListOf<CutoutInfo>()
      val cutoutIdsToDelete = mutableSetOf<String>()
      val newCutoutInfos = mutableListOf<CutoutInfo>()
      for ((index, cutoutInfo) in _uiState.value.cutoutInfos.withIndex()) {
        if (indices.contains(index)) {
          cutoutInfosToDelete.add(cutoutInfo)
          cutoutIdsToDelete.add(cutoutInfo.cutout.id)
          continue
        }
        newCutoutInfos.add(cutoutInfo)
      }
      _uiState.update {
        _uiState.value.copy(selectedCutoutIndices = TreeSet(), cutoutInfos = newCutoutInfos)
      }

      // Update data store.
      dataStoreRepository.setCutouts(newCutoutInfos.map { it.cutout })

      // Delete cutout items.
      for (cutoutItem in _uiState.value.cutoutCollageItems) {
        if (cutoutIdsToDelete.contains(cutoutItem.cutoutInfo.cutout.id)) {
          deleteCutoutCollageItem(itemId = cutoutItem.itemId)
        }
      }

      // Delete files and recycle bitmaps.
      for (cutoutInfo in cutoutInfosToDelete) {
        val cutout = cutoutInfo.cutout
        deleteFiles(
          files =
            listOf(getCutoutOriginalFile(id = cutout.id), getCutoutCurrentFile(id = cutout.id))
        )
        cutoutInfo.originalBitmap?.recycle()
        cutoutInfo.editingBitmap?.recycle()
        // We will let GC to take care this one because it might still being used by collage editor.
        // cutoutInfo.bitmap?.recycle()
      }

      Log.d(TAG, "Done deleting selected cutouts")
      onDone()
    }
  }

  fun saveCutoutsToAlbum(indices: Set<Int>, onDone: () -> Unit) {
    Log.d(TAG, "Start saving cutouts to album. Indices: $indices")
    viewModelScope.launch(Dispatchers.IO) {
      // Update state.
      for ((index, cutoutInfo) in _uiState.value.cutoutInfos.withIndex()) {
        if (indices.contains(index)) {
          val bitmap = cutoutInfo.bitmap
          if (bitmap == null) {
            Log.d(TAG, "Cutout bitmap is null. Skip saving to album")
            continue
          } else {
            saveBitmapToMediaStore(
              bitmap = bitmap,
              fileName = "edge_gallery_cutout_${System.currentTimeMillis()}.png",
            )
          }
        }
      }
      Log.d(TAG, "Done saving cutouts to album")
      onDone()
    }
  }

  fun cloneCutouts(indices: Set<Int>) {
    Log.d(TAG, "Start cloning cutouts. Indices: $indices")
    viewModelScope.launch(Dispatchers.IO) {
      // Update state.
      val newCutoutInfos = mutableListOf<CutoutInfo>()
      val newSelectedCutoutIndices = TreeSet<Int>()
      for ((index, cutoutInfo) in _uiState.value.cutoutInfos.withIndex()) {
        if (cutoutInfo.errorLoadingBitmap) {
          continue
        }

        newCutoutInfos.add(cutoutInfo)
        if (indices.contains(index)) {
          val clonedCutoutInfo = cutoutInfo.clone(cloneBitmaps = true, genCutoutId = true)
          newCutoutInfos.add(clonedCutoutInfo)
          newSelectedCutoutIndices.add(newCutoutInfos.size - 1)
        }
      }
      _uiState.update {
        _uiState.value.copy(
          selectedCutoutIndices = newSelectedCutoutIndices,
          cutoutInfos = newCutoutInfos,
        )
      }

      // Update data store.
      dataStoreRepository.setCutouts(newCutoutInfos.map { it.cutout })

      // Save to files.
      for (index in newSelectedCutoutIndices) {
        val cutoutInfo = newCutoutInfos[index]
        val id = cutoutInfo.cutout.id

        // Save original bitmap to file.
        val originalBitmap = cutoutInfo.originalBitmap ?: continue
        val originalFile = getCutoutOriginalFile(id = id)
        saveBitmapToFile(bitmap = originalBitmap, file = originalFile)

        // Save current bitmap to file.
        val currentBitmap = cutoutInfo.bitmap ?: continue
        val currentFile = getCutoutCurrentFile(id = id)
        saveBitmapToFile(bitmap = currentBitmap, file = currentFile)
      }

      Log.d(TAG, "Done cloning selected cutouts")
    }
  }

  fun toggleSelectedCutout(index: Int) {
    val newIndices = TreeSet(_uiState.value.selectedCutoutIndices)
    if (newIndices.contains(index)) {
      newIndices.remove(index)
    } else {
      newIndices.add(index)
    }
    _uiState.update { _uiState.value.copy(selectedCutoutIndices = newIndices) }
  }

  fun selectAllCutouts() {
    val newIndices: TreeSet<Int> = TreeSet()
    for (i in 0..<getCutoutCount()) {
      newIndices.add(i)
    }
    _uiState.update { _uiState.value.copy(selectedCutoutIndices = newIndices) }
  }

  fun deselectAllCutouts() {
    _uiState.update { _uiState.value.copy(selectedCutoutIndices = TreeSet()) }
  }

  fun loadCutoutBitmap(index: Int) {
    if (index < 0 || index > uiState.value.cutoutInfos.size - 1) {
      return
    }

    val cutoutInfo = uiState.value.cutoutInfos[index]
    val id = cutoutInfo.cutout.id
    viewModelScope.launch {
      var shouldStartLoading = false
      loadingCutoutMutex.withLock {
        val isLoading = loadingCutoutIds.contains(id)
        if (cutoutInfo.bitmap == null && !isLoading) {
          loadingCutoutIds.add(id)
          shouldStartLoading = true
        }
      }
      if (shouldStartLoading) {
        val bitmaps =
          loadBitmapFromFiles(listOf(getCutoutOriginalFile(id = id), getCutoutCurrentFile(id = id)))
        val originalBitmap = bitmaps[0]
        val currentBitmap = bitmaps[1]
        if (originalBitmap == null || currentBitmap == null) {
          updateCutoutBitmapLoadingError(index = index, hasError = true)
        } else {
          Log.d(TAG, "Loaded cutout bitmaps for $index")
          updateCutoutBitmaps(
            index = index,
            originalBitmap = originalBitmap,
            currentBitmap = currentBitmap,
          )
        }
        loadingCutoutMutex.withLock { loadingCutoutIds.remove(cutoutInfo.cutout.id) }
      }
    }
  }

  fun updateCutoutBitmaps(index: Int, originalBitmap: Bitmap, currentBitmap: Bitmap) {
    val newCutoutInfo = uiState.value.getCutoutInfoAtIndex(index = index)?.clone() ?: return
    newCutoutInfo.originalBitmap?.recycle()
    newCutoutInfo.originalBitmap = originalBitmap
    newCutoutInfo.bitmap?.recycle()
    newCutoutInfo.bitmap = currentBitmap

    if (index >= 0) {
      val newCutoutInfos = _uiState.value.cutoutInfos.toMutableList()
      newCutoutInfos[index] = newCutoutInfo
      _uiState.update { _uiState.value.copy(cutoutInfos = newCutoutInfos) }
    } else {
      _uiState.update { _uiState.value.copy(candidateCutoutInfo = newCutoutInfo) }
    }
  }

  fun updateCutoutCurrentBitmap(index: Int, currentBitmap: Bitmap) {
    val newCutoutInfo = uiState.value.getCutoutInfoAtIndex(index = index)?.clone() ?: return
    newCutoutInfo.bitmap?.recycle()
    newCutoutInfo.bitmap = currentBitmap

    if (index >= 0) {
      val newCutoutInfos = _uiState.value.cutoutInfos.toMutableList()
      newCutoutInfos[index] = newCutoutInfo
      _uiState.update { _uiState.value.copy(cutoutInfos = newCutoutInfos) }
    } else {
      _uiState.update { _uiState.value.copy(candidateCutoutInfo = newCutoutInfo) }
    }

    // Update cutout info in cutout items to make sure it is pointing to the latest bitmap.
    val cutoutId = newCutoutInfo.cutout.id
    val updatedCutoutItems =
      _uiState.value.cutoutCollageItems.map { item ->
        if (item.cutoutInfo.cutout.id == cutoutId) {
          item.copy(cutoutInfo = newCutoutInfo)
        } else {
          item
        }
      }
    _uiState.update { _uiState.value.copy(cutoutCollageItems = updatedCutoutItems) }
  }

  fun updateCutoutEditingBitmap(index: Int, editingBitmap: Bitmap?) {
    val newCutoutInfo = uiState.value.getCutoutInfoAtIndex(index = index)?.clone() ?: return
    if (newCutoutInfo.editingBitmap != editingBitmap) {
      newCutoutInfo.editingBitmap?.recycle()
      newCutoutInfo.editingBitmap = editingBitmap
    }
    // else {
    //   Log.d(TAG, "Editing bitmap unchanged. Skip updating cutout info's editing bitmap")
    // }

    if (index >= 0) {
      val newCutoutInfos = _uiState.value.cutoutInfos.toMutableList()
      newCutoutInfos[index] = newCutoutInfo
      _uiState.update { _uiState.value.copy(cutoutInfos = newCutoutInfos) }
    } else {
      _uiState.update { _uiState.value.copy(candidateCutoutInfo = newCutoutInfo) }
    }
  }

  fun updateCutoutBitmapLoadingError(index: Int, hasError: Boolean) {
    val newCutoutInfos = _uiState.value.cutoutInfos.toMutableList()
    if (index >= 0 && index <= newCutoutInfos.size - 1) {
      val newCutoutInfo = newCutoutInfos[index].clone()
      if (!newCutoutInfo.errorLoadingBitmap) {
        newCutoutInfo.errorLoadingBitmap = hasError
        newCutoutInfos[index] = newCutoutInfo
        _uiState.update { _uiState.value.copy(cutoutInfos = newCutoutInfos) }
      }
    }
  }

  fun updateCutout(index: Int, cutout: Cutout) {
    val newCutoutInfos = _uiState.value.cutoutInfos.toMutableList()
    if (index == -1) {
      _uiState.value.candidateCutoutInfo?.let {
        val newCandidateCutoutInfo = it.clone()
        newCandidateCutoutInfo.cutout = cutout
        _uiState.update { _uiState.value.copy(candidateCutoutInfo = newCandidateCutoutInfo) }
      }
    } else if (index >= 0 && index <= newCutoutInfos.size - 1) {
      val newCutoutInfo = newCutoutInfos[index].clone()
      newCutoutInfo.cutout = cutout
      newCutoutInfos[index] = newCutoutInfo
      _uiState.update { _uiState.value.copy(cutoutInfos = newCutoutInfos) }
    }
  }

  fun getCutoutCount(): Int {
    return uiState.value.cutoutInfos.size
  }

  fun queueEditing(editing: Editing) {
    viewModelScope.launch { editingFlow.emit(editing) }
  }

  fun queueGridPreview(info: GridInfo) {
    viewModelScope.launch { gridPreviewFlow.emit(info) }
  }

  suspend fun applyCutoutEdits(editing: Editing) {
    cutoutEditMutex.withLock {
      val index = editing.index
      val cutoutInfo = uiState.value.getCutoutInfoAtIndex(index = index) ?: return

      val originalBitmap = cutoutInfo.originalBitmap ?: return
      val width = originalBitmap.width
      val height = originalBitmap.height
      val rotationDegree = editing.rotationDegree

      // Log.d(TAG, "-------------\nStart applying edit")

      // Check if the editing bitmap needs to be re-created, i.e. it's size needs to be changed.
      //
      val lastRotationDegree = cutoutInfo.lastEditing?.rotationDegree
      val lastBorderWidth = cutoutInfo.lastEditing?.borderWidth
      val needToRecreateEditingBitmap =
        lastRotationDegree != editing.rotationDegree || lastBorderWidth != editing.borderWidth
      var editingBitmap = cutoutInfo.editingBitmap
      var doodleBitmapRecreated = false
      if (cutoutInfo.editingBitmap == null || needToRecreateEditingBitmap) {
        ////////////////////////////////////////////////////////////////////////////////////////////
        // Calculate the new size of the bitmap.

        // Calculate the transformation Matrix
        val matrix = Matrix()
        matrix.postRotate(rotationDegree.toFloat())

        // Determine the tightest bounding box dimensions
        //
        // Get the corners of the original bitmap: (0,0), (w,0), (0,h), (w,h)
        val sourcePoints =
          floatArrayOf(
            0f,
            0f,
            width.toFloat(),
            0f,
            0f,
            height.toFloat(),
            width.toFloat(),
            height.toFloat(),
          )

        // Apply the rotation matrix to the corner points
        val destinationPoints = FloatArray(8)
        matrix.mapPoints(destinationPoints, sourcePoints)

        // Find the min/max x and y coordinates of the rotated corners
        var minX = destinationPoints[0]
        var maxX = destinationPoints[0]
        var minY = destinationPoints[1]
        var maxY = destinationPoints[1]

        for (i in 2 until 8 step 2) {
          minX = minX.coerceAtMost(destinationPoints[i])
          maxX = maxX.coerceAtLeast(destinationPoints[i])
          minY = minY.coerceAtMost(destinationPoints[i + 1])
          maxY = maxY.coerceAtLeast(destinationPoints[i + 1])
        }

        // Account for border width.
        val borderWidth = editing.borderWidth
        val borderWidthPadding = borderWidth * 1.5f
        minX -= borderWidthPadding
        maxX += borderWidthPadding
        minY -= borderWidthPadding
        maxY += borderWidthPadding

        // Calculate the new required width and height.
        val newWidth = (maxX - minX).toInt()
        val newHeight = (maxY - minY).toInt()

        // Log.d(TAG, "Final bitmap size: $newWidth x $newHeight")

        ////////////////////////////////////////////////////////////////////////////////////////////
        // Setup new bitmaps.

        editingBitmap = createBitmap(newWidth, newHeight)

        // Doodle map.
        // Log.d(TAG, "Re-creating doodle bitmap")
        cutoutInfo.doodleBitmap?.recycle()
        cutoutInfo.doodleBitmap = createBitmap(newWidth, newHeight)
        doodleBitmapRecreated = true
      } else {
        // Log.d(TAG, "Re-using cutout info editing bitmap")
      }

      if (editingBitmap == null) {
        // Log.w(TAG, "editing bitmap is null. This shouldn't happen.")
        return
      }

      // Create a canvas to draw into the editing bitmap.
      val canvas = Canvas(editingBitmap)
      // Clear it.
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
      val newWidth = editingBitmap.width
      val newHeight = editingBitmap.height

      //////////////////////////////////////////////////////////////////////////////////////////////
      // Calculate the matrix to transform the original bitmap to the final bitmap.

      // Setup the Matrix to center and rotate
      val editMatrix = Matrix()

      // Move the original bitmap's center to the new canvas's center
      val centerX = width / 2f
      val centerY = height / 2f

      // Calculate the translation required to move the center point (w/2, h/2) to the canvas
      // center
      val translateX = newWidth / 2 - centerX
      val translateY = newHeight / 2 - centerY
      editMatrix.postTranslate(translateX, translateY)

      // Rotate around the center of the original image's coordinates.
      editMatrix.postRotate(rotationDegree.toFloat(), newWidth / 2f, newHeight / 2f)

      //////////////////////////////////////////////////////////////////////////////////////////////
      // Draw the border using dilation/offset technique

      val borderWidth = editing.borderWidth
      if (borderWidth > 0) {
        // The matrix that transform the borderBitmap correctly to the final position.
        val borderEditMatrix = Matrix()
        val translateX = newWidth / 2 - centerX - borderWidth
        val translateY = newHeight / 2 - centerY - borderWidth
        borderEditMatrix.postTranslate(translateX, translateY)
        borderEditMatrix.postRotate(rotationDegree.toFloat(), newWidth / 2f, newHeight / 2f)

        // Check if the border bitmap needs to be re-rendered or not.
        val lastBorderWidth = cutoutInfo.lastEditing?.borderWidth ?: DEFAULT_BORDER_WIDTH
        val lastBorderColor = cutoutInfo.lastEditing?.borderColor ?: DEFAULT_BORDER_COLOR.toArgb()
        val needToRerenderBorder =
          lastBorderWidth != borderWidth || lastBorderColor != editing.borderColor
        val borderBitmap = cutoutInfo.borderBitmap
        if (needToRerenderBorder || borderBitmap == null) {
          // Log.d(TAG, "Recreating border bitmap")

          val solidBorderColorBitmap = createBitmap(width, height)
          val solidBorderColorCanvas = Canvas(solidBorderColorBitmap)
          solidBorderColorCanvas.drawColor(editing.borderColor)

          // Use DST_IN to only keep the area where the original bitmap has non-0 alpha.
          val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
          solidBorderColorCanvas.drawBitmap(originalBitmap, Matrix.IDENTITY_MATRIX, maskPaint)

          // Define the offsets for 8 directions (1 pixel step)
          val offsets =
            arrayOf(
              -1f to 0f,
              1f to 0f,
              0f to -1f,
              0f to 1f, // Cardinal
              -1f to -1f,
              1f to -1f,
              -1f to 1f,
              1f to 1f, // Diagonal
            )

          // Draw the border using dilation/offset technique
          val borderBitmap = createBitmap(newWidth, newHeight)
          val borderCanvas = Canvas(borderBitmap)
          for (i in 1..borderWidth) {
            // Draw the colored cutout image for the current dilation step
            for ((dx, dy) in offsets) {
              val borderMatrix = Matrix()
              // Offset the matrix by the current dilation step
              borderMatrix.postTranslate(dx * i + borderWidth, dy * i + borderWidth)
              borderCanvas.drawBitmap(solidBorderColorBitmap, borderMatrix, null)
            }
          }
          canvas.drawBitmap(borderBitmap, borderEditMatrix, null)
          cutoutInfo.borderBitmap?.recycle()
          cutoutInfo.borderBitmap = borderBitmap
        } else {
          // Log.d(TAG, "Re-using border bitmap")
          canvas.drawBitmap(borderBitmap, borderEditMatrix, null)
        }
      }

      // Draw the rotated Bitmap onto the canvas.
      if (editing.fillMode != FillMode.FILL_MODE_SOLID) {
        // Log.d(TAG, "Drawing original bitmap")
        canvas.drawBitmap(originalBitmap, editMatrix, null)
      }

      // Colorize.
      if (editing.fillMode != FillMode.FILL_MODE_DISABLED) {
        // Log.d(TAG, "Drawing colorized bitmap")
        val colorizedBitmap = createBitmap(width, height)
        try {
          val colorizedCanvas = Canvas(colorizedBitmap)
          colorizedCanvas.drawColor(editing.fillColor)
          val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
          colorizedCanvas.drawBitmap(originalBitmap, Matrix.IDENTITY_MATRIX, maskPaint)

          if (editing.fillMode == FillMode.FILL_MODE_COLORIZE) {
            val colorizePaint = Paint().apply { blendMode = BlendMode.COLOR }
            canvas.drawBitmap(colorizedBitmap, editMatrix, colorizePaint)
          } else if (editing.fillMode == FillMode.FILL_MODE_SOLID) {
            canvas.drawBitmap(colorizedBitmap, editMatrix, null)
          }
        } finally {
          colorizedBitmap.recycle()
        }
      }

      // Draw Doodle Strokes (Final Step)
      //
      val strokePaths = editing.strokePaths
      if (strokePaths.isNotEmpty()) {
        // Check if the doodle bitmap needs to be re-rendered or not.
        val lastStrokePaths = cutoutInfo.lastEditing?.strokePaths
        val needToRerenderDoodle = lastStrokePaths != strokePaths
        val doodleBitmap = cutoutInfo.doodleBitmap
        if (needToRerenderDoodle || doodleBitmap == null || doodleBitmapRecreated) {
          // Log.d(TAG, "Re-rendering doodle bitmap")

          if (doodleBitmap == null) {
            // Log.d(TAG, "Re-creating doodle bitmap")
            cutoutInfo.doodleBitmap?.recycle()
            cutoutInfo.doodleBitmap = createBitmap(newWidth, newHeight)
          }

          cutoutInfo.doodleBitmap?.let { curDoodleBitmap ->
            val doodleBitmapCanvas = Canvas(curDoodleBitmap)
            doodleBitmapCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Iterate through each StrokePath
            for (strokePath in strokePaths) {
              // Define the Paint for drawing the strokes. Customize color, width, etc.
              val doodlePaint =
                Paint().apply {
                  color = strokePath.brushColor
                  style = Paint.Style.STROKE
                  strokeWidth = strokePath.brushSize
                  isAntiAlias = true
                  strokeCap = Paint.Cap.ROUND
                  strokeJoin = Paint.Join.ROUND
                }
              if (strokePath.brushSoftness > 0f) {
                doodlePaint.maskFilter =
                  BlurMaskFilter(
                    strokePath.brushSoftness * strokePath.brushSize,
                    strokePath.blurType,
                  )
              }

              if (strokePath.points.isNotEmpty()) {
                val path = Path()
                var isFirstPoint = true

                // point is relative to the center of the image.
                for (point in strokePath.points) {
                  val x = point.x
                  val y = point.y

                  if (isFirstPoint) {
                    path.moveTo(x, y)
                    isFirstPoint = false
                  } else {
                    path.lineTo(x, y)
                  }
                }

                // Transform and render.
                path.transform(editMatrix)
                doodleBitmapCanvas.drawPath(path, doodlePaint)
              }
            }
          }
        }

        doodleBitmap?.let { curDoodleBitmap ->
          canvas.drawBitmap(curDoodleBitmap, Matrix.IDENTITY_MATRIX, null)
        }
      }

      // Draw bounding box.
      if (editing.boundingBoxColor != Color.TRANSPARENT) {
        val boundingBoxPaint =
          Paint().apply {
            color = editing.boundingBoxColor
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
          }

        canvas.drawRect(
          0f,
          0f,
          editingBitmap.width.toFloat(),
          editingBitmap.height.toFloat(),
          boundingBoxPaint,
        )
      }

      // Update cutout's bitmap.
      cutoutInfo.lastEditing = editing

      // Update state.
      updateCutoutEditingBitmap(index = index, editingBitmap = editingBitmap)
    }
  }

  fun saveEditing(editing: Editing, onDone: () -> Unit) {
    val index = editing.index

    Log.d(TAG, "Start saving edits...")
    viewModelScope.launch {
      // Apply edit without rendering the bounding box.
      applyCutoutEdits(editing = editing)

      cutoutEditMutex.withLock {
        val cutoutInfo = uiState.value.getCutoutInfoAtIndex(index = index) ?: return@launch
        val id = cutoutInfo.cutout.id
        val editingBitmap = cutoutInfo.editingBitmap
        if (editingBitmap == null) {
          Log.d(TAG, "Nothing to save. Done")
          onDone()
          return@launch
        }

        if (index == -1) {
          setCollectinCutouts(collecting = true)
        }

        // Update storage on disk for the editing file.
        val file = getCutoutCurrentFile(id = id)
        if (file.exists()) {
          file.delete()
        }
        saveBitmapToFile(bitmap = editingBitmap, file = file)

        // For cutout candidate, also update storage on disk for the original file because it was
        // not created when the cutout was initially created..
        if (index == -1) {
          cutoutInfo.originalBitmap?.let { originalBitmap ->
            val originalFile = getCutoutOriginalFile(id = id)
            if (originalFile.exists()) {
              originalFile.delete()
            }
            saveBitmapToFile(bitmap = originalBitmap, file = originalFile)
          }
        }

        // Update data store with the editing.
        val strokePaths =
          editing.strokePaths.map { (points, brushColor, brushSize, brushSoftness, blurType) ->
            StrokePathProto.newBuilder()
              .setBrushColor(brushColor)
              .setBrushSize(brushSize)
              .setBrushSoftness(brushSoftness)
              .setBlurType(blurType.ordinal)
              .clearPoint()
              .addAllPoint(
                points.map { curPoint ->
                  Point.newBuilder().setX(curPoint.x).setY(curPoint.y).build()
                }
              )
              .build()
          }

        val newCutout =
          cutoutInfo.cutout
            .toBuilder()
            .setRotationDegree(editing.rotationDegree)
            .setBorderWidth(editing.borderWidth)
            .setBorderColor(editing.borderColor)
            .setFillColor(editing.fillColor)
            .setFillMode(editing.fillMode)
            .clearDoodleStroke()
            .addAllDoodleStroke(strokePaths)
            .build()
        if (index == -1) {
          // Save candidate cutout to cutout collection.
          dataStoreRepository.addCutout(cutout = newCutout)
        } else {
          // Update cutout in the collection.
          dataStoreRepository.setCutout(newCutout = newCutout)
        }

        // Update state.
        //
        // Use the editing bitmap as the current bitmap.
        val editingBitmapCopy = editingBitmap.copy(Bitmap.Config.ARGB_8888, false)
        updateCutoutCurrentBitmap(index = index, currentBitmap = editingBitmapCopy)
        if (index == -1) {
          // Deep clone the candidate cutout info and add it to the collection.
          uiState.value.candidateCutoutInfo?.let {
            val clonedCutoutInfo = it.clone(cloneBitmaps = true)
            clonedCutoutInfo.cutout = newCutout
            addCutout(cutoutInfo = clonedCutoutInfo)
          }
        } else {
          updateCutout(index = index, cutout = newCutout)
        }

        // Clean up editing bitmap.
        updateCutoutEditingBitmap(index = index, editingBitmap = null)

        if (index == -1) {
          setCollectinCutouts(collecting = false)
        }

        cutoutInfo.lastEditing = null

        Log.d(TAG, "Done saving edits")
        onDone()
      }
    }
  }

  fun revertCutoutToOriginal(index: Int) {
    val cutoutInfo = uiState.value.getCutoutInfoAtIndex(index = index) ?: return

    Log.d(TAG, "Start reverting cutout $index")
    viewModelScope.launch {
      val originalBitmap = cutoutInfo.originalBitmap ?: return@launch
      val id = cutoutInfo.cutout.id

      // Update bitmap state.
      val originalBitmapCopy = originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
      updateCutoutEditingBitmap(index = index, editingBitmap = null)
      updateCutoutCurrentBitmap(index = index, currentBitmap = originalBitmapCopy)

      // Create a new cutout with the same id to essentially clear all edits.
      val newCutout = Cutout.newBuilder().setId(id).build()

      // Update state.
      updateCutout(index = index, cutout = newCutout)

      if (index >= 0) {
        // Update storage on disk.
        val file = getCutoutCurrentFile(id = id)
        if (file.exists()) {
          file.delete()
        }
        saveBitmapToFile(bitmap = originalBitmap, file = file)

        // Update data store.
        dataStoreRepository.setCutout(newCutout = newCutout)
      }

      Log.d(TAG, "Done reverting")
    }
  }

  fun createGridForSelectedCutouts(info: GridInfo) {
    val bitmaps: List<Bitmap> =
      uiState.value.selectedCutoutIndices.mapNotNull { uiState.value.cutoutInfos[it].bitmap }

    if (bitmaps.isEmpty()) {
      // Return an empty, transparent bitmap if the list is empty
      updateCollagePreview(bitmap = createBitmap(1, 1))
      return
    }

    // Calculate the final dimensions
    val columns = info.columns
    val cellSize = info.cellSize + info.cellPadding * 2
    val cellPadding = info.cellPadding
    val rows = ceil(bitmaps.size.toDouble() / columns).toInt()
    val type = info.type
    val finalWidth = if (type == GridType.Vertical) columns * cellSize else rows * cellSize
    val finalHeight = if (type == GridType.Vertical) rows * cellSize else columns * cellSize

    // Create the final output Bitmap
    val outputBitmap = createBitmap(finalWidth, finalHeight)
    val canvas = Canvas(outputBitmap)

    // Iterate and draw each bitmap
    for ((index, bitmap) in bitmaps.withIndex()) {
      val col = if (type == GridType.Vertical) index % columns else index / columns
      val row = if (type == GridType.Vertical) index / columns else index % columns

      // Calculate cell boundaries
      val cellLeft = col * cellSize
      val cellTop = row * cellSize

      // Determine scaling and centering
      val srcWidth = bitmap.width.toFloat()
      val srcHeight = bitmap.height.toFloat()

      // Calculate the ratio needed to fit the largest dimension into the cell,
      // but only if it's larger than the cell.
      val scaleFactor =
        min(
          1f, // Max scale is 1.0 (no upscaling)
          min(
            (cellSize - cellPadding * 2) / srcWidth,
            (cellSize - cellPadding * 2) / srcHeight,
          ), // Ratio to fit in cell
        )

      val scaledWidth = srcWidth * scaleFactor
      val scaledHeight = srcHeight * scaleFactor

      // Calculate offsets to center the scaled bitmap within the cell
      val offsetX = (cellSize - scaledWidth) / 2f
      val offsetY = (cellSize - scaledHeight) / 2f

      // Define drawing rectangles
      // Source Rect: The whole source bitmap
      val srcRect = Rect(0, 0, bitmap.width, bitmap.height)

      // Destination RectF: Where to draw on the canvas
      val dstRect =
        RectF(
          cellLeft + offsetX, // Left
          cellTop + offsetY, // Top
          cellLeft + offsetX + scaledWidth, // Right
          cellTop + offsetY + scaledHeight, // Bottom
        )

      // Draw the bitmap to the canvas
      canvas.drawBitmap(bitmap, srcRect, dstRect, null)
    }

    updateCollagePreview(bitmap = outputBitmap)
  }

  fun generateAndUpdateBrushPreview(
    width: Int,
    height: Int,
    @ColorInt brushColor: Int,
    brushSize: Float,
    softness: Float,
    blurType: BlurMaskFilter.Blur,
  ) {
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    val paint =
      Paint().apply {
        color = brushColor
        style = Paint.Style.STROKE
        strokeWidth = brushSize
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
      }
    val blur = brushSize * softness
    if (blur > 0) {
      paint.maskFilter = BlurMaskFilter(blur, blurType)
    }

    val path = Path()
    path.moveTo(width * 0.2f, height * 0.5f)
    path.cubicTo(
      .48f * width,
      1.29f * height,
      .58f * width,
      -0.39f * height,
      width * 0.8f,
      height * 0.5f,
    )
    canvas.drawPath(path, paint)
    updateBrushPreview(bitmap = bitmap)
  }

  fun shareBitmap(context: Context, bitmap: Bitmap, fileName: String) {
    viewModelScope.launch(Dispatchers.IO) {
      // Ensure the images cache directory exists.
      val cachePath = File(context.cacheDir, "images")
      cachePath.mkdirs()

      val tempFile = File(cachePath, fileName)

      try {
        // Save Bitmap to a temporary file
        FileOutputStream(tempFile).use { outputStream ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        }

        // Get the content URI using FileProvider
        val contentUri =
          FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider" /* {applicationId}.provider */,
            tempFile,
          )

        // Create the Intent
        val shareIntent =
          Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = "image/png"
            clipData = ClipData.newRawUri("", contentUri)
          }

        // Launch the system chooser dialog
        context.startActivity(Intent.createChooser(shareIntent, "Share Cutout via"))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to share", e)
      }
    }
  }

  fun createAndUpdateCandidateCutoutInfoFromSegmentedObject(bitmap: Bitmap) {
    val maskBuffer = curMaskByteBuffer ?: return

    Log.d(TAG, "Creating cutout info from segmented object...")
    viewModelScope.launch {
      // Set "creating" to true after a short delay.
      val showLoadingJob = launch {
        delay(300)
        setCreatingCandidateCutout(creating = true)
      }

      // Create the bitmap for the segmented object.
      val objectBitmap = extractSegmentedObject(originalBitmap = bitmap, maskBuffer = maskBuffer)

      // Create a temp cutout.
      val id = "${System.currentTimeMillis()}"
      val curBitmap = objectBitmap.copy(Bitmap.Config.ARGB_8888, false)
      // We need to explicitly set the fill mode to disabled because the proto default
      // is FILL_MODE_UNSPECIFIED, which is not what we want.
      val cutout = Cutout.newBuilder().setId(id).setFillMode(FillMode.FILL_MODE_DISABLED).build()
      val cutoutInfo =
        CutoutInfo(cutout = cutout, bitmap = curBitmap, originalBitmap = objectBitmap)

      // Set state.
      setCandidateCutoutInfo(cutoutInfo = cutoutInfo)
      setShowCandidateCutoutEditor(show = true)

      // Set creating status to false after a short delay to account for the enter animation of
      // the editor.
      showLoadingJob.cancel()
      delay(200)
      setCreatingCandidateCutout(creating = false)
      Log.d(TAG, "Done creating candidate cutout info.")
    }
  }

  fun saveCollageEditorBitmapToAlbum(size: Size, onDone: () -> Unit) {
    Log.d(TAG, "Start saving collage editor bitmap to album...")

    viewModelScope.launch {
      val bitmap = createCollageEditorBitmap(size = size) ?: return@launch
      saveBitmapToMediaStore(
        bitmap = bitmap,
        fileName = "edge_gallery_image_with_cutouts_${System.currentTimeMillis()}.png",
      )
      Log.d(TAG, "Done saving collage editor bitmap to album")
      onDone()
    }
  }

  fun shareCollageEditorBitmap(size: Size, context: Context, onDone: () -> Unit) {
    Log.d(TAG, "Start sharing collage editor bitmap...")

    viewModelScope.launch {
      val bitmap = createCollageEditorBitmap(size = size) ?: return@launch
      shareBitmap(
        context = context,
        bitmap = bitmap,
        fileName = "edge_gallery_image_with_cutouts_${System.currentTimeMillis()}.png",
      )
      Log.d(TAG, "Done sharing")
      onDone()
    }
  }

  fun addSelectedCutoutCollageItem(cutoutInfo: CutoutInfo, centerX: Float, centerY: Float) {
    Log.d(TAG, "Adding cutout item to $centerX,$centerY")
    val newCutoutItems = _uiState.value.cutoutCollageItems.toMutableList()
    val newCutoutCollageItem =
      CutoutCollageItem(
        itemId = "${System.currentTimeMillis()}",
        cutoutInfo = cutoutInfo,
        transform = ItemTransformation(x = centerX, y = centerY, scale = 1f, rotationDegree = 0f),
      )
    newCutoutItems.add(newCutoutCollageItem)
    _uiState.update {
      _uiState.value.copy(
        cutoutCollageItems = newCutoutItems,
        selectedCutoutItemId = newCutoutCollageItem.itemId,
      )
    }
  }

  fun updateCutoutCollageItemTransformation(itemId: String, transform: ItemTransformation) {
    val newCutoutItems = _uiState.value.cutoutCollageItems.toMutableList()
    val cutoutItem =
      newCutoutItems.firstOrNull { it.itemId == itemId }
        ?: run {
          Log.e(TAG, "Cutout item not found: $itemId")
          return@updateCutoutCollageItemTransformation
        }
    val newCutoutItem = cutoutItem.copy(transform = transform)
    newCutoutItems[newCutoutItems.indexOf(cutoutItem)] = newCutoutItem
    _uiState.update { _uiState.value.copy(cutoutCollageItems = newCutoutItems) }
  }

  fun deleteCutoutCollageItem(itemId: String) {
    val newCutoutItems = _uiState.value.cutoutCollageItems.toMutableList()
    val index = newCutoutItems.indexOfFirst { it.itemId == itemId }
    if (index >= 0) {
      newCutoutItems.removeAt(index)

      // Select the next cutout item or the new last cutout item if this is the last cutout
      // item.
      val nextSelectedItemId =
        if (index < newCutoutItems.size) {
          newCutoutItems[index].itemId
        } else if (newCutoutItems.isNotEmpty()) {
          newCutoutItems.last().itemId
        } else {
          ""
        }
      _uiState.update {
        _uiState.value.copy(
          cutoutCollageItems = newCutoutItems,
          selectedCutoutItemId = nextSelectedItemId,
        )
      }
    } else {
      Log.e(TAG, "Cutout item not found: $itemId")
    }
  }

  fun cloneCutoutCollageItem(itemId: String) {
    val newCutoutItems = _uiState.value.cutoutCollageItems.toMutableList()
    val cutoutItem =
      newCutoutItems.firstOrNull { it.itemId == itemId }
        ?: run {
          Log.e(TAG, "Cutout item not found: $itemId")
          return@cloneCutoutCollageItem
        }
    val newCutoutItem =
      cutoutItem.copy(
        itemId = "${System.currentTimeMillis()}",
        transform =
          cutoutItem.transform.copy(
            x = cutoutItem.transform.x + 64,
            y = cutoutItem.transform.y + 64,
          ),
      )
    newCutoutItems.add(newCutoutItem)
    _uiState.update {
      _uiState.value.copy(
        cutoutCollageItems = newCutoutItems,
        selectedCutoutItemId = newCutoutItem.itemId,
      )
    }
  }

  fun setShowCandidateCutoutEditor(show: Boolean) {
    _uiState.update { _uiState.value.copy(showCandidateCutoutEditor = show) }
  }

  fun setCreatingCandidateCutout(creating: Boolean) {
    _uiState.update { _uiState.value.copy(creatingCandidateCutout = creating) }
  }

  fun setSelectedCutoutCollageItem(itemId: String) {
    _uiState.update { _uiState.value.copy(selectedCutoutItemId = itemId) }
  }

  fun setSegmenterBitmap(bitmap: Bitmap?) {
    _uiState.value.segmenterBitmap?.recycle()
    _uiState.update { _uiState.value.copy(segmenterBitmap = bitmap) }
  }

  fun setCollageEditorBitmap(bitmap: Bitmap) {
    _uiState.value.collageEditorBitmap?.recycle()
    _uiState.update {
      _uiState.value.copy(
        collageEditorBitmap = bitmap,
        cutoutCollageItems = listOf(),
        selectedCutoutItemId = "",
      )
    }
  }

  private fun setCutouts(cutouts: List<Cutout>) {
    _uiState.update { _uiState.value.copy(cutoutInfos = cutouts.map { CutoutInfo(cutout = it) }) }
  }

  private fun setCollectinCutouts(collecting: Boolean) {
    _uiState.update { _uiState.value.copy(collectingCutout = collecting) }
  }

  private fun setApplyingEditor(applyingEdit: Boolean) {
    _uiState.update { _uiState.value.copy(applyingEdit = applyingEdit) }
  }

  private fun updateCollagePreview(bitmap: Bitmap?) {
    _uiState.value.gridPreview?.recycle()
    _uiState.update { _uiState.value.copy(gridPreview = bitmap) }
  }

  private fun updateBrushPreview(bitmap: Bitmap?) {
    _uiState.value.brushPreviewBitmap?.recycle()
    _uiState.update { _uiState.value.copy(brushPreviewBitmap = bitmap) }
  }

  @VisibleForTesting
  internal fun setCandidateCutoutInfo(cutoutInfo: CutoutInfo?) {
    _uiState.value.candidateCutoutInfo?.cleanUp()
    _uiState.update { _uiState.value.copy(candidateCutoutInfo = cutoutInfo) }
  }

  @VisibleForTesting
  internal fun addCutout(cutoutInfo: CutoutInfo) {
    val newCutoutInfos = _uiState.value.cutoutInfos.toMutableList()
    newCutoutInfos.add(cutoutInfo)
    _uiState.update { _uiState.value.copy(cutoutInfos = newCutoutInfos) }
  }

  private suspend fun extractSegmentedObject(
    originalBitmap: Bitmap,
    maskBuffer: ByteBuffer,
    padding: Int = 10,
  ): Bitmap {
    return withContext(Dispatchers.Default) {
      maskBuffer.rewind()
      val width = originalBitmap.width
      val height = originalBitmap.height

      // Find the Bounding Box
      var minX = width
      var minY = height
      var maxX = 0
      var maxY = 0
      var objectFound = false

      for (y in 0 until height) {
        for (x in 0 until width) {
          val index = y * width + x
          val maskValue = maskBuffer.get(index).toInt() and 0xFF

          // Mask value of non-0 means it's part of the object.
          if (maskValue != 0) {
            objectFound = true
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
          }
        }
      }

      // If no object is found, return an empty 1x1 bitmap.
      if (!objectFound) {
        // Return a 1x1 fully transparent bitmap
        return@withContext createBitmap(1, 1)
      }

      // Calculate padded coordinates, clamped to the bounds of the original image
      val finalMinX = max(0, minX - padding)
      val finalMinY = max(0, minY - padding)
      val finalMaxX = min(width - 1, maxX + padding)
      val finalMaxY = min(height - 1, maxY + padding)

      val croppedWidth = finalMaxX - finalMinX + 1
      val croppedHeight = finalMaxY - finalMinY + 1

      // Create the destination Bitmap
      val resultBitmap = createBitmap(croppedWidth, croppedHeight)

      // Crop and Mask the Pixels
      for (y in 0 until croppedHeight) {
        for (x in 0 until croppedWidth) {

          // Calculate coordinates in the original bitmap
          val originalX = finalMinX + x
          val originalY = finalMinY + y

          var totalMaskValue: Int = 0
          var samplesCount: Int = 0

          // Iterate over the kernel
          for (ky in -FEATHER_KERNEL_SIZE..FEATHER_KERNEL_SIZE) {
            for (kx in -FEATHER_KERNEL_SIZE..FEATHER_KERNEL_SIZE) {
              val sampleX = originalX + kx
              val sampleY = originalY + ky

              // Check bounds to ensure the sample is within the original image
              if (sampleX in 0..<width && sampleY in 0..<height) {
                val sampleIndex = sampleY * width + sampleX

                // Read the mask value (0 = background, non-0 = object)
                val maskValue = if (maskBuffer.get(sampleIndex).toInt() == 0) 1 else 0

                totalMaskValue += maskValue
                samplesCount++
              }
            }
          }

          // Calculate the average mask value for the kernel
          // This average will be a float between 0 (fully object) and 1 (fully background)
          val averageMaskRatio: Float =
            if (samplesCount > 0) totalMaskValue.toFloat() / samplesCount else 1f

          // Get the original pixel color
          val originalColor = originalBitmap[originalX, originalY]

          // Calculate the new alpha value (0 to 255)
          // 1 - ratio: so 0.0 (object) -> 1.0 (opaque), 1.0 (background) -> 0.0 (transparent)
          val finalAlphaRatio = 1.0f - averageMaskRatio
          val finalAlpha = (255 * finalAlphaRatio).toInt().coerceIn(0, 255)

          // Apply the new alpha to the original RGB color
          val featheredColor =
            Color.argb(
              finalAlpha,
              Color.red(originalColor),
              Color.green(originalColor),
              Color.blue(originalColor),
            )

          // Set the feathered color to the result bitmap
          resultBitmap[x, y] = featheredColor
        }
      }

      resultBitmap
    }
  }

  private suspend fun saveBitmapToMediaStore(bitmap: Bitmap, fileName: String) {
    withContext(Dispatchers.IO) {
      val resolver: ContentResolver = context.contentResolver
      val imageCollection: Uri =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

      // Prepare file metadata
      val contentValues =
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
          put(MediaStore.Images.Media.MIME_TYPE, "image/png")
          put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
          put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }

      var imageUri: Uri? = null
      var outputStream: OutputStream? = null

      try {
        // Insert metadata into MediaStore and get the file URI
        imageUri = resolver.insert(imageCollection, contentValues) ?: return@withContext

        // Open the stream and write the bitmap data
        outputStream = resolver.openOutputStream(imageUri)
        if (outputStream != null) {
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
          // success
        } else {
          // Should not happen, but handle case where stream is null
          resolver.delete(imageUri, null, null)
        }
      } catch (e: Exception) {
        e.printStackTrace()
        // Cleanup on error
        imageUri?.let { resolver.delete(it, null, null) }
      } finally {
        outputStream?.close()
      }
    }
  }

  private suspend fun createCollageEditorBitmap(size: Size): Bitmap? {
    return withContext(Dispatchers.Default) {
      // Create the final bitmap
      val bgBitmap = uiState.value.collageEditorBitmap ?: return@withContext null
      val finalBitmap = createBitmap(size.width.toInt(), size.height.toInt())

      // Create the Canvas and Paint objects
      val canvas = Canvas(finalBitmap)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

      // Draw the background
      val srcRect = Rect(0, 0, bgBitmap.width, bgBitmap.height)
      val destRect = Rect(0, 0, size.width.toInt(), size.height.toInt())
      canvas.drawBitmap(bgBitmap, srcRect, destRect, paint)

      // Draw each cutout item with its transformation
      for (item in uiState.value.cutoutCollageItems) {
        val cutoutBitmap = item.cutoutInfo.bitmap
        val transform = item.transform

        // Check if the cutout has a drawable bitmap
        if (cutoutBitmap != null && !cutoutBitmap.isRecycled) {
          // Apply Transformations ---
          // Scale -> Rotate -> Translate to center -> Draw

          val matrix = Matrix()

          // Calculate the center of the cutout bitmap in its local coordinates.
          val cutoutCenterX = cutoutBitmap.width / 2f
          val cutoutCenterY = cutoutBitmap.height / 2f

          // Apply Scale
          matrix.postScale(transform.scale, transform.scale, cutoutCenterX, cutoutCenterY)

          // Apply Rotation
          // Rotate around the cutout's center.
          matrix.postRotate(transform.rotationDegree, cutoutCenterX, cutoutCenterY)

          // Apply Translation (Move the center of the transformed cutout)
          // The transformation moves the cutout's center from (cutoutCenterX, cutoutCenterY)
          // to the desired canvas position (transform.x, transform.y).
          matrix.postTranslate(transform.x - cutoutCenterX, transform.y - cutoutCenterY)

          // Draw the transformed cutout onto the canvas
          canvas.drawBitmap(cutoutBitmap, matrix, paint)
        }
      }
      finalBitmap
    }
  }

  private suspend fun saveBitmapToFile(bitmap: Bitmap, file: File) {
    withContext(Dispatchers.IO) {
      try {
        FileOutputStream(file).use { outputStream ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        }
      } catch (e: IOException) {
        Log.e(TAG, "Failed to save cutout bitmap to ${file.name}", e)
      }
    }
  }

  private suspend fun loadBitmapFromFiles(files: List<File>): List<Bitmap?> {
    return withContext(Dispatchers.IO) {
      val loadedBitmaps: MutableList<Bitmap?> = mutableListOf()
      for (file in files) {
        try {
          // Use BitmapFactory to decode the file path directly into a Bitmap
          val options =
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
          if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            loadedBitmaps.add(bitmap)
          } else {
            Log.w(TAG, "File not exist: ${file.absolutePath}")
            loadedBitmaps.add(null)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error loading file: '${file.absolutePath}'", e)
          loadedBitmaps.add(null)
        }
      }
      loadedBitmaps
    }
  }

  private suspend fun deleteFiles(files: List<File>) {
    withContext(Dispatchers.IO) {
      for (file in files) {
        try {
          if (file.exists()) {
            file.delete()
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to delete file: ${file.absolutePath}", e)
        }
      }
    }
  }

  private fun getCutoutOriginalFile(id: String): File {
    return File(
      context.getExternalFilesDir(null),
      "$CUTOUT_COLLECTION_BASE_DIR${File.separator}${id}_original.png",
    )
  }

  private fun getCutoutCurrentFile(id: String): File {
    return File(
      context.getExternalFilesDir(null),
      "$CUTOUT_COLLECTION_BASE_DIR${File.separator}${id}.png",
    )
  }
}
