package com.smartscreenshot.organizer.data.repository

import com.smartscreenshot.organizer.data.db.CategoryCount
import com.smartscreenshot.organizer.data.db.ScreenshotDao
import com.smartscreenshot.organizer.data.db.ScreenshotEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotRepositoryImpl @Inject constructor(
    private val dao: ScreenshotDao
) : ScreenshotRepository {

    override fun observeAll(): Flow<List<ScreenshotEntity>> = dao.observeAll()

    override fun observeRecent(limit: Int): Flow<List<ScreenshotEntity>> =
        dao.observeRecent(limit)

    override fun observeByCategory(category: String): Flow<List<ScreenshotEntity>> =
        dao.observeByCategory(category)

    override fun observeByDateRange(
        startMillis: Long,
        endMillis: Long
    ): Flow<List<ScreenshotEntity>> = dao.observeByDateRange(startMillis, endMillis)

    override fun observeCategoryCounts(): Flow<List<CategoryCount>> =
        dao.observeCategoryCounts()

    override fun search(query: String): Flow<List<ScreenshotEntity>> {
        // Sanitize FTS query: escape special characters, append wildcard for prefix matching
        val sanitized = query.trim()
            .replace("\"", "\"\"")
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }

        return if (sanitized.isNotBlank()) {
            dao.search(sanitized)
        } else {
            dao.observeAll()
        }
    }

    override suspend fun getById(id: Long): ScreenshotEntity? = dao.getById(id)

    override suspend fun getByUri(uri: String): ScreenshotEntity? = dao.getByUri(uri)

    override suspend fun getUnanalyzed(limit: Int): List<ScreenshotEntity> =
        dao.getUnanalyzed(limit)

    override suspend fun getUnanalyzedCount(): Int = dao.getUnanalyzedCount()

    override suspend fun getTotalCount(): Int = dao.getTotalCount()

    override suspend fun getAllUris(): List<String> = dao.getAllUris()

    override suspend fun uriExists(uri: String): Boolean = dao.uriExists(uri)

    override suspend fun insert(screenshot: ScreenshotEntity): Long =
        dao.insert(screenshot)

    override suspend fun insertAll(screenshots: List<ScreenshotEntity>): List<Long> =
        dao.insertAll(screenshots)

    override suspend fun update(screenshot: ScreenshotEntity) = dao.update(screenshot)

    override suspend fun deleteById(id: Long) = dao.deleteById(id)

    override suspend fun deleteAll() = dao.deleteAll()

    override suspend fun resetAllAnalysis() = dao.resetAllAnalysis()
}
