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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import com.google.ai.edge.gallery.customtasks.common.segmentation.SegmenterFactory
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.AUTO_CUTOUT_STROKE
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.CutoutStroke
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.SegmentationMask
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.StickerMaskPolarity
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.XrLanguageTeacherStickerImages
import com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models.scaledSizeWithin
import com.google.ai.edge.gallery.di.DefaultDispatcher
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenter.Stroke
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AGTeacherStickerMaker"

/** Longest side, in pixels, of the photo the segmenter runs on. */
private const val SEGMENTER_INPUT_MAX_SIDE_PX = 512

/** The result of segmenting a photo with [XrLanguageTeacherStickerMaker.segment]. */
sealed interface StickerSegmentationResult {
  /**
   * The segmenter produced [mask] for [image], the downscaled copy of the photo it ran on (the same
   * size as [mask]). [image] belongs to the caller: later segmentations or closing the sticker
   * maker never recycle it.
   */
  class Success(val image: Bitmap, val mask: SegmentationMask) : StickerSegmentationResult

  /**
   * The segmentation model is not downloaded yet, the segmenter can't be created on this device, or
   * the sticker maker was closed. Trying again later may succeed.
   */
  data object Unavailable : StickerSegmentationResult

  /**
   * The segmenter threw while segmenting this photo (e.g. a native error). The segmenter was
   * released, so the next segmentation creates it again. The photo may segment on a later try.
   */
  data object Error : StickerSegmentationResult

  /**
   * The photo can't be segmented: there are no strokes, the photo can't be read, or the segmenter
   * returned no mask.
   */
  data object Failed : StickerSegmentationResult
}

/** The result of cutting a sticker out of a photo with [makeSticker]. */
sealed interface StickerCutoutResult {
  /** [sticker] is the cutout, with a transparent background. */
  class Success(val sticker: Bitmap) : StickerCutoutResult

  /** The segmenter ran, but did not isolate a foreground object (or failed on this photo). */
  data object NoForeground : StickerCutoutResult

  /** Segmentation is not available yet (see [StickerSegmentationResult.Unavailable]). */
  data object Unavailable : StickerCutoutResult

  /** The segmenter threw on this photo (see [StickerSegmentationResult.Error]). */
  data object Error : StickerCutoutResult
}

/**
 * On-device foreground segmenter for AR Language Teacher vocabulary stickers, backed by the "Magic
 * touch" MediaPipe Interactive Segmentation model.
 */
interface XrLanguageTeacherStickerMaker {
  /** Whether a segmentation model is set and the sticker maker is not closed. */
  val isAvailable: Boolean

  /**
   * Sets the downloaded `.task` model file to segment with, or `null` while it is not downloaded.
   * Changing or clearing the model releases the current segmenter.
   */
  fun setModelPath(modelPath: String?)

  /** Creates and warms up the segmenter in the background so the first segmentation is fast. */
  suspend fun prepare()

  /**
   * Segments [source] with [strokes] (normalized to [source]'s bounds) off the main thread. The
   * segmenter runs on a copy of [source] downscaled to at most 512px on its longest side.
   */
  suspend fun segment(source: Bitmap, strokes: List<CutoutStroke>): StickerSegmentationResult

  /** Releases the segmenter. The sticker maker is unavailable afterwards. */
  fun close()

  companion object {
    /** A sticker maker that is never available, for screens and tests without segmentation. */
    val UNAVAILABLE: XrLanguageTeacherStickerMaker =
      object : XrLanguageTeacherStickerMaker {
        override val isAvailable: Boolean = false

        override fun setModelPath(modelPath: String?) {}

        override suspend fun prepare() {}

        override suspend fun segment(
          source: Bitmap,
          strokes: List<CutoutStroke>,
        ): StickerSegmentationResult = StickerSegmentationResult.Unavailable

        override fun close() {}
      }
  }
}

