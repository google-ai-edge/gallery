---
name: read-calendar-events
description: Read OS calendar events for a specific date.
---

# Read calendar events

## Instructions

To read calendar events for a specific date, you must follow these exact steps:
1. First, call the `run_intent` tool with `intent` as `get_current_date_and_time` and `parameters` as `{}` to get the user's local date, time, and the current day of the week.
2. Before reading the events, explicitly calculate the exact target date requested by the user in your response. Figure out:
- Today's exact date and day of the week.
- The target day or relative time requested by the user (e.g., "tomorrow", "this Friday", "May 15").
- The final calculated target date in YYYY-MM-DD format.
3. Once you have calculated the correct date, call the `run_intent` tool with the following exact parameters:
- `intent`: read_calendar_events
- `parameters`: A JSON string with the following field:
   - `date`: the target date to read events for, in YYYY-MM-DD format. String.
4. Interpret the returned JSON list of calendar events and provide a clear, friendly answer to the user detailing their schedule.
