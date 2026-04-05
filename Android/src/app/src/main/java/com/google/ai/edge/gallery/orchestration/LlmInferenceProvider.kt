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

package com.google.ai.edge.gallery.orchestration

/**
 * Abstraction over LLM inference.
 *
 * The app layer implements this interface (wrapping ViewModel / LiteRT-LM) and injects it into the
 * orchestration module. The module never depends on Android or LiteRT-LM directly.
 */
interface LlmInferenceProvider {
  /** Run inference with the given prompt and return the complete response text. */
  suspend fun generateResponse(prompt: String): String

  /** Cancel any in-flight inference. */
  fun cancel()
}
