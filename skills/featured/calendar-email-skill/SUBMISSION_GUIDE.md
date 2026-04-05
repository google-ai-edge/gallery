# Edge Gallery Submission Guide

## How to Submit Your Calendar Email Skill to Edge Gallery

### Step 1: Fork the Edge Gallery Repository
```bash
# Go to the Edge Gallery GitHub repository
# https://github.com/google-ai-edge/gallery

# Click "Fork" in the top-right corner
# Clone your fork locally
git clone https://github.com/YOUR_USERNAME/gallery.git
cd gallery
```

### Step 2: Add Your Skill to the Repository
```bash
# Navigate to the skills directory
cd skills/featured/

# Create a new directory for your skill
mkdir calendar-email-skill

# Copy all files from your EDGE-GALLERY-VERSION
cp -r /home/ggai/calendar-email-skill-dual/EDGE-GALLERY-VERSION/* calendar-email-skill/
```

### Step 3: Verify the Skill Structure
Your skill directory should look like this:
```
calendar-email-skill/
├── SKILL.md                    # Main skill metadata (YAML + instructions)
├── README.md                   # Documentation
├── GMAIL_API_SETUP.md         # Gmail API setup guide
├── scripts/
│   ├── index.html            # Loads JavaScript
│   ├── index.js              # Original implementation
│   ├── index_enhanced.js     # Enhanced version
│   ├── index_with_gmail.js   # Gmail API version (NEW!)
│   └── test_gmail_api.js     # Test script
└── assets/
    ├── webview.html          # Preview card
    └── ui.html               # Interactive UI
```

### Step 4: Update SKILL.md for Edge Gallery
Make sure your `SKILL.md` has the correct YAML frontmatter:

```yaml
---
name: calendar-email-skill
description: Schedule calendar events and send email invitations with Gmail API support.
metadata:
  require-secret: false
  require-secret-description: For actual email sending, provide email API key in secret parameter
  homepage: https://github.com/YOUR_USERNAME/calendar-email-skill
---
```

### Step 5: Create a Pull Request
```bash
# Add your changes
git add skills/featured/calendar-email-skill/

# Commit with a descriptive message
git commit -m "feat: Add Calendar Email Skill with Gmail API support"

# Push to your fork
git push origin main

# Go to GitHub and create a Pull Request
# From your fork to google-ai-edge/gallery main branch
```

### Step 6: PR Description Template
Use this template for your PR description:

```markdown
## Calendar Email Skill with Gmail API Support

### Overview
A skill for scheduling calendar events and sending email invitations with actual email sending via Gmail API and 5+ other email services.

### Features
- ✅ Create calendar events with date/time validation
- ✅ Send email invitations (simulation or actual sending)
- ✅ **Gmail API integration** with OAuth 2.0 support
- ✅ Support for SendGrid, Mailgun, AWS SES, SMTP, and generic APIs
- ✅ Smart email service detection from API key format
- ✅ Secure token handling via secret parameter
- ✅ Interactive webview preview cards
- ✅ Comprehensive error handling

### Email Services Supported
1. **Gmail API** (OAuth 2.0, most secure)
2. **SendGrid** (simple API)
3. **Mailgun** (transactional emails)
4. **AWS SES** (cost-effective)
5. **SMTP Relay** (traditional)
6. **Generic REST API** (flexible)

### Usage Examples
```json
// Create event
{
  "action": "create_event",
  "title": "Team Meeting",
  "date": "2024-12-25",
  "time": "14:00",
  "duration": 1.5
}

// Send invitation with Gmail API
{
  "action": "send_invite",
  "event_title": "Team Meeting",
  "event_date": "2024-12-25",
  "to_email": "john@example.com",
  "actually_send": true,
  "email_service": "gmail"
}
// Secret: ya29.your_gmail_access_token
```

### Security
- Tokens passed via `secret` parameter (not in JSON)
- No client-side token storage
- Gmail API uses send-only scope (`gmail.send`)
- Email content generated securely

### Testing
- Comprehensive test suite included
- Simulation mode for safe testing
- All edge cases handled
- Email service detection verified

### Files Included
- `SKILL.md` - Skill metadata and instructions
- `scripts/index_with_gmail.js` - Main implementation
- `GMAIL_API_SETUP.md` - Complete setup guide
- `test_gmail_api.js` - Test script
- `assets/webview.html` - Preview UI
- `assets/ui.html` - Interactive interface

### Documentation
- Complete setup instructions
- API key configuration guide
- Troubleshooting guide
- Security considerations

### Screenshots
*(Add screenshots of the webview UI if available)*

### Checklist
- [x] Skill follows Edge Gallery format
- [x] All required files included
- [x] YAML frontmatter correct
- [x] JavaScript properly formatted
- [x] No external dependencies
- [x] Security considerations documented
- [x] Testing included
```

### Step 7: Respond to Review Comments
The Edge Gallery team may request changes:
- Code formatting improvements
- Additional documentation
- Security enhancements
- Performance optimizations

### Step 8: After Approval
Once your PR is merged:
1. Your skill will be available in Edge Gallery
2. AI assistants can use it when users ask for calendar/email help
3. You can share it with others via the skill name: `calendar-email-skill`

### Step 9: Promote Your Skill
After approval:
1. Share on social media
2. Add to your portfolio
3. Write a blog post about it
4. Share in AI/developer communities

## Important Notes

### 1. **API Keys**
- Never include real API keys in the repository
- Use `require-secret-description` to explain how to get keys
- Document where users should get their own keys

### 2. **Security**
- All user data should be handled securely
- No personal information should be stored
- Follow Edge Gallery security guidelines

### 3. **Performance**
- Keep JavaScript files small
- Minimize external dependencies
- Optimize for fast loading

### 4. **Compatibility**
- Test in multiple browsers
- Ensure mobile responsiveness
- Follow web standards

### 5. **Updates**
- Monitor your skill's usage
- Fix bugs promptly
- Add new features based on feedback

## Troubleshooting

### Common Issues:
1. **Skill not loading**: Check YAML frontmatter format
2. **JavaScript errors**: Test in browser console
3. **API key issues**: Verify secret parameter format
4. **Webview not showing**: Check URL encoding

### Getting Help:
- Check Edge Gallery documentation
- Join Edge Gallery Discord/community
- Review other skills for examples
- Ask for help in PR comments

## Success Tips

1. **Start Simple**: Focus on core functionality first
2. **Test Thoroughly**: Test all edge cases
3. **Document Well**: Clear documentation helps users
4. **Follow Guidelines**: Adhere to Edge Gallery standards
5. **Be Responsive**: Quickly address review comments

## Congratulations! 🎉

Once submitted and approved, your Calendar Email Skill will be available to thousands of AI assistants and users through Edge Gallery!

**Good luck with your submission!** 🚀