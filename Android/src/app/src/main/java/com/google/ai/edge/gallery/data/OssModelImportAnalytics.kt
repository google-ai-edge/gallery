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

import android.os.Bundle
import android.util.Log
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.firebaseAnalytics
import javax.inject.Inject

private const val TAG = "AGOssModelImportAnalytics"

/** Firebase Analytics truncates string parameter values longer than this. */
private const val MAX_PARAM_VALUE_LENGTH = 100

/**
 * Encapsulates Firebase Analytics logging for OSS model exploration and import operations
 * (`oss_model_import`).
 *
 * Callers depend only on [OssModelImportLogger] and invoke high-level methods without constructing
 * low-level event payloads.
 */
open class OssModelImportLogger @Inject constructor() {

  /** Platform or origin of an OSS model operation, logged as the `source` parameter. */
  enum class Source(val paramValue: String) {
    /** The operation originated from entering the Hugging Face Explore catalog page. */
    HF_EXPLORE("hf_explore"),
    /** The operation originated from a model card in the Hugging Face Explore catalog. */
    HF_MODEL_CARD("hf_model_card"),
  }

  /** Categorical reason an OSS model operation failed, logged as the `error_type` parameter. */
  enum class ErrorType(val paramValue: String) {
    /** The remote catalog or model details request failed, e.g. due to a network or HTTP error. */
    FETCH_FAILED("fetch_failed"),
    /** The remote API returned no model details for the requested model. */
    DETAILS_UNAVAILABLE("details_unavailable"),
  }

  private enum class EventType(val paramValue: String) {
    MODEL_LIST_LOAD("model_list_load"),
    MODEL_DETAILS_LOAD("model_details_load"),
  }

  /** Logs that the initial OSS model catalog list finished loading successfully. */
  fun logModelListLoadSuccess(source: Source = Source.HF_EXPLORE) {
    logEvent(eventType = EventType.MODEL_LIST_LOAD, source = source, success = true)
  }

  /** Logs that the initial OSS model catalog list failed to load with [errorType]. */
  fun logModelListLoadFailure(
    errorType: ErrorType = ErrorType.FETCH_FAILED,
    source: Source = Source.HF_EXPLORE,
  ) {
    logEvent(
      eventType = EventType.MODEL_LIST_LOAD,
      source = source,
      success = false,
      errorType = errorType,
    )
  }

  /** Logs that details for [modelId] finished loading successfully. */
  fun logModelDetailsLoadSuccess(modelId: String, source: Source = Source.HF_MODEL_CARD) {
    logEvent(
      eventType = EventType.MODEL_DETAILS_LOAD,
      source = source,
      success = true,
      modelId = modelId,
    )
  }

  /** Logs that details for [modelId] failed to load with [errorType]. */
  fun logModelDetailsLoadFailure(
    modelId: String,
    errorType: ErrorType,
    source: Source = Source.HF_MODEL_CARD,
  ) {
    logEvent(
      eventType = EventType.MODEL_DETAILS_LOAD,
      source = source,
      success = false,
      modelId = modelId,
      errorType = errorType,
    )
  }

  private fun logEvent(
    eventType: EventType,
    source: Source,
    success: Boolean,
    modelId: String? = null,
    errorType: ErrorType? = null,
  ) {
    val bundle =
      Bundle().apply {
        putString("event_type", eventType.paramValue)
        putString("source", source.paramValue)
        if (!modelId.isNullOrEmpty()) {
          putString("model_id", modelId.take(MAX_PARAM_VALUE_LENGTH))
        }
        putBoolean("success", success)
        if (errorType != null) {
          putString("error_type", errorType.paramValue)
        }
      }
    Log.d(TAG, "Analytics: ${GalleryEvent.OSS_MODEL_IMPORT.id}, $bundle")
    sendEvent(GalleryEvent.OSS_MODEL_IMPORT.id, bundle)
  }

  protected open fun sendEvent(name: String, params: Bundle) {
    firebaseAnalytics?.logEvent(name, params)
  }
}
