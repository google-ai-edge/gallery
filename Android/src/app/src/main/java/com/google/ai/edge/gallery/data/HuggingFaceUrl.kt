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

import androidx.core.net.toUri

/**
 * Hugging Face URL helpers.
 *
 * Lives in the data layer so both the download worker and `HuggingFaceApiClient` can share it: the
 * client's dependencies already depend on the worker, so the worker can't depend on the client.
 */
object HuggingFaceUrls {
  /**
   * Returns whether [url] is served by Hugging Face: its host is `huggingface.co` or a subdomain. A
   * URL without a scheme is treated as `https://`.
   */
  fun isHuggingFaceUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val uri = (if (trimmed.contains("://")) trimmed else "https://$trimmed").toUri()
    val host = uri.host?.lowercase()
    return host == "huggingface.co" || host?.endsWith(".huggingface.co") == true
  }
}
