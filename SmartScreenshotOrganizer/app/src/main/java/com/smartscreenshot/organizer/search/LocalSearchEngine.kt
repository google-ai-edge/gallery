package com.smartscreenshot.organizer.search

import com.smartscreenshot.organizer.data.db.ScreenshotEntity
import com.smartscreenshot.organizer.data.repository.ScreenshotRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local search engine backed by Room FTS4.
 * Delegates to ScreenshotRepository which handles FTS query sanitization.
 */
@Singleton
class LocalSearchEngine @Inject constructor(
    private val repository: ScreenshotRepository
) : SearchEngine {

    override fun search(query: String): Flow<List<ScreenshotEntity>> =
        repository.search(query)

    override fun filterByCategory(category: String): Flow<List<ScreenshotEntity>> =
        repository.observeByCategory(category)

    override fun filterByDateRange(
        startMillis: Long,
        endMillis: Long
    ): Flow<List<ScreenshotEntity>> = repository.observeByDateRange(startMillis, endMillis)
}
