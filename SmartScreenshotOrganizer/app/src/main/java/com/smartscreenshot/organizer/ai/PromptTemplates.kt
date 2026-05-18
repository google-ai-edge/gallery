package com.smartscreenshot.organizer.ai

/**
 * Prompt templates for screenshot analysis.
 * Kept in a single location for consistency across inference providers.
 */
object PromptTemplates {

    /**
     * Builds the analysis prompt with the extracted text baked in.
     * The prompt instructs the LLM to return strict JSON matching AnalysisResult schema.
     */
    fun buildAnalysisPrompt(context: ScreenshotContext): String {
        val textSection = if (context.extractedText.isNotBlank()) {
            """
            |
            |--- EXTRACTED TEXT (from OCR) ---
            |${context.extractedText.take(4000)}
            |--- END EXTRACTED TEXT ---
            """.trimMargin()
        } else {
            "\n(No text was extracted from this screenshot via OCR.)"
        }

        return """
            |You are an AI assistant that analyzes mobile screenshots.
            |
            |Given the following information about a screenshot, analyze it and return STRICT JSON only.
            |Do not include any text outside the JSON object. Do not use markdown code fences.
            |
            |Screenshot filename: ${context.fileName}
            |Captured at: ${context.timestamp}
            |Image dimensions: ${context.imageWidth}x${context.imageHeight}
            |$textSection
            |
            |Analyze this screenshot and return a JSON object with these exact fields:
            |{
            |  "title": "A short, descriptive title (max 60 chars)",
            |  "summary": "A concise 1-2 sentence summary of what this screenshot shows",
            |  "category": "One of: Shopping, Receipts, Chat, Social Media, Banking, Travel, Work, Coding, Memes, Documents, Other",
            |  "tags": ["tag1", "tag2", "tag3"],
            |  "detected_apps": ["App names visible or identifiable in the screenshot"],
            |  "important_text": ["Key pieces of text worth remembering from this screenshot"],
            |  "priority_score": 5
            |}
            |
            |Rules:
            |- title: Concise, human-readable description of the screenshot content
            |- summary: What is happening in this screenshot? What information does it contain?
            |- category: Must be exactly one of the listed categories
            |- tags: 3-7 relevant keywords for search and organization
            |- detected_apps: Names of apps, websites, or services visible
            |- important_text: Key data points (amounts, dates, names, codes, addresses)
            |- priority_score: 0 (trivial, e.g. meme) to 10 (critical, e.g. banking transaction)
            |
            |Return ONLY the JSON object. No explanation.
        """.trimMargin()
    }
}
