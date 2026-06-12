const fs = require('fs');
const path = require('path');

async function run() {
  try {
    const handlerPath = path.resolve(__dirname, 'index.node.js');
    const handler = require(handlerPath);
    const request = {
      prompt: 'Validate node harness: produce 23 candidates for a self-improvement request',
      meta: { source: 'run_local' }
    };

    const result = typeof handler.handle === 'function'
      ? await handler.handle(request)
      : (typeof handler === 'function' ? await handler(request) : handler);

    const outFile = path.resolve(__dirname, '..', '.last_run.json');
    fs.writeFileSync(outFile, JSON.stringify(result, null, 2));
    console.log('WROTE', outFile);
    if (result && Array.isArray(result.candidates)) {
      console.log('candidates:', result.candidates.length);
    }
  } catch (err) {
    console.error('RUN_ERROR', err && err.stack ? err.stack : err);
    process.exitCode = 2;
  }
}

run();
