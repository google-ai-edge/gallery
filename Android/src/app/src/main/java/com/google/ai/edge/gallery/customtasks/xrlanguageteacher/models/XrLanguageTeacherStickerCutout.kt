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

@file:Suppress("KotlinArray")

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import com.google.ai.edge.gallery.customtasks.common.segmentation.SegmentationBrushMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A MediaPipe Interactive Segmenter confidence mask: [width] x [height] values in `[0.0, 1.0]`,
 * row-major.
 */
class SegmentationMask(val width: Int, val height: Int, val values: FloatArray)

/** How [XrLanguageTeacherStickerCutout.planCutout] tells foreground pixels from background ones. */
enum class StickerMaskPolarity {
  /** Foreground is whichever side of the threshold the 5x5 center patch falls on. */
  AUTO,
  /** Foreground is every pixel at or above the threshold (the region the user selected). */
  HIGH_IS_FOREGROUND,
}

/**
 * A point in normalized image coordinates, with `(0, 0)` the top-left and `(1, 1)` the
 * bottom-right.
 */
data class NormalizedPoint(val x: Float, val y: Float)

/** A segmentation stroke: the [points] the user brushed with [brushMode]. */
data class CutoutStroke(val points: List<NormalizedPoint>, val brushMode: SegmentationBrushMode)

/**
 * The single positive tap at the center `(0.5, 0.5)` of the photo that the automatic sticker cutout
 * segments with.
 */
val AUTO_CUTOUT_STROKE: CutoutStroke =
  CutoutStroke(
    points = listOf(NormalizedPoint(x = 0.5f, y = 0.5f)),
    brushMode = SegmentationBrushMode.ADD,
  )

/**
 * Where to crop a source image and how opaque each cropped pixel is.
 *
 * @property left The left edge of the crop region in mask pixels.
 * @property top The top edge of the crop region in mask pixels.
 * @property width The width of the crop region in mask pixels.
 * @property height The height of the crop region in mask pixels.
 * @property alpha The feathered opacity in `[0.0, 1.0]` of each cropped pixel, row-major.
 * @property foregroundRatio The fraction of mask pixels classified as foreground.
 */
class StickerCutoutPlan(
  val left: Int,
  val top: Int,
  val width: Int,
  val height: Int,
  val alpha: FloatArray,
  val foregroundRatio: Float,
)

/**
 * Pure pixel-level foreground sticker cutout and transparency detection for AR Language Teacher.
 *
 * Given a MediaPipe Interactive Segmenter confidence mask (`[0.0, 1.0]`), determines foreground
 * polarity from the 5x5 center patch, checks that foreground coverage is within `0.4%...92%`, crops
 * to the tight foreground bounding box with a `6%` margin, and feathers the binary mask with a 1px
 * 3x3 box blur. [applyCutout] then turns source ARGB pixels into the sticker's ARGB pixels with a
 * transparent background.
 */
object XrLanguageTeacherStickerCutout {
  /** Minimum foreground pixel fraction (`0.4%`) to accept a cutout as a valid object. */
  const val MIN_FOREGROUND_RATIO = 0.004f

  /**
   * Maximum foreground pixel fraction (`92%`) before treating the mask as full-frame background.
   */
  const val MAX_FOREGROUND_RATIO = 0.92f

  /** Confidence threshold separating foreground and background. */
  const val CONFIDENCE_THRESHOLD = 0.5f

  /** Fractional padding added around the tight foreground bounding box. */
  const val CROP_MARGIN_FRACTION = 0.06f

  /** Minimum padding in pixels added around the tight foreground bounding box. */
  private const val MIN_CROP_PADDING_PX = 4

  /** Half the side of the square center patch sampled to determine polarity (5x5). */
  private const val CENTER_PATCH_RADIUS = 2

  /** Alpha below which a corner pixel counts as transparent. */
  private const val TRANSPARENT_ALPHA_THRESHOLD = 128

