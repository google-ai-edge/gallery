package com.smartscreenshot.organizer.data.model

/**
 * Categories for screenshot classification.
 * AI inference maps screenshots into one of these buckets.
 */
enum class Category(val displayName: String) {
    SHOPPING("Shopping"),
    RECEIPTS("Receipts"),
    CHAT("Chat"),
    SOCIAL_MEDIA("Social Media"),
    BANKING("Banking"),
    TRAVEL("Travel"),
    WORK("Work"),
    CODING("Coding"),
    MEMES("Memes"),
    DOCUMENTS("Documents"),
    OTHER("Other");

    companion object {
        /**
         * Case-insensitive lookup with fallback to OTHER.
         * Handles both enum names ("SOCIAL_MEDIA") and display names ("Social Media").
         */
        fun fromString(value: String): Category {
            return entries.find {
                it.name.equals(value, ignoreCase = true) ||
                    it.displayName.equals(value, ignoreCase = true)
            } ?: OTHER
        }
    }
}
