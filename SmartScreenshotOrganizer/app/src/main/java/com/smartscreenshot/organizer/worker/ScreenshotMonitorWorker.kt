package com.smartscreenshot.organizer.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodically scans MediaStore for new screenshots that haven't been indexed.
 *
 * Runs every 15 minutes and catches screenshots that the ContentObserver
 * might have missed (e.g., app was killed, device was rebooting).
 *
 * After discovering new screenshots, it enqueues ScreenshotAnalysisWorker
 * for each unanalyzed entry.
 *
 * Uses [MediaStoreScanner] for MediaStore queries to avoid duplicating
 * query logic across the codebase.
 */
@HiltWorker
class ScreenshotMonitorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: ScreenshotRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ScreenshotMonitor"
        const val WORK_NAME = "screenshot_monitor_periodic"

        fun enqueuePeriodicWork(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScreenshotMonitorWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // flex interval
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting screenshot scan")

        return try {
            // Scan MediaStore without pre-loading known URIs.
            // We filter per-URI via DB check to avoid O(N) memory from getAllUris().
            val candidates = MediaStoreScanner.scanForNewScreenshots(
                context = applicationContext,
                knownUris = null,
                limit = null
            )

            // Filter out already-indexed screenshots via per-URI DB check.
            // Room caches compiled queries, so each check is ~1ms.
            val newScreenshots = candidates.filter { candidate ->
                !repository.uriExists(candidate.uri)
            }

            if (newScreenshots.isNotEmpty()) {
                repository.insertAll(newScreenshots)
                Log.i(TAG, "Indexed ${newScreenshots.size} new screenshots")

                // Trigger analysis for unanalyzed screenshots
                ScreenshotAnalysisWorker.enqueueWork(applicationContext)
            } else {
                Log.d(TAG, "No new screenshots found")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot scan failed", e)
            Result.retry()
        }
    }
}
