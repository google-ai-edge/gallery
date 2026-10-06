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

package com.google.ai.edge.gallery.services.semanticretrieval

/**
 * Sample rate, in Hz, expected by the embedding model's mel spectrogram frontend.
 *
 * The frontend is fixed at 16 kHz mono with a 320 sample frame and a 160 sample hop, so audio from
 * any other source rate has to be downmixed and resampled before it is embedded.
 */
const val AUDIO_SAMPLE_RATE_HZ = 16_000

/**
 * One element of an ordered multimodal embedding request.
 *
 * The embedding engine wraps each element in its modality markers (`<SOI>`/`<EOI>` for images,
 * `<SOA>`/`<EOA>` for audio) and preserves the list order in the token stream, so the order parts
 * are listed in is the order the model sees them.
 */
sealed interface EmbedPart {
  /** A text span, such as the `"00:03"` timestamp that precedes an interleaved frame. */
  data class Text(val text: String) : EmbedPart

  /** An encoded still image, in a format the embedder can decode (e.g. JPEG). */
  class Image(val imageData: ByteArray) : EmbedPart {
    override fun equals(other: Any?): Boolean =
      this === other || (other is Image && imageData.contentEquals(other.imageData))

    override fun hashCode(): Int = imageData.contentHashCode()

    override fun toString(): String = "Image(${imageData.size} bytes)"
  }

  /**
   * A mono PCM audio clip.
   *
   * [pcm] holds signed 16-bit samples at [sampleRateHz]. Empty clips are rejected because the
   * embedder's audio container cannot represent a zero length buffer; callers that may have no
   * audio for a moment should omit the part instead of passing an empty one.
   */
  class Audio(val pcm: ShortArray, val sampleRateHz: Int = AUDIO_SAMPLE_RATE_HZ) : EmbedPart {
    init {
      require(pcm.isNotEmpty()) { "Audio part requires at least one PCM sample" }
      require(sampleRateHz > 0) { "Audio sample rate must be positive, got $sampleRateHz" }
    }

    override fun equals(other: Any?): Boolean =
      this === other ||
        (other is Audio && sampleRateHz == other.sampleRateHz && pcm.contentEquals(other.pcm))

    override fun hashCode(): Int = 31 * pcm.contentHashCode() + sampleRateHz

    override fun toString(): String = "Audio(${pcm.size} samples @ ${sampleRateHz}Hz)"
  }
}
