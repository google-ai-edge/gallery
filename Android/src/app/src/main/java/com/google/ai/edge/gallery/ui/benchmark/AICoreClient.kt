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

package com.google.ai.edge.gallery.ui.benchmark

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface AICoreClient {
  suspend fun warmup()

  suspend fun countTokens(text: String): Int

  fun generateContentStream(text: String): Flow<String>
}

class RealAICoreClient(private val generativeModel: com.google.mlkit.genai.prompt.GenerativeModel) :
  AICoreClient {
  override suspend fun warmup() = generativeModel.warmup()

  override suspend fun countTokens(text: String): Int {
    return generativeModel
      .countTokens(
        com.google.mlkit.genai.prompt.generateContentRequest(
          com.google.mlkit.genai.prompt.TextPart(text)
        ) {}
      )
      .totalTokens
  }

  override fun generateContentStream(text: String): Flow<String> {
    return flow {
      generativeModel
        .generateContentStream(
          com.google.mlkit.genai.prompt.generateContentRequest(
            com.google.mlkit.genai.prompt.TextPart(text)
          ) {}
        )
        .collect { response -> this@flow.emit(response.candidates.firstOrNull()?.text ?: "") }
    }
  }
}
