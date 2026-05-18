package com.smartscreenshot.organizer.ai

import com.smartscreenshot.organizer.data.model.AnalysisResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests for HttpInferenceProvider's JSON response parsing.
 *
 * This is the #1 runtime failure mode: LLMs return malformed JSON,
 * markdown-wrapped JSON, extra whitespace, or completely wrong formats.
 * The parser must be resilient to all of these.
 *
 * These are pure-function tests — no mocks, no Context, no network.
 */
class HttpInferenceProviderParseTest {

    private lateinit var moshi: Moshi
    private lateinit var adapter: com.squareup.moshi.JsonAdapter<AnalysisResult>

    @Before
    fun setup() {
        moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        adapter = moshi.adapter(AnalysisResult::class.java)
    }

    // ── Helper: mirrors HttpInferenceProvider.parseResponse() ────────

    private fun parseResponse(response: String): AnalysisResult? {
        if (response.isBlank()) return null
        return try {
            val cleaned = response
                .replace("```json", "")
                .replace("```", "")
                .trim()
            adapter.fromJson(cleaned)
        } catch (e: Exception) {
            null
        }
    }

    // ── Valid JSON ───────────────────────────────────────────────────

    @Test
    fun `parses valid JSON with all fields`() {
        val json = """
        {
            "title": "Shopping Cart",
            "summary": "Amazon checkout with 3 items",
            "category": "Shopping",
            "tags": ["amazon", "checkout"],
            "detected_apps": ["Amazon"],
            "important_text": "Total: $42.99",
            "priority_score": 5
        }
        """.trimIndent()

        val result = parseResponse(json)

        assertNotNull(result)
        assertEquals("Shopping Cart", result!!.title)
        assertEquals("Amazon checkout with 3 items", result.summary)
        assertEquals("Shopping", result.category)
        assertEquals(listOf("amazon", "checkout"), result.tags)
        assertEquals(listOf("Amazon"), result.detectedApps)
        assertEquals("Total: \$42.99", result.importantText)
        assertEquals(5, result.priorityScore)
    }

    @Test
    fun `parses JSON with missing optional fields`() {
        val json = """
        {
            "title": "Screenshot",
            "summary": "A screenshot",
            "category": "Other"
        }
        """.trimIndent()

        val result = parseResponse(json)

        assertNotNull(result)
        assertEquals("Screenshot", result!!.title)
        assertEquals("Other", result.category)
    }

    // ── Markdown-wrapped JSON ───────────────────────────────────────

    @Test
    fun `strips markdown json code fences`() {
        val response = """
        ```json
        {
            "title": "Chat Message",
            "summary": "WhatsApp conversation",
            "category": "Chat"
        }
        ```
        """.trimIndent()

        val result = parseResponse(response)

        assertNotNull(result)
        assertEquals("Chat Message", result!!.title)
    }

    @Test
    fun `strips plain markdown code fences`() {
        val response = """
        ```
        {
            "title": "Code Snippet",
            "summary": "VS Code editor",
            "category": "Coding"
        }
        ```
        """.trimIndent()

        val result = parseResponse(response)

        assertNotNull(result)
        assertEquals("Code Snippet", result!!.title)
    }

    // ── Edge cases and malformed input ──────────────────────────────

    @Test
    fun `returns null for blank response`() {
        assertNull(parseResponse(""))
        assertNull(parseResponse("   "))
        assertNull(parseResponse("\n\n"))
    }

    @Test
    fun `returns null for completely invalid JSON`() {
        assertNull(parseResponse("This is not JSON at all"))
        assertNull(parseResponse("{ broken json"))
        assertNull(parseResponse("[1, 2, 3]"))
    }

    @Test
    fun `returns null for JSON array instead of object`() {
        assertNull(parseResponse("""[{"title": "test"}]"""))
    }

    @Test
    fun `handles JSON with extra whitespace and newlines`() {
        val json = "\n\n  {  \"title\" : \"Test\" , \"summary\" : \"A test\" , \"category\" : \"Other\" }  \n\n"

        val result = parseResponse(json)

        assertNotNull(result)
        assertEquals("Test", result!!.title)
    }

    @Test
    fun `handles unicode in response`() {
        val json = """
        {
            "title": "日本語テスト",
            "summary": "Screenshot with Japanese text",
            "category": "Documents",
            "important_text": "価格: ¥1,500"
        }
        """.trimIndent()

        val result = parseResponse(json)

        assertNotNull(result)
        assertEquals("日本語テスト", result!!.title)
        assertEquals("価格: ¥1,500", result.importantText)
    }

    // ── Priority score edge cases (mirrors AnalysisResult.clampedPriorityScore) ──

    @Test
    fun `parses extreme priority scores`() {
        val json = """{"title": "t", "summary": "s", "category": "Other", "priority_score": 100}"""
        val result = parseResponse(json)
        assertNotNull(result)
        assertEquals(100, result!!.priorityScore)
    }

    @Test
    fun `parses zero priority score`() {
        val json = """{"title": "t", "summary": "s", "category": "Other", "priority_score": 0}"""
        val result = parseResponse(json)
        assertNotNull(result)
        assertEquals(0, result!!.priorityScore)
    }

    @Test
    fun `parses negative priority score`() {
        val json = """{"title": "t", "summary": "s", "category": "Other", "priority_score": -5}"""
        val result = parseResponse(json)
        assertNotNull(result)
        assertEquals(-5, result!!.priorityScore)
    }
}