  /**
   * Returns whether foreground pixels have high confidence values, from the mean of the 5x5 patch
   * at the center of [mask] (`width x height`), clamping samples to the mask bounds.
   */
  fun isForegroundHigh(mask: FloatArray, width: Int, height: Int): Boolean {
    val centerX = width / 2
    val centerY = height / 2
    var centerSum = 0f
    var centerSamples = 0
    for (dy in -CENTER_PATCH_RADIUS..CENTER_PATCH_RADIUS) {
      for (dx in -CENTER_PATCH_RADIUS..CENTER_PATCH_RADIUS) {
        val sampleX = (centerX + dx).coerceIn(0, width - 1)
        val sampleY = (centerY + dy).coerceIn(0, height - 1)
        centerSum += mask[sampleY * width + sampleX]
        centerSamples++
      }
    }
    return centerSum / max(centerSamples, 1) >= CONFIDENCE_THRESHOLD
  }

  /**
   * Plans the sticker cutout of [mask] (`width x height` confidence values).
   *
   * Returns `null` when the mask is empty, its dimensions are invalid, or foreground coverage falls
   * outside [MIN_FOREGROUND_RATIO]..[MAX_FOREGROUND_RATIO].
   */
  fun planCutout(
    mask: FloatArray,
    width: Int,
    height: Int,
    polarity: StickerMaskPolarity = StickerMaskPolarity.AUTO,
  ): StickerCutoutPlan? {
    if (width <= 0 || height <= 0) return null
    val totalPixels = width * height
    if (mask.size < totalPixels) return null

    val foregroundIsHigh =
      when (polarity) {
        StickerMaskPolarity.AUTO -> isForegroundHigh(mask, width, height)
        StickerMaskPolarity.HIGH_IS_FOREGROUND -> true
      }

    val binary = FloatArray(totalPixels)
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    var foregroundCount = 0
    for (y in 0 until height) {
      val rowOffset = y * width
      for (x in 0 until width) {
        val value = mask[rowOffset + x]
        val isForeground =
          if (foregroundIsHigh) value >= CONFIDENCE_THRESHOLD else value < CONFIDENCE_THRESHOLD
        if (isForeground) {
          binary[rowOffset + x] = 1f
          foregroundCount++
          if (x < minX) minX = x
          if (x > maxX) maxX = x
          if (y < minY) minY = y
          if (y > maxY) maxY = y
        }
      }
    }

    val coverage = foregroundCount.toFloat() / totalPixels
    if (
      coverage < MIN_FOREGROUND_RATIO ||
        coverage > MAX_FOREGROUND_RATIO ||
        maxX < minX ||
        maxY < minY
    ) {
      return null
    }

    val boxWidth = maxX - minX + 1
    val boxHeight = maxY - minY + 1
    val pad =
      max(MIN_CROP_PADDING_PX, (max(boxWidth, boxHeight) * CROP_MARGIN_FRACTION).roundToInt())
    val cropLeft = max(0, minX - pad)
    val cropTop = max(0, minY - pad)
    val cropRight = min(width - 1, maxX + pad)
    val cropBottom = min(height - 1, maxY + pad)
    val cropWidth = cropRight - cropLeft + 1
    val cropHeight = cropBottom - cropTop + 1
    if (cropWidth <= 0 || cropHeight <= 0) return null

    return StickerCutoutPlan(
      left = cropLeft,
      top = cropTop,
      width = cropWidth,
      height = cropHeight,
      alpha =
        boxBlur3x3(
          binary = binary,
          width = width,
          height = height,
          left = cropLeft,
          top = cropTop,
          right = cropRight,
          bottom = cropBottom,
        ),
      foregroundRatio = coverage,
    )
  }

  /**
   * Returns the sticker's ARGB pixels (`plan.width x plan.height`, non-premultiplied as
   * [Bitmap.setPixels] expects): [sourcePixels] (`sourceWidth` wide, the same size as the planned
   * mask) cropped to [plan] with its feathered alpha. Fully transparent pixels are `0`.
   */
  fun applyCutout(sourcePixels: IntArray, sourceWidth: Int, plan: StickerCutoutPlan): IntArray {
    val output = IntArray(plan.width * plan.height)
    for (cy in 0 until plan.height) {
      val srcRowOffset = (plan.top + cy) * sourceWidth
      for (cx in 0 until plan.width) {
        val dstIndex = cy * plan.width + cx
        val alphaByte = (plan.alpha[dstIndex].coerceIn(0f, 1f) * 255f).roundToInt()
        output[dstIndex] =
          if (alphaByte == 0) {
            0
          } else {
            (alphaByte shl 24) or (sourcePixels[srcRowOffset + plan.left + cx] and 0x00FFFFFF)
          }
      }
    }
    return output
  }

