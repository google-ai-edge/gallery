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

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Declares [TtsServiceProvider] as an optional binding.
 *
 * The TTS engines and their bindings ([TtsModule]) live in `services/tts:tts_service`, which only
 * the experimental app depends on, so the engines' dependencies (ML Kit GenAI TTS) stay out of the
 * prod APK. Code that is shared with the prod app injects `Optional<Lazy<TtsServiceProvider>>` and
 * treats an absent binding as "TTS unavailable".
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TtsApiModule {
  @BindsOptionalOf abstract fun bindOptionalTtsServiceProvider(): TtsServiceProvider
}
