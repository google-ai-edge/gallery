package com.smartscreenshot.organizer.ai

/**
 * Context data passed alongside OCR text to the inference provider.
 * Helps the LLM make more informed classifications.
 */
data class ScreenshotContext(
    val extractedText: String,
    val fileName: String,
    val timestamp: Long,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)
