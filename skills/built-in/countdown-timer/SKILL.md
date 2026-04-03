---
name: countdown-timer
description: Starts a visual countdown timer for a given duration. Use when the user wants a timer, needs to track time for cooking, exercise, meetings, or any timed activity.
---

# Countdown Timer

Displays an animated countdown timer on screen. Runs fully on-device, no internet required.

## Examples

* "Set a 5 minute timer"
* "Start a 30 second countdown"
* "Give me a 10 minute timer for my pasta"
* "Set a timer for 1 hour 30 minutes"
* "Start a 45 second timer for push-ups"

## Instructions

Call the `run_js` tool with the following exact parameters:

- **script name**: `index.html`
- **data**: A JSON string with the following fields:
  - `duration_seconds`: Number — total countdown duration in seconds
  - `label`: String (Optional) — a label to display on the timer, e.g. `"Pasta"` or `"Meeting"`

### Parsing user input
Convert the user's time to seconds before calling the skill:
- "5 minutes" → `300`
- "1 hour 30 minutes" → `5400`
- "45 seconds" → `45`
- "2 hours" → `7200`
