package com.smartscreenshot.organizer.worker

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.smartscreenshot.organizer.data.db.ScreenshotEntity

/**
 * Centralized MediaStore scanning logic for screenshot discovery.
 *
 * Extracts the duplicated query logic from ScreenshotMonitorWorker and
 * ScreenshotContentObserver into a single, testable utility. One place
 * to maintain the projection, selection, and entity construction.
 */
object MediaStoreScanner {

    private const val TAG = "MediaStoreScanner"

    /** Columns to fetch from MediaStore for screenshot indexing. */
    private val PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.RELATIVE_PATH
    )

    /** Screenshot directory patterns (covers most OEM conventions). */
    private val SCREENSHOT_PATH_PATTERNS = arrayOf(
        "%Screenshots%",
        "%Screenshot%",
        "%DCIM/Screenshots%"
    )

    private val SELECTION =
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? " +
            "OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? " +
            "OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"

    /**
     * Scans MediaStore for screenshots not present in [knownUris].
     *
     * @param context Application context for ContentResolver access.
     * @param knownUris Set of content URIs already in the database. Pass null to return all.
     * @param limit Maximum number of results to return. Pass null for unlimited.
     * @return List of new ScreenshotEntity instances ready for database insertion.
     */
    fun scanForNewScreenshots(
        context: Context,
        knownUris: Set<String>? = null,
        limit: Int? = null
    ): List<ScreenshotEntity> {
        val screenshots = mutableListOf<ScreenshotEntity>()

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        // Use Bundle-based query for portable LIMIT support (API 26+)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && limit != null) {
            val queryArgs = Bundle().apply {
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    SELECTION
                )
                putStringArray(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    SCREENSHOT_PATH_PATTERNS
                )
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    sortOrder
                )
                putInt(
                    android.content.ContentResolver.QUERY_ARG_LIMIT,
                    limit
                )
            }
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                queryArgs,
                null
            )
        } else {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                SELECTION,
                SCREENSHOT_PATH_PATTERNS,
                sortOrder
            )
        }

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                ).toString()

                // Skip already-indexed screenshots
                if (knownUris != null && contentUri in knownUris) continue

                val name = c.getString(nameCol) ?: "screenshot_$id"
                val dateAdded = c.getLong(dateCol) * 1000 // seconds → millis
                val filePath = c.getString(dataCol)

                screenshots.add(
                    ScreenshotEntity(
                        uri = contentUri,
                        filePath = filePath,
                        createdAt = dateAdded,
                        title = name.substringBeforeLast(".")
                    )
                )

                // Manual limit enforcement for pre-O fallback
                if (limit != null && screenshots.size >= limit) break
            }
        }

        Log.d(TAG, "MediaStore scan found ${screenshots.size} new screenshots")
        return screenshots
    }
}
