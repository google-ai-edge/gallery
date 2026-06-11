# Gmail API Integration for Calendar Email Skill

This document explains how to integrate Gmail API with the Calendar Email Skill for actual email sending.

## Overview

The Calendar Email Skill now supports Gmail API for sending actual email invitations. This provides a more secure and reliable email sending option compared to other email services.

## Prerequisites

1. **Google Cloud Project** with Gmail API enabled
2. **OAuth 2.0 Client ID** with proper scopes
3. **Access Token** with `gmail.send` scope

## Setup Steps

### Step 1: Create Google Cloud Project

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing project
3. Enable the **Gmail API**:
   - Navigate to "APIs & Services" > "Library"
   - Search for "Gmail API"
   - Click "Enable"

### Step 2: Configure OAuth Consent Screen

1. Go to "APIs & Services" > "OAuth consent screen"
2. Choose "External" user type
3. Fill in required information:
   - App name: "Calendar Email Skill"
   - User support email: Your email
   - Developer contact information: Your email
4. Add scopes:
   - `https://www.googleapis.com/auth/gmail.send`
5. Add test users (your email address)
6. Save and continue

### Step 3: Create OAuth 2.0 Credentials

1. Go to "APIs & Services" > "Credentials"
2. Click "Create Credentials" > "OAuth 2.0 Client ID"
3. Choose "Web application"
4. Add authorized redirect URIs:
   - `http://localhost:8080` (for testing)
   - `https://your-domain.com/oauth2callback` (for production)
5. Save and note your Client ID and Client Secret

### Step 4: Get Access Token

#### Option A: Using OAuth Playground (Easiest for Testing)

1. Go to [Google OAuth 2.0 Playground](https://developers.google.com/oauthplayground)
2. Click the gear icon (settings) and check:
   - Use your own OAuth credentials
   - Enter your Client ID and Client Secret
3. Select Gmail API v1 > `https://www.googleapis.com/auth/gmail.send`
4. Click "Authorize APIs"
5. Click "Exchange authorization code for tokens"
6. Copy the **Access Token** (valid for 1 hour) or **Refresh Token**

#### Option B: Programmatic OAuth Flow

For production use, implement the OAuth 2.0 flow in your application:

```javascript
// Example OAuth flow
const oauth2Client = new google.auth.OAuth2(
  CLIENT_ID,
  CLIENT_SECRET,
  REDIRECT_URI
);

// Generate authorization URL
const authUrl = oauth2Client.generateAuthUrl({
  access_type: 'offline',
  scope: ['https://www.googleapis.com/auth/gmail.send']
});

// After user authorization, exchange code for tokens
const { tokens } = await oauth2Client.getToken(code);
const accessToken = tokens.access_token;
const refreshToken = tokens.refresh_token;
```

## Using Gmail API with Calendar Email Skill

### Method 1: Direct Access Token

Pass the Gmail access token as the `secret` parameter:

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

### Method 2: Refresh Token (Recommended for Production)

For production use, you'll need to implement token refresh logic. The skill can work with a backend service that handles token refresh.

## Code Implementation

The Gmail API integration is implemented in `scripts/index_with_gmail.js`. Key functions:

### 1. `sendViaGmailAPI()`
Sends email using Gmail API with proper MIME encoding.

### 2. `detectEmailService()`
Automatically detects Gmail API tokens (starts with `ya29.`).

### 3. Email Encoding
Properly encodes email content for Gmail API raw messages.

## Example Usage

### Creating an Event
```json
{
  "action": "create_event",
  "title": "Project Review",
  "date": "2024-12-25",
  "time": "14:00",
  "duration": 1.5
}
```

### Sending Invitation with Gmail API
```json
{
  "action": "send_invite",
  "event_title": "Project Review",
  "event_date": "2024-12-25",
  "to_email": "team@company.com",
  "event_time": "14:00",
  "event_duration": 1.5,
  "location": "Conference Room A",
  "actually_send": true,
  "email_service": "gmail"
}
```

**Secret:** `ya29.a0AfH6SMD...` (Gmail access token)

## Security Considerations

### 1. Token Security
- Access tokens are short-lived (1 hour)
- Refresh tokens should be stored securely
- Never expose tokens in client-side code

### 2. Scope Limitation
- Use only `gmail.send` scope (not full Gmail access)
- This allows sending emails only

### 3. Rate Limiting
- Gmail API has rate limits
- Monitor usage to avoid hitting limits

### 4. Email Content
- Ensure email content complies with Gmail policies
- Avoid spammy content
- Include unsubscribe option if sending to mailing lists

## Testing

### Test Without Sending
```json
{
  "action": "send_invite",
  "event_title": "Test Meeting",
  "event_date": "2024-12-25",
  "to_email": "test@example.com",
  "actually_send": false
}
```

### Test With Gmail API
```json
{
  "action": "send_invite",
  "event_title": "Test Meeting",
  "event_date": "2024-12-25",
  "to_email": "your-email@gmail.com",
  "actually_send": true,
  "email_service": "gmail"
}
```

## Troubleshooting

### Common Issues

1. **Invalid Credentials**
   - Check if token is expired
   - Verify token has correct scope
   - Ensure OAuth consent screen is configured

2. **Permission Denied**
   - Check if test user is added
   - Verify domain verification if needed
   - Check if app is in testing mode

3. **Rate Limit Exceeded**
   - Wait before sending more emails
   - Check Gmail API quotas
   - Implement exponential backoff

4. **Email Not Delivered**
   - Check spam folder
   - Verify recipient email address
   - Check Gmail sending limits

### Error Messages

- `401 Unauthorized`: Invalid or expired token
- `403 Forbidden`: Insufficient permissions
- `429 Too Many Requests`: Rate limit exceeded
- `500 Internal Server Error`: Gmail API issue

## Production Deployment

For production use, consider:

1. **Backend Proxy**: Handle OAuth flow on server-side
2. **Token Refresh**: Automatically refresh expired tokens
3. **Queue System**: Queue emails for reliable delivery
4. **Monitoring**: Track email delivery and failures
5. **Logging**: Log all email sending attempts

## Alternative Email Services

If Gmail API is too complex, consider:

1. **SendGrid**: Simple API, good deliverability
2. **Mailgun**: Good for transactional emails
3. **AWS SES**: Cost-effective for high volume
4. **SMTP Relay**: Simple but less secure

## Support

For Gmail API issues:
1. Check [Gmail API Documentation](https://developers.google.com/gmail/api)
2. Review [OAuth 2.0 Guide](https://developers.google.com/identity/protocols/oauth2)
3. Check [Google Cloud Console](https://console.cloud.google.com/) for quotas and errors
4. Use [Google OAuth Playground](https://developers.google.com/oauthplayground) for testing

## Updates to PR #558

This Gmail API integration has been added to PR #558 with:
1. Updated JavaScript implementation with Gmail API support
2. Enhanced email service detection
3. Proper error handling for Gmail API
4. Security considerations for token management
5. Comprehensive documentation