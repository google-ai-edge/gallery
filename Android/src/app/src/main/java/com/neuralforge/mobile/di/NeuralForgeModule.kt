/*
 * Copyright 2025 Neural Forge
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

package com.neuralforge.mobile.di

import android.content.Context
import com.neuralforge.mobile.converter.ModelConverter
import com.neuralforge.mobile.core.HardwareAccelerationManager
import com.neuralforge.mobile.core.ModelFormatDetector
import com.neuralforge.mobile.core.NeuralForgeEngine
import com.neuralforge.mobile.downloader.ModelDownloadManager
import com.neuralforge.mobile.downloader.SplitModelDownloader
import com.neuralforge.mobile.execution.ONNXInferenceEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Neural Forge core components
 */
@Module
@InstallIn(SingletonComponent::class)
object NeuralForgeModule {

    @Provides
    @Singleton
    fun provideModelDownloadManager(
        @ApplicationContext context: Context
    ): ModelDownloadManager {
        return ModelDownloadManager(context)
    }

    @Provides
    @Singleton
    fun provideModelFormatDetector(): ModelFormatDetector {
        return ModelFormatDetector()
    }

    @Provides
    @Singleton
    fun provideModelConverter(): ModelConverter {
        return ModelConverter()
    }

    @Provides
    @Singleton
    fun provideONNXInferenceEngine(
        @ApplicationContext context: Context
    ): ONNXInferenceEngine {
        return ONNXInferenceEngine(context)
    }

    @Provides
    @Singleton
    fun provideHardwareAccelerationManager(
        @ApplicationContext context: Context
    ): HardwareAccelerationManager {
        return HardwareAccelerationManager(context)
    }

    @Provides
    @Singleton
    fun provideSplitModelDownloader(
        @ApplicationContext context: Context,
        downloadManager: ModelDownloadManager
    ): SplitModelDownloader {
        return SplitModelDownloader(context, downloadManager)
    }

    @Provides
    @Singleton
    fun provideNeuralForgeEngine(
        @ApplicationContext context: Context,
        downloadManager: ModelDownloadManager,
        formatDetector: ModelFormatDetector,
        modelConverter: ModelConverter,
        onnxEngine: ONNXInferenceEngine
    ): NeuralForgeEngine {
        return NeuralForgeEngine(
            context,
            downloadManager,
            formatDetector,
            modelConverter,
            onnxEngine
        )
    }
}
