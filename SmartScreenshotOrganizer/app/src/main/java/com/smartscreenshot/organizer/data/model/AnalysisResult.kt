package com.smartscreenshot.organizer.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Structured output from the AI inference provider.
 * Maps directly to the JSON schema sent in the analysis prompt.
 *
 * All fields have safe defaults so partial/malformed AI responses
 * degrade gracefully instead of crashing.
 */
@JsonClass(generateAdapter = true)
data class AnalysisResult(
    @Json(name = "title")
    val title: String = "",

    @Json(name = "summary")
    val summary: String = "",

    @Json(name = "category")
    val category: String = "Other",

    @Json(name = "tags")
    val tags: List<String> = emptyList(),

    @Json(name = "detected_apps")
    val detectedApps: List<String> = emptyList(),

    @Json(name = "important_text")
    val importantText: List<String> = emptyList(),

    @Json(name = "priority_score")
    val priorityScore: Int = 0
) {
    /** Parsed category with fallback to OTHER. */
    val parsedCategory: Category
        get() = Category.fromString(category)

    /** Clamp priority score to valid range. */
    val clampedPriorityScore: Int
        get() = priorityScore.coerceIn(0, 10)
}
