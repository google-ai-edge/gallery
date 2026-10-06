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

package com.google.ai.edge.gallery.customtasks.videomomentfinder

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * A reusable thumbnail card for video projects and saved clips with rounded corners, transparent
 * cutout effect with blurred edges, and duration label overlay.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThumbnailCard(
  relativeThumbnailPath: String,
  modifier: Modifier = Modifier,
  durationInSeconds: Long = 0L,
  showOverlay: Boolean = true,
  onClick: (() -> Unit)? = null,
  onLongClick: (() -> Unit)? = null,
  content: @Composable BoxScope.() -> Unit = {},
) {
  val shape = RoundedCornerShape(16.dp)

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .shadow(elevation = 4.dp, shape = shape)
        .background(color = MaterialTheme.colorScheme.surfaceContainer, shape = shape)
        .border(width = 2.dp, color = MaterialTheme.colorScheme.surface, shape = shape)
        .clip(shape)
        .then(
          if (onLongClick != null) {
            Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
          } else if (onClick != null) {
            Modifier.clickable { onClick() }
          } else {
            Modifier
          }
        )
  ) {
    val context = LocalContext.current
    val thumbnailFile =
      if (relativeThumbnailPath.isNotEmpty()) {
        File(context.getExternalFilesDir(null), relativeThumbnailPath)
      } else {
        null
      }
    if (thumbnailFile != null && thumbnailFile.exists()) {
      AsyncImage(
        model =
          ImageRequest.Builder(context)
            .data(thumbnailFile)
            .memoryCacheKey("${thumbnailFile.absolutePath}_${thumbnailFile.lastModified()}")
            .diskCacheKey("${thumbnailFile.absolutePath}_${thumbnailFile.lastModified()}")
            .build(),
        contentDescription = "Video Thumbnail",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    } else {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
          Icons.Outlined.VideoFile,
          contentDescription = "Video Thumbnail",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (showOverlay) {
      // Cutout overlay: 90% semi-transparent black background with a blurred transparent hole
      Canvas(
        modifier =
          Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
      ) {
        // Base semi-transparent black overlay
        drawRect(color = Color.Black.copy(alpha = 0.9f))

        // Carve out a blurred transparent hole using PorterDuff.Mode.CLEAR
        drawIntoCanvas { canvas ->
          val blurRadius = 24.dp.toPx()
          val sideInset = (-4).dp.toPx()
          val topInset = (-20).dp.toPx()
          val bottomInset = 40.dp.toPx()
          val cornerRadius = 24.dp.toPx()

          val paint =
            AndroidPaint().apply {
              isAntiAlias = true
              maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
              xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }

          canvas.nativeCanvas.drawRoundRect(
            sideInset,
            topInset,
            size.width - sideInset,
            size.height - bottomInset,
            cornerRadius,
            cornerRadius,
            paint,
          )
        }
      }

      // Duration label placed in the bottom reserved area
      Text(
        text = formatTime(durationInSeconds * 1000L),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp),
      )
    }

    content()
  }
}
