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

package com.google.ai.edge.gallery.huggingface

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.google.ai.edge.gallery.data.SOC
import com.google.ai.edge.gallery.proto.HfModelItemProto
import com.google.ai.edge.gallery.proto.HfSortOptionProto
import java.net.URI

/**
 * Extracts the simple display model name from the full repository ID (e.g., extracting
 * "Gemma-2b-IT" from "litert-community/Gemma-2b-IT"). Used as the header title on UI model cards.
 */
val HfModelItemProto.modelName: String
  get() = id.substringAfterLast("/")

/** Checks if repo contains LiteRT model files (.litertlm or .task). */
fun HfModelItemProto.hasCompatibleModelFiles(): Boolean {
  return siblingsList.any { isLiteRtLmFileName(it.rfilename) }
}

/** Gets all LiteRT model files in this model card. */
fun HfModelItemProto.getCompatibleModelFiles(): List<String> {
  return siblingsList.map { it.rfilename }.filter { isLiteRtLmFileName(it) }
}

/** Checks if the model card has at least one file compatible with current device hardware. */
fun HfModelItemProto.isDeviceCompatible(): Boolean {
  val files = getCompatibleModelFiles()
  if (files.isEmpty()) {
    return false
  }
  return files.any { isFileCompatibleWithDevice(it) }
}

/** Returns true if model is published by the official litert-community org. */
fun HfModelItemProto.isCommunityRecommended(): Boolean {
  return author.equals("litert-community", ignoreCase = true) || id.startsWith("litert-community/")
}

/** Thread-safe process-level cache for physical RAM info. */
private object DeviceMemoryCache {
  @Volatile private var cachedTotalRamBytes: Long? = null
  @Volatile private var isFetched: Boolean = false

  fun getTotalRamBytes(context: Context): Long? {
    if (isFetched) {
      return cachedTotalRamBytes
    }
    synchronized(this) {
      if (isFetched) {
        return cachedTotalRamBytes
      }
      val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val memoryInfo = ActivityManager.MemoryInfo()
      activityManager?.getMemoryInfo(memoryInfo)
      val totalRam =
        if (activityManager != null && memoryInfo.totalMem > 0L) {
          memoryInfo.totalMem
        } else {
          null
        }
      cachedTotalRamBytes = totalRam
      isFetched = true
      return totalRam
    }
  }
}

/**
 * Determines total physical RAM on Android dynamically via ActivityManager (cached once per
 * process). Returns null if unavailable.
 */
fun getDeviceTotalRamBytes(context: Context): Long? {
  return DeviceMemoryCache.getTotalRamBytes(context)
}

/**
 * Checks if a confirmed model file size exceeds half of the device's total physical RAM (50%
 * threshold). If sizeBytes <= 0L (size not yet confirmed) or deviceRamBytes is null/<=0L, returns
 * false.
 */
fun isFileTooLarge(sizeBytes: Long, deviceRamBytes: Long? = null): Boolean {
  if (deviceRamBytes == null || deviceRamBytes <= 0L || sizeBytes <= 0L) {
    return false
  }
  val halfRamThresholdBytes = deviceRamBytes / 2
  return sizeBytes > halfRamThresholdBytes
}

/** Checks if a filename has a supported LiteRT-LM model extension (.litertlm). */
fun isLiteRtLmFileName(filename: String): Boolean {
  val lower = filename.lowercase()
  return lower.endsWith(".litertlm") && !lower.contains("-web")
}

/** Known mobile hardware vendors on Android devices. */
@Suppress("ImmutableEnum")
enum class DeviceVendor(val aliases: Set<String>) {
  GOOGLE_TENSOR(setOf("tensor", "zuma", "gs101", "gs201", "laguna", "lga")),
  QUALCOMM_SNAPDRAGON(setOf("snapdragon", "qualcomm", "qcom", "sm8", "sm7")),
  MEDIATEK_DIMENSITY(setOf("dimensity", "mediatek", "mtk")),
  SAMSUNG_EXYNOS(setOf("exynos")),
  GENERIC_ANDROID(setOf("android")),
}

/** Non-Android platform signatures (e.g. Apple Silicon, Intel/AMD desktop). */
private val nonAndroidPlatformTokens =
  setOf("apple", "metal", "ios", "intel", "intel_lnl", "intel_ptl", "amd", "nvidia")

/** Structured hardware info representation of a target device. */
data class DeviceHardwareInfo(val vendor: DeviceVendor, val rawSocName: String = SOC.lowercase()) {
  /** Checks if a model filename is compatible with this device's hardware capabilities. */
  fun isCompatibleWithFile(filename: String): Boolean {
    if (!isLiteRtLmFileName(filename)) {
      return false
    }
    val lower = filename.lowercase().removeSuffix(".litertlm")
    val tokens = lower.split('_', '-', '.', ' ', '/').toSet()

    // Reject non-Android platform files (Apple, Desktop) on Android devices
    if (tokens.any { it in nonAndroidPlatformTokens }) {
      return false
    }

    // Check if file specifies an explicit vendor target
    val targetVendor =
      DeviceVendor.entries.firstOrNull { v ->
        v != DeviceVendor.GENERIC_ANDROID && tokens.any { token -> v.aliases.contains(token) }
      } ?: return true

    return targetVendor == vendor
  }

