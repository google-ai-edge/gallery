---
name: sleep-log
description: Tracks sleep times and duration on-device. Use when the user wants to log when they went to bed, when they woke up, view their sleep history, or analyze their sleep patterns.
---

# Sleep Log

Records and tracks your sleep on-device. Fully private — no internet, no account.

## Examples

* "I went to bed at 11pm and woke up at 7am"
* "Log sleep: 23:30 to 06:45"
* "Show my sleep history"
* "How much did I sleep last night?"
* "Show my average sleep this week"

## Actions

### Log sleep
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"log"`
  - `bedtime`: String — time went to bed, e.g. `"23:00"` or `"11pm"`
  - `wake_time`: String — time woke up, e.g. `"07:00"` or `"7am"`
  - `date`: String (Optional) — the date of the night's sleep. Use `"today"` for last night (default), or `"YYYY-MM-DD"` for a specific date.
  - `quality`: Number (Optional) — self-rated sleep quality 1–5
  - `note`: String (Optional) — a short note about the sleep

### Get last night's sleep
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"last"`

### Get history
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"history"`
  - `days`: Number (Optional, default 7)

### Delete an entry
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"delete"`
  - `date`: String — the date to delete (`"today"`, `"yesterday"`, or `"YYYY-MM-DD"`)

### Wipe all data
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"wipe"`

### Parsing tips
- "last night" → `date: "today"` (sleep that ended today)
- "I went to bed at 11" → infer pm (23:00) if context suggests night
- If user says "I slept 7 hours" without times, ask for bedtime or wake time to log accurately
