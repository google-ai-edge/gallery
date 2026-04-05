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
        
        if (!toEmail || !toEmail.includes('@')) {
          throw new Error('Invalid email address. Please provide a valid email (e.g., john@example.com)');
        }
        
        // Validate date format
        if (!/^\d{4}-\d{2}-\d{2}$/.test(eventDate)) {
          throw new Error('Invalid date format. Use YYYY-MM-DD');
        }
        
        result = {
          success: true,
          message: `Invitation for "${eventTitle}" on ${eventDate} sent to ${toEmail}`,
          email: {
            to: toEmail,
            subject: `Invitation: ${eventTitle} on ${eventDate}`,
            sent: true
          }
        };
        
        webviewData = {
          action: 'invite_sent',
          eventTitle: eventTitle,
          eventDate: eventDate,
          toEmail: toEmail
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
      result: result.message
    });
    
  } catch (error) {
    console.error('Calendar Email Skill Error:', error);
    return JSON.stringify({
      error: `Calendar Email Skill failed: ${error.message}`,
      suggestion: 'Please check your parameters and try again.'
    });
  }
};