/**
 * Segments the object near the center `(0.5, 0.5)` of [source] and cuts it out on
 * [cutoutDispatcher] as a cropped sticker with a transparent background.
 *
 * The positive center tap selects the high-confidence region, so the cutout keeps it as the
 * foreground, the same way the cutout editor's Done does. (iOS instead infers the polarity from the
 * center patch, which can cut out the background when the tap misses the object.)
 */
suspend fun XrLanguageTeacherStickerMaker.makeSticker(
  source: Bitmap,
  cutoutDispatcher: CoroutineDispatcher,
): StickerCutoutResult =
  when (val result = segment(source = source, strokes = listOf(AUTO_CUTOUT_STROKE))) {
    is StickerSegmentationResult.Success -> {
      val sticker =
        withContext(cutoutDispatcher) {
          XrLanguageTeacherStickerImages.cutOut(
            source = result.image,
            mask = result.mask,
            polarity = StickerMaskPolarity.HIGH_IS_FOREGROUND,
          )
        }
      if (sticker != null) StickerCutoutResult.Success(sticker)
      else StickerCutoutResult.NoForeground
    }
    StickerSegmentationResult.Failed -> StickerCutoutResult.NoForeground
    StickerSegmentationResult.Unavailable -> StickerCutoutResult.Unavailable
    StickerSegmentationResult.Error -> StickerCutoutResult.Error
  }

/** A segmentation engine for one model file. Calls are serialized by the sticker maker. */
internal interface StickerSegmenterEngine {
  /**
   * Gives the engine [image] to segment. The engine must not recycle or keep a reference to [image]
   * that outlives the call, since the caller hands it out.
   */
  fun setImage(image: Bitmap)

  /** Segments the last image with [strokes], or returns `null` if the mask can't be read. */
  fun segment(strokes: List<CutoutStroke>): SegmentationMask?

  /** Releases the engine's native resources. */
  fun close()
}

/** Creates the [StickerSegmenterEngine] for a model file, or returns `null` if it can't. */
internal fun interface StickerSegmenterEngineFactory {
  /** Creates the engine for the model at [modelPath]. Called off the main thread. */
  suspend fun create(modelPath: String): StickerSegmenterEngine?
}

/**
 * [XrLanguageTeacherStickerMaker] that runs a [StickerSegmenterEngine] (the MediaPipe
 * [InteractiveSegmenter] in the app) on [segmentationDispatcher].
 *
 * Segmentations are serialized: the engine keeps the last photo it was given, so the cutout editor
 * can segment new strokes on the same photo without setting it again.
 */
