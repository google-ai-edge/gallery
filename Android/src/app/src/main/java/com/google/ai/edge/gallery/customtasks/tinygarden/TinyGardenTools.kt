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
package com.google.ai.edge.gallery.customtasks.tinygarden

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

private const val TAG = "AGTGTools"

/** The items that can be used in the Tiny Garden game. */
enum class TinyGardenItem(val label: String) {
  SUNFLOWER(label = "sunflower"),
  DAISY(label = "daisy"),
  ROSE(label = "rose"),
  SPECIAL(label = "secret"),
  WATERING_CAN(label = "water"),
  SCYTHE(label = "harvest"),
}

/** A command to be sent to the Tiny Garden game. */
data class TinyGardenCommand(
  // This is 1-based.
  val item: Int,
  val plots: List<Int>,
  val ts: Long = System.currentTimeMillis(),
)

/**
 * A class that defines the tools available to the Tiny Garden game.
 *
 * Instructions:
 * https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md#6-defining-and-using-tools
 */
class TinyGardenTools(val onFunctionCalled: (command: TinyGardenCommand) -> Unit) {

  /** Waters one or more garden plots. */
  @Tool(description = "Water one or more garden plots.")
  fun waterPlots(
    @ToolParam(description = "The IDs of the plots to water.") plots: List<Int>
  ): Map<String, Any> {
    Log.d(TAG, "waterPlots. Plots=$plots")

    onFunctionCalled(
      TinyGardenCommand(item = TinyGardenItem.WATERING_CAN.ordinal + 1, plots = plots)
    )

    // Return a response object to the model confirming the action.
    return mapOf("result" to "success", "plots" to plots)
  }

  /** Plants a seed in one or more garden plots. */
  @Tool(description = "Plant a seed in one or more garden plots.")
  fun plantSeed(
    @ToolParam(description = "The name of the seed to plant.") seed: String,
    @ToolParam(description = "The IDs of the plots to plant a seed in.") plots: List<Int>,
  ): Map<String, Any> {
    Log.d(TAG, "plantSeed. seed: $seed, plots; $plots")

    val itemId =
      when (seed.lowercase()) {
        "sunflower" -> TinyGardenItem.SUNFLOWER.ordinal
        "daisy" -> TinyGardenItem.DAISY.ordinal
        "rose" -> TinyGardenItem.ROSE.ordinal
        "special",
        "edge gallery",
        "secret" -> TinyGardenItem.SPECIAL.ordinal
        else -> -1
      } + 1
    if (itemId > 0) {
      onFunctionCalled(TinyGardenCommand(item = itemId, plots = plots))
    }

    // Return a response object to the model confirming the action
    return mapOf("result" to "success", "seed" to seed, "plots" to plots)
  }

  /** Harvests one or more garden plots. */
  @Tool(description = "Harvest one or more garden plots.")
  fun harvestPlots(
    @ToolParam(description = "The IDs of the plots to harvest.") plots: List<Int>
  ): Map<String, Any> {
    Log.d(TAG, "harvestPlots. Plots=$plots")

    onFunctionCalled(TinyGardenCommand(item = TinyGardenItem.SCYTHE.ordinal + 1, plots = plots))

    // Return a response object to the model confirming the action.
    return mapOf("result" to "success", "plots" to plots)
  }
}
