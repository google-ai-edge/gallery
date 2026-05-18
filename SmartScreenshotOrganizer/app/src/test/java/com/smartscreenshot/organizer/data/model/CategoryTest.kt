package com.smartscreenshot.organizer.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTest {

    @Test
    fun `fromString matches enum name case-insensitively`() {
        assertEquals(Category.SHOPPING, Category.fromString("SHOPPING"))
        assertEquals(Category.SHOPPING, Category.fromString("shopping"))
        assertEquals(Category.SHOPPING, Category.fromString("Shopping"))
    }

    @Test
    fun `fromString matches display name`() {
        assertEquals(Category.SOCIAL_MEDIA, Category.fromString("Social Media"))
        assertEquals(Category.SOCIAL_MEDIA, Category.fromString("social media"))
    }

    @Test
    fun `fromString returns OTHER for unknown values`() {
        assertEquals(Category.OTHER, Category.fromString(""))
        assertEquals(Category.OTHER, Category.fromString("unknown_category"))
        assertEquals(Category.OTHER, Category.fromString("Games"))
    }

    @Test
    fun `all categories have display names`() {
        Category.entries.forEach { category ->
            assert(category.displayName.isNotBlank()) {
                "${category.name} has blank displayName"
            }
        }
    }
}
