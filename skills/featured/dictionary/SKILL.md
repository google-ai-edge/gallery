---
name: dictionary
description: Looks up word definitions, pronunciation, synonyms, antonyms, and example sentences using the free Dictionary API. Use when the user asks what a word means, wants synonyms, or needs to understand a term.
metadata:
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/featured/dictionary
---

# Dictionary

Looks up English words using the free [Dictionary API](https://dictionaryapi.dev) — no API key required.

## Examples

* "What does 'ephemeral' mean?"
* "Define the word 'resilience'"
* "Give me synonyms for 'happy'"
* "How do you pronounce 'quinoa'?"
* "What is the origin of the word 'serendipity'?"

## Instructions

Call the `run_js` tool with the following exact parameters:

- **script name**: `index.html`
- **data**: A JSON string with the following fields:
  - `word`: String — the word to look up

### Rules
- Always extract the clean word from the user's question (e.g. "what does 'ephemeral' mean?" → `word: "ephemeral"`)
- After receiving the result, present it in a clean, readable format:
  - Pronunciation (if available)
  - Part of speech + definition
  - Example sentence (if available)
  - Synonyms (if available)
- If the word is not found, tell the user and suggest checking the spelling
