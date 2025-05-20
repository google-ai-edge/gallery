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
import android.util.Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASKS
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageBenchmarkResult
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.Histogram
import com.google.ai.edge.gallery.ui.common.chat.Stat
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.customColors
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "AGUtils"
private const val LAUNCH_INFO_FILE_NAME = "launch_info"

private val STATS = listOf(
  Stat(id = "min", label = "Min", unit = "ms"),
  Stat(id = "max", label = "Max", unit = "ms"),
  Stat(id = "avg", label = "Avg", unit = "ms"),
  Stat(id = "stddev", label = "Stddev", unit = "ms")
)

interface LatencyProvider {
  val latencyMs: Float
}

private const val START_THINKING = "***Thinking...***"
private const val DONE_THINKING = "***Done thinking***"

data class JsonObjAndTextContent<T>(
  val jsonObj: T, val textContent: String,
)

data class LaunchInfo(
  val ts: Long
)

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

fun convertValueToTargetType(value: Any, valueType: ValueType): Any {
  return when (valueType) {
    ValueType.INT -> when (value) {
      is Int -> value
      is Float -> value.toInt()
      is Double -> value.toInt()
      is String -> value.toIntOrNull() ?: ""
      is Boolean -> if (value) 1 else 0
      else -> ""
    }

    ValueType.FLOAT -> when (value) {
      is Int -> value.toFloat()
      is Float -> value
      is Double -> value.toFloat()
      is String -> value.toFloatOrNull() ?: ""
      is Boolean -> if (value) 1f else 0f
      else -> ""
    }

    ValueType.DOUBLE -> when (value) {
      is Int -> value.toDouble()
      is Float -> value.toDouble()
      is Double -> value
      is String -> value.toDoubleOrNull() ?: ""
      is Boolean -> if (value) 1.0 else 0.0
      else -> ""
    }

    ValueType.BOOLEAN -> when (value) {
      is Int -> value == 0
      is Boolean -> value
      is Float -> abs(value) > 1e-6
      is Double -> abs(value) > 1e-6
      is String -> value.isNotEmpty()
      else -> false
    }

    ValueType.STRING -> value.toString()
  }
}

fun getDistinctiveColor(index: Int): Color {
  val colors = listOf(
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
    Color(0xff000075)
  )
  return colors[index % colors.size]
}

fun Context.createTempPictureUri(
  fileName: String = "picture_${System.currentTimeMillis()}", fileExtension: String = ".png"
): Uri {
  val tempFile = File.createTempFile(
    fileName, fileExtension, cacheDir
  ).apply {
    createNewFile()
  }

  return FileProvider.getUriForFile(
    applicationContext,
    "com.google.aiedge.gallery.provider" /* {applicationId}.provider */,
    tempFile
  )
}

