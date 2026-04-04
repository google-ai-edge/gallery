const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const skillsRoot = path.join(__dirname, 'skills');
const skillDirs = fs.readdirSync(skillsRoot).filter(d => {
  return fs.existsSync(path.join(skillsRoot, d, 'testing', 'test-input.json'))
    && fs.existsSync(path.join(skillsRoot, d, 'scripts', 'index.html'));
});

let totalPass = 0, totalFail = 0;
const failures = [];

function checkAssertions(tc, value) {
  if (tc.expected !== undefined && value !== tc.expected) {
    return `expected "${tc.expected}", got "${String(value).substring(0, 100)}"`;
  }
  if (tc.expected_length !== undefined && String(value).length !== tc.expected_length) {
    return `expected length ${tc.expected_length}, got ${String(value).length}`;
  }
  if (tc.expected_pattern !== undefined && !new RegExp(tc.expected_pattern).test(value)) {
    return `pattern /${tc.expected_pattern}/ did not match "${String(value).substring(0, 100)}"`;
  }
  if (tc.expected_line_count !== undefined) {
    const lines = String(value).split('\n').length;
    if (lines !== tc.expected_line_count) return `expected ${tc.expected_line_count} lines, got ${lines}`;
  }
  if (tc.expected_contains !== undefined) {
    const str = typeof value === 'string' ? value : JSON.stringify(value);
    for (const sub of tc.expected_contains) {
      if (!str.includes(sub)) return `expected to contain "${sub}"`;
    }
  }
  if (tc.expected_starts_with !== undefined && !String(value).startsWith(tc.expected_starts_with)) {
    return `expected to start with "${tc.expected_starts_with}", got "${String(value).substring(0, 60)}"`;
  }
  if (tc.expected_not_empty && (!value || String(value).trim().length === 0)) {
    return 'expected non-empty result';
  }
  return null;
}

function extractValue(result) {
  if (result.image && result.image.base64) return result.image.base64;
  if (result.result !== undefined) return result.result;
  if (result.webview) return JSON.stringify(result.webview);
  return JSON.stringify(result);
}

async function runTestInBrowser(page, htmlPath, input) {
  await page.goto('file://' + htmlPath, { waitUntil: 'domcontentloaded' });
  const resultStr = await page.evaluate(async (inputStr) => {
    return await window['ai_edge_gallery_get_result'](inputStr);
  }, JSON.stringify(input));
  return JSON.parse(resultStr);
}

async function runSkill(browser, skillDir) {
  const testFile = path.join(skillsRoot, skillDir, 'testing', 'test-input.json');
  const htmlFile = path.join(skillsRoot, skillDir, 'scripts', 'index.html');
  const tests = JSON.parse(fs.readFileSync(testFile, 'utf8'));
  const page = await browser.newPage();

  for (const tc of tests) {
    const testName = `${skillDir}/${tc.name}`;

    // Handle multi-round tests (games with state)
    if (tc.rounds) {
      let roundFail = null;
      let carryOver = {};
      for (let ri = 0; ri < tc.rounds.length; ri++) {
        const round = tc.rounds[ri];
        const input = { ...carryOver, ...round.input };
        try {
          const result = await runTestInBrowser(page, htmlFile, input);
          if (result.error) {
            if (round.expected_contains && round.expected_contains.includes('error')) continue;
            roundFail = `round ${ri + 1}: error: ${result.error}`;
            break;
          }
          carryOver = {};
          if (result.game_state) carryOver.game_state = result.game_state;
          if (result.player_cards) carryOver.player_cards = result.player_cards;
          if (result.dealer_visible) carryOver.dealer_visible = result.dealer_visible;
          if (result.dealer_hidden) carryOver.dealer_hidden = result.dealer_hidden;
          const value = extractValue(result);
          if (round.expected_contains) {
            const str = typeof value === 'string' ? value : JSON.stringify(value);
            const fullStr = str + ' ' + JSON.stringify(result);
            for (const sub of round.expected_contains) {
              if (!fullStr.includes(sub)) {
                roundFail = `round ${ri + 1}: expected to contain "${sub}"`;
                break;
              }
            }
            if (roundFail) break;
          }
        } catch (e) {
          roundFail = `round ${ri + 1}: ${e.message}`;
          break;
        }
      }
      if (!roundFail) {
        console.log(`  PASS: ${testName} (${tc.rounds.length} rounds)`);
        totalPass++;
      } else {
        console.log(`  FAIL: ${testName} - ${roundFail}`);
        totalFail++;
        failures.push({ test: testName, reason: roundFail });
      }
      continue;
    }

    // Handle batch tests
    if (tc.input_batch) {
      let batchFail = null;
      for (const batchInput of tc.input_batch) {
        try {
          const result = await runTestInBrowser(page, htmlFile, batchInput);
          if (result.error) {
            batchFail = `shape=${batchInput.shape}: ${result.error}`;
            break;
          }
          const value = extractValue(result);
          if (tc.all_expected_starts_with && !String(value).startsWith(tc.all_expected_starts_with)) {
            batchFail = `shape=${batchInput.shape}: expected to start with "${tc.all_expected_starts_with}"`;
            break;
          }
        } catch (e) {
          batchFail = `shape=${batchInput.shape}: ${e.message}`;
          break;
        }
      }
      if (!batchFail) {
        console.log(`  PASS: ${testName}`);
        totalPass++;
      } else {
        console.log(`  FAIL: ${testName} - ${batchFail}`);
        totalFail++;
        failures.push({ test: testName, reason: batchFail });
      }
      continue;
    }

    try {
      const result = await runTestInBrowser(page, htmlFile, tc.input);
      if (result.error) {
        console.log(`  FAIL: ${testName} - error: ${result.error}`);
        totalFail++;
        failures.push({ test: testName, reason: result.error });
        continue;
      }
      const value = extractValue(result);
      const err = checkAssertions(tc, value);
      if (err) {
        console.log(`  FAIL: ${testName} - ${err}`);
        totalFail++;
        failures.push({ test: testName, reason: err });
      } else {
        console.log(`  PASS: ${testName}`);
        totalPass++;
      }
    } catch (e) {
      console.log(`  FAIL: ${testName} - ${e.message}`);
      totalFail++;
      failures.push({ test: testName, reason: e.message });
    }
  }

  await page.close();
}

(async () => {
  console.log('Launching Chrome...');
  const browser = await puppeteer.launch({
    executablePath: CHROME_PATH,
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox'],
  });

  console.log(`Running scenario tests for ${skillDirs.length} skills...\n`);

  for (const dir of skillDirs.sort()) {
    console.log(`[${dir}]`);
    await runSkill(browser, dir);
    console.log('');
  }

  await browser.close();

  console.log('='.repeat(60));
  console.log(`Results: ${totalPass} passed, ${totalFail} failed`);
  if (failures.length > 0) {
    console.log('\nFailures:');
    for (const f of failures) {
      console.log(`  - ${f.test}: ${f.reason}`);
    }
  }
  process.exit(totalFail > 0 ? 1 : 0);
})();
