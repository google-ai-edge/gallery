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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.services

import com.google.ai.edge.gallery.services.tts.TtsConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which (model, language, voice) combinations the TTS engines were prepared for, so that
 * speaking a segment doesn't call `TtsService.prepare` again, since preparing can wait for
 * synthesis that is already in progress.
 *
 * A combination only counts as prepared while its engine reports that it is loaded; once the engine
 * is evicted (for example on memory trim), every combination of its model is forgotten.
 */
internal class PreparedTtsKeys {
  private data class Key(val modelId: String, val languageCode: String?, val voiceId: String?)

  private val keys: MutableSet<Key> = ConcurrentHashMap.newKeySet()

  /**
   * Returns whether [config]'s model, language and voice were prepared and its engine is still
   * loaded ([isEngineLoaded]). Forgets the model's combinations when the engine isn't loaded.
   */
  fun isPrepared(config: TtsConfig, isEngineLoaded: Boolean): Boolean {
    val modelId = config.modelId ?: return false
    if (!isEngineLoaded) {
      invalidateModel(modelId)
      return false
    }
    return keyOf(config) in keys
  }

  /** Records that the engine was prepared for [config]'s model, language and voice. */
  fun markPrepared(config: TtsConfig) {
    if (config.modelId == null) return
    keys += keyOf(config)
  }

  /** Forgets every prepared combination of [modelId]. */
  fun invalidateModel(modelId: String) {
    keys.removeAll { it.modelId == modelId }
  }

  /** Forgets every prepared combination. */
  fun clear() {
    keys.clear()
  }

  private fun keyOf(config: TtsConfig) =
    Key(
      modelId = config.modelId.orEmpty(),
      languageCode = config.languageCode,
      voiceId = config.voiceId,
    )
}
