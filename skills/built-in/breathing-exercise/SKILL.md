---
name: breathing-exercise
description: Guides the user through animated breathing exercises for relaxation and stress relief. Supports box breathing, 4-7-8 breathing, and calm breathing patterns. Use when the user wants to relax, reduce stress, meditate, or do a breathing exercise.
---

# Breathing Exercise

An animated, guided breathing exercise running fully on-device. Helps with stress relief, focus, and relaxation.

## Supported Patterns

| Pattern | Description | Best for |
|---------|-------------|----------|
| `box` | 4s inhale → 4s hold → 4s exhale → 4s hold | Focus, stress relief |
| `478` | 4s inhale → 7s hold → 8s exhale | Sleep, deep relaxation |
| `calm` | 5s inhale → 5s exhale | Everyday calm, beginners |

## Examples

* "Start a breathing exercise"
* "Guide me through box breathing"
* "Help me relax with 4-7-8 breathing"
* "I'm stressed, can we do some breathing?"
* "Start 5 rounds of calm breathing"

## Instructions

Call the `run_js` tool with the following exact parameters:

- **script name**: `index.html`
- **data**: A JSON string with the following fields:
  - `pattern`: String (Optional) — `"box"`, `"478"`, or `"calm"`. Defaults to `"box"`.
  - `rounds`: Number (Optional) — number of breathing cycles to complete. Defaults to `5`.

### Pattern selection guide

- User says "relax" / "calm down" / "I'm stressed" → use `"box"` (5 rounds)
- User says "help me sleep" / "wind down" → use `"478"` (4 rounds)
- User says "beginner" / "simple" / "just breathe" → use `"calm"` (5 rounds)
- User specifies a number of rounds → pass that as `rounds`
