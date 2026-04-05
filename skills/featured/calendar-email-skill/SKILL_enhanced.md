---
name: calendar-email-skill
description: Schedule calendar events and send email invitations. Create events with specific dates/times, check availability, and send invites via email. Supports actual email sending via external APIs.
metadata:
  require-secret: false
  require-secret-description: For actual email sending, provide email API key (SendGrid, Mailgun, etc.) in secret parameter
  homepage: https://github.com/gaoofeii/Calendar-Email-Skill/tree/main/EDGE-GALLERY-VERSION
---

# Calendar Email Skill

Schedule calendar events and send email invitations with optional actual email sending via external APIs.

## Examples

* "Schedule team meeting on Friday at 2 PM"
* "Send invitation for the meeting to john@example.com"
* "Send invitation for math tutorial to student@example.com actually_send=true"
* "Check my availability on December 25"
* "Create calendar event for project review next Monday at 10 AM"
* "Invite the team to the quarterly planning session actually_send=true"

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

### For sending invitations (simulation):
```json
{
  "action": "send_invite", 
  "event_title": "Event Title",
  "event_date": "YYYY-MM-DD",
  "to_email": "recipient@example.com"
}
```

### For actually sending emails (requires API key):
```json
{
  "action": "send_invite", 
  "event_title": "Event Title",
  "event_date": "YYYY-MM-DD",
  "to_email": "recipient@example.com",
  "event_time": "HH:MM",
  "event_duration": 1.5,
  "location": "Meeting Room",
  "actually_send": true
}
```

### For checking availability:
```json
{
  "action": "check_availability",
  "date": "YYYY-MM-DD"
}
```

## Email Sending Configuration

### Supported Email Services:
1. **Gmail API** - OAuth 2.0 tokens starting with `ya29.` (most secure, requires Google Cloud setup)
2. **SendGrid** - API keys starting with `SG.` or containing `sg.`
3. **Mailgun** - API keys containing `key-`
4. **AWS SES** - API keys containing `ses.`
5. **SMTP Relay** - SMTP configuration URLs or JSON
6. **Generic REST API** - Any other API key

### How to Enable Actual Email Sending:

1. **Get an email API key** from your preferred provider
2. **Pass the API key** in the `secret` parameter when calling the skill
3. **Set `actually_send": true`** in the JSON data

### Example with Gmail API:
```json
{
  "action": "send_invite",
  "event_title": "Team Meeting",
  "event_date": "2024-12-25",
  "to_email": "john@example.com",
  "actually_send": true,
  "email_service": "gmail"
}
```
**Secret parameter:** `ya29.your_gmail_access_token_here`

### Example with SendGrid:
```json
{
  "action": "send_invite",
  "event_title": "Team Meeting",
  "event_date": "2024-12-25",
  "to_email": "john@example.com",
  "actually_send": true
}
```
**Secret parameter:** `SG.your_sendgrid_api_key_here`

### Example with Mailgun:
```json
{
  "action": "send_invite",
  "event_title": "Project Review",
  "event_date": "2024-12-25",
  "to_email": "team@company.com",
  "actually_send": true
}
```
**Secret parameter:** `key-yourmailgunapikey`

## Date/Time Formats

- **Date**: YYYY-MM-DD (e.g., 2024-12-25)
- **Time**: HH:MM 24-hour format (e.g., 14:00 for 2 PM)
- **Duration**: Hours as decimal (e.g., 1.5 for 1 hour 30 minutes)

## Email Templates

Emails include:
- Professional invitation format
- Event details (title, date, time, duration, location)
- Clear call-to-action
- "Calendar Assistant" as sender name

## Important Notes

1. **By default**, email sending is simulated (no actual emails sent)
2. **For actual sending**, set `actually_send": true` and provide API key
3. **Email API keys** should be kept secure and not shared
4. **Test with simulation first** before enabling actual sending
5. **Check provider limits** for email sending quotas

## Error Handling

If the skill encounters an error, it will return an error message with suggestions for correction. Common issues include:
- Invalid date format (use YYYY-MM-DD)
- Invalid time format (use HH:MM 24-hour)
- Missing required parameters
- Email format issues
- API key authentication failures
- Network connectivity issues

## Security Notes

- Email API keys are passed via the `secret` parameter (not in JSON data)
- The skill does not store API keys
- Email content is generated on-the-fly
- No personal data is stored or logged
- Use HTTPS for all API communications

## Testing Recommendations

1. **Start with simulation**: Test without `actually_send": true`
2. **Verify parameters**: Ensure all required fields are correct
3. **Test API key**: Use a test endpoint if available
4. **Check quotas**: Ensure you have sufficient email sending capacity
5. **Monitor delivery**: Check spam folders and delivery reports

## Provider Links

- [Gmail API Documentation](https://developers.google.com/gmail/api)
- [Gmail API Setup Guide](GMAIL_API_SETUP.md) (included in package)
- [SendGrid API Documentation](https://docs.sendgrid.com/for-developers/sending-email/api-getting-started)
- [Mailgun API Documentation](https://documentation.mailgun.com/en/latest/api_reference.html)
- [AWS SES Documentation](https://docs.aws.amazon.com/ses/latest/DeveloperGuide/send-email-api.html)

## Support

For issues with email sending:
1. Check API key format and permissions
2. Verify network connectivity
3. Review provider documentation
4. Test with simulation mode first
5. Contact email service provider for API issues