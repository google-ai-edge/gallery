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
- **query**: String, Required.

### Mandatory query construction rules
- You MUST preserve the user's original question as literally as possible.
- You MUST preserve the user's original intent.
- You MUST NOT convert a factual question into a prediction, opinion, or generic topic.
- You MUST NOT replace a full question with vague keywords when the original question is already clear.
- If the user asks about **whether**, **is**, **did**, **has**, **current**, **latest**, **status**, **qualify**, **qualified**, **going to**, or similar status/outcome wording, you MUST keep that wording in the `query` field.
- If the user already wrote a clear search-style question, copy it with minimal changes.
- If unsure, prefer the user's longer literal question over a shorter rewritten query.

### Do NOT do this
- Do NOT rewrite `Will Italy go to the next World Cup?` into `Italy World Cup prediction`
- Do NOT rewrite `Did Company X raise funding?` into `Company X funding`
- Do NOT rewrite `Is Product Y available in Singapore?` into `Product Y Singapore`

### Good examples
- User: `Can you search whether Italy will go to next world cup or not`
  - `query`: `Can you search whether Italy will go to next world cup or not`
- User: `Did OpenAI just raise a new funding round?`
  - `query`: `Did OpenAI just raise a new funding round?`
- User: `Is the Nintendo Switch 2 available in Singapore yet?`
  - `query`: `Is the Nintendo Switch 2 available in Singapore yet?`
- User: `What's the latest on OpenAI's funding round?`
  - `query`: `What's the latest on OpenAI's funding round?`

### After the tool returns
- Answer the user's question directly.
- Keep the answer concise and factual.
- Cite sources inline or in a short sources list.
- If an error is returned, report it clearly and suggest checking the Gemini API key and permissions.

DO NOT use any other tool. DO NOT call `run_intent`.
