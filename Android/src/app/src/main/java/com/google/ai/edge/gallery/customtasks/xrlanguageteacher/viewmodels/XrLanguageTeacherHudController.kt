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

package com.google.ai.edge.gallery.customtasks.xrlanguageteacher.viewmodels

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates the XR Language Teacher HUD greeting lifecycle across session resets, preference
 * updates, and speech-engine warmup gates.
 */
internal class XrLanguageTeacherHudController {
  private val hasGreetedAtomic = AtomicBoolean(false)
  private val pendingGreetingAtomic = AtomicBoolean(false)

  val hasGreeted: Boolean
    get() = hasGreetedAtomic.get()

  val pendingGreeting: Boolean
    get() = pendingGreetingAtomic.get()

  /**
   * Resets the greeting latch when a session is invalidated so the next `greetNewSessionIfNeeded`
   * call can surface a fresh greeting.
   */
  fun resetGreetingLatch() {
    hasGreetedAtomic.set(false)
    pendingGreetingAtomic.set(false)
  }

  /**
   * Explicitly queues a new greeting to be surfaced immediately (if speech engines are ready) or as
   * soon as speech engines finish warming up.
   */
  fun requestGreeting() {
    hasGreetedAtomic.set(false)
    pendingGreetingAtomic.set(true)
  }

  /**
   * Clears any pending greeting when an active session is dismissed ("Ask about something else").
   */
  fun onSessionDismissed() {
    pendingGreetingAtomic.set(false)
  }

  /**
   * Evaluates whether `greetNewSessionIfNeeded` should emit a greeting right now.
   *
   * If speech engines are still warming up, sets [pendingGreeting] so
   * [shouldCompletePendingGreeting] can emit the greeting once both ASR and TTS are ready.
   */
  fun consumeGreetingGate(
    isSessionActive: Boolean,
    isRecordingAsr: Boolean,
    currentGreeting: String,
    areSpeechEnginesReady: Boolean,
  ): Boolean {
    if (hasGreetedAtomic.get()) return false
    if (isSessionActive || isRecordingAsr || currentGreeting.isNotBlank()) return false
    if (!areSpeechEnginesReady) {
      pendingGreetingAtomic.set(true)
      return false
    }
    pendingGreetingAtomic.set(false)
    return hasGreetedAtomic.compareAndSet(false, true)
  }

  /**
   * Returns `true` when a deferred greeting is waiting for speech engines and both ASR and TTS are
   * now ready.
   */
  fun shouldCompletePendingGreeting(areSpeechEnginesReady: Boolean): Boolean {
    return pendingGreetingAtomic.get() && !hasGreetedAtomic.get() && areSpeechEnginesReady
  }
}
