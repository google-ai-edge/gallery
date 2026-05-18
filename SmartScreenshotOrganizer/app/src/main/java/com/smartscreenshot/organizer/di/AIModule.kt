package com.smartscreenshot.organizer.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * AI-specific Hilt bindings.
 *
 * AICoreInferenceProvider and HttpInferenceProvider are both
 * constructor-injected singletons, so no explicit bindings needed here.
 * InferenceProviderFactory handles provider selection at runtime.
 *
 * This module exists as an extension point for future AI backends
 * (e.g., ONNX Runtime, TFLite direct).
 */
@Module
@InstallIn(SingletonComponent::class)
object AIModule
