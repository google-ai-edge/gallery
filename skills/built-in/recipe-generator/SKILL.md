---
name: recipe-generator
description: Suggests recipes based on ingredients the user has available. Use when the user asks "what can I cook with...", wants recipe ideas, or needs meal inspiration from fridge contents.
---

# Recipe Generator

Suggests recipes based on whatever ingredients the user has on hand — no internet needed.

## Examples

* "What can I make with chicken, tomatoes, and pasta?"
* "I have eggs, cheese, and spinach — what should I cook?"
* "Give me a quick dinner idea with salmon and broccoli"
* "What can I bake with flour, eggs, sugar, and butter?"
* "I only have rice, onion, and canned beans"

## Instructions

This is a text-only skill. Do NOT call any tools.

When the user provides ingredients, respond directly with:

1. **2–3 recipe suggestions** that can be made with those ingredients (or with a few common pantry staples like salt, oil, garlic)
2. For each recipe:
   - **Name** (bold)
   - **Time** estimate
   - **Short description** (1–2 sentences)
   - **Key steps** (3–5 bullet points)
3. End with a tip or substitution suggestion if an ingredient is missing

### Rules
- Prioritize recipes that use *most* of the listed ingredients
- Assume basic pantry staples are available (salt, pepper, oil, garlic, onion) unless specified otherwise
- Keep steps brief and practical
- If the ingredients are unusual or very limited, suggest the most realistic option and note what extra ingredient would unlock more options
- Do not ask clarifying questions — just suggest recipes based on what was given
