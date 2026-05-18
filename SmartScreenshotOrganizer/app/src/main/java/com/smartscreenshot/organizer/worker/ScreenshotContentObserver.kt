package com.smartscreenshot.organizer.worker

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Real-time screenshot detection via ContentObserver on MediaStore.Images.
 *
 * Watches for new images added to the Screenshots directory and immediately
 * enqueues them for analysis. This complements the periodic WorkManager scan
 * which catches anything missed while the app was killed.
 *
 * Uses [MediaStoreScanner] for MediaStore queries to avoid duplicating
 * query logic across the codebase.
 *
 * Lifecycle:
 * - Call register() when the app is in the foreground (Activity.onStart)
 * - Call unregister() when backgrounded (Activity.onStop)
 * - The periodic ScreenshotMonitorWorker handles background detection
 */
class ScreenshotContentObserver(
    private val context: Context,
    private val repository: ScreenshotRepository
) {

    companion object {
        private const val TAG = "ScreenshotObserver"
        // Debounce: MediaStore can fire multiple events for a single screenshot
        private const val DEBOUNCE_MS = 2000L
        // Only check the 5 most recently added images on each event
        private const val RECENT_SCAN_LIMIT = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var isRegistered = false
    private var lastProcessedTime = 0L

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)

            val now = System.currentTimeMillis()
            if (now - lastProcessedTime < DEBOUNCE_MS) {
                Log.d(TAG, "Debounced duplicate MediaStore event")
                return
            }
            lastProcessedTime = now

            Log.d(TAG, "MediaStore change detected: $uri")
            scope.launch {
                handleNewScreenshot()
            }
        }
    }

    /**
     * Start observing MediaStore for new screenshots.
     * Safe to call multiple times; only registers once.
     */
    fun register() {
        if (isRegistered) return

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // notify for descendants
            observer
        )
        isRegistered = true
        Log.d(TAG, "Registered MediaStore observer")
    }

    /**
     * Stop observing MediaStore.
     * Safe to call multiple times.
     */
    fun unregister() {
        if (!isRegistered) return

        context.contentResolver.unregisterContentObserver(observer)
        isRegistered = false
        Log.d(TAG, "Unregistered MediaStore observer")
    }

    /**
     * Clean up the coroutine scope.
     * Call this when the observer is permanently destroyed.
     */
    fun destroy() {
        unregister()
        scope.cancel()
    }

    /**
     * Handles a MediaStore change event by scanning for recent screenshots
     * and indexing any that aren't already in the database.
     */
    private suspend fun handleNewScreenshot() {
        try {
            // Scan the 5 most recent screenshots from MediaStore
            val candidates = MediaStoreScanner.scanForNewScreenshots(
                context = context,
                knownUris = null, // Check per-URI via DB
                limit = RECENT_SCAN_LIMIT
            )

            var insertedCount = 0
            for (candidate in candidates) {
                // Skip already-indexed screenshots
                if (repository.uriExists(candidate.uri)) continue

                repository.insert(candidate)
                insertedCount++
                Log.i(TAG, "Indexed new screenshot: ${candidate.title}")
            }

            if (insertedCount > 0) {
                // Trigger analysis
                ScreenshotAnalysisWorker.enqueueWork(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process new screenshot", e)
        }
    }
}
