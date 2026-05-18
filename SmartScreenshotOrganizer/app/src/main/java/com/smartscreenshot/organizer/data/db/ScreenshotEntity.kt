package com.smartscreenshot.organizer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Primary entity storing screenshot metadata and AI analysis results.
 *
 * Indices optimize the most common query patterns:
 * - category: filtering by classification
 * - createdAt: chronological sorting
 * - isAnalyzed: finding unprocessed screenshots
 * - uri: uniqueness constraint for deduplication
 */
@Entity(
    tableName = "screenshots",
    indices = [
        Index(value = ["category"], name = "idx_category"),
        Index(value = ["createdAt"], name = "idx_created_at"),
        Index(value = ["isAnalyzed"], name = "idx_is_analyzed"),
        Index(value = ["uri"], unique = true, name = "idx_uri_unique")
    ]
)
data class ScreenshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "filePath")
    val filePath: String? = null,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "summary")
    val summary: String? = null,

    @ColumnInfo(name = "category", defaultValue = "OTHER")
    val category: String = "OTHER",

    /** JSON-serialized List<String> via Converters */
    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList(),

    /** JSON-serialized List<String> via Converters */
    @ColumnInfo(name = "detectedApps")
    val detectedApps: List<String> = emptyList(),

    /** JSON-serialized List<String> via Converters */
    @ColumnInfo(name = "importantText")
    val importantText: List<String> = emptyList(),

    /** Raw OCR text output for full-text search */
    @ColumnInfo(name = "extractedText")
    val extractedText: String? = null,

    @ColumnInfo(name = "priorityScore", defaultValue = "0")
    val priorityScore: Int = 0,

    /** Epoch millis when AI analysis completed */
    @ColumnInfo(name = "analyzedAt")
    val analyzedAt: Long? = null,

    /** Epoch millis of screenshot creation (from MediaStore) */
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "thumbnailUri")
    val thumbnailUri: String? = null,

    @ColumnInfo(name = "isAnalyzed", defaultValue = "0")
    val isAnalyzed: Boolean = false
)
