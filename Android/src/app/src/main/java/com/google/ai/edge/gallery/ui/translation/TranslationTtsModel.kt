/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.translation

internal enum class TranslationTtsModel(
  val displayName: String,
  val description: String,
  val packageSizeBytes: Long,
  val backendId: String,
  val revision: String,
  val licenseLabel: String,
) {
  KOKORO(
    displayName = "Kokoro",
    description = "Natural, expressive speech with language-specific voices.",
    packageSizeBytes = 147_031_220L,
    backendId = KOKORO_SHERPA_BACKEND,
    revision = KOKORO_SHERPA_PACKAGE_ID,
    licenseLabel = "Apache 2.0 model; eSpeak NG GPLv3 runtime component",
  ),
  SUPERTONIC_3(
    displayName = "Supertonic 3",
    description = "Fast multilingual speech with consistent voices across languages.",
    packageSizeBytes = 128_774_318L,
    backendId = SUPERTONIC_SHERPA_BACKEND,
    revision = SUPERTONIC_SHERPA_PACKAGE_ID,
    licenseLabel = "OpenRAIL-M model",
  );

  companion object {
    val DEFAULT = KOKORO

    fun fromStoredValue(value: String): TranslationTtsModel =
      entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
  }
}
