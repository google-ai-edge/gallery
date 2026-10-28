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

/**
 * Description of a single text-to-speech model exposed by a [TtsService].
 *
 * @property modelId Canonical identifier accepted by [TtsConfig.modelId]. See [TtsModelIdentifier].
 * @property displayName Human-readable name, suitable for showing in a model picker.
 * @property engineType Engine that serves this model.
 * @property supportedLanguages BCP-47 language tags the model can speak, for example `"en-US"`. May
 *   be empty if the engine cannot enumerate them.
 * @property availableVoices Voice names accepted by [TtsConfig.voiceId]. May be empty for engines
 *   whose voice selection is delegated to the system.
 * @property voiceDescriptions Optional mapping from voice ID to a human-readable display label (for
 *   example `"Puck"` to `"Puck (Male)"`).
 * @property voiceLocales Optional mapping from voice ID to its BCP-47 language tag (for example
 *   `"af_heart"` to `"en-US"`).
 */
data class TtsModelMetadata(
  val modelId: String,
  val displayName: String,
  val engineType: TtsEngineType,
  val supportedLanguages: List<String>,
  val availableVoices: List<String>,
  val voiceDescriptions: Map<String, String> = emptyMap(),
  val voiceLocales: Map<String, String> = emptyMap(),
)
