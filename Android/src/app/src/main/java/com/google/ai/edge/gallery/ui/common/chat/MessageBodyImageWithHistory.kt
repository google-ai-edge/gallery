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

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Composable function to display an image message with history, allowing users to navigate through
 * different versions by sliding on the image.
 */
@Composable
fun MessageBodyImageWithHistory(
  message: ChatMessageImageWithHistory,
  imageHistoryCurIndex: MutableIntState,
) {
  val prevMessage: MutableState<ChatMessageImageWithHistory?> = remember { mutableStateOf(null) }

  LaunchedEffect(message) {
    imageHistoryCurIndex.intValue = message.bitmaps.size - 1
    prevMessage.value = message
  }

  Column {
    val curImage = message.bitmaps[imageHistoryCurIndex.intValue]
    val curImageBitmap = message.imageBitMaps[imageHistoryCurIndex.intValue]

    val bitmapWidth = curImage.width
    val bitmapHeight = curImage.height
    val imageWidth =
      if (bitmapWidth >= bitmapHeight) 200 else (200f / bitmapHeight * bitmapWidth).toInt()
    val imageHeight =
      if (bitmapHeight >= bitmapWidth) 200 else (200f / bitmapWidth * bitmapHeight).toInt()

    var value by remember { mutableFloatStateOf(0f) }
    var savedIndex by remember { mutableIntStateOf(0) }
    Image(
      bitmap = curImageBitmap,
      contentDescription = null,
      modifier =
        Modifier.height(imageHeight.dp).width(imageWidth.dp).pointerInput(Unit) {
          detectHorizontalDragGestures(
            onDragStart = {
              value = 0f
              savedIndex = imageHistoryCurIndex.intValue
            }
          ) { _, dragAmount ->
            value += (dragAmount / 20f) // Adjust sensitivity here
            imageHistoryCurIndex.intValue = (savedIndex + value).toInt()
            if (imageHistoryCurIndex.intValue < 0) {
              imageHistoryCurIndex.intValue = 0
            } else if (imageHistoryCurIndex.intValue > message.bitmaps.size - 1) {
              imageHistoryCurIndex.intValue = message.bitmaps.size - 1
            }
          }
        },
      contentScale = ContentScale.Fit,
    )
  }
}
