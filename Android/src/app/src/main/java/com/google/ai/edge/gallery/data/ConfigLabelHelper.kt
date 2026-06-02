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

package com.google.ai.edge.gallery.data

import android.content.Context
import com.google.ai.edge.gallery.R

/**
 * Helper functions to resolve localized labels for [ConfigKey] and [Accelerator] instances.
 *
 * The string resources follow the naming convention:
 * - For ConfigKey: `config_${configKey.id}`  (e.g. "config_max_tokens")
 * - For Accelerator: `config_${accelerator.name.lowercase()}`  (e.g. "config_cpu")
 *
 * These resources are defined in values/strings.xml under the "Config labels" section.
 */

/** Returns the localized label for a [ConfigKey], falling back to [ConfigKey.label]. */
fun getConfigLabel(context: Context, configKey: ConfigKey): String {
  val resName = "config_${configKey.id}"
  val resId = context.resources.getIdentifier(resName, "string", context.packageName)
  return if (resId != 0) {
    context.getString(resId)
  } else {
    configKey.label
  }
}

/** Returns the localized label for an [Accelerator], falling back to [Accelerator.label]. */
fun getAcceleratorLabel(context: Context, accelerator: Accelerator): String {
  val resName = "config_${accelerator.name.lowercase()}"
  val resId = context.resources.getIdentifier(resName, "string", context.packageName)
  return if (resId != 0) {
    context.getString(resId)
  } else {
    accelerator.label
  }
}
