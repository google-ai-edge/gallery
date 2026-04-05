/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the governing permissions and
 * limitations under the License.
 */

window['ai_edge_gallery_get_result'] = async (dataStr, secret) => {
  try {
    // Parse the JSON data from AI agent
    const jsonData = JSON.parse(dataStr || '{}');
    const action = jsonData.action || 'create_event';
    
    // Validate required fields based on action
    let result = {};
    let webviewData = {};
    
    switch (action) {
      case 'create_event':
        // Validate create_event parameters
        const title = jsonData.title || 'Meeting';
        const date = jsonData.date || new Date().toISOString().split('T')[0];
        const time = jsonData.time || '14:00';
        const duration = jsonData.duration || 1.0;
        
        // Validate date format (YYYY-MM-DD)
        if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
          throw new Error('Invalid date format. Use YYYY-MM-DD (e.g., 2024-12-25)');
        }
        
        // Validate time format (HH:MM)
        if (!/^\d{2}:\d{2}$/.test(time)) {
          throw new Error('Invalid time format. Use HH:MM 24-hour format (e.g., 14:00)');
        }
        
        // Validate duration
        if (isNaN(duration) || duration <= 0) {
          throw new Error('Invalid duration. Must be a positive number (e.g., 1.5 for 1.5 hours)');
        }
        
        // Create event data for webview
        result = {
          success: true,
          message: `Event "${title}" scheduled for ${date} at ${time} for ${duration} hours`,
          event: {
            title: title,
            date: date,
            time: time,
            duration: duration,
            eventId: `event_${Date.now()}`
          }
        };
        
        // Prepare webview data
        webviewData = {
          action: 'event_created',
          title: title,
          date: date,
          time: time,
          duration: duration
        };
        break;
        
      case 'send_invite':
        // Validate send_invite parameters
        const eventTitle = jsonData.event_title || 'Meeting';
        const eventDate = jsonData.event_date || new Date().toISOString().split('T')[0];
        const toEmail = jsonData.to_email || '';
        const eventTime = jsonData.event_time || '';
        const eventDuration = jsonData.event_duration || '';
        const location = jsonData.location || 'Virtual Meeting';
        const actuallySend = jsonData.actually_send || false;
        const emailService = jsonData.email_service || 'auto'; // auto, gmail, sendgrid, mailgun
        
        if (!toEmail || !toEmail.includes('@')) {
          throw new Error('Invalid email address. Please provide a valid email (e.g., john@example.com)');
        }
        
        // Validate date format
        if (!/^\d{4}-\d{2}-\d{2}$/.test(eventDate)) {
          throw new Error('Invalid date format. Use YYYY-MM-DD');
        }
        
        // Generate email content
        const timeInfo = eventTime ? `⏰ Time: ${eventTime}` : '⏰ Time: To be confirmed';
        const durationInfo = eventDuration ? `⏱️ Duration: ${eventDuration} hours` : '';
        
        const emailBody = `Subject: Calendar Invitation: ${eventTitle}

Dear Colleague,

You are invited to: ${eventTitle}

📅 Date: ${eventDate}
${timeInfo}
${durationInfo}
📍 Location: ${location}

Please save the date and add this to your calendar.

Best regards,
Calendar Assistant
`;
        
        // Prepare result
        const emailDetails = {
          to: toEmail,
          subject: `Calendar Invitation: ${eventTitle}`,
          body: emailBody.trim(),
          eventTitle: eventTitle,
          eventDate: eventDate,
          status: actuallySend ? 'sending' : 'ready_to_send',
          service: emailService
        };
        
        result = {
          success: true,
          message: `Invitation for "${eventTitle}" on ${eventDate} prepared for ${toEmail}`,
          email: emailDetails,
          notes: [
            'Email is ready to send',
            actuallySend ? `Attempting to send via ${emailService}...` : 'Set actually_send=true to send via email API'
          ]
        };
        
        // Actually send email if requested
        if (actuallySend && secret) {
          try {
            const sendResult = await sendEmailViaService(
              toEmail,
              emailDetails.subject,
              emailDetails.body,
              secret,
              emailService
            );
            
            if (sendResult.success) {
              emailDetails.status = 'sent';
              result.message = `📧 Invitation for "${eventTitle}" SENT to ${toEmail} via ${sendResult.service}`;
              result.sendResult = sendResult;
            } else {
              emailDetails.status = 'failed';
              result.success = false;
              result.message = `📧 Failed to send invitation to ${toEmail}`;
              result.error = sendResult.error;
              result.suggestion = sendResult.suggestion || 'Check email API configuration';
            }
          } catch (apiError) {
            emailDetails.status = 'failed';
            result.success = false;
            result.message = `📧 Email sending error: ${apiError.message}`;
            result.error = 'Email API call failed';
            result.suggestion = 'Check API key and network connection';
          }
        } else if (actuallySend && !secret) {
          result.notes.push('⚠️ Email API key not provided. Set secret parameter with email API key.');
        }
        
        webviewData = {
          action: 'invite_sent',
          eventTitle: eventTitle,
          eventDate: eventDate,
          toEmail: toEmail,
          actuallySent: emailDetails.status === 'sent',
          service: emailDetails.service
        };
        break;
        
      case 'check_availability':
        // Validate check_availability parameters
        const checkDate = jsonData.date || new Date().toISOString().split('T')[0];
        
        if (!/^\d{4}-\d{2}-\d{2}$/.test(checkDate)) {
          throw new Error('Invalid date format. Use YYYY-MM-DD');
        }
        
        // Generate sample availability slots
        const slots = [
          '09:00 - 10:00',
          '11:00 - 12:00',
          '14:00 - 15:00',
          '16:00 - 17:00'
        ];
        
        result = {
          success: true,
          message: `Available time slots on ${checkDate}:`,
          availability: {
            date: checkDate,
            slots: slots,
            count: slots.length
          }
        };
        
        webviewData = {
          action: 'availability_checked',
          date: checkDate,
          slots: slots
        };
        break;
        
      default:
        throw new Error(`Unknown action: ${action}. Supported actions: create_event, send_invite, check_availability`);
    }
    
    // Compress data for URL
    const compressedData = btoa(unescape(encodeURIComponent(JSON.stringify(webviewData))));
    
    // Build webview URL
    const baseUrl = 'webview.html';
    const fullUrl = `${baseUrl}?action=${encodeURIComponent(action)}&data=${encodeURIComponent(compressedData)}&v=${Date.now()}`;
    
    // Return result with webview
    return JSON.stringify({
      webview: { url: fullUrl },
      result: result.message,
      details: result
    });
    
  } catch (error) {
    console.error('Calendar Email Skill Error:', error);
    return JSON.stringify({
      error: `Calendar Email Skill failed: ${error.message}`,
      suggestion: 'Please check your parameters and try again.'
    });
  }
};

