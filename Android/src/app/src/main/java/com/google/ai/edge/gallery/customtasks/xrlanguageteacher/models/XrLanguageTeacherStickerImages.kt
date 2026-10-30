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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.models

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.BlurMaskFilter.Blur
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.withClip
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val TAG = "AGTeacherStickerImages"

/** Number of directions the white sticker outline is stamped in around the cutout. */
private const val OUTLINE_DIRECTION_COUNT = 16

/** How far the drop shadow under a cutout's outline falls, in pixels. */
private const val SHADOW_OFFSET_Y_PX = 2f

/** The color of the drop shadow under a cutout's outline: black at 28% opacity. */
private val SHADOW_COLOR = Color.argb(71, 0, 0, 0)

/**
 * Bitmap adapters around [XrLanguageTeacherStickerCutout]: cuts stickers out of photos, detects
 * cutouts, renders the cutout editor's mask overlay, and pre-bakes white sticker outlines.
 */
object XrLanguageTeacherStickerImages {
  /**
   * Returns the pre-baked outlined sticker [ImageBitmap] cached for [source] (at its full size) in
   * [XrLanguageTeacherVocabImageCache], or `null` if it has not been prepared yet (or was evicted).
   */
  fun getCachedOutlinedSticker(source: Bitmap): ImageBitmap? =
    XrLanguageTeacherVocabImageCache.getOutlined(source)

  /**
   * Returns the outlined sticker [ImageBitmap] for [source] at its full size, computing and caching
   * it in [XrLanguageTeacherVocabImageCache] when not already cached. Call off the main thread to
   * avoid UI jank. Returns [source] itself, uncached, when it is recycled meanwhile (e.g. a camera
   * frame replaced while its outline was baked).
   */
  fun getOrPrepareOutlinedSticker(source: Bitmap): ImageBitmap {
    XrLanguageTeacherVocabImageCache.getOutlined(source)?.let {
      return it
    }
    val prepared =
      try {
        prepareStickerImage(source = source, maxSidePx = max(source.width, source.height))
          .asImageBitmap()
      } catch (e: RuntimeException) {
        // Drawing a bitmap that was recycled concurrently throws; anything else is a real error.
        if (!source.isRecycled) throw e
        Log.w(TAG, "Sticker was recycled while its outline was prepared", e)
        return source.asImageBitmap()
      }
    XrLanguageTeacherVocabImageCache.putOutlined(source, prepared)
    return prepared
  }

