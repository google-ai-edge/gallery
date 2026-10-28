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

package com.google.ai.edge.gallery.services.tts

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType
import com.google.ai.edge.gallery.data.RuntimeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Registry of downloadable supplementary TTS models declared in the active model allowlist.
 *
 * Mirrors `AsrModelRegistry.registerAllowlistModels`: `ModelManagerViewModel` registers the
 * allowlist models when the allowlist loads, and engines that run downloaded model files (such as
 * [MediaPipeTtsEngine]) resolve the backing [Model], and so its downloaded file via
 * `Model.getPath`, by name.
 */
object TtsModelRegistry {
  private val lock = Any()
  private var allowlistTtsModels: List<Model> = emptyList()
  private val _allowlistModelsFlow = MutableStateFlow<List<Model>>(emptyList())

  /**
   * Observable stream of downloadable `.litertlm` TTS models registered from the active allowlist.
   */
  val allowlistModelsFlow: StateFlow<List<Model>> = _allowlistModelsFlow.asStateFlow()

  /** Downloadable `.litertlm` TTS models registered from the active allowlist. */
  val registeredAllowlistModels: List<Model>
    get() = synchronized(lock) { allowlistTtsModels }

  /**
   * Registers the downloadable `.litertlm` TTS models (`modelType == ModelType.TTS`) from the
   * loaded allowlist, replacing any previously registered models.
   */
  fun registerAllowlistModels(models: List<Model>) {
    val filtered =
      synchronized(lock) {
        models
          .filter {
            it.modelType == ModelType.TTS && it.backendSpec.runtimeType == RuntimeType.LITERT_LM
          }
          .also { allowlistTtsModels = it }
      }
    _allowlistModelsFlow.value = filtered
  }

  /**
   * Returns the registered allowlist TTS [Model] matching [named] by its engine model ID
   * (`ttsConfig.engineModelId`), [Model.name], or [Model.modelId], or `null` if none is registered.
   */
  fun model(named: String): Model? =
    synchronized(lock) {
      allowlistTtsModels.firstOrNull {
        it.ttsProfile?.engineModelId == named ||
          it.name == named ||
          (it.modelId.isNotEmpty() && it.modelId == named)
      }
    }

  /**
   * Returns the supported BCP-47 language tags for a TTS [model], preferring the allowlist
   * [Model.ttsProfile] languages and falling back to [MediaPipeTtsModels]'s per-engine defaults.
   */
  fun supportedLanguages(model: Model): List<String> {
    val profileLanguages = model.ttsProfile?.languages.orEmpty()
    if (profileLanguages.isNotEmpty()) {
      return profileLanguages
    }
    val engineId = model.ttsProfile?.engineModelId?.ifEmpty { null } ?: model.name
    return MediaPipeTtsModels.MODEL_SPECS.firstOrNull { it.engineModelId == engineId }
      ?.defaultLanguages
      .orEmpty()
  }
}
