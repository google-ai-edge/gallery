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

/**
 * Configuration specific to Automatic Speech Recognition (ASR) supplementary models.
 *
 * Attached to [Model.asrProfile] when the allowlist entry declares an `asrConfig`.
 */
data class AsrProfile(
  /**
   * Supported BCP-47 language tags declared by the allowlist, for language pickers and the model
   * details card. Empty if the allowlist declares none, in which case `AsrModelRegistry` falls back
   * to its built-in lists.
   */
  val languages: List<String> = emptyList()
)
