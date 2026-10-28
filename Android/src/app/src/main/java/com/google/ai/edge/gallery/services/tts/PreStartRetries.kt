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
 * Counts the retries of one utterance under its engine's [TtsService.preStartRetryDelaysMs] policy.
 * Create one per utterance, and only ask it about failures before any audio played.
 *
 * Retries are counted separately for each list of delays the engine returns, so different kinds of
 * failures of the same utterance each get their own retries.
 */
class PreStartRetries {
  private val retriesBySchedule = mutableMapOf<List<Long>, Int>()

  /** Returns whether [service] has a retry policy for [error], whether or not retries are left. */
  fun hasPolicy(service: TtsService, error: Exception): Boolean =
    error is TtsServiceError && service.preStartRetryDelaysMs(error).isNotEmpty()

  /**
   * Takes the next retry for [error] from [service]'s policy, and returns how long to wait before
   * synthesizing the utterance again, or `null` when the engine has no retry, or no retry left, for
   * it.
   */
  fun nextDelayMs(service: TtsService, error: Exception): Long? {
    if (error !is TtsServiceError) return null
    val delays = service.preStartRetryDelaysMs(error)
    val retries = retriesBySchedule[delays] ?: 0
    val delayMs = delays.getOrNull(retries) ?: return null
    retriesBySchedule[delays] = retries + 1
    return delayMs
  }
}
