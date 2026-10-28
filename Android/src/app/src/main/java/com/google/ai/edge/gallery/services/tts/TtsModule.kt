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

import android.content.Context
import com.google.ai.edge.gallery.data.ModelDownloader
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.RuntimeTypeKey
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

/**
 * Hilt bindings for the text-to-speech stack.
 *
 * Every engine is a `@Singleton` because each one owns process-wide native resources (a platform
 * `TextToSpeech` instance, an ML Kit Binder connection). Callers inject [TtsServiceProvider] to
 * resolve the concrete [TtsService] for a desired engine or model.
 *
 * Because the engines are singletons, whoever calls [TtsService.destroy] tears them down for the
 * whole process. See the [TtsService] KDoc for the lifecycle contract.
 *
 * This module is only on the classpath of the experimental app, which keeps the engines and their
 * dependencies out of the prod APK. Code shared with the prod app must inject [TtsServiceProvider]
 * through the optional binding declared in [TtsApiModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TtsModule {

  /** Binds [MlKitTtsModelDownloader] for ML Kit speech synthesis supplementary models. */
  @Binds
  @IntoMap
  @RuntimeTypeKey(RuntimeType.MLKIT_SPEECH)
  abstract fun bindMlKitTtsModelDownloader(downloader: MlKitTtsModelDownloader): ModelDownloader

  companion object {
    /** Registers the always-available Android platform engine. */
    @Provides
    @Singleton
    @IntoMap
    @TtsEngineKey(TtsEngineType.ANDROID_NATIVE)
    fun providePlatformTtsEngine(@ApplicationContext context: Context): TtsService {
      return PlatformTtsEngine(context)
    }

    /** Registers the ML Kit GenAI Text-to-Speech engine. */
    @Provides
    @Singleton
    @IntoMap
    @TtsEngineKey(TtsEngineType.ML_KIT)
    fun provideMlKitTtsEngine(@ApplicationContext context: Context): TtsService {
      return MlKitTtsEngine(context)
    }

    /** Registers the MediaPipe `TextToSpeech` engine for downloadable models such as Kokoro 82M. */
    @Provides
    @Singleton
    @IntoMap
    @TtsEngineKey(TtsEngineType.MEDIAPIPE)
    fun provideMediaPipeTtsEngine(@ApplicationContext context: Context): TtsService {
      return MediaPipeTtsEngine(context)
    }

    /** Provides the central [TtsServiceProvider] that feature code uses to resolve engines. */
    @Provides
    @Singleton
    fun provideTtsServiceProvider(
      engines: Map<TtsEngineType, @JvmSuppressWildcards TtsService>
    ): TtsServiceProvider {
      return TtsServiceProvider(engines)
    }
  }
}
