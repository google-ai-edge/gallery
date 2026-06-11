/**
 * Test script for Gmail API integration in Calendar Email Skill
 * 
 * This script tests the Gmail API functionality without actually sending emails
 */

// Test data for Gmail API integration
const testCases = [
  {
    name: "Test 1: Create Event",
    data: {
      action: "create_event",
      title: "Team Meeting",
      date: "2024-12-25",
      time: "14:00",
      duration: 1.5
    },
    secret: null
  },
  {
    name: "Test 2: Send Invitation (Simulation)",
    data: {
      action: "send_invite",
      event_title: "Project Review",
      event_date: "2024-12-25",
      to_email: "test@example.com",
      event_time: "15:30",
      event_duration: 2.0,
      location: "Conference Room A",
      actually_send: false
    },
    secret: null
  },
  {
    name: "Test 3: Send Invitation with Gmail API (Simulated)",
    data: {
      action: "send_invite",
      event_title: "Gmail API Test",
      event_date: "2024-12-25",
      to_email: "test@gmail.com",
      event_time: "10:00",
      event_duration: 1.0,
      location: "Virtual Meeting",
      actually_send: true,
      email_service: "gmail"
    },
    secret: "ya29.test_gmail_token_simulation"
  },
  {
    name: "Test 4: Check Availability",
    data: {
      action: "check_availability",
      date: "2024-12-25"
    },
    secret: null
  }
];

// Mock the window.ai_edge_gallery_get_result function for testing
async function testGmailIntegration() {
  console.log("=== Testing Gmail API Integration ===\n");
  
  for (const testCase of testCases) {
    console.log(`\n${testCase.name}`);
    console.log("=".repeat(testCase.name.length));
    
    try {
      // Convert data to JSON string
      const dataStr = JSON.stringify(testCase.data);
      
      // Call the function (simulated)
      const result = await simulateEdgeGalleryCall(dataStr, testCase.secret);
      
      console.log("Input Data:", JSON.stringify(testCase.data, null, 2));
      console.log("Result:", JSON.stringify(result, null, 2));
      
      if (result.error) {
        console.log("❌ Error:", result.error);
        if (result.suggestion) {
          console.log("💡 Suggestion:", result.suggestion);
        }
      } else {
        console.log("✅ Success");
        if (result.details && result.details.email) {
          console.log("📧 Email Status:", result.details.email.status);
          console.log("📧 Service:", result.details.email.service);
        }
      }
      
    } catch (error) {
      console.log("❌ Test Failed:", error.message);
    }
    
    console.log("\n");
  }
  
  console.log("=== Testing Complete ===");
}

// Simulate the Edge Gallery function call
async function simulateEdgeGalleryCall(dataStr, secret) {
  // This simulates what the Edge Gallery would do
  const jsonData = JSON.parse(dataStr || '{}');
  const action = jsonData.action || 'create_event';
  
  // Simulate different responses based on action
  switch (action) {
    case 'create_event':
      return {
        success: true,
        message: `Event "${jsonData.title}" scheduled for ${jsonData.date} at ${jsonData.time} for ${jsonData.duration} hours`,
        details: {
          event: {
            title: jsonData.title,
            date: jsonData.date,
            time: jsonData.time,
            duration: jsonData.duration,
            eventId: `event_${Date.now()}`
          }
        },
        webview: {
          url: `webview.html?action=create_event&data=test&v=${Date.now()}`
        }
      };
      
    case 'send_invite':
      const emailDetails = {
        to: jsonData.to_email,
        subject: `Calendar Invitation: ${jsonData.event_title}`,
        body: `Invitation for ${jsonData.event_title} on ${jsonData.event_date}`,
        eventTitle: jsonData.event_title,
        eventDate: jsonData.event_date,
        service: jsonData.email_service || 'auto'
      };
      
      // Check if actually_send is true and secret is provided
      if (jsonData.actually_send && secret) {
        // Simulate email sending
        const service = detectEmailService(secret);
        emailDetails.status = 'sent';
        emailDetails.service = service;
        
        return {
          success: true,
          message: `📧 Invitation for "${jsonData.event_title}" SENT to ${jsonData.to_email} via ${service}`,
          details: {
            email: emailDetails,
            sendResult: {
              success: true,
              message: `Email sent via ${service}`,
              service: service
            }
          },
          webview: {
            url: `webview.html?action=send_invite&data=test&v=${Date.now()}`
          }
        };
      } else {
        emailDetails.status = 'ready_to_send';
        
        return {
          success: true,
          message: `Invitation for "${jsonData.event_title}" on ${jsonData.event_date} prepared for ${jsonData.to_email}`,
          details: {
            email: emailDetails,
            notes: [
              'Email is ready to send',
              jsonData.actually_send ? 'Set actually_send=true and provide API key to send' : 'Set actually_send=true to send via email API'
            ]
          },
          webview: {
            url: `webview.html?action=send_invite&data=test&v=${Date.now()}`
          }
        };
      }
      
    case 'check_availability':
      return {
        success: true,
        message: `Available time slots on ${jsonData.date}:`,
        details: {
          availability: {
            date: jsonData.date,
            slots: ['09:00 - 10:00', '11:00 - 12:00', '14:00 - 15:00', '16:00 - 17:00'],
            count: 4
          }
        },
        webview: {
          url: `webview.html?action=check_availability&data=test&v=${Date.now()}`
        }
      };
      
    default:
      return {
        error: `Unknown action: ${action}`,
        suggestion: 'Supported actions: create_event, send_invite, check_availability'
      };
  }
}

// Detect email service from API key (from index_with_gmail.js)
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

// Run the tests
testGmailIntegration().catch(console.error);

// Also test the email service detection
console.log("\n=== Email Service Detection Tests ===");
const testKeys = [
  { key: 'ya29.a0AfH6SMD...', expected: 'gmail' },
  { key: 'SG.test_sendgrid_key', expected: 'sendgrid' },
  { key: 'key-testmailgunkey', expected: 'mailgun' },
  { key: 'smtp://user:pass@smtp.gmail.com:587', expected: 'smtp' },
  { key: 'ses.test_aws_key', expected: 'aws_ses' },
  { key: 'some_generic_key', expected: 'generic' },
  { key: null, expected: 'simulation' }
];

for (const test of testKeys) {
  const detected = detectEmailService(test.key);
  const passed = detected === test.expected;
  console.log(`${passed ? '✅' : '❌'} Key: ${test.key || 'null'} -> Detected: ${detected}, Expected: ${test.expected}`);
}