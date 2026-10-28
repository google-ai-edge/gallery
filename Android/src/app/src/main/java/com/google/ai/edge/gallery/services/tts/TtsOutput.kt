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

package com.google.ai.edge.gallery.services.tts

import kotlinx.coroutines.flow.Flow

/**
 * Word-level timestamp span within a synthesized sentence, measured in 24 kHz mono PCM frames
 * ([TtsAudioStreamFormat.STANDARD_24KHZ_MONO]) relative to the start of the sentence's audio.
 */
data class TtsWordTimestamp(val word: String, val startFrame: Long, val endFrame: Long)

/**
 * Result of a [TtsService.synthesize] call.
 *
 * Both variants wrap a cold flow: synthesis starts when the flow is collected and is cancelled when
 * the collector is cancelled. Which variant an engine returns is a property of the engine, not of
 * the request, so callers must handle both. Failures are reported by the flow terminating with an
 * exception.
 */
sealed class TtsOutput {
  /**
   * The engine produces raw audio that the caller is responsible for playing.
   *
   * @property flow Emits linear PCM chunks in the layout described by
   *   [TtsAudioStreamFormat.STANDARD_24KHZ_MONO], then completes.
   * @property wordTimestamps Word-level frame spans populated as [flow] emits audio chunks, or
   *   empty when the engine does not report word-level alignment.
   */
  data class AudioStream(
    val flow: Flow<ByteArray>,
    val wordTimestamps: List<TtsWordTimestamp> = emptyList(),
  ) : TtsOutput()

  /**
   * The engine plays the audio itself and only reports progress.
   *
   * @property statusFlow Emits [TtsPlaybackEvent]s, then completes once playback has finished.
   */
  data class DirectPlayback(val statusFlow: Flow<TtsPlaybackEvent>) : TtsOutput()
}
