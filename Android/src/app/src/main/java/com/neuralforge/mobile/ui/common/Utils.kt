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

package com.neuralforge.mobile.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.neuralforge.mobile.data.Model
import com.neuralforge.mobile.data.Task
import com.neuralforge.mobile.ui.modelmanager.ModelManagerViewModel
import java.io.File
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.delay

private const val TAG = "AGUtils"

/** Format the bytes into a human-readable format. */
fun Long.humanReadableSize(si: Boolean = true, extraDecimalForGbAndAbove: Boolean = false): String {
  val bytes = this

  val unit = if (si) 1000 else 1024
  if (bytes < unit) return "$bytes B"
  val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
  val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
  var formatString = "%.1f %sB"
  if (extraDecimalForGbAndAbove && pre.lowercase() != "k" && pre != "M") {
    formatString = "%.2f %sB"
  }
  return formatString.format(bytes / unit.toDouble().pow(exp.toDouble()), pre)
}

fun Float.humanReadableDuration(): String {
  val milliseconds = this
  if (milliseconds < 1000) {
    return "$milliseconds ms"
  }
  val seconds = milliseconds / 1000f
  if (seconds < 60) {
    return "%.1f s".format(seconds)
  }

  val minutes = seconds / 60f
  if (minutes < 60) {
    return "%.1f min".format(minutes)
  }

  val hours = minutes / 60f
  return "%.1f h".format(hours)
}

fun Long.formatToHourMinSecond(): String {
  val ms = this
  if (ms < 0) {
    return "-"
  }

  val seconds = ms / 1000
  val hours = seconds / 3600
  val minutes = (seconds % 3600) / 60
  val remainingSeconds = seconds % 60

  val parts = mutableListOf<String>()

  if (hours > 0) {
    parts.add("$hours h")
  }
  if (minutes > 0) {
    parts.add("$minutes min")
  }
  if (remainingSeconds > 0 || (hours == 0L && minutes == 0L)) {
    parts.add("$remainingSeconds sec")
  }

  return parts.joinToString(" ")
}

fun getDistinctiveColor(index: Int): Color {
  val colors =
    listOf(
      //      Color(0xffe6194b),
      Color(0xff3cb44b),
      Color(0xffffe119),
      Color(0xff4363d8),
      Color(0xfff58231),
      Color(0xff911eb4),
      Color(0xff46f0f0),
      Color(0xfff032e6),
      Color(0xffbcf60c),
      Color(0xfffabebe),
      Color(0xff008080),
      Color(0xffe6beff),
      Color(0xff9a6324),
      Color(0xfffffac8),
      Color(0xff800000),
      Color(0xffaaffc3),
      Color(0xff808000),
      Color(0xffffd8b1),
      Color(0xff000075),
    )
  return colors[index % colors.size]
}

fun Context.createTempPictureUri(
  fileName: String = "picture_${System.currentTimeMillis()}",
  fileExtension: String = ".png",
): Uri {
  val tempFile = File.createTempFile(fileName, fileExtension, cacheDir).apply { createNewFile() }

  return FileProvider.getUriForFile(
    applicationContext,
    "com.google.aiedge.gallery.provider" /* {applicationId}.provider */,
    tempFile,
  )
}

fun checkNotificationPermissionAndStartDownload(
  context: Context,
  launcher: ManagedActivityResultLauncher<String, Boolean>,
  modelManagerViewModel: ModelManagerViewModel,
  task: Task,
  model: Model,
) {
  // Check permission
  when (PackageManager.PERMISSION_GRANTED) {
    // Already got permission. Call the lambda.
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) -> {
      modelManagerViewModel.downloadModel(task = task, model = model)
    }

    // Otherwise, ask for permission
    else -> {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }
}

fun ensureValidFileName(fileName: String): String {
  return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}

/**
 * A composable that animates text appearing to "swipe" into view from left to right.
 *
 * This effect is created by animating a linear gradient brush that colors the text, combined with
 * an alpha animation for fading. The text gradually becomes visible as the gradient moves across
 * it, revealing the full text by the end of the animation.
 */
