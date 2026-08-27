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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.theme.customColors

@Composable
fun getTaskBgColor(task: Task): Color {
  val colors = MaterialTheme.customColors.taskBgColors
  if (colors.isEmpty()) return Color.Transparent
  val colorIndex: Int = (task.index.coerceAtLeast(0)) % colors.size
  return colors[colorIndex]
}

@Composable
fun getTaskBgGradientColors(task: Task): List<Color> {
  val colors = MaterialTheme.customColors.taskBgGradientColors
  if (colors.isEmpty()) return emptyList()
  val colorIndex: Int = (task.index.coerceAtLeast(0)) % colors.size
  return colors[colorIndex]
}

@Composable
fun getTaskIconColor(task: Task): Color {
  val colors = MaterialTheme.customColors.taskIconColors
  if (colors.isEmpty()) return Color.Transparent
  val colorIndex: Int = (task.index.coerceAtLeast(0)) % colors.size
  return colors[colorIndex]
}

@Composable
fun getTaskIconColor(index: Int): Color {
  val colors = MaterialTheme.customColors.taskIconColors
  if (colors.isEmpty()) return Color.Transparent
  val colorIndex: Int = (index.coerceAtLeast(0)) % colors.size
  return colors[colorIndex]
}
