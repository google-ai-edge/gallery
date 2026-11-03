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

import android.os.Bundle
import android.util.Log
import com.google.ai.edge.gallery.GalleryAnalytics.logGalleryEvent

private const val TAG = "AGAnalytics"

/**
 * A Firebase Analytics event: its [name] and its [params].
 *
 * Each param value is a [String], [Long], or [Double], the only types Firebase Analytics accepts on
 * Android. Log booleans as 0/1 with [toAnalyticsLong]. Never put user content (prompts,
 * transcripts, words, file paths, or exception messages) in [params].
 */
data class GalleryAnalyticsEvent(val name: String, val params: Map<String, Any>)

/** Returns this boolean as the 0/1 value analytics params use for booleans. */
internal fun Boolean.toAnalyticsLong(): Long = if (this) 1L else 0L

/** Returns [params][GalleryAnalyticsEvent.params] as a Firebase Analytics [Bundle]. */
fun GalleryAnalyticsEvent.toBundle(): Bundle =
  Bundle().apply {
    for ((key, value) in params) {
      when (value) {
        is String -> putString(key, value)
        is Long -> putLong(key, value)
        is Int -> putLong(key, value.toLong())
        is Boolean -> putLong(key, value.toAnalyticsLong())
        is Double -> putDouble(key, value)
        else -> Log.w(TAG, "Dropping analytics param '$key' of $name: unsupported type")
      }
    }
  }

/** Returns this event as `"<name>, key=value, ..."`, for debug logs. */
fun GalleryAnalyticsEvent.toLogString(): String =
  (listOf(name) + params.map { (key, value) -> "$key=$value" }).joinToString(", ")

/** Logs [GalleryAnalyticsEvent]s. */
object GalleryAnalyticsLogger {
  /**
   * Logs [event] to Firebase Analytics through [logGalleryEvent], with a matching `Analytics:`
   * debug log line under [tag].
   */
  fun logGalleryAnalyticsEvent(event: GalleryAnalyticsEvent, tag: String = TAG) {
    Log.d(tag, "Analytics: ${event.toLogString()}")
    logGalleryEvent(event.name, event.toBundle())
  }
}
