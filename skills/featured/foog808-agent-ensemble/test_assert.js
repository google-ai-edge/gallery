const fs = require('fs');
const data = JSON.parse(fs.readFileSync('.last_run.json','utf8'));
const expectedKeys = ['final_result','best_candidate','candidates','goose_notes','agency_agents_notes','gallery_notes','action_items','confidence','learning_summary','discussion_log','medal_counts'];
for (const key of expectedKeys) {
  if (!(key in data)) {
    console.error('Missing key', key);
    process.exit(2);
  }
}
if (!Array.isArray(data.candidates) || data.candidates.length !== 23) {
  console.error('Candidates count invalid', data.candidates ? data.candidates.length : typeof data.candidates);
  process.exit(3);
}
for (const candidate of data.candidates) {
  const candidateKeys = ['id','focus','summary','details','score_components','score','rank','medal','recommendation','self_review'];
  for (const key of candidateKeys) {
    if (!(key in candidate)) {
      console.error('Candidate missing key', key, 'candidate', candidate.id);
      process.exit(4);
    }
  }
}
console.log('TEST_OK');
