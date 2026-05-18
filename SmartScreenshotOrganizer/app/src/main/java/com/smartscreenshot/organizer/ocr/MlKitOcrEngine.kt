package com.smartscreenshot.organizer.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * ML Kit Text Recognition v2 implementation.
 *
 * Runs entirely on-device with no network dependency.
 * Uses the Latin script recognizer by default; swap to
 * TextRecognizerOptions for other scripts (Chinese, Devanagari, etc.).
 *
 * Text blocks are merged in reading order (top-to-bottom, left-to-right)
 * with newlines between blocks.
 */
@Singleton
class MlKitOcrEngine @Inject constructor() : OcrEngine {

    companion object {
        private const val TAG = "MlKitOcr"
    }

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun extractText(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    // Merge text blocks with newlines, preserving reading order
                    val result = text.textBlocks
                        .sortedBy { it.boundingBox?.top ?: 0 }
                        .joinToString("\n") { block ->
                            block.lines.joinToString("\n") { line ->
                                line.text
                            }
                        }
                        .trim()

                    Log.d(TAG, "Extracted ${result.length} characters from image")
                    continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Text recognition failed", e)
                    continuation.resume("")
                }

            continuation.invokeOnCancellation {
                Log.d(TAG, "OCR task cancelled")
            }
        }
    }

    override fun isReady(): Boolean = true
}