/**
 * Send email via various email services
 */
async function sendEmailViaService(toEmail, subject, body, apiKey, service = 'auto') {
  // Auto-detect service if not specified
  if (service === 'auto') {
    service = detectEmailService(apiKey);
  }
  
  console.log(`Sending email via ${service} to ${toEmail}`);
  
  try {
    let response;
    
    switch (service.toLowerCase()) {
      case 'gmail':
        // Gmail API via proxy (since direct Gmail API requires OAuth)
        response = await sendViaGmailAPI(toEmail, subject, body, apiKey);
        break;
        
      case 'sendgrid':
        // SendGrid API
        response = await fetch('https://api.sendgrid.com/v3/mail/send', {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${apiKey}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            personalizations: [{ to: [{ email: toEmail }] }],
            from: { email: 'calendar@edgegallery.app', name: 'Calendar Assistant' },
            subject: subject,
            content: [{ type: 'text/plain', value: body }]
          })
        });
        break;
        
      case 'mailgun':
        // Mailgun API
        const domain = 'edgegallery.app';
        const mailgunUrl = `https://api.mailgun.net/v3/${domain}/messages`;
        
        const formData = new FormData();
        formData.append('from', 'Calendar Assistant <calendar@edgegallery.app>');
        formData.append('to', toEmail);
        formData.append('subject', subject);
        formData.append('text', body);
        
        response = await fetch(mailgunUrl, {
          method: 'POST',
          headers: {
            'Authorization': `Basic ${btoa(`api:${apiKey}`)}`
          },
          body: formData
        });
        break;
        
      case 'smtp':
        // SMTP relay via proxy
        response = await sendViaSMTPProxy(toEmail, subject, body, apiKey);
        break;
        
      default:
        // Generic REST API
        response = await fetch('https://api.emailservice.com/send', {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${apiKey}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            to: toEmail,
            from: 'calendar@edgegallery.app',
            subject: subject,
            text: body
          })
        });
    }
    
    if (response && !response.ok) {
      const errorText = await response.text();
      throw new Error(`Email API error (${service}): ${response.status} - ${errorText}`);
    }
    
    return {
      success: true,
      message: `Email sent via ${service}`,
      service: service
    };
    
  } catch (error) {
    console.error('Email API error:', error);
    return {
      success: false,
      error: error.message,
      suggestion: `Check ${service} API key and configuration`,
      service: service
    };
  }
}

