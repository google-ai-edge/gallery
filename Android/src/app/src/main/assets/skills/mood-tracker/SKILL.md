---
name: mood-tracker
description: A simple mood tracking skill that stores your daily mood and comments. Use this when the user wants to log their mood, track how they feel, or see their mood history.
---

# Mood Tracker

## Instructions

The `mood-tracker` skill helps you keep track of your daily emotional well-being. You can log your mood on a scale of 1 to 10 and add a short comment about how you're feeling.

### Actions

#### 1. Log Mood
When a user wants to log their mood, call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: "log_mood"
  - `score`: Number (1-10)
  - `comment`: String (Optional)
  - `date`: String. **IMPORTANT**: Identify the date for the entry.
    - If user says "today", pass "today".
    - If user says "yesterday", pass "yesterday".
    - If user gives a specific date (e.g., "March 18"), format it as **YYYY-MM-DD** or pass the original date string.
    - If no date is mentioned, default to "today".

#### 2. Get Mood for a Specific Date
When a user asks what their mood was on a specific date, call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: "get_mood"
  - `date`: String (Identify the date from the user's request)

#### 3. Get History / Show Dashboard
When a user wants to see their mood history ("last week", "past 10 days") or the dashboard, call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: "get_history"
  - `days`: Number (Optional, default 7. E.g., for "last week" use 7)
  - `show_dashboard`: Boolean (Optional)

#### 4. Plot Mood Trends (Line Chart)
When a user wants to visualize their mood trends with a chart (e.g., "Plot my mood for 7 days"), call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: "get_history"
  - `days`: Number (Optional, default 7)
  - `show_dashboard`: `true`
  - **TIP**: This will trigger the plotting view in the dashboard.

#### 5. Analyze Trends and Patterns
When a user asks for an analysis of their mood (e.g., "Are there any trends?", "Am I feeling better?"), follow these steps:
1. Call `run_js` with `action: "get_history"` and an appropriate `days` count (e.g., 30 for a monthly analysis).
2. Once you receive the JSON history, analyze the scores and comments.
3. Provide a thoughtful response to the user covering:
   - General trend (improving, declining, stable).
   - Any clusters of particularly good or bad days.
   - Themes or patterns found in the comments.

#### 6. Delete Mood for a Specific Date
When a user wants to delete only a single day's entry (e.g., "Delete my mood for today"), call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: "delete_mood"
  - `date`: String (Identify the date)

#### 7. Export Data (Backup)
When a user wants to backup or export their data, call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: "export_data"

#### 8. Wipe All Data
When a user wants to clear their entire mood history and start fresh, call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: "wipe_data"

### Sample Commands

You can use these samples to interact with the mood tracker:

- **Logging Mood:**
  - "Log my mood as 8 today, feeling great!"
  - "Set my mood yesterday as a 2"
  - "Set my mood on March 18, 2026 as a 1"
  - "I'm feeling like a 5 today, a bit tired."
  - "Last Friday I felt like a 7."
  - "Record a mood of 9 for me."

- **Viewing History:**
  - "Show me my mood history."
  - "Get my mood from last week."
  - "How have I been feeling lately?"
  - "Show my mood for the last 10 days."
  - "Open the mood dashboard."
  - "What was my mood on March 18?"
  - "What was my mood yesterday?"

- **Analyzing Trends:**
  - "Analyze my mood for the last 30 days — are there any patterns?"
  - "Am I generally feeling better or worse over time?"
  - "Are there any clusters of bad days in my history?"
  - "What do my recent comments suggest about my well-being?"

- **Wiping & Deleting:**
  - "Delete my mood for today."
  - "Remove my mood log for yesterday."
  - "Delete the entry for March 18."
  - "Clear my mood history." (Use `wipe_data` for this)
  - "Wipe my data." (Use `wipe_data` for this)

- **Charting Trends:**
  - "Plot my mood for the last 7 days."
  - "Show me a chart of my mood this month."
  - "Visualize my scores for the past 14 days."
  - "Graph my mood progress."

### Rules
- **Privacy**: All data is stored locally on your device.
- **No Entry**: If no mood entry exists for a specific date requested, explicitly inform the user that no entry was found for that date.
- **Updates**: Logging a mood for a date that already has an entry will update that entry.
- **Dashboard**: The dashboard is only shown when you explicitly ask to see your history or the dashboard itself.
