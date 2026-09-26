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

package com.google.ai.edge.gallery.common

import android.os.Build
import java.util.Locale

/** Utility object for hardware and device detection. */
object HardwareUtils {
  private val PIXEL_9_CODENAMES = setOf("tokay", "caiman", "komodo", "comet", "tegu")

  fun isOldMaliGpu(
    hardware: String = Build.HARDWARE,
    board: String = Build.BOARD,
    socModel: String = Build.SOC_MODEL,
    device: String = Build.DEVICE,
    model: String = Build.MODEL,
  ): Boolean {
    val lowerModel = model.lowercase(Locale.ROOT)
    val lowerDevice = device.lowercase(Locale.ROOT)
    val lowerBoard = board.lowercase(Locale.ROOT)
    val lowerSocModel = socModel.lowercase(Locale.ROOT)
    val lowerHardware = hardware.lowercase(Locale.ROOT)

    // Pixel 9 and later should use GPU.
    if (
      lowerModel.contains("pixel 9") ||
        lowerDevice in PIXEL_9_CODENAMES ||
        lowerBoard in PIXEL_9_CODENAMES ||
        lowerSocModel.contains("tensor g4") ||
        lowerHardware.contains("zumapro")
    ) {
      return false
    }
    val combined = "$lowerHardware $lowerBoard $lowerSocModel"
    return !combined.contains("malibu") &&
      (combined.contains("exynos") || combined.contains("mali"))
  }

  private val TENSOR_G3_OR_HIGHER_CODENAMES =
    setOf(
      // Tensor G3 (Pixel 8 / 8 Pro / 8a)
      "shiba",
      "husky",
      "akita",
      "zuma",
      // Tensor G4 (Pixel 9 / 9 Pro / 9 Pro XL / 9 Pro Fold / 9a)
      "tokay",
      "caiman",
      "komodo",
      "comet",
      "tegu",
      "zumapro",
      // Tensor G5 (Pixel 10 / 10 Pro / 10 Pro XL / 10 Pro Fold)
      "frankel",
      "blazer",
      "mustang",
      "rango",
      "laguna",
      // Tensor G6 (Pixel 11)
      "malibu",
    )

  /** Returns true if device is Google Tensor G3 or higher (Pixel 8 and newer). */
  fun isTensorG3OrHigher(
    hardware: String = Build.HARDWARE,
    board: String = Build.BOARD,
    socModel: String = Build.SOC_MODEL,
    device: String = Build.DEVICE,
    model: String = Build.MODEL,
  ): Boolean {
    val lowerModel = model.lowercase(Locale.ROOT)
    val lowerDevice = device.lowercase(Locale.ROOT)
    val lowerBoard = board.lowercase(Locale.ROOT)
    val lowerSocModel = socModel.lowercase(Locale.ROOT)
    val lowerHardware = hardware.lowercase(Locale.ROOT)

    // Check Pixel model name (e.g. "Pixel 8", "Pixel 9 Pro", "Pixel 10")
    val pixelMatch = Regex("""pixel\s+(\d+)""").find(lowerModel)
    if (pixelMatch != null) {
      val generation = pixelMatch.groupValues[1].toIntOrNull() ?: 0
      if (generation >= 8) return true
    }

    // Check Tensor SoC model (e.g. "tensor g3", "tensor g4", "tensor g5")
    val tensorMatch = Regex("""tensor\s+g?(\d+)""").find(lowerSocModel)
    if (tensorMatch != null) {
      val gen = tensorMatch.groupValues[1].toIntOrNull() ?: 0
      if (gen >= 3) return true
    }

    // Check known codenames and hardware boards
    if (
      lowerDevice in TENSOR_G3_OR_HIGHER_CODENAMES ||
        lowerBoard in TENSOR_G3_OR_HIGHER_CODENAMES ||
        lowerHardware in TENSOR_G3_OR_HIGHER_CODENAMES
    ) {
      return true
    }

    return false
  }
}
