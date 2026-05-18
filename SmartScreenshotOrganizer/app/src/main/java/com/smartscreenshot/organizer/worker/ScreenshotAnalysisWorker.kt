package com.smartscreenshot.organizer.worker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartscreenshot.organizer.ai.InferenceProviderFactory
import com.smartscreenshot.organizer.ai.ScreenshotContext
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import com.smartscreenshot.organizer.ocr.OcrEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Processes unanalyzed screenshots through the OCR + AI pipeline.
 *
 * For each unanalyzed screenshot:
 * 1. Load the image bitmap from content URI
 * 2. Run OCR to extract visible text
 * 3. Send text + context to the selected inference provider
 * 4. Store structured results in the database
 *
 * Batch size is capped at 5 per execution to avoid ANR and battery drain.
 * WorkManager handles retries with exponential backoff.
 */
@HiltWorker
class ScreenshotAnalysisWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: ScreenshotRepository,
    private val ocrEngine: OcrEngine,
    private val inferenceFactory: InferenceProviderFactory
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ScreenshotAnalysis"
        const val WORK_NAME = "screenshot_analysis"
        private const val BATCH_SIZE = 5

        /** Max bitmap dimension in pixels. 2048px is sufficient for OCR accuracy. */
        private const val MAX_BITMAP_DIMENSION = 2048

        fun enqueueWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = OneTimeWorkRequestBuilder<ScreenshotAnalysisWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting screenshot analysis batch")

        val provider = inferenceFactory.getProvider()
        if (provider == null) {
            Log.w(TAG, "No inference provider available, will retry later")
            return Result.retry()
        }

        val unanalyzed = repository.getUnanalyzed(BATCH_SIZE)
        if (unanalyzed.isEmpty()) {
            Log.d(TAG, "No unanalyzed screenshots in queue")
            return Result.success()
        }

        Log.i(TAG, "Processing ${unanalyzed.size} screenshots with ${provider.providerName()}")

        var successCount = 0
        var failCount = 0

        for (screenshot in unanalyzed) {
            try {
                processScreenshot(screenshot, provider)
                successCount++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process screenshot ${screenshot.id}: ${e.message}")
                failCount++
            }
        }

        Log.i(TAG, "Batch complete: $successCount succeeded, $failCount failed")

        // Re-enqueue if more screenshots are waiting
        val remaining = repository.getUnanalyzedCount()
        if (remaining > 0) {
            Log.d(TAG, "$remaining screenshots remaining, re-enqueueing")
            enqueueWork(applicationContext)
        }

        return if (failCount > 0 && successCount == 0) Result.retry() else Result.success()
    }

    private suspend fun processScreenshot(
        screenshot: com.smartscreenshot.organizer.data.db.ScreenshotEntity,
        provider: com.smartscreenshot.organizer.ai.InferenceProvider
    ) {
        // Step 1: Load bitmap with downsampling to avoid OOM on large screenshots
        val bitmap = loadBitmap(Uri.parse(screenshot.uri))
            ?: throw RuntimeException("Could not load bitmap for ${screenshot.uri}")

        try {
            // Step 2: OCR
            val extractedText = try {
                ocrEngine.extractText(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "OCR failed for ${screenshot.id}, proceeding with empty text", e)
                ""
            }

            // Step 3: AI Analysis
            val context = ScreenshotContext(
                extractedText = extractedText,
                fileName = screenshot.filePath?.substringAfterLast("/") ?: "screenshot",
                timestamp = screenshot.createdAt,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )

            val result = provider.analyze(context)

            // Step 4: Update database
            if (result != null) {
                val updated = screenshot.copy(
                    title = result.title.ifBlank { screenshot.title },
                    summary = result.summary,
                    category = result.parsedCategory.name,
                    tags = result.tags,
                    detectedApps = result.detectedApps,
                    importantText = result.importantText,
                    extractedText = extractedText,
                    priorityScore = result.clampedPriorityScore,
                    analyzedAt = System.currentTimeMillis(),
                    isAnalyzed = true
                )
                repository.update(updated)
                Log.d(TAG, "Analyzed: ${result.title} [${result.parsedCategory}]")
            } else {
                // Mark as analyzed even on AI failure to avoid infinite retries
                // User can re-index from settings
                val updated = screenshot.copy(
                    extractedText = extractedText,
                    analyzedAt = System.currentTimeMillis(),
                    isAnalyzed = true
                )
                repository.update(updated)
                Log.w(TAG, "AI analysis returned null for ${screenshot.id}, marked as analyzed with OCR only")
            }
        } finally {
            // Guarantee bitmap recycle even if analyze() or OCR throws
            bitmap.recycle()
        }
    }

    /**
     * Loads a bitmap with automatic downsampling for large images.
     *
     * A 4K screenshot (3840x2160) in ARGB_8888 uses ~33MB of heap.
     * Downsampling to MAX_BITMAP_DIMENSION (2048px) reduces this to ~8MB
     * with negligible OCR accuracy loss.
     */
    private fun loadBitmap(uri: Uri): android.graphics.Bitmap? {
        return try {
            // First pass: decode bounds only to calculate sample size
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }

            val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            val sampleSize = if (maxDim > MAX_BITMAP_DIMENSION) {
                // inSampleSize must be a power of 2 for optimal performance
                Integer.highestOneBit(maxDim / MAX_BITMAP_DIMENSION)
            } else {
                1
            }

            // Second pass: decode with calculated sample size
            applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from $uri", e)
            null
        }
    }
}
