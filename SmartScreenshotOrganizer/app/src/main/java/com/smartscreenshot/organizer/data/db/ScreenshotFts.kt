package com.smartscreenshot.organizer.data.db

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

/**
 * FTS4 virtual table for full-text search across screenshot metadata.
 *
 * Content-synced with the screenshots table so inserts/updates/deletes
 * are automatically reflected in the search index.
 *
 * Tokenizer: unicode61 handles multi-language text from OCR output.
 */
@Fts4(
    contentEntity = ScreenshotEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61
)
@Entity(tableName = "screenshots_fts")
data class ScreenshotFts(
    val title: String?,
    val summary: String?,
    val extractedText: String?,
    val tags: List<String>
)
