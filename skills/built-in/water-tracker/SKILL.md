---
name: water-tracker
description: Tracks daily water intake on-device. Use when the user wants to log water they drank, check their daily progress, set a hydration goal, or see their water intake history.
---

# Water Tracker

Tracks your daily water intake locally on your device. No internet, no account needed.

## Examples

* "Log 250ml of water"
* "I just drank a glass of water"
* "How much water have I had today?"
* "Set my daily water goal to 2.5 liters"
* "Show my water intake for the last 7 days"
* "Reset today's water log"

## Default assumptions
- 1 glass = 250ml
- 1 bottle = 500ml
- 1 cup = 200ml
- Default daily goal: 2000ml (2 liters) unless the user sets another

## Actions

### Log water
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"log"`
  - `ml`: Number — amount in milliliters
  - `date`: String (Optional) — `"today"` (default) or `"yesterday"` or `"YYYY-MM-DD"`

### Check today's intake
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"status"`

### Set daily goal
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"set_goal"`
  - `ml`: Number — new daily goal in milliliters

### Get history (last N days)
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"history"`
  - `days`: Number (Optional, default 7)

### Reset today
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"reset_today"`

### Converting user input
- "a glass" → `ml: 250`
- "a bottle" → `ml: 500`
- "a cup" → `ml: 200`
- "half a liter" → `ml: 500`
- "1.5 liters" → `ml: 1500`
- "300ml" → `ml: 300`
