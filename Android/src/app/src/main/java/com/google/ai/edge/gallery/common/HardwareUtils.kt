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
}
