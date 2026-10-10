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

package com.google.ai.edge.gallery.customtasks.livevideo

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ─────────────────────── Shutter Flash ───────────────────────

@Composable
fun ShutterFlashEffect(trigger: Boolean, modifier: Modifier = Modifier) {
  var showFlash by remember { mutableStateOf(false) }

  LaunchedEffect(trigger) {
    if (trigger) {
      showFlash = true
      delay(150)
      showFlash = false
    }
  }

  AnimatedVisibility(
    visible = showFlash,
    enter = fadeIn(tween(50)),
    exit = fadeOut(tween(200)),
    modifier = modifier,
  ) {
    Box(modifier = Modifier.fillMaxSize().background(Color.White))
  }
}

// ─────────────────────── Capture Thumbnail ───────────────────────

@Composable
fun CaptureThumbnail(bitmap: Bitmap?, reason: String, modifier: Modifier = Modifier) {
  var visible by remember { mutableStateOf(false) }

  LaunchedEffect(bitmap) {
    if (bitmap != null) {
      visible = true
      delay(3000)
      visible = false
    }
  }

  AnimatedVisibility(
    visible = visible && bitmap != null,
    enter = scaleIn(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(),
    exit = scaleOut(tween(300)) + fadeOut(tween(300)),
    modifier = modifier,
  ) {
    Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp, color = Color.Black) {
      Column(
        modifier = Modifier.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        bitmap?.let {
          Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Captured photo",
            modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)),
          )
        }
        Spacer(Modifier.height(4.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            tint = Color.Green,
            modifier = Modifier.size(14.dp),
          )
          Spacer(Modifier.width(4.dp))
          Text(reason, color = Color.White, fontSize = 10.sp, maxLines = 1)
        }
      }
    }
  }
}

// ─────────────────────── Mode Switch Banner ───────────────────────

@Composable
fun ModeSwitchBanner(mode: String?, modifier: Modifier = Modifier) {
  var visible by remember { mutableStateOf(false) }
  var currentMode by remember { mutableStateOf("") }

  LaunchedEffect(mode) {
    if (mode != null && mode != currentMode) {
      currentMode = mode
      visible = true
      delay(2500)
      visible = false
    }
  }

  AnimatedVisibility(
    visible = visible,
    enter = slideInVertically(tween(300)) { -it } + fadeIn(tween(300)),
    exit = slideOutVertically(tween(400)) { -it } + fadeOut(tween(400)),
    modifier = modifier,
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = getModeColor(currentMode).copy(alpha = 0.9f),
      shadowElevation = 4.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        Icon(
          getModeIcon(currentMode),
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
          Text(
            getModeDisplayName(currentMode),
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
          )
          Text("Auto-detected", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
        }
      }
    }
  }
}

private fun getModeIcon(mode: String): ImageVector =
  when (mode.lowercase()) {
    "portrait" -> Icons.Default.Person
    "night" -> Icons.Default.Nightlight
    "landscape" -> Icons.Default.Landscape
    "macro" -> Icons.Default.Yard
    "action" -> Icons.Default.Speed
    else -> Icons.Default.CameraAlt
  }

private fun getModeColor(mode: String): Color =
  when (mode.lowercase()) {
    "portrait" -> Color(0xFFE91E63)
    "night" -> Color(0xFF3F51B5)
    "landscape" -> Color(0xFF4CAF50)
    "macro" -> Color(0xFFFF9800)
    "action" -> Color(0xFFF44336)
    else -> Color(0xFF607D8B)
  }

private fun getModeDisplayName(mode: String): String =
  when (mode.lowercase()) {
    "portrait" -> "Portrait Mode"
    "night" -> "Night Sight"
    "landscape" -> "Landscape"
    "macro" -> "Macro"
    "action" -> "Action Shot"
    else -> mode.replaceFirstChar { it.uppercase() }
  }

// ─────────────────────── Zoom Crosshair ───────────────────────

