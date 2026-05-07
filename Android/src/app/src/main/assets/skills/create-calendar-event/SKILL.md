---
name: create-calendar-event
description: Create a calendar event.
---

# Create calendar event

## Instructions
To schedule an event, you must follow these exact steps:
1. First, call the `run_intent` tool with `intent` as `get_current_date_and_time` and `parameters` as `{}` to get the user's local date, time, and the current day of the week.
2. Before creating the event, you must explicitly calculate the exact date in your response. Write out:
    - Today's exact date and day of the week.
    - The target day or relative time requested by the user (e.g., "tomorrow", "this Friday").
    - The exact number of days you need to add to today's date.
    - The final calculated dates, ensuring you correctly roll over to the next month or year if the added days exceed the days in the current month.
3. Once you have calculated the correct dates, call the `run_intent` tool with the following exact parameters:
    - `intent`: create_calendar_event
    - `parameters`: A JSON string with the following fields:
        - `title`: the title of the event. String.
        - `description`: the description of the event. String.
        - `begin_time`: the start time of the event in YYYY-MM-DDTHH:MM:SS format. String.
        - `end_time`: the end time of the event in YYYY-MM-DDTHH:MM:SS format. String.
