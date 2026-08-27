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

/** Accessibility status of a remote model URL when probed with or without an access token. */
enum class ModelAccessibility {
  /** The model URL is accessible (HTTP 200 OK) with the provided token or anonymously. */
  ACCESSIBLE,

  /** The model is gated (HTTP 403 Forbidden); user must acknowledge the license on Hugging Face. */
  GATED,

  /** The token is invalid/expired or unauthorized (HTTP 401); an OAuth token exchange is needed. */
  NEEDS_TOKEN_EXCHANGE,

  /** Network connection or other unexpected error when probing the model URL. */
  ERROR,
}
