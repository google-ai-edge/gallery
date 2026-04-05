---
name: google-search
description: >
  Searches the web using Google Custom Search and returns current results with
  titles, URLs, and summaries. Use when the user needs up-to-date information,
  news, facts, product details, or anything that benefits from a live web search.
metadata:
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/featured/google-search
  require-secret: true
  require-secret-description: >
    Enter your Google Custom Search credentials in this exact format:
    API_KEY|CX_ID (separated by a pipe character |).
    Step 1 - Get your free API key: https://console.cloud.google.com/apis/credentials (enable the "Custom Search JSON API").
    Step 2 - Create a free Search Engine ID: https://programmablesearchengine.google.com (set "Search the entire web" to ON).
    Example: AIzaSyABC123|017576662512468239146:omuauf_lfve
---

# Google Search

## Instructions

When the user asks a question that requires current information, recent news,
facts, prices, or any topic that benefits from a live web search, extract the
most effective search query and call the `google_search` tool.

Call the tool with a JSON object containing:
- `query` (required): the search query string — be specific and concise
- `num` (optional): number of results to return, between 1 and 10, default 5

After receiving the results, synthesize a helpful, accurate response based on
the search findings. Always cite your sources by mentioning the title and URL
of the relevant result.

If the tool returns an error about missing credentials, instruct the user to
tap the key icon in the skill manager to enter their API_KEY|CX_ID.

## Examples

User: "What is the weather like in Copenhagen today?"
Call with: `{ "query": "Copenhagen weather today", "num": 3 }`

User: "Find the latest news about artificial intelligence"
Call with: `{ "query": "artificial intelligence news 2025", "num": 5 }`

User: "What is the price of iPhone 16 Pro?"
Call with: `{ "query": "iPhone 16 Pro price 2025", "num": 5 }`
