---
name: schedule-notification
description: Schedule a notification for a specific date or repeating daily.
---

# Schedule Notification

## Instructions

To schedule a notification, you must follow these exact steps:
1. First, if the notification doesn't need to repeat daily, call the `run_intent` tool with `intent` as `get_current_date_and_time` and `parameters` as `{}` to get the user's local date and time. Then explicitly calculate the scheduling date and time in your response. Write out:
- Today's exact date.
- The target day or relative time requested by the user (e.g., "tomorrow", "this Friday").
- The exact number of days you need to add to today's date.
- The final calculated dates, ensuring you correctly roll over to the next month or year if the added days exceed the days in the current month.
2. Call the `run_intent` tool with the following exact parameters:
- intent: schedule_notification
- parameters: A JSON string with the following fields:
   - title: the title of the notification. String.
   - message: the message content of the notification. String.
   - hour: the hour of the day (0-23) for the notification. Integer.
   - minute: the minute of the hour (0-59) for the notification. Integer.
   - deeplink: (optional) the deeplink URI to open when the notification is tapped. String.
   - year: (optional) the year for the notification. Integer.
   - month: (optional) the month (1-12) for the notification. Integer.
   - day: (optional) the day of the month (1-31) for the notification. Integer.
   - repeat_daily: (optional) true if the notification should repeat daily at this time. Boolean.