class MediaPipeXrLanguageTeacherStickerMaker
internal constructor(
  private val segmentationDispatcher: CoroutineDispatcher,
  private val engineFactory: StickerSegmenterEngineFactory,
) : XrLanguageTeacherStickerMaker {

  @Inject
  constructor(
    @ApplicationContext context: Context,
    @DefaultDispatcher segmentationDispatcher: CoroutineDispatcher,
  ) : this(segmentationDispatcher, MediaPipeStickerSegmenterEngineFactory(context))

  private val mutex = Mutex()
  @Volatile private var modelPath: String? = null
  @Volatile private var isClosed = false
  /** Set when the model changed while a segmentation held the engine, so it releases it after. */
  @Volatile private var isReleaseRequested = false

  // Guarded by [mutex].
  private var engine: StickerSegmenterEngine? = null
  private var engineModelPath: String? = null
  private var currentSource: Bitmap? = null
  private var currentInput: Bitmap? = null

  override val isAvailable: Boolean
    get() = !isClosed && modelPath != null

  override fun setModelPath(modelPath: String?) {
    val newPath = modelPath?.takeIf { it.isNotBlank() }
    if (this.modelPath == newPath) return
    this.modelPath = newPath
    releaseNowOrAfterCurrentSegmentation()
  }

  override suspend fun prepare() {
    withContext(segmentationDispatcher) {
      mutex.withLock {
        try {
          if (!isClosed) {
            val unused = ensureEngine()
          }
        } finally {
          releaseIfRequested()
        }
      }
    }
  }

  override suspend fun segment(
    source: Bitmap,
    strokes: List<CutoutStroke>,
  ): StickerSegmentationResult =
    withContext(segmentationDispatcher) {
      val validStrokes = strokes.filter { it.points.isNotEmpty() }
      if (validStrokes.isEmpty()) return@withContext StickerSegmentationResult.Failed
      mutex.withLock {
        try {
          if (isClosed) return@withLock StickerSegmentationResult.Unavailable
          val activeEngine = ensureEngine() ?: return@withLock StickerSegmentationResult.Unavailable
          val input =
            setSourceImage(activeEngine, source) ?: return@withLock StickerSegmentationResult.Failed
          val mask = activeEngine.segment(validStrokes)
          if (mask == null) {
            StickerSegmentationResult.Failed
          } else {
            StickerSegmentationResult.Success(image = input, mask = mask)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // The engine may be broken after a native error: release it so the next segmentation
          // creates it again, and leave the photo to be segmented on a later try.
          Log.w(TAG, "Interactive segmentation failed", e)
          releaseEngine()
          StickerSegmentationResult.Error
        } finally {
          releaseIfRequested()
        }
      }
    }

  override fun close() {
    isClosed = true
    releaseNowOrAfterCurrentSegmentation()
  }

  /**
   * Releases the engine now, or, while a segmentation holds it, when that segmentation finishes.
   */
  private fun releaseNowOrAfterCurrentSegmentation() {
    isReleaseRequested = true
    if (mutex.tryLock()) {
      try {
        releaseIfRequested()
      } finally {
        mutex.unlock()
      }
    }
  }

  /** Releases the engine if the model changed or the maker closed. Call under [mutex]. */
  private fun releaseIfRequested() {
    if (isClosed || isReleaseRequested) {
      isReleaseRequested = false
      releaseEngine()
    }
  }

  /** Returns the engine for the current model, creating it if needed. Call under [mutex]. */
  private suspend fun ensureEngine(): StickerSegmenterEngine? {
    val path = modelPath ?: return null
    val existing = engine
    if (existing != null && engineModelPath == path) return existing
    releaseEngine()
    if (!File(path).isFile) {
      Log.w(TAG, "The segmentation model file is missing.")
      return null
    }
    val created =
      try {
        engineFactory.create(path)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to initialize the sticker segmenter", e)
        null
      } ?: return null
    engine = created
    engineModelPath = path
    return created
  }

  /**
   * Gives [activeEngine] a downscaled ARGB copy of [source], unless it already has one, and returns
   * that copy, or `null` when [source] can't be read. Call under [mutex].
   */
  private fun setSourceImage(activeEngine: StickerSegmenterEngine, source: Bitmap): Bitmap? {
    val cachedInput = currentInput
    if (source === currentSource && cachedInput != null && !cachedInput.isRecycled) {
      return cachedInput
    }
    val input = prepareSegmenterInput(source) ?: return null
    activeEngine.setImage(input)
    currentSource = source
    currentInput = input
    return input
  }

  /** Closes the engine and forgets the photo it was given. Call under [mutex]. */
  private fun releaseEngine() {
    // The input copies were handed out as `Success.image`, so they are left to their holders.
    currentSource = null
    currentInput = null
    engine?.close()
    engine = null
    engineModelPath = null
  }
}

/**
 * Creates MediaPipe [InteractiveSegmenter] engines on the CPU, like iOS pins Magic touch to the
 * CPU: the 512px input is cheap there, and it keeps the GPU free for the teacher's Gemma model.
 * (Scrapbook, which segments full-size photos interactively, keeps preferring the GPU.)
 */
private class MediaPipeStickerSegmenterEngineFactory(private val context: Context) :
  StickerSegmenterEngineFactory {
  override suspend fun create(modelPath: String): StickerSegmenterEngine? {
    val segmenter =
      SegmenterFactory.createVerifiedSegmenter(
        context = context,
        baseOptions =
          SegmenterFactory.segmenterBaseOptions(modelPath = modelPath, delegate = Delegate.CPU)
            .build(),
      ) ?: return null
    Log.i(TAG, "Initialized the sticker segmenter on the CPU.")
    return MediaPipeStickerSegmenterEngine(segmenter)
  }
}

/** [StickerSegmenterEngine] backed by a MediaPipe [InteractiveSegmenter]. */
private class MediaPipeStickerSegmenterEngine(private val segmenter: InteractiveSegmenter) :
  StickerSegmenterEngine {
  /** The image the segmenter was last given. Closing it recycles its private bitmap copy. */
  private var currentImage: MPImage? = null

  override fun setImage(image: Bitmap) {
    // Closing an MPImage recycles its bitmap, so it wraps a private copy that the engine owns.
    val ownedCopy = image.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ false)
    val mpImage = BitmapImageBuilder(ownedCopy).build()
    try {
      segmenter.setImage(mpImage)
    } catch (e: Exception) {
      mpImage.close()
      throw e
    }
    currentImage?.close()
    currentImage = mpImage
  }

  override fun segment(strokes: List<CutoutStroke>): SegmentationMask? {
    val mediaPipeStrokes = strokes.mapNotNull { it.toMediaPipeStroke() }
    if (mediaPipeStrokes.isEmpty()) return null
    return segmenter.segment(mediaPipeStrokes).use { extractMask(it) }
  }

  override fun close() {
    currentImage?.close()
    currentImage = null
    segmenter.close()
  }
}

