/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery.ui.common.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import kotlin.math.ceil

@Composable
fun MessageBodyImage(
  message: ChatMessageImage,
  onImageClicked: (bitmaps: List<Bitmap>, selectedBitmapIndex: Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val imageCount = message.bitmaps.size
  // Single image.
  if (imageCount == 1) {
    val bitmap = message.bitmaps[0]
    val imageBitMap = message.imageBitMaps[0]
    val bitmapWidth = bitmap.width
    val bitmapHeight = bitmap.height
    val imageWidth =
      if (bitmapWidth >= bitmapHeight) 200 else (200f / bitmapHeight * bitmapWidth).toInt()
    val imageHeight =
      if (bitmapHeight >= bitmapWidth) 200 else (200f / bitmapWidth * bitmapHeight).toInt()

    Image(
      bitmap = imageBitMap,
      contentDescription = stringResource(R.string.cd_user_image),
      modifier =
        modifier.height(imageHeight.dp).width(imageWidth.dp).clickable {
          onImageClicked(message.bitmaps, 0)
        },
      contentScale = ContentScale.Fit,
    )
  }
  // Multiple images.
  //
  // Lay them out in a grid.
  else {
    var colCount = 3
    if (imageCount == 4) {
      colCount = 2
    }
    val rowCount = ceil(imageCount.toFloat() / colCount).toInt()
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
      for (row in 0..<rowCount) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          for (col in 0..<colCount) {
            val imageIndex = row * colCount + col
            if (imageIndex >= imageCount) {
              return@Row
            }
            val imageBitMap = message.imageBitMaps[imageIndex]
            Image(
              bitmap = imageBitMap,
              contentDescription =
                stringResource(R.string.cd_user_image_in_group, imageIndex + 1, imageCount),
              modifier =
                Modifier.height(100.dp).width(100.dp).clickable {
                  onImageClicked(message.bitmaps, imageIndex)
                },
              contentScale = ContentScale.Crop,
            )
          }
        }
      }
    }
  }
}
