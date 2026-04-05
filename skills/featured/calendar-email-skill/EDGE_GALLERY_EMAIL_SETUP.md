# 📧 Edge Gallery Email Configuration Guide

## 🚀 Enhanced Calendar-Email-Skill with Actual Email Sending

**Version 2.0.0** adds actual email sending capability to your Edge Gallery skill using external email APIs.

## 📋 What's New

### **Enhanced Features:**
1. ✅ **Actual email sending** via external APIs
2. ✅ **Multiple provider support** (SendGrid, Mailgun, AWS SES, generic)
3. ✅ **API key detection** - auto-detects service from key format
4. ✅ **Simulation mode** - still works without API keys
5. ✅ **Enhanced error handling** - detailed API error messages

### **File Updates:**
- `scripts/index_enhanced.js` - Main skill with email API integration
- `scripts/index_enhanced.html` - Enhanced test interface
- `SKILL_enhanced.md` - Updated documentation

## 🔧 How Edge Gallery Email Sending Works

### **Architecture:**
```
Edge Gallery → Skill (Browser) → Email API → Recipient
      ↓              ↓              ↓
   User        JSON + API Key    External
   Request                      Email Service
```

### **Key Points:**
1. **Browser-based** - Skill runs in Edge Gallery webview
2. **External APIs** - Uses fetch() to call email services
3. **Secure** - API keys passed via `secret` parameter (not in code)
4. **Flexible** - Supports multiple email providers

## 🎯 Configuration Steps

### **Step 1: Choose Email Service**

| Service | API Key Format | Free Tier | Best For |
|---------|---------------|-----------|----------|
| **SendGrid** | `SG.` or `sg.` prefix | 100 emails/day | Beginners, testing |
| **Mailgun** | `key-` prefix | 10,000 emails/month | Developers, small apps |
| **AWS SES** | `ses.` prefix | 62,000 emails/month | Production, high volume |
| **Generic** | Any format | Varies | Custom email services |

### **Step 2: Get API Key**

#### **SendGrid:**
1. Sign up at: https://sendgrid.com
2. Go to Settings → API Keys
3. Create "Restricted Access" key with "Mail Send" permission
4. Copy API key (starts with `SG.`)

#### **Mailgun:**
1. Sign up at: https://www.mailgun.com
2. Go to Sending → API Keys
3. Copy Private API key (starts with `key-`)
4. Verify domain or use sandbox domain

#### **AWS SES:**
1. AWS Console → SES Service
2. Create SMTP credentials
3. Verify email address or domain
4. Request production access if needed

### **Step 3: Use in Edge Gallery**

When calling the skill from Edge Gallery AI:

```json
{
  "action": "send_invite",
  "event_title": "Math Tutorial",
  "event_date": "2026-04-05",
  "to_email": "student@example.com",
  "actually_send": true
}
```

**Secret parameter:** `SG.your_sendgrid_api_key_here`

## 🧪 Testing Your Setup

### **Local Testing:**
1. Open `scripts/index_enhanced.html` in browser
2. Enter API key in test interface
3. Click "Test Send Invite (Actual)"
4. Check console for results

### **Edge Gallery Testing:**
1. Deploy enhanced skill to Edge Gallery
2. Use AI to call skill with `actually_send": true`
3. Provide API key in secret parameter
4. Check email delivery

## 📝 Usage Examples

### **Example 1: Schedule with Email (SendGrid)**
```json
// AI sends this to skill:
{
  "action": "send_invite",
  "event_title": "Team Meeting",
  "event_date": "2026-04-10",
  "to_email": "team@company.com",
  "event_time": "14:00",
  "event_duration": 1.5,
  "location": "Conference Room A",
  "actually_send": true
}

// Secret parameter: SG.ABC123DEF456GHI789JKL012
```

### **Example 2: Simulation Mode (No API Key)**
```json
{
  "action": "send_invite",
  "event_title": "Math Tutorial",
  "event_date": "2026-04-05",
  "to_email": "student@example.com"
  // No actually_send, no API key needed
}
```

### **Example 3: Complete Workflow**
```json
// 1. Schedule event
{
  "action": "create_event",
  "title": "Project Review",
  "date": "2026-04-15",
  "time": "10:00",
  "duration": 2.0
}

// 2. Send invitations
{
  "action": "send_invite",
  "event_title": "Project Review",
  "event_date": "2026-04-15",
  "to_email": "client@company.com",
  "actually_send": true
}
```

## 🔍 API Key Detection Logic

The skill automatically detects email service:

```javascript
if (apiKey.includes('sg.') || apiKey.startsWith('SG.')) {
    service = 'sendgrid';
} else if (apiKey.includes('key-')) {
    service = 'mailgun';
} else if (apiKey.includes('ses.')) {
    service = 'aws_ses';
} else {
    service = 'generic'; // Assume REST API
}
```

## 🐛 Troubleshooting

### **Common Issues:**

1. **API Key Rejected:**
   - Check key format and permissions
   - Verify service is active
   - Check billing/quotas

2. **Email Not Delivered:**
   - Check spam folder
   - Verify recipient email
   - Check sender domain reputation

3. **CORS Errors:**
   - Ensure API supports CORS
   - Check network connectivity
   - Use HTTPS endpoints

4. **Rate Limiting:**
   - Check provider limits
   - Implement retry logic
   - Upgrade plan if needed

### **Debug Commands:**
```javascript
// Test API key detection
console.log('Service detected:', detectService(apiKey));

// Test network connectivity
fetch('https://api.sendgrid.com/v3/user/profile', {
    headers: { 'Authorization': `Bearer ${apiKey}` }
});
```

## 🔒 Security Best Practices

1. **Never hardcode** API keys in skill files
2. **Use secret parameter** for API keys
3. **Rotate keys** regularly
4. **Monitor usage** and set alerts
5. **Use environment-specific** keys (dev/staging/prod)
6. **Implement rate limiting** on your side

## 📊 Provider Comparison

| Aspect | SendGrid | Mailgun | AWS SES |
|--------|----------|---------|---------|
| **Ease of Use** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Free Tier** | 100/day | 10K/month | 62K/month |
| **Delivery** | Excellent | Good | Excellent |
| **Pricing** | $$ | $$ | $ |
| **API Quality** | Excellent | Good | Good |
| **Best For** | Beginners | Developers | Production |

## 🚀 Deployment to Edge Gallery

### **Option A: Replace Existing Files**
```bash
# Backup original
cp scripts/index.js scripts/index.js.backup
cp SKILL.md SKILL.md.backup

# Replace with enhanced versions
cp scripts/index_enhanced.js scripts/index.js
cp SKILL_enhanced.md SKILL.md
```

### **Option B: Keep Both Versions**
- Submit enhanced version as separate skill
- Users can choose based on needs
- Maintain backward compatibility

### **Option C: Update PR #558**
1. Update files in your fork
2. Push changes to PR branch
3. Edge Gallery team will review

## ✅ Verification Checklist

- [ ] API key obtained and tested
- [ ] Skill detects correct service
- [ ] Emails deliver successfully
- [ ] Error handling works
- [ ] Simulation mode still functional
- [ ] Documentation updated
- [ ] Security measures in place

## 🎉 Ready for Production!

Your enhanced Calendar-Email-Skill now supports:

1. **Actual email sending** via external APIs
2. **Multiple provider support** for flexibility
3. **Secure API key handling** via secret parameter
4. **Backward compatibility** with simulation mode
5. **Professional email templates** with customization

**Deploy and start sending real calendar invitations from Edge Gallery!** 🚀📧