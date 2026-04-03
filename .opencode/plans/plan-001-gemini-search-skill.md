# Plan 001: Gemini Search Skill for AI Edge Gallery

## Context

Build a new JS skill for the Google AI Edge Gallery app that lets on-device Gemma 4 models call
the Gemini API with Google Search grounding — enabling real-time web search from the edge device.

This mirrors the existing `restaurant-roulette` skill which already calls `generativelanguage.googleapis.com`
with a user-provided API key via the `secret` parameter.

## Reference files to read first

Before writing any files, read and understand these existing skills:
- `skills/featured/restaurant-roulette/SKILL.md` — how require-secret works
- `skills/featured/restaurant-roulette/scripts/index.js` — how to call Gemini API + handle secret
- `skills/built-in/query-wikipedia/SKILL.md` — clean input schema example
- `skills/built-in/query-wikipedia/scripts/index.html` — clean JS skill structure
- `skills/README.md` — full authoring spec

## Files to create

Write the following files:

### 1. `skills/featured/gemini-search/SKILL.md`

Frontmatter (YAML between --- delimiters):
```yaml
name: gemini-search
description: Search the web using Google Search via Gemini grounding API and return a synthesized answer with sources.
metadata:
  require-secret: true
  require-secret-description: Enter your Gemini API key from https://aistudio.google.com/app/apikey
  homepage: https://github.com/google-ai-edge/gallery
```

Instructions section (after second ---):
The LLM instructions should tell the model to:
- Call `run_js` using `index.html`
- Pass a JSON string as `data` with schema: `{ "query": "<user's search query>" }`
- The query should be the user's question exactly as asked — do not rephrase
- Present the result as a well-formatted answer with sources cited inline
- If an error is returned, report it clearly and suggest the user check their API key

### 2. `skills/featured/gemini-search/scripts/index.html`

A self-contained HTML file that implements `window.ai_edge_gallery_get_result`.

Requirements:
- Parse `data` as JSON to get `{ query }`
- Use `secret` as the Gemini API key
- Call the Gemini API with Google Search grounding:
  - Endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={secret}`
  - Model: `gemini-2.0-flash` (fast, good for search grounding)
  - Enable Google Search grounding tool: `{ "googleSearch": {} }` in the `tools` array
  - Request body structure:
    ```json
    {
      "contents": [{ "parts": [{ "text": "<query>" }], "role": "user" }],
      "tools": [{ "googleSearch": {} }],
      "generationConfig": { "temperature": 1.0, "maxOutputTokens": 1024 }
    }
    ```
- Extract the response text from `candidates[0].content.parts[0].text`
- Extract grounding sources from `candidates[0].groundingMetadata.groundingChunks` if present
  - Each chunk has `web.uri` and `web.title`
  - Append sources as a formatted list at the end of the result
- Return format:
  - Success: `JSON.stringify({ result: "<synthesized answer>\n\nSources:\n- [title](url)\n..." })`
  - Error: `JSON.stringify({ error: "<error message>" })`
- Handle network errors and non-200 responses gracefully
- Do NOT use any external CDN libraries — pure fetch() API only
- The function must be async and assigned to `window['ai_edge_gallery_get_result']`

## Completion

After writing both files, verify:
1. `skills/featured/gemini-search/SKILL.md` exists and has valid frontmatter
2. `skills/featured/gemini-search/scripts/index.html` exists and implements the function
3. Check that the Gemini API endpoint and request body structure matches the `restaurant-roulette` skill pattern

Reply: GEMINI-SEARCH-BUILD-DONE
