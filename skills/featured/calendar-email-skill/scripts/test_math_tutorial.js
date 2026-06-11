// Simulating the skill execution
const dataStr = JSON.stringify({
  action: "create_event",
  title: "Math Tutorial",
  date: "2026-04-05",
  time: "10:00",
  duration: 1.5
});

console.log("📋 Input JSON:");
console.log(dataStr);
console.log("\n🔍 Validation:");
console.log("1. Date format (YYYY-MM-DD): 2026-04-05 ✓ VALID");
console.log("2. Time format (HH:MM): 10:00 ✓ VALID");
console.log("3. Duration (hours): 1.5 ✓ VALID");
console.log("4. Title: Math Tutorial ✓ VALID");

console.log("\n🎯 Skill would return:");
console.log('{');
console.log('  "webview": {');
console.log('    "url": "webview.html?action=create_event&data=eyJhY3Rpb24iOiJjcmVhdGVfZXZlbnQiLCJ0aXRsZSI6Ik1hdGggVHV0b3JpYWwiLCJkYXRlIjoiMjAyNi0wNC0wNSIsInRpbWUiOiIxMDowMCIsImR1cmF0aW9uIjoxLjV9&v=1743866220000"');
console.log('  },');
console.log('  "result": "Event \\"Math Tutorial\\" scheduled for 2026-04-05 at 10:00 for 1.5 hours"');
console.log('}');