/**
 * Returns a private ARGB_8888 copy of [source] whose longest side is at most 512px, or `null` when
 * [source] was recycled or can't be copied.
 */
private fun prepareSegmenterInput(source: Bitmap): Bitmap? {
  if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
  return try {
    val (scaledWidth, scaledHeight) =
      scaledSizeWithin(source.width, source.height, SEGMENTER_INPUT_MAX_SIDE_PX)
    val scaled =
      if (scaledWidth != source.width || scaledHeight != source.height) {
        source.scale(scaledWidth, scaledHeight)
      } else {
        source
      }
    if (scaled !== source && scaled.config == Bitmap.Config.ARGB_8888) {
      scaled
    } else {
      val copy = scaled.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ false)
      if (scaled !== source && copy !== scaled) scaled.recycle()
      copy
    }
  } catch (e: IllegalStateException) {
    // The source can be recycled concurrently by the camera pipeline.
    Log.w(TAG, "Photo was recycled before it could be segmented", e)
    null
  } catch (e: IllegalArgumentException) {
    Log.w(TAG, "Invalid photo for segmentation", e)
    null
  }
}

/** Reads the float confidence mask out of [maskImage], or returns `null` if it is incomplete. */
private fun extractMask(maskImage: MPImage): SegmentationMask? {
  val width = maskImage.width
  val height = maskImage.height
  if (width <= 0 || height <= 0) return null
  val floatBuffer =
    ByteBufferExtractor.extract(maskImage).order(ByteOrder.nativeOrder()).asFloatBuffer()
  val pixelCount = width * height
  if (floatBuffer.remaining() < pixelCount) return null
  val values = FloatArray(pixelCount)
  floatBuffer.get(values)
  return SegmentationMask(width = width, height = height, values = values)
}

/** Returns the MediaPipe stroke for this stroke, or `null` when it has no points. */
internal fun CutoutStroke.toMediaPipeStroke(): Stroke? {
  if (points.isEmpty()) return null
  return Stroke.builder()
    .setBrushMode(brushMode.mediaPipeBrushMode)
    .setPoints(
      points.map { NormalizedKeypoint.create(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
    )
    .setCompleted(true)
    .build()
}
