package com.smartscreenshot.organizer.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {

    @Test
    fun `prompt includes extracted text when present`() {
        val context = ScreenshotContext(
            extractedText = "Hello World from OCR",
            fileName = "screenshot_001.png",
            timestamp = 1700000000000L,
            imageWidth = 1080,
            imageHeight = 1920
        )

        val prompt = PromptTemplates.buildAnalysisPrompt(context)

        assertTrue(prompt.contains("Hello World from OCR"))
        assertTrue(prompt.contains("screenshot_001.png"))
        assertTrue(prompt.contains("1080x1920"))
        assertTrue(prompt.contains("STRICT JSON"))
    }

    @Test
    fun `prompt handles empty extracted text`() {
        val context = ScreenshotContext(
            extractedText = "",
            fileName = "screenshot.png",
            timestamp = 1700000000000L
        )

        val prompt = PromptTemplates.buildAnalysisPrompt(context)

        assertTrue(prompt.contains("No text was extracted"))
        assertFalse(prompt.contains("EXTRACTED TEXT"))
    }

    @Test
    fun `prompt truncates long text to 4000 chars`() {
        val longText = "A".repeat(10000)
        val context = ScreenshotContext(
            extractedText = longText,
            fileName = "screenshot.png",
            timestamp = 1700000000000L
        )

        val prompt = PromptTemplates.buildAnalysisPrompt(context)

        // The prompt should not contain the full 10000 chars
        assertFalse(prompt.contains("A".repeat(5000)))
        assertTrue(prompt.contains("A".repeat(4000)))
    }

    @Test
    fun `prompt specifies all expected JSON fields`() {
        val context = ScreenshotContext(
            extractedText = "test",
            fileName = "test.png",
            timestamp = 0L
        )

        val prompt = PromptTemplates.buildAnalysisPrompt(context)

        listOf("title", "summary", "category", "tags", "detected_apps", "important_text", "priority_score")
            .forEach { field ->
                assertTrue("Prompt missing field: $field", prompt.contains("\"$field\""))
            }
    }

    @Test
    fun `prompt lists all categories`() {
        val context = ScreenshotContext(
            extractedText = "test",
            fileName = "test.png",
            timestamp = 0L
        )

        val prompt = PromptTemplates.buildAnalysisPrompt(context)

        listOf("Shopping", "Receipts", "Chat", "Social Media", "Banking", "Travel", "Work", "Coding", "Memes", "Documents", "Other")
            .forEach { cat ->
                assertTrue("Prompt missing category: $cat", prompt.contains(cat))
            }
    }
}
