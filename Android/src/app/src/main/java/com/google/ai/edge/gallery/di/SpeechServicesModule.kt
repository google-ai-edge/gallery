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

package com.google.ai.edge.gallery.di

import android.content.Context
import com.google.ai.edge.gallery.data.ModelDownloader
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.RuntimeTypeKey
import com.google.ai.edge.gallery.services.asr.AsrEngineKey
import com.google.ai.edge.gallery.services.asr.AsrEngineType
import com.google.ai.edge.gallery.services.asr.AsrService
import com.google.ai.edge.gallery.services.asr.AsrSettingsRepository
import com.google.ai.edge.gallery.services.asr.MlKitAsrModelDownloader
import com.google.ai.edge.gallery.services.asr.MlKitAsrService
import com.google.ai.edge.gallery.services.asr.SpeechProcessorAsrService
import com.google.ai.edge.gallery.services.asr.SystemSpeechAsrService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Hilt module providing the ASR engines (keyed by [AsrEngineKey]) and the [MlKitAsrModelDownloader]
 * for [RuntimeType.MLKIT_ASR].
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SpeechServicesModule {

  @Binds
  @IntoMap
  @RuntimeTypeKey(RuntimeType.MLKIT_ASR)
  abstract fun bindMlKitAsrModelDownloader(downloader: MlKitAsrModelDownloader): ModelDownloader

  companion object {
    @Provides
    @Singleton
    @IntoMap
    @AsrEngineKey(AsrEngineType.SYSTEM_DEFAULT)
    fun provideSystemSpeechAsrService(@ApplicationContext context: Context): AsrService =
      SystemSpeechAsrService(context)

    @Provides
    @Singleton
    @IntoMap
    @AsrEngineKey(AsrEngineType.MLKIT_BASIC)
    fun provideMlKitBasicAsrService(@ApplicationContext context: Context): AsrService =
      MlKitAsrService(context, AsrEngineType.MLKIT_BASIC)

    @Provides
    @Singleton
    @IntoMap
    @AsrEngineKey(AsrEngineType.MLKIT_ADVANCED)
    fun provideMlKitAdvancedAsrService(@ApplicationContext context: Context): AsrService =
      MlKitAsrService(context, AsrEngineType.MLKIT_ADVANCED)

    @Provides
    @Singleton
    @IntoMap
    @AsrEngineKey(AsrEngineType.SPEECH_PROCESSOR)
    fun provideSpeechProcessorAsrService(
      @ApplicationContext context: Context,
      @IoDispatcher ioDispatcher: CoroutineDispatcher,
      asrSettingsRepository: AsrSettingsRepository,
    ): AsrService =
      SpeechProcessorAsrService(
        context = context,
        ioDispatcher = ioDispatcher,
        selectedModelId = { asrSettingsRepository.selectedModelId.value },
      )
  }
}
