# Calendar-Email-Skill (Edge Gallery Version)

HTML/JavaScript skill for Edge Gallery to schedule calendar events and send email invitations.

## 🚀 Quick Start

1. **Upload to Edge Gallery:**
   - Upload the entire `EDGE-GALLERY-VERSION/` folder to Edge Gallery
   - The skill will be available to AI agents

2. **AI Agent Usage:**
   ```
   User: "Schedule team meeting on Friday at 2 PM"
   AI → Calls skill with: {action: "create_event", title: "Team Meeting", date: "2024-12-27", time: "14:00", duration: 1.0}
   ```

## 📋 Skill Structure

```
EDGE-GALLERY-VERSION/
├── SKILL.md                    # Skill metadata + AI instructions
├── scripts/
│   ├── index.html             # Loads JavaScript
│   └── index.js               # Main skill logic
└── assets/
    ├── webview.html           # Preview card
    └── ui.html                # Interactive UI
```

## 🔧 Available Actions

### 1. Create Event
**AI Parameters:**
```json
{
  "action": "create_event",
  "title": "Team Meeting",
  "date": "2024-12-25",
  "time": "14:00",
  "duration": 2.0
}
```

### 2. Send Invitation
**AI Parameters:**
```json
{
  "action": "send_invite",
  "event_title": "Team Meeting",
  "event_date": "2024-12-25",
  "to_email": "john@example.com"
}
```

### 3. Check Availability
**AI Parameters:**
```json
{
  "action": "check_availability",
  "date": "2024-12-25"
}
```

## 🎨 User Experience Flow

### Step 1: AI Processes Request
```
User: "Schedule project review next Monday at 10 AM"
AI → Extracts: {action: "create_event", title: "Project Review", date: "2024-01-08", time: "10:00", duration: 1.0}
```

### Step 2: Skill Execution
- AI calls `index.js` with parameters
- Skill validates and processes request
- Returns webview URL with compressed data

### Step 3: Preview Card
```
┌─────────────────┐
│   📅 PREVIEW    │
│ Event Scheduled │
│                 │
│ Project Review  │
│ 2024-01-08 10:00│
│                 │
│ [VIEW CALENDAR] │
└─────────────────┘
```

### Step 4: Interactive UI
User taps → Opens full interface with:
- Event details
- Action buttons
- Success messages

## 🔌 Integration Points

### Calendar APIs (Future Enhancement):
```javascript
// Google Calendar API integration
async function createGoogleCalendarEvent(eventData) {
  const response = await fetch('https://www.googleapis.com/calendar/v3/calendars/primary/events', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${secret}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(eventData)
  });
  return await response.json();
}
```

### Email APIs (Future Enhancement):
```javascript
// Email service integration
async function sendEmailViaAPI(emailData) {
  const response = await fetch('https://api.emailservice.com/send', {
    method: 'POST',
    headers: {
      'API-Key': secret,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(emailData)
  });
  return await response.json();
}
```

## 🧪 Local Testing

Open `scripts/index.html` in a browser to test:

1. **Test Create Event:** Click "Test Create Event"
2. **Test Send Invite:** Click "Test Send Invite"  
3. **Test Check Availability:** Click "Test Check Availability"

## 📁 File Details

### `SKILL.md`
- YAML frontmatter with skill metadata
- AI instructions and examples
- Parameter formats and validation rules

### `scripts/index.js`
- Main skill logic with `ai_edge_gallery_get_result` function
- Parameter validation and error handling
- Data compression and URL building

### `assets/webview.html`
- Preview card interface
- Data decoding from URL parameters
- Redirect to full UI

### `assets/ui.html`
- Interactive calendar/email interface
- Dynamic content based on action
- User interaction handlers

## ⚙️ Configuration

### Environment Variables (Future):
```bash
# Calendar API
export GOOGLE_CALENDAR_API_KEY="your_key"

# Email Service
export EMAIL_SERVICE_API_KEY="your_key"
```

### Customization:
1. **Modify `index.js`** for different calendar/email APIs
2. **Update `ui.html`** for custom UI design
3. **Add new actions** in the switch statement
4. **Customize validation** rules

## 🐛 Troubleshooting

### Common Issues:

1. **JSON Parse Errors:**
   ```
   Error: Unterminated string in JSON
   Fix: Ensure AI sends valid JSON with proper escaping
   ```

2. **Date Format Errors:**
   ```
   Error: Invalid date format
   Fix: Use YYYY-MM-DD format (2024-12-25)
   ```

3. **Time Format Errors:**
   ```
   Error: Invalid time format
   Fix: Use HH:MM 24-hour format (14:00)
   ```

### Debug Mode:
```javascript
// Add to index.js for debugging
console.log('Received data:', dataStr);
console.log('Parsed data:', jsonData);
```

## 🔗 Related Resources

- [Edge Gallery Documentation](https://github.com/google-ai-edge/gallery)
- [Google Calendar API](https://developers.google.com/calendar)
- [Email API Examples](https://developers.google.com/gmail/api)
- [JavaScript Fetch API](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API)

## 📄 License

MIT License - See LICENSE file for details.

---

**Ready for Edge Gallery deployment!** 🎉