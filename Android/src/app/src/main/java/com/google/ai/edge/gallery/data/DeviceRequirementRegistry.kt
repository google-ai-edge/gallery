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

package com.google.ai.edge.gallery.data

import android.os.Build
import android.util.Log

private const val TAG = "AGDeviceRequirements"

/** Names of the device-requirement sets that the app refers to in code. */
object DeviceRequirementSets {
  /** AICore LLMs. Read from the legacy top-level `aicoreRequirements` allowlist key. */
  const val AICORE_LLM = "aicore_llm"
}

/**
 * The identifiers of a device that device-requirement sets match against.
 *
 * @property model the device's `Build.MODEL`.
 * @property soc the device's `Build.SOC_MODEL`.
 */
data class DeviceIdentity(val model: String, val soc: String) {
  companion object {
    /** Returns the identity of the device the app runs on. */
    fun current(): DeviceIdentity =
      DeviceIdentity(model = Build.MODEL.orEmpty().trim(), soc = SOC.trim())
  }
}

/**
 * The named device-requirement sets of a [ModelAllowlist], and the one rule that matches a device
 * against them.
 *
 * Sets come from [ModelAllowlist.deviceRequirements]. The legacy
 * [ModelAllowlist.aicoreRequirements] is read as the set [DeviceRequirementSets.AICORE_LLM], unless
 * `deviceRequirements` defines that name itself.
 *
 * A device satisfies a set if its lowercase model is in a group's `deviceModels`, or its lowercase
 * SOC is in a group's `socModels`. An unknown or empty set is never satisfied.
 */
class DeviceRequirementRegistry(sets: Map<String, DeviceRequirements>) {
  private val matchers: Map<String, Matcher> = sets.mapValues { (_, set) -> Matcher(set) }

  /** Returns whether [device] satisfies the set named [setName]. */
  fun isSatisfied(setName: String, device: DeviceIdentity): Boolean {
    val matcher = matchers[setName]
    if (matcher == null) {
      Log.w(TAG, "Unknown device-requirement set '$setName'")
      return false
    }
    if (matcher.isEmpty) {
      Log.w(TAG, "Device-requirement set '$setName' has no devices")
      return false
    }
    return matcher.matches(device)
  }

  /**
   * Returns the set that the model [allowedModel] needs, or null if it can run on any device.
   *
   * Delegates to [AllowedModel.requiredDeviceRequirementSet].
   */
  fun requiredSetFor(allowedModel: AllowedModel): String? =
    allowedModel.requiredDeviceRequirementSet

  private class Matcher(set: DeviceRequirements) {
    private val deviceModels: Set<String> =
      set.allowedDeviceGroups
        .orEmpty()
        .flatMap { (it.deviceModels as List<String>?).orEmpty().filterNotNull() }
        .toLowercaseSet()
    private val socModels: Set<String> =
      set.allowedDeviceGroups
        .orEmpty()
        .flatMap { (it.socModels as List<String>?).orEmpty().filterNotNull() }
        .toLowercaseSet()

    val isEmpty: Boolean
      get() = deviceModels.isEmpty() && socModels.isEmpty()

    fun matches(device: DeviceIdentity): Boolean =
      device.model.trim().lowercase() in deviceModels || device.soc.trim().lowercase() in socModels

    /**
     * Lowercases and trims the entries, dropping blank ones, so a blank device value never matches.
     */
    private fun List<String>.toLowercaseSet(): Set<String> =
      filter { it.isNotBlank() }.map { it.trim().lowercase() }.toSet()
  }

  companion object {
    /** Builds the registry of the sets in [allowlist]. */
    fun from(allowlist: ModelAllowlist): DeviceRequirementRegistry {
      val legacy = allowlist.aicoreRequirements
      val named = allowlist.deviceRequirements.orEmpty()
      if (legacy != null && DeviceRequirementSets.AICORE_LLM in named) {
        Log.w(
          TAG,
          "Both aicoreRequirements and deviceRequirements.${DeviceRequirementSets.AICORE_LLM}" +
            " are set; using deviceRequirements.${DeviceRequirementSets.AICORE_LLM}",
        )
      }
      return DeviceRequirementRegistry(
        buildMap {
          if (legacy != null) put(DeviceRequirementSets.AICORE_LLM, legacy)
          putAll(named)
        }
      )
    }
  }
}