  companion object {
    /**
     * Auto-detects the current device's hardware profile generically across all Android devices.
     */
    fun current(): DeviceHardwareInfo {
      val soc = SOC.lowercase()
      val board = (Build.BOARD ?: "").lowercase()
      val hardware = (Build.HARDWARE ?: "").lowercase()
      val combined = "$soc $board $hardware".trim()

      val vendor =
        DeviceVendor.entries.firstOrNull { v ->
          v != DeviceVendor.GENERIC_ANDROID && v.aliases.any { combined.contains(it) }
        } ?: DeviceVendor.GENERIC_ANDROID
      return DeviceHardwareInfo(vendor = vendor, rawSocName = combined)
    }
  }
}

/** Determines if a specific file name in the repository is compatible with this device. */
fun isFileCompatibleWithDevice(
  filename: String,
  device: DeviceHardwareInfo = DeviceHardwareInfo.current(),
): Boolean {
  return device.isCompatibleWithFile(filename)
}

/** Represents parsed information from a Hugging Face URL. */
data class HfUrlInfo(
  val modelId: String? = null,
  val fileName: String? = null,
  val isDirectModelFile: Boolean = false,
) {
  companion object {
    private val MODEL_FILE_EXTENSIONS = setOf(".litertlm", ".task")
    private val HF_ACTION_KEYWORDS = setOf("resolve", "blob", "tree", "raw")

    private fun String.isModelFileName(): Boolean = MODEL_FILE_EXTENSIONS.any {
      endsWith(it, ignoreCase = true)
    }

    /**
     * Parses a Hugging Face URL or model ID string into an [HfUrlInfo]. Handles formats such as:
     * - https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
     * - https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
     * - https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main
     * - litert-community/gemma-4-E2B-it-litert-lm
     */
    fun parse(rawUrl: String): HfUrlInfo {
      val trimmed = rawUrl.trim()
      if (trimmed.isEmpty()) return HfUrlInfo()

      val uri =
        runCatching { URI(if (trimmed.contains("://")) trimmed else "https://$trimmed") }
          .getOrNull()

      val host = uri?.host?.lowercase().orEmpty()
      val isBareId = !trimmed.contains("://") && !host.contains(".")
      val isHfDomain = host.isEmpty() || host.contains("huggingface.co") || isBareId

      val path = if (isBareId) trimmed else uri?.path.orEmpty()
      val segments = path.split("/").filter { it.isNotBlank() }
      if (segments.isEmpty()) return HfUrlInfo()

      val rawLastSegment = segments.last().substringBefore("?")
      val isDirectFile = rawLastSegment.isModelFileName()

      if (!isHfDomain) {
        return HfUrlInfo(fileName = rawLastSegment, isDirectModelFile = isDirectFile)
      }

      val actionIdx = segments.indexOfFirst { it.lowercase() in HF_ACTION_KEYWORDS }
      if (actionIdx > 0) {
        val modelId = segments.subList(0, actionIdx).joinToString("/")
        val fileSegment =
          if (actionIdx + 2 < segments.size) {
            segments.subList(actionIdx + 2, segments.size).joinToString("/").substringBefore("?")
          } else {
            null
          }
        val isFile = fileSegment?.isModelFileName() == true
        return HfUrlInfo(
          modelId = modelId,
          fileName = if (isFile) fileSegment else null,
          isDirectModelFile = isFile,
        )
      }

      val modelId = segments.take(2).joinToString("/")
      return HfUrlInfo(
        modelId = modelId,
        fileName = if (isDirectFile) rawLastSegment else null,
        isDirectModelFile = isDirectFile,
      )
    }
  }
}

/** Parses a Hugging Face URL to extract model ID and file name if present. */
fun extractHfUrlInfo(rawUrl: String): HfUrlInfo = HfUrlInfo.parse(rawUrl)

/** URL query parameter value for Hugging Face sort options. */
val HfSortOptionProto.queryValue: String
  get() =
    when (this) {
      HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS -> "downloads"
      HfSortOptionProto.HF_SORT_OPTION_LIKES -> "likes"
      HfSortOptionProto.HF_SORT_OPTION_RECENT -> "lastModified"
      else -> "downloads"
    }

/**
 * Sorts a list of catalog models by ranking device-compatible models at the top (with official
 * litert-community models ranked first among them), and incompatible models at the bottom.
 */
fun sortModels(
  models: List<HfModelItemProto>,
  sort: HfSortOptionProto = HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS,
  communityRecommendedFirst: Boolean = true,
): List<HfModelItemProto> {
  return models.sortedWith(
    compareByDescending<HfModelItemProto> { it.isDeviceCompatible() }
      .thenByDescending { if (communityRecommendedFirst) it.isCommunityRecommended() else false }
      .thenByDescending { getSortMetric(it, sort) }
  )
}

private fun getSortMetric(model: HfModelItemProto, sort: HfSortOptionProto): Comparable<*> {
  return when (sort) {
    HfSortOptionProto.HF_SORT_OPTION_DOWNLOADS -> model.downloads
    HfSortOptionProto.HF_SORT_OPTION_LIKES -> model.likes
    HfSortOptionProto.HF_SORT_OPTION_RECENT -> model.lastModified
    else -> model.downloads
  }
}