  /**
   * Returns `true` when any of the 4 corner pixels of a `width x height` image has alpha `< 128`
   * ([alphaAt] returns the `0..255` alpha at `(x, y)`), indicating that the image is a segmented
   * sticker cutout rather than an unsegmented rectangular photo.
   */
  fun hasTransparentCorner(width: Int, height: Int, alphaAt: (x: Int, y: Int) -> Int): Boolean {
    if (width <= 0 || height <= 0) return false
    val corners = listOf(0 to 0, width - 1 to 0, 0 to height - 1, width - 1 to height - 1)
    return corners.any { (x, y) -> alphaAt(x, y) < TRANSPARENT_ALPHA_THRESHOLD }
  }

  /**
   * Returns the ARGB pixels of the cutout editor's mask overlay for [mask]: the selected region
   * (confidence at or above the threshold) is transparent and everything else is [overlayColor].
   */
  fun buildMaskOverlayPixels(mask: SegmentationMask, overlayColor: Int): IntArray {
    val totalPixels = mask.width * mask.height
    val pixels = IntArray(totalPixels)
    for (i in 0 until min(totalPixels, mask.values.size)) {
      pixels[i] = if (mask.values[i] >= CONFIDENCE_THRESHOLD) 0 else overlayColor
    }
    return pixels
  }

  /** Computes a 3x3 box-blurred alpha array for the `[left..right, top..bottom]` crop region. */
  private fun boxBlur3x3(
    binary: FloatArray,
    width: Int,
    height: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
  ): FloatArray {
    val cropWidth = right - left + 1
    val cropHeight = bottom - top + 1
    val output = FloatArray(cropWidth * cropHeight)
    for (cy in 0 until cropHeight) {
      val y = top + cy
      val y0 = max(0, y - 1)
      val y1 = min(height - 1, y + 1)
      for (cx in 0 until cropWidth) {
        val x = left + cx
        val x0 = max(0, x - 1)
        val x1 = min(width - 1, x + 1)
        var sum = 0f
        var count = 0
        for (sy in y0..y1) {
          val rowOffset = sy * width
          for (sx in x0..x1) {
            sum += binary[rowOffset + sx]
            count++
          }
        }
        output[cy * cropWidth + cx] = sum / max(count, 1)
      }
    }
    return output
  }
}

/**
 * Returns the size of a [width] x [height] image scaled down so that its longest side is at most
 * [maxSidePx], keeping its aspect ratio (at least 1px per side), or its size when it already fits.
 */
internal fun scaledSizeWithin(width: Int, height: Int, maxSidePx: Int): Pair<Int, Int> {
  val longestSide = max(width, height)
  if (longestSide <= maxSidePx) return width to height
  val scale = maxSidePx.toFloat() / longestSide
  return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}

/**
 * The size of a sticker pre-baked by `XrLanguageTeacherStickerImages.prepareStickerImage`.
 *
 * @property scaledWidth The width the source is drawn at.
 * @property scaledHeight The height the source is drawn at.
 * @property pad The white outline or border width on each side.
 */
data class StickerImageLayout(val scaledWidth: Int, val scaledHeight: Int, val pad: Int) {
  /** The width of the pre-baked sticker. */
  val outputWidth: Int
    get() = scaledWidth + pad * 2

  /** The height of the pre-baked sticker. */
  val outputHeight: Int
    get() = scaledHeight + pad * 2

  companion object {
    /**
     * Returns the layout that downscales a `sourceWidth x sourceHeight` image so its longest side
     * is at most [maxSidePx] and pads it by [outlineFraction] of its longest scaled side (at least
     * 4px), or `null` for an empty source.
     */
    fun compute(
      sourceWidth: Int,
      sourceHeight: Int,
      maxSidePx: Int,
      outlineFraction: Float,
    ): StickerImageLayout? {
      if (sourceWidth <= 0 || sourceHeight <= 0) return null
      val (scaledWidth, scaledHeight) = scaledSizeWithin(sourceWidth, sourceHeight, maxSidePx)
      val pad = max(4, (max(scaledWidth, scaledHeight) * outlineFraction).roundToInt())
      return StickerImageLayout(scaledWidth = scaledWidth, scaledHeight = scaledHeight, pad = pad)
    }
  }
}
