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

package com.google.ai.edge.gallery.services.asr

import com.google.ai.edge.gallery.data.Accelerator

/** Default constants shared across ASR settings, engine preparation, and UI pickers. */
object AsrDefaults {
  /** Display name representing the built-in OS speech recognizer (`SystemSpeechAsrService`). */
  const val SYSTEM_DEFAULT_MODEL_NAME = "System Default"

  /** Default BCP-47 language code for ASR recognition. */
  const val LANGUAGE_CODE = "en-US"

  /** Whether interim (partial) transcription hypotheses are shown by default. */
  const val SHOW_INTERIM_RESULTS = true

  /** Default accelerator for on-device ASR models that support hardware acceleration. */
  val BACKEND: Accelerator = Accelerator.GPU
}

/**
 * Metadata describing an ASR model or engine option exposed by an [AsrService].
 *
 * @property name Unique display or file name identifying this model or engine option.
 * @property modelId Canonical [AsrModelIdentifier] for this model or engine entry.
 * @property engineType The [AsrEngineType] that backs this model entry.
 * @property supportedLanguages BCP-47 language tags supported by this model.
 * @property installsLanguagePacks Whether this model expects users to explicitly download a
 *   language pack for each language.
 * @property isLanguageAutoDetected Whether this model auto-detects the spoken language regardless
 *   of the configured locale.
 * @property requiresRepositoryModel Whether this engine is provisioned through a supplementary
 *   repository/allowlist Model (true by default).
 * @property hasFixedLanguageList Whether this engine has a fixed list of languages known at compile
 *   time.
 * @property supportedAccelerators Hardware execution targets supported by this model (empty for
 *   system-managed engines).
 * @property preferredBackend Default hardware execution target for this model when the caller does
 *   not specify an explicit backend override (`null` for system-managed engines).
 */
data class AsrModelMetadata(
  val name: String,
  val modelId: String = "",
  val engineType: AsrEngineType = AsrEngineType.SYSTEM_DEFAULT,
  val supportedLanguages: List<String> = emptyList(),
  val installsLanguagePacks: Boolean = false,
  val isLanguageAutoDetected: Boolean = false,
  val requiresRepositoryModel: Boolean = true,
  val hasFixedLanguageList: Boolean = false,
  val supportedAccelerators: List<Accelerator> = emptyList(),
  val preferredBackend: Accelerator? = null,
)
