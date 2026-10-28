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

import com.google.ai.edge.gallery.data.supplementary.gates.MlKitAsrAdvancedGate
import com.google.ai.edge.gallery.data.supplementary.gates.MlKitAsrBasicGate
import com.google.ai.edge.gallery.data.supplementary.gates.MlKitTtsGate
import com.google.ai.edge.gallery.services.asr.AsrModelIdentifier
import com.google.ai.edge.gallery.services.tts.TtsModelIdentifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/** Hilt module binding per-engine [SpeechModelGate] implementations into a map. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SpeechModule {
  @Binds
  @IntoMap
  @SpeechModelGateKey(TtsModelIdentifier.ML_KIT)
  abstract fun bindMlKitTtsGate(gate: MlKitTtsGate): SpeechModelGate

  @Binds
  @IntoMap
  @SpeechModelGateKey(AsrModelIdentifier.MLKIT_BASIC)
  abstract fun bindMlKitAsrBasicGate(gate: MlKitAsrBasicGate): SpeechModelGate

  @Binds
  @IntoMap
  @SpeechModelGateKey(AsrModelIdentifier.MLKIT_ADVANCED)
  abstract fun bindMlKitAsrAdvancedGate(gate: MlKitAsrAdvancedGate): SpeechModelGate
}
