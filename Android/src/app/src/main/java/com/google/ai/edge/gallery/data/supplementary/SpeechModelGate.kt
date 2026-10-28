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

package com.google.ai.edge.gallery.data.supplementary

import com.google.ai.edge.gallery.data.Model
import dagger.MapKey

/**
 * Gate evaluator responsible for determining pre-download availability for a speech model.
 *
 * Evaluates the ordered gating pipeline: device compatibility -> component version -> opt-in ->
 * service status.
 */
interface SpeechModelGate {
  /** Evaluates pre-download gating conditions for [model]. */
  suspend fun checkGate(model: Model): SpeechModelState
}

/** Dagger map key for multibinding [SpeechModelGate] instances by model identifier or name. */
@MapKey
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class SpeechModelGateKey(val value: String)
