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

import com.google.gson.annotations.SerializedName

enum class ModelCapability {
  @SerializedName("llm_thinking") LLM_THINKING,
  @SerializedName("speculative_decoding") SPECULATIVE_DECODING,
}

enum class ModelType {
  @SerializedName("task") TASK,
}

/**
 * Runtime that serves a model, together with the capabilities that runtime gives the model.
 *
 * @property downloadsViaRepository Whether models using this runtime are downloaded as local files
 *   via [DownloadRepository] and stored in the app's models directory.
 * @property autoDownloadsOnStartup Whether models using this runtime automatically initiate
 *   download/probing on app startup.
 * @property supportsTopLevelDownload Whether models using this runtime expose a top-level Download
 *   button on the model card when not yet downloaded. System-managed runtimes (AICore, ML Kit
 *   Speech) manage base models via Google Play Services or per-language packs rather than a
 *   top-level model download.
 * @property supportsDelete Whether models using this runtime can be deleted by the user from within
 *   the app.
 */
enum class RuntimeType(
  val downloadsViaRepository: Boolean,
  val autoDownloadsOnStartup: Boolean,
  val supportsTopLevelDownload: Boolean,
  val supportsDelete: Boolean,
) {
  @SerializedName("unknown")
  UNKNOWN(
    downloadsViaRepository = true,
    autoDownloadsOnStartup = false,
    supportsTopLevelDownload = true,
    supportsDelete = true,
  ),
  @SerializedName("litert_lm")
  LITERT_LM(
    downloadsViaRepository = true,
    autoDownloadsOnStartup = false,
    supportsTopLevelDownload = true,
    supportsDelete = true,
  ),
  @SerializedName("aicore")
  AICORE(
    downloadsViaRepository = false,
    autoDownloadsOnStartup = true,
    supportsTopLevelDownload = false,
    supportsDelete = false,
  ),

}

enum class AICoreModelReleaseStage {
  @SerializedName("stable") STABLE,
  @SerializedName("preview") PREVIEW,
}

enum class AICoreModelPreference {
  @SerializedName("fast") FAST,
  @SerializedName("full") FULL,
}