@Composable
fun ZoomCrosshair(
  centerX: Float,
  centerY: Float,
  zoomLevel: Float,
  isActive: Boolean,
  modifier: Modifier = Modifier,
) {
  if (!isActive) return

  val infiniteTransition = rememberInfiniteTransition(label = "crosshair")
  val pulseScale by
    infiniteTransition.animateFloat(
      initialValue = 0.9f,
      targetValue = 1.1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(800, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "pulse",
    )

  val animatedZoom by
    animateFloatAsState(
      targetValue = zoomLevel,
      animationSpec = spring(stiffness = Spring.StiffnessLow),
      label = "zoom",
    )

  Canvas(modifier = modifier) {
    val cx = centerX * size.width
    val cy = centerY * size.height
    val radius = 60.dp.toPx() * pulseScale / animatedZoom

    // Outer ring
    drawCircle(
      color = Color.Cyan.copy(alpha = 0.6f),
      radius = radius,
      center = Offset(cx, cy),
      style = Stroke(width = 2.dp.toPx()),
    )

    // Inner ring
    drawCircle(
      color = Color.Cyan.copy(alpha = 0.8f),
      radius = radius * 0.4f,
      center = Offset(cx, cy),
      style = Stroke(width = 1.5.dp.toPx()),
    )

    // Crosshair lines
    val lineLen = radius * 0.3f
    val gap = radius * 0.5f
    val lineColor = Color.Cyan.copy(alpha = 0.7f)
    val lineWidth = 1.5.dp.toPx()

    // Top
    drawLine(
      lineColor,
      Offset(cx, cy - gap),
      Offset(cx, cy - gap - lineLen),
      strokeWidth = lineWidth,
    )
    // Bottom
    drawLine(
      lineColor,
      Offset(cx, cy + gap),
      Offset(cx, cy + gap + lineLen),
      strokeWidth = lineWidth,
    )
    // Left
    drawLine(
      lineColor,
      Offset(cx - gap, cy),
      Offset(cx - gap - lineLen, cy),
      strokeWidth = lineWidth,
    )
    // Right
    drawLine(
      lineColor,
      Offset(cx + gap, cy),
      Offset(cx + gap + lineLen, cy),
      strokeWidth = lineWidth,
    )

    // Zoom level label
    drawCircle(
      color = Color.Black.copy(alpha = 0.6f),
      radius = 18.dp.toPx(),
      center = Offset(cx, cy - radius - 24.dp.toPx()),
    )
  }

  // Zoom level text (above crosshair)
  Box(modifier = modifier) {
    Text(
      "%.1fx".format(animatedZoom),
      color = Color.Cyan,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      modifier =
        Modifier.align(Alignment.TopCenter)
          .offset(x = ((centerX - 0.5f) * 300).dp, y = ((centerY - 0.15f) * 300).dp),
    )
  }
}

// ─────────────────────── Rule of Thirds Grid ───────────────────────

@Composable
fun RuleOfThirdsGrid(visible: Boolean, modifier: Modifier = Modifier) {
  if (!visible) return

  Canvas(modifier = modifier.alpha(0.3f)) {
    val thirdW = size.width / 3f
    val thirdH = size.height / 3f
    val gridColor = Color.White

    // Vertical lines
    drawLine(gridColor, Offset(thirdW, 0f), Offset(thirdW, size.height), strokeWidth = 1f)
    drawLine(gridColor, Offset(thirdW * 2, 0f), Offset(thirdW * 2, size.height), strokeWidth = 1f)
    // Horizontal lines
    drawLine(gridColor, Offset(0f, thirdH), Offset(size.width, thirdH), strokeWidth = 1f)
    drawLine(gridColor, Offset(0f, thirdH * 2), Offset(size.width, thirdH * 2), strokeWidth = 1f)
  }
}

// ─────────────────────── GemmaGaze Heatmap ───────────────────────

@Composable
fun GazeHeatmapOverlay(allScores: FloatArray?, modifier: Modifier = Modifier) {
  if (allScores == null || allScores.isEmpty()) return

  val minScore = allScores.min()
  val maxScore = allScores.max()
  val range = (maxScore - minScore).coerceAtLeast(0.001f)

  Canvas(modifier = modifier) {
    val cellW = size.width / GemmaGazeInterpreter.CELLS_W
    val cellH = size.height / GemmaGazeInterpreter.CELLS_H

    for (i in allScores.indices) {
      val cy = i / GemmaGazeInterpreter.CELLS_W
      val cx = i % GemmaGazeInterpreter.CELLS_W
      val normalized = (allScores[i] - minScore) / range

      val color =
        when {
          normalized > 0.8f -> Color.Red.copy(alpha = 0.4f)
          normalized > 0.6f -> Color(0xFFFF9800).copy(alpha = 0.3f)
          normalized > 0.4f -> Color.Yellow.copy(alpha = 0.2f)
          normalized > 0.2f -> Color.Green.copy(alpha = 0.1f)
          else -> Color.Transparent
        }

      if (color != Color.Transparent) {
        drawRect(color = color, topLeft = Offset(cx * cellW, cy * cellH), size = Size(cellW, cellH))
      }
    }
  }
}

// ─────────────────────── Scanning Line Animation ───────────────────────

@Composable
fun ScanningLineEffect(isActive: Boolean, modifier: Modifier = Modifier) {
  if (!isActive) return

  val infiniteTransition = rememberInfiniteTransition(label = "scan")
  val scanProgress by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(2000, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "scanLine",
    )

  Canvas(modifier = modifier) {
    val y = scanProgress * size.height
    drawLine(
      brush =
        Brush.horizontalGradient(
          colors =
            listOf(
              Color.Transparent,
              Color.Cyan.copy(alpha = 0.6f),
              Color.Cyan.copy(alpha = 0.8f),
              Color.Cyan.copy(alpha = 0.6f),
              Color.Transparent,
            )
        ),
      start = Offset(0f, y),
      end = Offset(size.width, y),
      strokeWidth = 2.dp.toPx(),
    )
    // Glow region below scan line
    drawRect(
      brush =
        Brush.verticalGradient(
          colors = listOf(Color.Cyan.copy(alpha = 0.1f), Color.Transparent),
          startY = y,
          endY = (y + 30.dp.toPx()).coerceAtMost(size.height),
        ),
      topLeft = Offset(0f, y),
      size = Size(size.width, 30.dp.toPx()),
    )
  }
}