fun runBasicBenchmark(
  model: Model,
  warmupCount: Int,
  iterations: Int,
  chatViewModel: ChatViewModel,
  inferenceFn: () -> LatencyProvider,
  chatMessageType: ChatMessageType,
) {
  val start = System.currentTimeMillis()
  var lastUpdateTs = 0L
  val update: (ChatMessageBenchmarkResult) -> Unit = { message ->
    if (lastUpdateTs == 0L) {
      chatViewModel.addMessage(
        model = model,
        message = message,
      )
      lastUpdateTs = System.currentTimeMillis()
    } else {
      val curTs = System.currentTimeMillis()
      if (curTs - lastUpdateTs > 500) {
        chatViewModel.replaceLastMessage(model = model, message = message, type = chatMessageType)
        lastUpdateTs = curTs
      }
    }
  }

  // Warmup.
  val latencies: MutableList<Float> = mutableListOf()
  for (count in 1..warmupCount) {
    inferenceFn()
    update(
      ChatMessageBenchmarkResult(
        orderedStats = STATS,
        statValues = calculateStats(min = 0f, max = 0f, sum = 0f, latencies = latencies),
        histogram = calculateLatencyHistogram(
          latencies = latencies, min = 0f, max = 0f, avg = 0f
        ),
        values = latencies,
        warmupCurrent = count,
        warmupTotal = warmupCount,
        iterationCurrent = 0,
        iterationTotal = iterations,
        latencyMs = (System.currentTimeMillis() - start).toFloat(),
        highlightStat = "avg"
      )
    )
  }

  // Benchmark iterations.
  var min = Float.MAX_VALUE
  var max = 0f
  var sum = 0f
  for (count in 1..iterations) {
    val result = inferenceFn()
    val latency = result.latencyMs
    min = min(min, latency)
    max = max(max, latency)
    sum += latency
    latencies.add(latency)

    val curTs = System.currentTimeMillis()
    if (curTs - lastUpdateTs > 500 || count == iterations) {
      lastUpdateTs = curTs

      val stats = calculateStats(min = min, max = max, sum = sum, latencies = latencies)
      chatViewModel.replaceLastMessage(
        model = model,
        message = ChatMessageBenchmarkResult(
          orderedStats = STATS,
          statValues = stats,
          histogram = calculateLatencyHistogram(
            latencies = latencies,
            min = min,
            max = max,
            avg = stats["avg"] ?: 0f,
          ),
          values = latencies,
          warmupCurrent = warmupCount,
          warmupTotal = warmupCount,
          iterationCurrent = count,
          iterationTotal = iterations,
          latencyMs = (System.currentTimeMillis() - start).toFloat(),
          highlightStat = "avg"
        ),
        type = chatMessageType,
      )
    }

    // Go through other benchmark messages and update their buckets for the common min/max values.
    var allMin = Float.MAX_VALUE
    var allMax = 0f
    val allMessages = chatViewModel.uiState.value.messagesByModel[model.name] ?: listOf()
    for (message in allMessages) {
      if (message is ChatMessageBenchmarkResult) {
        val curMin = message.statValues["min"] ?: 0f
        val curMax = message.statValues["max"] ?: 0f
        allMin = min(allMin, curMin)
        allMax = max(allMax, curMax)
      }
    }

    for ((index, message) in allMessages.withIndex()) {
      if (message === allMessages.last()) {
        break
      }
      if (message is ChatMessageBenchmarkResult) {
        val updatedMessage = ChatMessageBenchmarkResult(
          orderedStats = STATS,
          statValues = message.statValues,
          histogram = calculateLatencyHistogram(
            latencies = message.values,
            min = allMin,
            max = allMax,
            avg = message.statValues["avg"] ?: 0f,
          ),
          values = message.values,
          warmupCurrent = message.warmupCurrent,
          warmupTotal = message.warmupTotal,
          iterationCurrent = message.iterationCurrent,
          iterationTotal = message.iterationTotal,
          latencyMs = message.latencyMs,
          highlightStat = "avg"
        )
        chatViewModel.replaceMessage(model = model, index = index, message = updatedMessage)
      }
    }
  }
}

private fun calculateStats(
  min: Float, max: Float, sum: Float, latencies: MutableList<Float>
): MutableMap<String, Float> {
  val avg = if (latencies.size == 0) 0f else sum / latencies.size
  val squaredDifferences = latencies.map { (it - avg).pow(2) }
  val variance = squaredDifferences.average()
  val stddev = if (latencies.size == 0) 0f else sqrt(variance).toFloat()
  var medium = 0f
  if (latencies.size == 1) {
    medium = latencies[0]
  } else if (latencies.size > 1) {
    latencies.sort()
    val middle = latencies.size / 2
    medium =
      if (latencies.size % 2 == 0) (latencies[middle - 1] + latencies[middle]) / 2.0f else latencies[middle]
  }
  return mutableMapOf(
    "min" to min, "max" to max, "avg" to avg, "stddev" to stddev, "medium" to medium
  )
}

fun calculateLatencyHistogram(
  latencies: List<Float>, min: Float, max: Float, avg: Float, numBuckets: Int = 20
): Histogram {
  if (latencies.isEmpty() || numBuckets <= 0) {
    return Histogram(
      buckets = List(numBuckets) { 0 }, maxCount = 0
    )
  }

  if (min == max) {
    // All latencies are the same.
    val result = MutableList(numBuckets) { 0 }
    result[0] = latencies.size
    return Histogram(buckets = result, maxCount = result[0], highlightBucketIndex = 0)
  }

  val bucketSize = (max - min) / numBuckets

  val histogram = MutableList(numBuckets) { 0 }

  val getBucketIndex: (value: Float) -> Int = {
    var bucketIndex = ((it - min) / bucketSize).toInt()
    // Handle the case where latency equals maxLatency
    if (bucketIndex == numBuckets) {
      bucketIndex = numBuckets - 1
    }
    bucketIndex
  }

  for (latency in latencies) {
    val bucketIndex = getBucketIndex(latency)
    histogram[bucketIndex]++
  }

  val avgBucketIndex = getBucketIndex(avg)
  return Histogram(
    buckets = histogram,
    maxCount = histogram.maxOrNull() ?: 0,
    highlightBucketIndex = avgBucketIndex
  )
}

