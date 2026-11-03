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

package com.google.ai.edge.gallery.analytics

import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelType

/** The `model_type` analytics param, which groups model and speech events by modality. */
enum class AnalyticsModelType(val id: String) {
  LLM("llm"),
  ASR("asr"),
  TTS("tts"),
  /** Any other model, such as a sticker segmentation or embedding model. */
  OTHER("other"),
}

/** The `event_type` values of the `model_download` event logged by the speech downloaders. */
enum class ModelDownloadEventType(val id: String) {
  START("start"),
  SUCCESS("success"),
  FAILURE("failure"),
  CANCELLED("cancelled"),
}

/** Builders of the `model_download` event and its params. */
object ModelDownloadAnalytics {
  /** Returns the [AnalyticsModelType] of [model]. */
  fun analyticsModelType(model: Model): AnalyticsModelType =
    when {
      model.modelType == ModelType.TTS || model.ttsProfile != null -> AnalyticsModelType.TTS
      model.modelType == ModelType.ASR || model.asrProfile != null -> AnalyticsModelType.ASR
      model.isLlm -> AnalyticsModelType.LLM
      else -> AnalyticsModelType.OTHER
    }

  /**
   * Returns a `model_download` event.
   *
   * @param modelId The model's allowlist name. For a language pack, the base model's name, without
   *   the language.
   * @param targetLanguage The language tag of a language pack download, or null for a model
   *   download.
   * @param durationMs How long the download ran, for the events that end it.
   */
  fun modelDownloadEvent(
    eventType: ModelDownloadEventType,
    modelId: String,
    modelType: AnalyticsModelType,
    targetLanguage: String? = null,
    durationMs: Long? = null,
  ): GalleryAnalyticsEvent =
    GalleryAnalyticsEvent(
      name = GalleryEvent.MODEL_DOWNLOAD.id,
      params =
        buildMap {
          put("event_type", eventType.id)
          put("model_id", modelId)
          put("model_type", modelType.id)
          targetLanguage?.let { put("target_language", it) }
          durationMs?.let { put("duration_ms", it) }
        },
    )
}
