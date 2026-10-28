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

/** A selectable voice option within a TTS model. */
data class TtsVoice(val id: String, val displayName: String = id, val locale: String = "")

/**
 * Configuration specific to Text-to-Speech (TTS) supplementary models.
 *
 * Attached to [Model.ttsProfile] when the model is a TTS model.
 */
data class TtsProfile(
  /**
   * Engine identifier passed to `TtsService`; must be a `TtsModelIdentifier` value (e.g.
   * `"mlkit_tts"`). Distinct from [Model.name], which is the user-visible allowlist key.
   */
  val engineModelId: String,
  /** Selectable voices exposed by this model; empty if the engine offers a single fixed voice. */
  val voices: List<TtsVoice> = emptyList(),
  /** Default voice ID when the user has not chosen one, or `null` if [voices] is empty. */
  val defaultVoiceId: String? = null,
  /** Supported BCP-47 language tags, for display in the model details card. */
  val languages: List<String> = emptyList(),
)
