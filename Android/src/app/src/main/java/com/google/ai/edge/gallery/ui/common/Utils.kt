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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

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
