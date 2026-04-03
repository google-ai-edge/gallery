---
name: gemini-search
description: Search the web using Google Search via Gemini grounding API and return a synthesized answer with sources.
metadata:
  require-secret: true
  require-secret-description: Enter your Gemini API key from https://aistudio.google.com/app/apikey
  homepage: https://github.com/google-ai-edge/gallery
---

# Gemini Search

This skill searches the web using Google Search grounding via the Gemini API and returns a synthesized answer with cited sources.

## Instructions

Call the `run_js` tool using `index.html` with the following exact parameters:
- data: A JSON string with the following field:
  - **query**: Required. The user's search question exactly as asked — do not rephrase or simplify it.

After receiving the result, present the answer as a well-formatted response with sources cited inline where relevant.

If an error is returned, report it clearly to the user and suggest they check that their Gemini API key is valid and has the necessary permissions.

DO NOT use any other tool. DO NOT call `run_intent`.
