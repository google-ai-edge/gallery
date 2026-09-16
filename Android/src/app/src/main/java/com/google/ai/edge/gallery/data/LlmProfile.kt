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
 * LLM-specific configuration and capability profile.
 *
 * Holds fields that are only relevant to an LLM model.
 *
 * @property promptTemplates Ready-made prompts offered to the user when a chat session is empty.
 * @property maxTokens The upper bound the user can configure for the model's context length. Always
 *   positive; a zero or negative context length is not a meaningful model configuration.
 * @property supportTinyGarden Whether the model supports Tiny Garden.
 * @property supportMobileActions Whether the model supports mobile actions.
 */
data class LlmProfile(
  val promptTemplates: List<PromptTemplate> = emptyList(),
  val maxTokens: Int = DEFAULT_MAX_TOKEN,
  val supportTinyGarden: Boolean = false,
  val supportMobileActions: Boolean = false,
) {
  init {
    require(maxTokens > 0) { "maxTokens must be positive, but was $maxTokens." }
  }
}
