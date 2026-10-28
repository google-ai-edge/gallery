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
import com.google.ai.edge.gallery.data.Model

/**
 * Snapshot of the user's current ASR session settings, used to build both [AsrPreparationConfig]
 * and [AsrRecordingOptions] from a single value type.
 *
 * @property modelId Canonical [AsrModelIdentifier] or allowlist model name of the selected engine.
 * @property languageCode BCP-47 language code for recognition.
 * @property backend Selected hardware execution target for models exposing
 *   [AsrModelMetadata.supportedAccelerators]; ignored by system-managed engines.
 * @property showInterimResults Whether partial recognition hypotheses should be emitted while the
 *   user is speaking.
 */
data class AsrSessionSettings(
  val modelId: String = AsrModelIdentifier.SYSTEM_DEFAULT,
  val languageCode: String = AsrDefaults.LANGUAGE_CODE,
  val backend: Accelerator = AsrModelRegistry.preferredBackend(modelId),
  val showInterimResults: Boolean = AsrDefaults.SHOW_INTERIM_RESULTS,
) {
  /**
   * Resolves which [AsrEngineType] corresponds to [modelId], or [AsrEngineType.SYSTEM_DEFAULT] if
   * [modelId] is not a known [AsrModelIdentifier] or allowlist ASR model.
   */
  val engineType: AsrEngineType
    get() = AsrModelRegistry.resolveEngineType(modelId) ?: AsrEngineType.SYSTEM_DEFAULT

  /** Builds the [AsrPreparationConfig] corresponding to these session settings. */
  fun preparationConfig(): AsrPreparationConfig =
    AsrPreparationConfig(languageCode = languageCode, backend = backend, modelId = modelId)

  /** Builds the [AsrRecordingOptions] corresponding to these session settings. */
  fun recordingOptions(): AsrRecordingOptions =
    AsrRecordingOptions(showInterimResults = showInterimResults)

  companion object {
    /**
     * Resolves the preferred [Accelerator] for [model] based on its allowlist `backendSpec`:
     * - Prefers [Accelerator.GPU] whenever `GPU` is listed in `model.backendSpec.accelerators`
     *   (e.g. Parakeet TDT 0.6B ASR).
     * - Falls back to [Accelerator.CPU] when only `CPU` is listed.
     * - Defaults to [AsrDefaults.BACKEND] when [model] is `null` or has no accelerators declared.
     */
    fun preferredBackend(model: Model?): Accelerator {
      val accelerators = model?.backendSpec?.accelerators.orEmpty()
      return when {
        Accelerator.GPU in accelerators -> Accelerator.GPU
        Accelerator.CPU in accelerators -> Accelerator.CPU
        else -> AsrDefaults.BACKEND
      }
    }

    /** Creates an [AsrSessionSettings] instance configured from an [AsrEngineType]. */
    fun fromEngineType(
      engineType: AsrEngineType,
      languageCode: String = AsrDefaults.LANGUAGE_CODE,
      backend: Accelerator? = null,
      showInterimResults: Boolean = AsrDefaults.SHOW_INTERIM_RESULTS,
    ): AsrSessionSettings {
      val resolvedModelId = AsrModelRegistry.modelId(engineType)
      return if (backend != null) {
        AsrSessionSettings(
          modelId = resolvedModelId,
          languageCode = languageCode,
          backend = backend,
          showInterimResults = showInterimResults,
        )
      } else {
        AsrSessionSettings(
          modelId = resolvedModelId,
          languageCode = languageCode,
          showInterimResults = showInterimResults,
        )
      }
    }
  }
}
