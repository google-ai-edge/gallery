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

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.TASKS
import com.google.ai.edge.gallery.data.TASK_LLM_CHAT
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.gallery.ui.theme.customColors

private val SHAPES: List<Int> =
  listOf(R.drawable.pantegon, R.drawable.double_circle, R.drawable.circle, R.drawable.four_circle)

/**
 * Composable that displays an icon representing a task. It consists of a background image and a
 * foreground icon, both centered within a square box.
 */
@Composable
fun TaskIcon(task: Task, modifier: Modifier = Modifier, width: Dp = 56.dp) {
  Box(modifier = modifier.width(width).aspectRatio(1f), contentAlignment = Alignment.Center) {
    Image(
      painter = getTaskIconBgShape(task = task),
      contentDescription = "",
      modifier = Modifier.fillMaxSize().alpha(0.6f),
      contentScale = ContentScale.Fit,
      colorFilter =
        ColorFilter.tint(
          MaterialTheme.customColors.taskIconShapeBgColor,
          blendMode = BlendMode.SrcIn,
        ),
    )
    Icon(
      task.icon ?: ImageVector.vectorResource(task.iconVectorResourceId!!),
      tint = getTaskIconColor(task = task),
      modifier = Modifier.size(width * 0.6f),
      contentDescription = "",
    )
  }
}

@Composable
private fun getTaskIconBgShape(task: Task): Painter {
  val colorIndex: Int = task.index % SHAPES.size
  return painterResource(SHAPES[colorIndex])
}

@Preview(showBackground = true)
@Composable
fun TaskIconPreview() {
  for ((index, task) in TASKS.withIndex()) {
    task.index = index
  }

  GalleryTheme {
    Column(modifier = Modifier.background(Color.Gray)) {
      TaskIcon(task = TASK_LLM_CHAT, width = 80.dp)
    }
  }
}
