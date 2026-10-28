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

/**
 * Playback progress reported by [TtsOutput.DirectPlayback].
 *
 * There is no failure event: an engine reports failure by terminating the flow with an exception.
 */
enum class TtsPlaybackEvent {
  /** Audible playback of the utterance has begun. */
  PLAYBACK_STARTED,

  /** The utterance finished playing. The flow completes immediately afterwards. */
  PLAYBACK_COMPLETED,
}
