package com.smartscreenshot.organizer.ocr

import android.graphics.Bitmap

/**
 * Abstraction over text extraction from images.
 * Allows swapping implementations (ML Kit, Tesseract) without touching callers.
 */
interface OcrEngine {

    /**
     * Extract text from a bitmap image.
     *
     * @param bitmap The screenshot image
     * @return Extracted text, or empty string if no text found
     */
    suspend fun extractText(bitmap: Bitmap): String

    /** Whether the OCR engine is initialized and ready. */
    fun isReady(): Boolean
}
