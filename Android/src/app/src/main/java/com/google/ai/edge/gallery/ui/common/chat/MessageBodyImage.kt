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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun MessageBodyImage(message: ChatMessageImage, modifier: Modifier = Modifier) {
  val bitmapWidth = message.bitmap.width
  val bitmapHeight = message.bitmap.height
  val imageWidth =
    if (bitmapWidth >= bitmapHeight) 200 else (200f / bitmapHeight * bitmapWidth).toInt()
  val imageHeight =
    if (bitmapHeight >= bitmapWidth) 200 else (200f / bitmapWidth * bitmapHeight).toInt()

  Image(
    bitmap = message.imageBitMap,
    contentDescription = "",
    modifier = modifier.height(imageHeight.dp).width(imageWidth.dp),
    contentScale = ContentScale.Fit,
  )
}
