package com.smartscreenshot.organizer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenshotDao {

    // ── Inserts ──────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(screenshot: ScreenshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(screenshots: List<ScreenshotEntity>): List<Long>

    @Update
    suspend fun update(screenshot: ScreenshotEntity)

    // ── Queries (reactive) ──────────────────────────────────────────────

    /** All screenshots ordered by creation date, newest first. */
    @Query("SELECT * FROM screenshots ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ScreenshotEntity>>

    /** Screenshots filtered by category. */
    @Query("SELECT * FROM screenshots WHERE category = :category ORDER BY createdAt DESC")
    fun observeByCategory(category: String): Flow<List<ScreenshotEntity>>

    /** Screenshots within a date range (epoch millis). */
    @Query(
        "SELECT * FROM screenshots WHERE createdAt BETWEEN :startMillis AND :endMillis " +
            "ORDER BY createdAt DESC"
    )
    fun observeByDateRange(startMillis: Long, endMillis: Long): Flow<List<ScreenshotEntity>>

    /** Recent screenshots limited to N items. */
    @Query("SELECT * FROM screenshots ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ScreenshotEntity>>

    /** Category counts for the home screen chips. */
    @Query("SELECT category, COUNT(*) as count FROM screenshots GROUP BY category ORDER BY count DESC")
    fun observeCategoryCounts(): Flow<List<CategoryCount>>

    // ── Queries (one-shot) ──────────────────────────────────────────────

    @Query("SELECT * FROM screenshots WHERE id = :id")
    suspend fun getById(id: Long): ScreenshotEntity?

    @Query("SELECT * FROM screenshots WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): ScreenshotEntity?

    @Query("SELECT * FROM screenshots WHERE isAnalyzed = 0 ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getUnanalyzed(limit: Int = 10): List<ScreenshotEntity>

    @Query("SELECT COUNT(*) FROM screenshots WHERE isAnalyzed = 0")
    suspend fun getUnanalyzedCount(): Int

    @Query("SELECT COUNT(*) FROM screenshots")
    suspend fun getTotalCount(): Int

    @Query("SELECT uri FROM screenshots")
    suspend fun getAllUris(): List<String>

    /** Check if a URI already exists in the database. O(1) memory vs loading all URIs. */
    @Query("SELECT EXISTS(SELECT 1 FROM screenshots WHERE uri = :uri)")
    suspend fun uriExists(uri: String): Boolean

    // ── Full-Text Search ────────────────────────────────────────────────

    /**
     * FTS4 search across title, summary, extracted text, and tags.
     * Uses MATCH syntax for efficient token-based search.
     */
    @Query(
        "SELECT screenshots.* FROM screenshots " +
            "JOIN screenshots_fts ON screenshots.rowid = screenshots_fts.rowid " +
            "WHERE screenshots_fts MATCH :query " +
            "ORDER BY screenshots.createdAt DESC"
    )
    fun search(query: String): Flow<List<ScreenshotEntity>>

    // ── Maintenance ─────────────────────────────────────────────────────

    @Query("DELETE FROM screenshots WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM screenshots")
    suspend fun deleteAll()

    /** Reset analysis state for all screenshots (re-index). */
    @Query("UPDATE screenshots SET isAnalyzed = 0, analyzedAt = NULL")
    suspend fun resetAllAnalysis()
}

/** Projection for category count aggregation. */
data class CategoryCount(
    val category: String,
    val count: Int
)
