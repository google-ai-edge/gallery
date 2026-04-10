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

package com.google.ai.edge.gallery.server

import com.google.ai.edge.gallery.data.Model

/**
 * Process-global pointer to the LLM model that the HTTP server should use for inference.
 *
 * The Edge Gallery app initialises a [Model] the first time a user opens a chat screen for it. That
 * initialisation lives on the [Model] itself (`model.instance`), so the embedded HTTP server just
 * needs a way to find the most-recently-initialised model. That's what this object provides: the
 * chat runtime publishes here, and [LlmServerService] reads from here.
 */
object ServerModelHolder {
  @Volatile private var current: Model? = null

  /** The model the HTTP server should currently dispatch requests to, or `null` if none. */
  val activeModel: Model?
    get() = current

  /** Called by the chat runtime after a successful model initialisation. */
  fun publish(model: Model) {
    current = model
  }

  /** Called by the chat runtime when the given model has been cleaned up. */
  fun clearIfMatches(model: Model) {
    if (current === model) {
      current = null
    }
  }
}
