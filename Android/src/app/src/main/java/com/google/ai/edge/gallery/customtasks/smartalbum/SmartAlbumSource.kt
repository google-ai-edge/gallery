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

package com.google.ai.edge.gallery.customtasks.smartalbum

import android.content.Context
import com.google.ai.edge.gallery.R

/** Specifies the photo album source (user device photos vs sample demo album). */
enum class SmartAlbumSource(val rawValue: String) {
  USER_PHOTOS("userPhotos"),
  SAMPLE_ALBUM("sampleAlbum");

  fun getDisplayName(context: Context): String =
    when (this) {
      USER_PHOTOS -> context.getString(R.string.smartalbum_my_photos)
      SAMPLE_ALBUM -> context.getString(R.string.smartalbum_sample_photos)
    }
}
