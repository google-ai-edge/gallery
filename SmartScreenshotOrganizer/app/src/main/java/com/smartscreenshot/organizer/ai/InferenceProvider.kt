package com.smartscreenshot.organizer.ai

import com.smartscreenshot.organizer.data.model.AnalysisResult

/**
 * Abstraction over LLM inference backends.
 *
 * Implementations must be:
 * - Thread-safe (called from WorkManager coroutines)
 * - Fault-tolerant (return null on failure, never throw to caller)
 * - Offline-capable (AI Core) or clearly documented as network-dependent (HTTP)
 */
interface InferenceProvider {

    /**
     * Analyze a screenshot given its OCR-extracted text and metadata context.
     *
     * @param context Screenshot metadata including extracted text
     * @return Parsed analysis result, or null if inference fails
     */
    suspend fun analyze(context: ScreenshotContext): AnalysisResult?

    /** Whether this provider is currently available and ready. */
    suspend fun isAvailable(): Boolean

    /** Human-readable provider name for UI display and logging. */
    fun providerName(): String
}