/**
 * Detect email service from API key format
 */
function detectEmailService(apiKey) {
  if (!apiKey) return 'simulation';
  
  const key = apiKey.toLowerCase();
  
  if (key.includes('ya29.') || key.includes('googleapis.com')) {
    return 'gmail';
  } else if (key.includes('sg.') || key.startsWith('sg.')) {
    return 'sendgrid';
  } else if (key.includes('key-')) {
    return 'mailgun';
  } else if (key.includes('smtp://') || key.includes(':@')) {
    return 'smtp';
  } else if (key.includes('ses.')) {
    return 'aws_ses';
  } else {
    return 'generic';
  }
}

/**
 * Send email via Gmail API (requires OAuth token)
 * Note: Gmail API requires OAuth 2.0, which is complex for browser
 * This is a simplified version that would work with a proxy
 */
async function sendViaGmailAPI(toEmail, subject, body, accessToken) {
  // For Gmail API, we need to use the Gmail API endpoint
  // This requires proper OAuth 2.0 setup which is complex for browser
  // In a real implementation, you'd need:
  // 1. OAuth 2.0 client ID
  // 2. User authorization
  // 3. Access token with gmail.send scope
  
  // Simplified version that shows the concept
  const emailContent = [
    'Content-Type: text/plain; charset="UTF-8"\n',
    'MIME-Version: 1.0\n',
    'Content-Transfer-Encoding: 7bit\n',
    `to: ${toEmail}\n`,
    `subject: ${subject}\n\n`,
    body
  ].join('');
  
  const encodedEmail = btoa(emailContent).replace(/\+/g, '-').replace(/\//g, '_');
  
  const response = await fetch('https://gmail.googleapis.com/gmail/v1/users/me/messages/send', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      raw: encodedEmail
    })
  });
  
  return response;
}

/**
 * Send email via SMTP proxy (simpler alternative to Gmail API)
 */
async function sendViaSMTPProxy(toEmail, subject, body, smtpConfig) {
  // SMTP config format: "smtp://username:password@smtp.gmail.com:587"
  // or JSON: {"host":"smtp.gmail.com","port":587,"user":"email","pass":"password"}
  
  let config;
  try {
    if (smtpConfig.startsWith('{')) {
      config = JSON.parse(smtpConfig);
    } else if (smtpConfig.includes('smtp://')) {
      // Parse SMTP URL
      const url = new URL(smtpConfig);
      config = {
        host: url.hostname,
        port: url.port || 587,
        user: url.username,
        pass: url.password
      };
    } else {
      throw new Error('Invalid SMTP configuration format');
    }
    
    // Call SMTP proxy service
    const response = await fetch('https://smtp-proxy.edgegallery.app/send', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        to: toEmail,
        subject: subject,
        text: body,
        smtp: config
      })
    });
    
    return response;
  } catch (error) {
    throw new Error(`SMTP configuration error: ${error.message}`);
  }
}

/**
 * Check email configuration
 */
async function checkEmailConfiguration(apiKey, service = 'auto') {
  if (!apiKey) {
    return {
      success: false,
      message: 'No email API key provided',
      suggestion: 'Provide email API key in secret parameter'
    };
  }
  
  const detectedService = detectEmailService(apiKey);
  const finalService = service === 'auto' ? detectedService : service;
  
  let serviceInfo = {
    name: finalService,
    detected: detectedService,
    keyFormat: 'Valid'
  };
  
  // Add service-specific info
  switch (finalService) {
    case 'gmail':
      serviceInfo.requirements = 'OAuth 2.0 token with gmail.send scope';
      serviceInfo.note = 'Requires Google Cloud project setup';
      break;
    case 'sendgrid':
      serviceInfo.requirements = 'SendGrid API key with mail.send permission';
      serviceInfo.note = 'Free tier: 100 emails/day';
      break;
    case 'mailgun':
      serviceInfo.requirements = 'Mailgun API key with verified domain';
      serviceInfo.note = 'Free tier: 10,000 emails/month';
      break;
    case 'smtp':
      serviceInfo.requirements = 'SMTP server credentials';
      serviceInfo.note = 'Works with Gmail, Outlook, custom SMTP';
      break;
    default:
      serviceInfo.note = 'Generic REST API - check provider documentation';
  }
  
  return {
    success: true,
    message: `Email service: ${finalService} (detected: ${detectedService})`,
    service: serviceInfo,
    note: 'Set actually_send=true to send real emails'
  };
}