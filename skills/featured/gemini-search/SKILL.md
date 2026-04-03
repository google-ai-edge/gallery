---
name: gemini-search
description: Search the web using Google Search via Gemini grounding API and return a synthesized answer with sources.
metadata:
  require-secret: true
  require-secret-description: Enter your Gemini API key from https://aistudio.google.com/app/apikey
  homepage: https://github.com/google-ai-edge/gallery
---

# Gemini Search

This skill searches the live web using Google Search grounding via the Gemini API and returns a direct answer with sources.

## Instructions

You MUST call the `run_js` tool using `index.html`.

### Exact payload schema
Pass `data` as a JSON string with exactly one field:
- **query**: String, required. Copy the user's search question as literally as possible.

### Query handling rules
- Preserve the user's original intent.
- Do NOT turn factual questions into predictions, opinions, or broader topics.
- Do NOT shorten the query into vague keywords if the original wording is already clear.
- If the user asks a current-status question, keep that current-status wording.

### Examples
- User: `Can you search whether Italy will go to next world cup or not`
  - `query`: `Can you search whether Italy will go to next world cup or not`
- User: `What's the latest on OpenAI's funding round?`
  - `query`: `What's the latest on OpenAI's funding round?`

After receiving the result:
- Answer the user's question directly.
- Cite sources inline or in a short sources list.
- If an error is returned, report it clearly and suggest checking the Gemini API key and permissions.

DO NOT use any other tool. DO NOT call `run_intent`.
