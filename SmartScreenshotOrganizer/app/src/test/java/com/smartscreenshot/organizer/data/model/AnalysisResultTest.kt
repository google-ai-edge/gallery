package com.smartscreenshot.organizer.data.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AnalysisResultTest {

    private lateinit var moshi: Moshi
    private lateinit var adapter: com.squareup.moshi.JsonAdapter<AnalysisResult>

    @Before
    fun setup() {
        moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        adapter = moshi.adapter(AnalysisResult::class.java)
    }

    @Test
    fun `parse valid JSON response`() {
        val json = """
            {
                "title": "Amazon Order Confirmation",
                "summary": "Order confirmation for headphones from Amazon",
                "category": "Shopping",
                "tags": ["amazon", "order", "headphones"],
                "detected_apps": ["Amazon"],
                "important_text": ["Order #123-456", "$79.99"],
                "priority_score": 6
            }
        """.trimIndent()

        val result = adapter.fromJson(json)

        assertNotNull(result)
        assertEquals("Amazon Order Confirmation", result!!.title)
        assertEquals("Shopping", result.category)
        assertEquals(Category.SHOPPING, result.parsedCategory)
        assertEquals(3, result.tags.size)
        assertEquals(6, result.clampedPriorityScore)
    }

    @Test
    fun `defaults work for partial JSON`() {
        val json = """{"title": "Test"}"""

        val result = adapter.fromJson(json)

        assertNotNull(result)
        assertEquals("Test", result!!.title)
        assertEquals("", result.summary)
        assertEquals("Other", result.category)
        assertEquals(Category.OTHER, result.parsedCategory)
        assertEquals(emptyList<String>(), result.tags)
        assertEquals(0, result.priorityScore)
    }

    @Test
    fun `priority score clamping`() {
        val overMax = AnalysisResult(priorityScore = 15)
        assertEquals(10, overMax.clampedPriorityScore)

        val underMin = AnalysisResult(priorityScore = -5)
        assertEquals(0, underMin.clampedPriorityScore)

        val valid = AnalysisResult(priorityScore = 7)
        assertEquals(7, valid.clampedPriorityScore)
    }

    @Test
    fun `empty JSON object parses with defaults`() {
        val result = adapter.fromJson("{}")
        assertNotNull(result)
        assertEquals("", result!!.title)
        assertEquals(Category.OTHER, result.parsedCategory)
    }
}
