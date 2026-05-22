const fs = require('fs');
const path = require('path');
const handler = require('./scripts/index.node.js').handle;
const request = `Improve the FooG808 Agent Ensemble skill itself: propose concrete code changes to make orchestration realistic, add logging of runs, add a node harness, add automated tests, and improve documentation.`;
const result = handler(request);
const outPath = path.resolve('.last_run.json');
fs.writeFileSync(outPath, JSON.stringify(result, null, 2));

// Append to persistent run log for later inspection
const logPath = path.resolve('scripts', 'run_log.json');
try {
	const raw = fs.readFileSync(logPath, 'utf8');
	const arr = JSON.parse(raw || '[]');
	arr.push({ timestamp: new Date().toISOString(), request, resultSummary: { final_result: result.final_result, best_id: result.best_candidate?result.best_candidate.id:null } });
	fs.writeFileSync(logPath, JSON.stringify(arr, null, 2));
} catch (err) {
	// best-effort: create log if missing or malformed
	fs.writeFileSync(logPath, JSON.stringify([{ timestamp: new Date().toISOString(), request, resultSummary: { final_result: result.final_result, best_id: result.best_candidate?result.best_candidate.id:null } }], null, 2));
}

console.log(JSON.stringify({ ok: true, keys: Object.keys(result), candidates: result.candidates ? result.candidates.length : 0 }));