fun getConfigValueString(value: Any, config: Config): String {
  var strNewValue = "$value"
  if (config.valueType == ValueType.FLOAT) {
    strNewValue = "%.2f".format(value)
  }
  return strNewValue
}

@Composable
fun getTaskBgColor(task: Task): Color {
  val colorIndex: Int = task.index % MaterialTheme.customColors.taskBgColors.size
  return MaterialTheme.customColors.taskBgColors[colorIndex]
}

@Composable
fun getTaskIconColor(task: Task): Color {
  val colorIndex: Int = task.index % MaterialTheme.customColors.taskIconColors.size
  return MaterialTheme.customColors.taskIconColors[colorIndex]
}

@Composable
fun getTaskIconColor(index: Int): Color {
  val colorIndex: Int = index % MaterialTheme.customColors.taskIconColors.size
  return MaterialTheme.customColors.taskIconColors[colorIndex]
}

fun checkNotificationPermissionAndStartDownload(
  context: Context,
  launcher: ManagedActivityResultLauncher<String, Boolean>,
  modelManagerViewModel: ModelManagerViewModel,
  task: Task,
  model: Model
) {
  // Check permission
  when (PackageManager.PERMISSION_GRANTED) {
    // Already got permission. Call the lambda.
    ContextCompat.checkSelfPermission(
      context, Manifest.permission.POST_NOTIFICATIONS
    ) -> {
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

fun cleanUpMediapipeTaskErrorMessage(message: String): String {
  val index = message.indexOf("=== Source Location Trace")
  if (index >= 0) {
    return message.substring(0, index)
  }
  return message
}

fun processTasks() {
  for ((index, task) in TASKS.withIndex()) {
    task.index = index
    for (model in task.models) {
      model.preProcess()
    }
  }
}

fun processLlmResponse(response: String): String {
  // Add "thinking" and "done thinking" around the thinking content.
  var newContent =
    response.replace("<think>", "$START_THINKING\n").replace("</think>", "\n$DONE_THINKING")

  // Remove empty thinking content.
  val endThinkingIndex = newContent.indexOf(DONE_THINKING)
  if (endThinkingIndex >= 0) {
    val thinkingContent =
      newContent.substring(0, endThinkingIndex + DONE_THINKING.length).replace(START_THINKING, "")
        .replace(DONE_THINKING, "")
    if (thinkingContent.isBlank()) {
      newContent = newContent.substring(endThinkingIndex + DONE_THINKING.length)
    }
  }

  newContent = newContent.replace("\\n", "\n")

  return newContent
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> getJsonResponse(url: String): JsonObjAndTextContent<T>? {
  try {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connect()

    val responseCode = connection.responseCode
    if (responseCode == HttpURLConnection.HTTP_OK) {
      val inputStream = connection.inputStream
      val response = inputStream.bufferedReader().use { it.readText() }

      // Parse JSON using kotlinx.serialization
      val json = Json {
        // Handle potential extra fields
        ignoreUnknownKeys = true
        allowComments = true
        allowTrailingComma = true
      }
      val jsonObj = json.decodeFromString<T>(response)
      return JsonObjAndTextContent(jsonObj = jsonObj, textContent = response)
    } else {
      Log.e("AGUtils", "HTTP error: $responseCode")
    }
  } catch (e: Exception) {
    Log.e(
      "AGUtils", "Error when getting json response: ${e.message}"
    )
    e.printStackTrace()
  }

  return null
}

fun writeLaunchInfo(context: Context) {
  try {
    val gson = Gson()
    val launchInfo = LaunchInfo(ts = System.currentTimeMillis())
    val jsonString = gson.toJson(launchInfo)
    val file = File(context.getExternalFilesDir(null), LAUNCH_INFO_FILE_NAME)
    file.writeText(jsonString)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to write launch info", e)
  }
}

fun readLaunchInfo(context: Context): LaunchInfo? {
  try {
    val gson = Gson()
    val type = object : TypeToken<LaunchInfo>() {}.type
    val file = File(context.getExternalFilesDir(null), LAUNCH_INFO_FILE_NAME)
    val content = file.readText()
    return gson.fromJson(content, type)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to read launch info", e)
    return null
  }
}