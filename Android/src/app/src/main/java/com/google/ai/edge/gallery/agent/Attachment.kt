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

package com.google.ai.edge.gallery.agent

import android.graphics.Bitmap

/** Represents multimedia attachments associated with an [AgentRequest]. */
sealed interface Attachment {
  data class ImageBitmap(val bitmap: Bitmap) : Attachment

  data class ImageUri(val uri: String) : Attachment

  data class AudioBytes(val audioBytes: ByteArray) : Attachment {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as AudioBytes

      return audioBytes.contentEquals(other.audioBytes)
    }

    override fun hashCode(): Int {
      return audioBytes.contentHashCode()
    }
  }

  data class AudioUri(val uri: String) : Attachment

  data class File(val path: String, val mimeType: String) : Attachment
}
