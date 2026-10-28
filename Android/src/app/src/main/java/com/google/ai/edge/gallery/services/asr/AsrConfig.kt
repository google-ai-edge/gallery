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

import android.os.ParcelFileDescriptor
import com.google.ai.edge.gallery.data.Accelerator

/**
 * Configuration for preparing an [AsrService] instance.
 *
 * @property languageCode BCP-47 language tag (for example `"en-US"`, `"es-ES"`, `"ja-JP"`).
 * @property backend The requested hardware execution target for engines that support backend
 *   selection (e.g. LiteRT models). Null for system-managed engines like ML Kit and System Speech.
 * @property modelId Optional canonical [AsrModelIdentifier] or allowlist model name / ID specifying
 *   which model file to load (for example when multiple `.litertlm` ASR models are registered).
 */
data class AsrPreparationConfig(
  val languageCode: String = AsrDefaults.LANGUAGE_CODE,
  val backend: Accelerator? = null,
  val modelId: String? = null,
) {
  companion object {
    val DEFAULT: AsrPreparationConfig = AsrPreparationConfig()
  }
}

/**
 * Options for a single ASR recording session.
 *
 * @property showInterimResults Whether partial recognition hypotheses should be emitted during this
 *   recording session.
 * @property externalAudioSource Optional factory returning a readable [ParcelFileDescriptor] that
 *   streams raw 16 kHz 16-bit mono PCM audio into the recognizer instead of opening the device
 *   microphone. Honored by [MlKitAsrService], [SpeechProcessorAsrService], and
 *   [SystemSpeechAsrService] (API 33+).
 */
data class AsrRecordingOptions(
  val showInterimResults: Boolean = AsrDefaults.SHOW_INTERIM_RESULTS,
  val externalAudioSource: (() -> ParcelFileDescriptor?)? = null,
) {
  companion object {
    val DEFAULT: AsrRecordingOptions = AsrRecordingOptions()
  }
}
