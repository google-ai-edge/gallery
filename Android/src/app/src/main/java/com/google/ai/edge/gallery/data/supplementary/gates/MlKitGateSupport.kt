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

package com.google.ai.edge.gallery.data.supplementary.gates

import com.google.ai.edge.gallery.data.supplementary.AiCoreVersion
import com.google.ai.edge.gallery.data.supplementary.HiddenReason
import com.google.ai.edge.gallery.data.supplementary.SpeechAvailability
import com.google.ai.edge.gallery.data.supplementary.SpeechModelState
import com.google.ai.edge.gallery.data.supplementary.SpeechServicesVersion
import com.google.ai.edge.gallery.data.supplementary.SpeechUnavailabilityReason
import com.google.ai.edge.gallery.data.supplementary.UpdatableComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal const val DEFAULT_MAX_PROBE_ATTEMPTS = 2
internal const val DEFAULT_INITIAL_BACKOFF_MS = 200L

/** Builds the standard [SpeechModelState.NeedsUpdate] for Speech Services by Google. */
internal fun speechServicesNeedsUpdateState(): SpeechModelState.NeedsUpdate =
  SpeechModelState.NeedsUpdate(
    component = UpdatableComponent.SPEECH_SERVICES,
    storeUrl = SpeechServicesVersion.PLAY_STORE_URL,
  )

/** Builds the standard [SpeechModelState.NeedsUpdate] for AICore. */
internal fun aiCoreNeedsUpdateState(): SpeechModelState.NeedsUpdate =
  SpeechModelState.NeedsUpdate(
    component = UpdatableComponent.AICORE,
    storeUrl = AiCoreVersion.PLAY_STORE_URL,
  )

/**
 * Maps a [SpeechUnavailabilityReason] reported by an availability probe to a unified
 * [SpeechModelState] across all ML Kit speech gates.
 */
internal fun mapUnavailableToModelState(
  reason: SpeechUnavailabilityReason,
  optInGuideUrl: String? = null,
): SpeechModelState =
  when (reason) {
    SpeechUnavailabilityReason.SPEECH_SERVICES_OUTDATED -> speechServicesNeedsUpdateState()
    SpeechUnavailabilityReason.AICORE_OUTDATED -> aiCoreNeedsUpdateState()
    SpeechUnavailabilityReason.NOT_OPTED_IN ->
      if (optInGuideUrl != null) {
        SpeechModelState.NeedsOptIn(optInGuideUrl)
      } else {
        SpeechModelState.CheckFailed("Couldn't check status")
      }
    SpeechUnavailabilityReason.DEVICE_NOT_SERVED ->
      SpeechModelState.Hidden(HiddenReason.DEVICE_NOT_COMPATIBLE)
    SpeechUnavailabilityReason.NO_ENGINE_IN_BUILD ->
      SpeechModelState.Hidden(HiddenReason.NO_ENGINE_IN_BUILD)
    SpeechUnavailabilityReason.NOT_SERVED_RIGHT_NOW ->
      SpeechModelState.Unavailable(reason = SpeechUnavailabilityReason.NOT_SERVED_RIGHT_NOW)
    SpeechUnavailabilityReason.LOCALE_NOT_OFFERED ->
      SpeechModelState.CheckFailed("Couldn't check status")
  }

/**
 * Executes [block] with bounded exponential backoff when transient [SpeechAvailability.ProbeFailed]
 * or uncaught non-cancellation exceptions occur.
 */
internal suspend fun retryProbe(
  maxAttempts: Int = DEFAULT_MAX_PROBE_ATTEMPTS,
  initialBackoffMs: Long = DEFAULT_INITIAL_BACKOFF_MS,
  block: suspend () -> SpeechAvailability,
): SpeechAvailability {
  var attempt = 0
  var delayMs = initialBackoffMs
  while (true) {
    attempt++
    try {
      val result = block()
      if (result !is SpeechAvailability.ProbeFailed || attempt >= maxAttempts) {
        return result
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (attempt >= maxAttempts) {
        return SpeechAvailability.ProbeFailed(e)
      }
    }
    delay(delayMs)
    delayMs *= 2
  }
}
