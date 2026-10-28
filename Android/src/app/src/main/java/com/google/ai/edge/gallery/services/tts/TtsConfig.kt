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

/** Queueing behavior for synthesis requests on direct-playback speech engines. */
enum class TtsQueueMode {
  /** Flushes any in-flight or queued speech and begins playing this utterance immediately. */
  FLUSH,

  /** Appends this utterance to the engine's native request queue for gapless pipelining. */
  ADD,
}

/**
 * Per-request text-to-speech settings.
 *
 * Every property is optional; `null` means "leave the engine default, or whatever the most recent
 * [TtsService.prepare] established". Properties an engine does not support are ignored rather than
 * rejected.
 *
 * @property modelId Canonical model identifier that selects the backing engine. See
 *   [TtsModelIdentifier] for the recognized values. Changing this between [TtsService.synthesize]
 *   calls requires a fresh [TtsService.prepare]; see the [TtsService] KDoc.
 * @property languageCode BCP-47 language tag for the utterance, for example `"en-US"`.
 * @property voiceId Engine-specific voice name, as listed in [TtsModelMetadata.availableVoices].
 * @property speed Speech rate multiplier, where `1.0` is the engine's normal rate.
 * @property pitch Pitch multiplier, where `1.0` is the engine's normal pitch.
 * @property queueMode Queue mode governing whether this utterance flushes or appends to queued
 *   speech on direct-playback engines.
 */
data class TtsConfig(
  val modelId: String? = null,
  val languageCode: String? = null,
  val voiceId: String? = null,
  val speed: Float? = null,
  val pitch: Float? = null,
  val queueMode: TtsQueueMode = TtsQueueMode.FLUSH,
)