  /**
   * Cuts the foreground object of [source] out using [mask], scaling [source] to the mask size
   * first if needed. Returns an ARGB_8888 sticker with a transparent background, or `null` when the
   * mask does not isolate a valid foreground object or [source] can't be read.
   */
  fun cutOut(
    source: Bitmap,
    mask: SegmentationMask,
    polarity: StickerMaskPolarity = StickerMaskPolarity.AUTO,
  ): Bitmap? {
    val plan =
      XrLanguageTeacherStickerCutout.planCutout(
        mask = mask.values,
        width = mask.width,
        height = mask.height,
        polarity = polarity,
      ) ?: return null
    return try {
      if (source.isRecycled) return null
      val sized =
        if (source.width == mask.width && source.height == mask.height) {
          source
        } else {
          source.scale(mask.width, mask.height)
        }
      val pixels = IntArray(mask.width * mask.height)
      sized.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
      if (sized !== source) {
        sized.recycle()
      }
      val stickerPixels =
        XrLanguageTeacherStickerCutout.applyCutout(
          sourcePixels = pixels,
          sourceWidth = mask.width,
          plan = plan,
        )
      Bitmap.createBitmap(stickerPixels, plan.width, plan.height, Bitmap.Config.ARGB_8888)
    } catch (e: IllegalStateException) {
      // The source can be recycled concurrently by the camera pipeline.
      Log.w(TAG, "Source photo was recycled before the sticker could be cut out", e)
      null
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "Invalid source photo for the sticker cutout", e)
      null
    }
  }

  /**
   * Returns `true` when [bitmap] has an alpha channel and any of its 4 corner pixels has alpha `<
   * 128`, indicating a segmented sticker cutout rather than an unsegmented rectangular photo.
   */
  fun hasTransparentEdge(bitmap: Bitmap): Boolean {
    if (bitmap.isRecycled || !bitmap.hasAlpha()) return false
    return try {
      XrLanguageTeacherStickerCutout.hasTransparentCorner(bitmap.width, bitmap.height) { x, y ->
        Color.alpha(bitmap[x, y])
      }
    } catch (e: IllegalStateException) {
      Log.w(TAG, "Bitmap was recycled while checking for a transparent edge", e)
      false
    }
  }

  /** Returns `true` when [image] is a segmented sticker cutout (see [hasTransparentEdge]). */
  fun hasTransparentEdge(image: ImageBitmap): Boolean = hasTransparentEdge(image.asAndroidBitmap())

  /**
   * Returns the cutout editor's mask overlay for [mask]: transparent over the selection and
   * [overlayColor] (ARGB) everywhere else.
   */
  fun buildMaskOverlay(mask: SegmentationMask, overlayColor: Int): Bitmap =
    Bitmap.createBitmap(
      XrLanguageTeacherStickerCutout.buildMaskOverlayPixels(mask, overlayColor),
      mask.width,
      mask.height,
      Bitmap.Config.ARGB_8888,
    )

  /**
   * Encodes [bitmap] as a lossless PNG that keeps its transparency, or returns `null` on failure.
   */
  @Suppress("KotlinArray")
  fun encodePng(bitmap: Bitmap): ByteArray? {
    if (bitmap.isRecycled) return null
    return try {
      ByteArrayOutputStream().use { stream ->
        if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) stream.toByteArray() else null
      }
    } catch (e: IllegalStateException) {
      Log.w(TAG, "Sticker was recycled before it could be encoded", e)
      null
    }
  }

  /**
   * Downscales [source] so its longest side is at most [maxSidePx] and bakes either a
   * contour-following white silhouette with a soft drop shadow (when [source] has transparent
   * corners) or a white-bordered rounded rectangle around it, so a sticker is drawn as a single
   * bitmap (one outline algorithm for vocabulary cards and the game targets and obstacles).
   */
  fun prepareStickerImage(
    source: Bitmap,
    maxSidePx: Int = 256,
    outlineFraction: Float = 0.055f,
  ): Bitmap {
    if (source.isRecycled) return source
    val isCutout = hasTransparentEdge(source)
    val layout =
      StickerImageLayout.compute(
        sourceWidth = source.width,
        sourceHeight = source.height,
        maxSidePx = maxSidePx,
        outlineFraction = outlineFraction,
        withShadow = isCutout,
      ) ?: return source
    val pad = layout.pad.toFloat()
    val output = createBitmap(layout.outputWidth, layout.outputHeight)
    val canvas = Canvas(output)
    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val imageRect = RectF(pad, pad, pad + layout.scaledWidth, pad + layout.scaledHeight)

    if (isCutout) {
      val outline = createBitmap(layout.outputWidth, layout.outputHeight)
      val outlineCanvas = Canvas(outline)
      val silhouettePaint =
        Paint(imagePaint).apply {
          colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
      val outlinePad = layout.outlinePad.toFloat()
      for (index in 0 until OUTLINE_DIRECTION_COUNT) {
        val angle = 2.0 * PI * index / OUTLINE_DIRECTION_COUNT
        val offsetRect = RectF(imageRect)
        offsetRect.offset((cos(angle) * outlinePad).toFloat(), (sin(angle) * outlinePad).toFloat())
        outlineCanvas.drawBitmap(source, null, offsetRect, silhouettePaint)
      }
      drawDropShadow(canvas, outline, layout.shadowBlur)
      canvas.drawBitmap(outline, 0f, 0f, null)
      outline.recycle()
      canvas.drawBitmap(source, null, imageRect, imagePaint)
    } else {
      val outerRadius = max(layout.outputWidth, layout.outputHeight) * 0.14f
      val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
      canvas.drawRoundRect(
        RectF(0f, 0f, layout.outputWidth.toFloat(), layout.outputHeight.toFloat()),
        outerRadius,
        outerRadius,
        whitePaint,
      )
      val innerRadius = max(2f, outerRadius - pad * 0.6f)
      val innerPath =
        Path().apply { addRoundRect(imageRect, innerRadius, innerRadius, Path.Direction.CW) }
      canvas.withClip(innerPath) { drawBitmap(source, null, imageRect, imagePaint) }
    }
    return output
  }

  /**
   * Draws a soft black shadow of [silhouette], blurred by [blurRadius] pixels and dropped
   * [SHADOW_OFFSET_Y_PX] pixels down, onto [canvas].
   */
  private fun drawDropShadow(canvas: Canvas, silhouette: Bitmap, blurRadius: Int) {
    if (blurRadius <= 0) return
    val offset = IntArray(2)
    val blurPaint = Paint().apply { maskFilter = BlurMaskFilter(blurRadius.toFloat(), Blur.NORMAL) }
    val shadow = silhouette.extractAlpha(blurPaint, offset)
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADOW_COLOR }
    canvas.drawBitmap(
      shadow,
      offset[0].toFloat(),
      offset[1].toFloat() + SHADOW_OFFSET_Y_PX,
      shadowPaint,
    )
    shadow.recycle()
  }
}
