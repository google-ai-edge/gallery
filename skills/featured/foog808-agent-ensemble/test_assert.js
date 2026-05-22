const fs = require('fs');
const path = require('path');
const data = JSON.parse(fs.readFileSync('.last_run.json','utf8'));
const keys = ['final_result','best_candidate','candidates','goose_notes','agency_agents_notes','gallery_notes','action_items','confidence','discussion_log'];
for(const k of keys){ if(!(k in data)) { console.error('Missing key',k); process.exit(2); }}
if(!Array.isArray(data.candidates) || data.candidates.length!==23){ console.error('Candidates count invalid', data.candidates?data.candidates.length:typeof data.candidates); process.exit(3); }
console.log('TEST_OK');
