package com.smartscreenshot.organizer.data.repository

import com.smartscreenshot.organizer.data.db.CategoryCount
import com.smartscreenshot.organizer.data.db.ScreenshotEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository abstraction for screenshot data access.
 * Hides Room implementation details from ViewModels and Workers.
 */
interface ScreenshotRepository {

    fun observeAll(): Flow<List<ScreenshotEntity>>
    fun observeRecent(limit: Int = 20): Flow<List<ScreenshotEntity>>
    fun observeByCategory(category: String): Flow<List<ScreenshotEntity>>
    fun observeByDateRange(startMillis: Long, endMillis: Long): Flow<List<ScreenshotEntity>>
    fun observeCategoryCounts(): Flow<List<CategoryCount>>
    fun search(query: String): Flow<List<ScreenshotEntity>>

    suspend fun getById(id: Long): ScreenshotEntity?
    suspend fun getByUri(uri: String): ScreenshotEntity?
    suspend fun getUnanalyzed(limit: Int = 10): List<ScreenshotEntity>
    suspend fun getUnanalyzedCount(): Int
    suspend fun getTotalCount(): Int
    suspend fun getAllUris(): List<String>
    suspend fun uriExists(uri: String): Boolean

    suspend fun insert(screenshot: ScreenshotEntity): Long
    suspend fun insertAll(screenshots: List<ScreenshotEntity>): List<Long>
    suspend fun update(screenshot: ScreenshotEntity)
    suspend fun deleteById(id: Long)
    suspend fun deleteAll()
    suspend fun resetAllAnalysis()
}
