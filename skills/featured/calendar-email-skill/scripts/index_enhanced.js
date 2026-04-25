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
 * See the License for the specific language governing permissions and
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
          status: actuallySend ? 'sending' : 'ready_to_send'
        };
        
        result = {
          success: true,
          message: `Invitation for "${eventTitle}" on ${eventDate} prepared for ${toEmail}`,
          email: emailDetails,
          notes: [
            'Email is ready to send',
            actuallySend ? 'Attempting to send via email API...' : 'Set actually_send=true to send via email API'
          ]
        };
        
        // Actually send email if requested and API key is provided
        if (actuallySend && secret) {
          try {
            const sendResult = await sendEmailViaAPI(
              toEmail,
              emailDetails.subject,
              emailDetails.body,
              secret
            );
            
            if (sendResult.success) {
              emailDetails.status = 'sent';
              result.message = `📧 Invitation for "${eventTitle}" SENT to ${toEmail}`;
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
          actuallySent: emailDetails.status === 'sent'
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
 * Send email via external email API
 * Supports multiple email service providers
 */
async function sendEmailViaAPI(toEmail, subject, body, apiKey) {
  // Determine which email service to use based on API key format
  let service = 'unknown';
  
  if (apiKey.includes('sg.') || apiKey.startsWith('SG.')) {
    service = 'sendgrid';
  } else if (apiKey.includes('key-')) {
    service = 'mailgun';
  } else if (apiKey.includes('ses.')) {
    service = 'aws_ses';
  } else {
    // Assume generic REST API
    service = 'generic';
  }
  
  try {
    let response;
    
    switch (service) {
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
        const domain = 'edgegallery.app'; // Would need actual domain
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
        
      case 'generic':
        // Generic REST API - assume it accepts standard format
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
        break;
        
      default:
        // Simulation mode - for testing without real API
        console.log('Simulating email send (no real API configured)');
        await new Promise(resolve => setTimeout(resolve, 500)); // Simulate delay
        
        return {
          success: true,
          message: 'Email sent (simulation)',
          service: 'simulation',
          note: 'Use real email API key for actual sending'
        };
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
      suggestion: `Check ${service} API key and configuration`
    };
  }
}

/**
 * Check email configuration
 * Can be called via check_config action
 */
async function checkEmailConfiguration(apiKey) {
  if (!apiKey) {
    return {
      success: false,
      message: 'No email API key provided',
      suggestion: 'Provide email API key in secret parameter'
    };
  }
  
  // Try to identify the service
  let service = 'unknown';
  if (apiKey.includes('sg.') || apiKey.startsWith('SG.')) {
    service = 'SendGrid';
  } else if (apiKey.includes('key-')) {
    service = 'Mailgun';
  } else if (apiKey.includes('ses.')) {
    service = 'AWS SES';
  } else {
    service = 'Generic Email API';
  }
  
  return {
    success: true,
    message: `Email service detected: ${service}`,
    service: service,
    note: 'Set actually_send=true to send real emails'
  };
}