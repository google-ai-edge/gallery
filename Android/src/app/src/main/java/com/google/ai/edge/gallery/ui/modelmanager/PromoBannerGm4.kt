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

package com.google.ai.edge.gallery.ui.modelmanager

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.ui.theme.customColors

private val BUTTON_PADDING = PaddingValues(start = 8.dp, top = 0.dp, end = 8.dp, bottom = 0.dp)

@Composable
fun PromoBannerGm4(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
  val density = LocalDensity.current
  val uriHandler = LocalUriHandler.current
  var columnHeightDp by remember { mutableStateOf(0.dp) }

  Box(modifier = modifier) {
    val iconBrush = MaterialTheme.customColors.promoBannerIconBgBrush
    Image(
      ImageVector.vectorResource(R.drawable.gemini_star),
      contentDescription = null,
      contentScale = ContentScale.Fit,
      modifier =
        Modifier.height(columnHeightDp)
          .width(columnHeightDp)
          .align(Alignment.CenterEnd)
          .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen, alpha = 0.99f)
          .drawWithContent {
            drawContent()
            drawRect(brush = iconBrush, blendMode = BlendMode.SrcIn)
          },
    )

    Column(
      modifier =
        Modifier.fillMaxWidth()
          .background(
            brush = MaterialTheme.customColors.promoBannerBgBrush,
            shape = RoundedCornerShape(16.dp),
          )
          .onGloballyPositioned { coordinates ->
            columnHeightDp = with(density) { coordinates.size.height.toDp() }
          }
          .padding(horizontal = 16.dp)
          .padding(top = 16.dp, bottom = 8.dp)
    ) {
      Text(text = "Gemma 4: now available", style = MaterialTheme.typography.titleMedium)
      Text(
        "Built from the same world-class technology as Gemini 3, Gemma 4 brings frontier intelligence to your mobile and edge devices.",
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
        modifier = Modifier.padding(top = 4.dp),
      )
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
      ) {
        TextButton(onClick = onDismiss, contentPadding = BUTTON_PADDING) { Text("Dismiss") }
        Button(
          onClick = { uriHandler.openUri("https://ai.google.dev/gemma") },
          modifier = Modifier.padding(start = 8.dp).height(32.dp),
          contentPadding = BUTTON_PADDING,
        ) {
          Text("Read more")
        }
      }
    }
  }
}
