package com.smartscreenshot.organizer.search

import com.smartscreenshot.organizer.data.db.ScreenshotEntity
import kotlinx.coroutines.flow.Flow

/**
 * Search abstraction supporting full-text and filtered queries.
 */
interface SearchEngine {

    /** Full-text search across title, summary, extracted text, and tags. */
    fun search(query: String): Flow<List<ScreenshotEntity>>

    /** Filter by category. */
    fun filterByCategory(category: String): Flow<List<ScreenshotEntity>>

    /** Filter by date range (epoch millis). */
    fun filterByDateRange(startMillis: Long, endMillis: Long): Flow<List<ScreenshotEntity>>
}
