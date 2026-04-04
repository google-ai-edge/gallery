const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const skillsRoot = path.join(__dirname, 'skills');
const skillDirs = fs.readdirSync(skillsRoot).filter(d => {
  const p = path.join(skillsRoot, d, 'testing', 'test-input.json');
  return fs.existsSync(p);
});

let totalPass = 0, totalFail = 0, totalSkip = 0;
const failures = [];

async function runSkill(skillDir) {
  const testFile = path.join(skillsRoot, skillDir, 'testing', 'test-input.json');
  const htmlFile = path.join(skillsRoot, skillDir, 'scripts', 'index.html');

  if (!fs.existsSync(htmlFile)) {
    console.log(`  SKIP: ${skillDir} (no index.html)`);
    totalSkip++;
    return;
  }

  const tests = JSON.parse(fs.readFileSync(testFile, 'utf8'));
  const htmlContent = fs.readFileSync(htmlFile, 'utf8');

  // Load JS file directly to inject after DOM creation (avoids HTML parsing issues)
  const jsFile = path.join(skillsRoot, skillDir, 'scripts', 'index.js');
  let jsContent = null;
  let inlinedHtml = htmlContent;
  if (fs.existsSync(jsFile)) {
    jsContent = fs.readFileSync(jsFile, 'utf8');
    // Remove the script src tag — we'll eval the JS manually
    inlinedHtml = htmlContent.replace(/<script\s+src=["']index\.js["']\s*><\/script>/, '');
  }

  function createDom() {
    const dom = new JSDOM(inlinedHtml, {
      runScripts: 'dangerously',
      url: 'https://localhost/', pretendToBeVisual: true,
    });
    if (jsContent) {
      dom.window.eval(jsContent);
    }
    return dom;
  }

  for (const tc of tests) {
    const testName = `${skillDir}/${tc.name}`;

    // Handle multi-round tests (games with state)
    if (tc.rounds) {
      let roundPassed = true;
      let roundReason = '';
      let carryOver = {};
      const dom = createDom();
      await new Promise(r => setTimeout(r, 50));
      const fn = dom.window['ai_edge_gallery_get_result'];
      if (!fn) {
        console.log(`  FAIL: ${testName} - no ai_edge_gallery_get_result function`);
        totalFail++;
        failures.push({ test: testName, reason: 'no function found' });
        dom.window.close();
        continue;
      }

      for (let ri = 0; ri < tc.rounds.length; ri++) {
        const round = tc.rounds[ri];
        const input = { ...carryOver, ...round.input };
        try {
          const resultStr = await fn(JSON.stringify(input));
          const result = JSON.parse(resultStr);

          if (result.error) {
            if (round.expected_contains && round.expected_contains.includes('error')) {
              // Error was expected
              continue;
            }
            roundPassed = false;
            roundReason = `round ${ri + 1}: error: ${result.error}`;
            break;
          }

          // Extract carry-over fields for next round
          carryOver = {};
          if (result.game_state) carryOver.game_state = result.game_state;
          if (result.player_cards) carryOver.player_cards = result.player_cards;
          if (result.dealer_visible) carryOver.dealer_visible = result.dealer_visible;
          if (result.dealer_hidden) carryOver.dealer_hidden = result.dealer_hidden;

          // Determine value for assertions
          let value;
          if (result.image && result.image.base64) value = result.image.base64;
          else if (result.result !== undefined) value = result.result;
          else if (result.webview) value = JSON.stringify(result.webview);
          else value = JSON.stringify(result);

          // Check round assertions
          if (round.expected_contains) {
            const str = typeof value === 'string' ? value : JSON.stringify(value);
            const fullStr = str + ' ' + JSON.stringify(result);
            for (const sub of round.expected_contains) {
              if (!fullStr.includes(sub)) {
                roundPassed = false;
                roundReason = `round ${ri + 1}: expected to contain "${sub}" in "${fullStr.substring(0, 200)}"`;
                break;
              }
            }
            if (!roundPassed) break;
          }
        } catch (e) {
          roundPassed = false;
          roundReason = `round ${ri + 1}: ${e.message}`;
          break;
        }
      }

      dom.window.close();
      if (roundPassed) {
        console.log(`  PASS: ${testName} (${tc.rounds.length} rounds)`);
        totalPass++;
      } else {
        console.log(`  FAIL: ${testName} - ${roundReason}`);
        totalFail++;
        failures.push({ test: testName, reason: roundReason });
      }
      continue;
    }

    // Handle batch tests (input_batch runs each input separately)
    if (tc.input_batch) {
      let allPassed = true;
      let batchReason = '';
      for (const batchInput of tc.input_batch) {
        try {
          const dom = createDom();
          await new Promise(r => setTimeout(r, 50));
          const fn = dom.window['ai_edge_gallery_get_result'];
          const resultStr = await fn(JSON.stringify(batchInput));
          const result = JSON.parse(resultStr);
          dom.window.close();
          if (result.error) {
            allPassed = false;
            batchReason = `input ${JSON.stringify(batchInput)} error: ${result.error}`;
            break;
          }
          let value = result.image && result.image.base64 ? result.image.base64
            : result.result !== undefined ? result.result
            : result.webview ? JSON.stringify(result.webview) : JSON.stringify(result);
          if (tc.all_expected_starts_with && !String(value).startsWith(tc.all_expected_starts_with)) {
            allPassed = false;
            batchReason = `input ${JSON.stringify(batchInput)}: expected to start with "${tc.all_expected_starts_with}", got "${String(value).substring(0, 60)}"`;
            break;
          }
        } catch (e) {
          allPassed = false;
          batchReason = `input ${JSON.stringify(batchInput)}: ${e.message}`;
          break;
        }
      }
      if (allPassed) {
        console.log(`  PASS: ${testName}`);
        totalPass++;
      } else {
        console.log(`  FAIL: ${testName} - ${batchReason}`);
        totalFail++;
        failures.push({ test: testName, reason: batchReason });
      }
      continue;
    }

    try {
      const dom = createDom();
      const win = dom.window;

      // Wait for script to register the function
      await new Promise(r => setTimeout(r, 50));

      const fn = win['ai_edge_gallery_get_result'];
      if (!fn) {
        console.log(`  FAIL: ${testName} - no ai_edge_gallery_get_result function`);
        totalFail++;
        failures.push({ test: testName, reason: 'no function found' });
        dom.window.close();
        continue;
      }

      const resultStr = await fn(JSON.stringify(tc.input));
      const result = JSON.parse(resultStr);
      dom.window.close();

      if (result.error) {
        console.log(`  FAIL: ${testName} - error: ${result.error}`);
        totalFail++;
        failures.push({ test: testName, reason: result.error });
        continue;
      }

      // Determine the value to check
      let value;
      if (result.image && result.image.base64) {
        value = result.image.base64;
      } else if (result.result !== undefined) {
        value = result.result;
      } else if (result.webview) {
        value = JSON.stringify(result.webview);
      } else {
        value = JSON.stringify(result);
      }

      let passed = true;
      let reason = '';

      if (tc.expected !== undefined) {
        if (value !== tc.expected) {
          passed = false;
          reason = `expected "${tc.expected}", got "${String(value).substring(0, 100)}"`;
        }
      }
      if (tc.expected_length !== undefined) {
        if (String(value).length !== tc.expected_length) {
          passed = false;
          reason = `expected length ${tc.expected_length}, got ${String(value).length}`;
        }
      }
      if (tc.expected_pattern !== undefined) {
        const re = new RegExp(tc.expected_pattern);
        if (!re.test(value)) {
          passed = false;
          reason = `pattern /${tc.expected_pattern}/ did not match "${String(value).substring(0, 100)}"`;
        }
      }
      if (tc.expected_line_count !== undefined) {
        const lines = String(value).split('\n').length;
        if (lines !== tc.expected_line_count) {
          passed = false;
          reason = `expected ${tc.expected_line_count} lines, got ${lines}`;
        }
      }
      if (tc.expected_contains !== undefined) {
        const str = typeof value === 'string' ? value : JSON.stringify(value);
        for (const sub of tc.expected_contains) {
          if (!str.includes(sub)) {
            passed = false;
            reason = `expected to contain "${sub}" in "${str.substring(0, 200)}"`;
            break;
          }
        }
      }
      if (tc.expected_starts_with !== undefined) {
        if (!String(value).startsWith(tc.expected_starts_with)) {
          passed = false;
          reason = `expected to start with "${tc.expected_starts_with}", got "${String(value).substring(0, 60)}"`;
        }
      }

      if (passed) {
        console.log(`  PASS: ${testName}`);
        totalPass++;
      } else {
        console.log(`  FAIL: ${testName} - ${reason}`);
        totalFail++;
        failures.push({ test: testName, reason });
      }
    } catch (e) {
      console.log(`  FAIL: ${testName} - ${e.message}`);
      totalFail++;
      failures.push({ test: testName, reason: e.message });
    }
  }
}

(async () => {
  console.log(`Running tests for ${skillDirs.length} skills...\n`);

  for (const dir of skillDirs.sort()) {
    console.log(`[${dir}]`);
    await runSkill(dir);
    console.log('');
  }

  console.log('='.repeat(60));
  console.log(`Results: ${totalPass} passed, ${totalFail} failed, ${totalSkip} skipped`);
  if (failures.length > 0) {
    console.log('\nFailures:');
    for (const f of failures) {
      console.log(`  - ${f.test}: ${f.reason}`);
    }
  }
  process.exit(totalFail > 0 ? 1 : 0);
})();
