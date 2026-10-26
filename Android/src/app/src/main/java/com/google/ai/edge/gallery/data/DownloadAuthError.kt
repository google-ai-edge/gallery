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

import java.net.HttpURLConnection

/**
 * What the user must do before Hugging Face serves a model file, derived from the HTTP response of
 * the download request itself.
 *
 * The app doesn't probe the URL before downloading: Hugging Face counts every request to a model
 * file, including HEAD, as a download, so a probe would double count every download.
 */
enum class DownloadAuthError {
  /** HTTP 401, or 403 without an access token: the user must sign in to Hugging Face. */
  NEEDS_SIGN_IN,

  /** HTTP 403 with an access token: the user must accept the gated model's license. */
  NEEDS_LICENSE_ACCEPTANCE;

  companion object {
    /**
     * Classifies a failed download response.
     *
     * @param url The URL that was requested.
     * @param httpCode The HTTP status code of the response.
     * @param sentAccessToken Whether the request carried a Hugging Face access token.
     * @return The action the user must take, or `null` if the failure isn't a Hugging Face access
     *   error (other hosts, or other status codes).
     */
    fun fromResponse(url: String, httpCode: Int, sentAccessToken: Boolean): DownloadAuthError? {
      if (!HuggingFaceUrls.isHuggingFaceUrl(url)) {
        return null
      }
      return when (httpCode) {
        HttpURLConnection.HTTP_UNAUTHORIZED -> NEEDS_SIGN_IN
        HttpURLConnection.HTTP_FORBIDDEN ->
          if (sentAccessToken) NEEDS_LICENSE_ACCEPTANCE else NEEDS_SIGN_IN
        else -> null
      }
    }

    /** Parses a value stored with [name], returning `null` for `null` or unknown names. */
    fun fromName(name: String?): DownloadAuthError? = entries.firstOrNull { it.name == name }
  }
}