@Composable
fun SwipingText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
  animationDelay: Long = 0,
  animationDurationMs: Int = 300,
  edgeGradientRelativeSize: Float = 1.0f,
) {
  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = animationDelay,
      animationDurationMs = animationDurationMs,
      animationLabel = "swiping text",
      easing = LinearEasing,
    )
  Text(
    text,
    style =
      style.copy(
        brush =
          linearGradient(
            colorStops =
              arrayOf(
                (1f + edgeGradientRelativeSize) * progress - edgeGradientRelativeSize to color,
                (1f + edgeGradientRelativeSize) * progress to Color.Transparent,
              )
          )
      ),
    modifier = modifier.graphicsLayer { alpha = progress },
  )
}

/**
 * A composable that animates the revelation of text using a linear gradient mask.
 *
 * The text appears to "wipe" into view from left to right, controlled by an animation progress.
 * This is achieved by drawing a gradient mask over the text that moves horizontally, revealing the
 * content as the animation progresses.
 *
 * The core of the revelation effect relies on `BlendMode.DstOut`. First, the text content
 * (`drawContent()`) is rendered as the "destination." Then, a rectangle filled with a `maskBrush`
 * (our linear gradient) is drawn as the "source." `DstOut` works by taking the destination (the
 * text) and making transparent any parts that overlap with the opaque (non-transparent) regions of
 * the source (the red part of our mask). As the `maskBrush` animates and slides across the text,
 * the transparent portion of the mask "reveals" the text, creating the wipe-in effect.
 */
@Composable
fun RevealingText(
  text: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
  animationDelay: Long = 0,
  animationDurationMs: Int = 300,
  edgeGradientRelativeSize: Float = 0.5f,
  extraTextPadding: Dp = 16.dp,
) {
  val progress =
    rememberDelayedAnimationProgress(
      initialDelay = animationDelay,
      animationDurationMs = animationDurationMs,
      animationLabel = "revealing text",
    )
  val maskBrush =
    linearGradient(
      colorStops =
        arrayOf(
          (1f + edgeGradientRelativeSize) * progress - edgeGradientRelativeSize to
            Color.Transparent,
          (1f + edgeGradientRelativeSize) * progress to Color.Red,
        )
    )
  Box(
    modifier =
      modifier
        .graphicsLayer(alpha = 0.99f, compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
          drawContent()
          drawRect(brush = maskBrush, blendMode = BlendMode.DstOut)
        },
    contentAlignment = Alignment.Center,
  ) {
    Text(text, style = style, modifier = Modifier.padding(horizontal = extraTextPadding))
  }
}

/** Another version of RevealingText with animationProgress passed in. */
@Composable
fun RevealingText(
  text: String,
  style: TextStyle,
  animationProgress: Float,
  modifier: Modifier = Modifier,
  textAlign: TextAlign? = null,
  edgeGradientRelativeSize: Float = 0.5f,
) {
  val maskBrush =
    linearGradient(
      colorStops =
        arrayOf(
          (1f + edgeGradientRelativeSize) * animationProgress - edgeGradientRelativeSize to
            Color.Transparent,
          (1f + edgeGradientRelativeSize) * animationProgress to Color.Red,
        )
    )
  Box(
    modifier =
      modifier
        .graphicsLayer(alpha = 0.99f, compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
          drawContent()
          drawRect(brush = maskBrush, blendMode = BlendMode.DstOut)
        },
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      style = style,
      modifier = modifier.padding(horizontal = 16.dp),
      textAlign = textAlign,
    )
  }
}

/**
 * A reusable Composable function that provides an animated float progress value after an initial
 * delay.
 *
 * This function is ideal for creating "enter" animations that start after a specified pause,
 * allowing for staggered or timed visual effects. It uses `animateFloatAsState` to smoothly
 * transition the progress from 0f to 1f.
 */
@Composable
fun rememberDelayedAnimationProgress(
  initialDelay: Long = 0,
  animationDurationMs: Int,
  animationLabel: String,
  easing: Easing = FastOutSlowInEasing,
): Float {
  var startAnimation by remember { mutableStateOf(false) }
  val progress: Float by
    animateFloatAsState(
      if (startAnimation) 1f else 0f,
      label = animationLabel,
      animationSpec = tween(durationMillis = animationDurationMs, easing = easing),
    )
  LaunchedEffect(Unit) {
    delay(initialDelay)
    startAnimation = true
  }
  return progress
}
