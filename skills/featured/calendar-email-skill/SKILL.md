---
name: calendar-email-skill
description: Schedule calendar events and send email invitations. Create events with specific dates/times, check availability, and send invites via email.
metadata:
  require-secret: false
  homepage: https://github.com/gaoofeii/Calendar-Email-Skill/tree/main/EDGE-GALLERY-VERSION
---

# Calendar Email Skill

Schedule calendar events and send email invitations.

## Examples

* "Schedule team meeting on Friday at 2 PM"
* "Send invitation for the meeting to john@example.com"
* "Check my availability on December 25"
* "Create calendar event for project review next Monday at 10 AM"
* "Invite the team to the quarterly planning session"

## Instructions

Call the `run_js` tool with the following parameters:

### For creating events:
```json
{
  "action": "create_event",
  "title": "Event Title",
  "date": "YYYY-MM-DD",
  "time": "HH:MM",
  "duration": 1.5
}
```

### For sending invitations:
```json
{
  "action": "send_invite", 
  "event_title": "Event Title",
  "event_date": "YYYY-MM-DD",
  "to_email": "recipient@example.com"
}
```

### For checking availability:
```json
{
  "action": "check_availability",
  "date": "YYYY-MM-DD"
}
```

## Date/Time Formats

- **Date**: YYYY-MM-DD (e.g., 2024-12-25)
- **Time**: HH:MM 24-hour format (e.g., 14:00 for 2 PM)
- **Duration**: Hours as decimal (e.g., 1.5 for 1 hour 30 minutes)

## Important Notes

1. Always confirm event details with the user before creating
2. For email invitations, ensure the recipient email is valid
3. Check for scheduling conflicts when creating events
4. Provide clear confirmation messages after each action

## Error Handling

If the skill encounters an error, it will return an error message with suggestions for correction. Common issues include:
- Invalid date format (use YYYY-MM-DD)
- Invalid time format (use HH:MM 24-hour)
- Missing required parameters
- Email format issues