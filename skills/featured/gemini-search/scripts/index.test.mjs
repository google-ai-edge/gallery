import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const htmlPath = path.join(__dirname, 'index.html');

async function loadSkill(fetchImpl) {
  const html = await fs.readFile(htmlPath, 'utf8');
  const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/i);
  assert.ok(scriptMatch, 'expected inline <script> in index.html');

  const context = {
    window: {},
    fetch: fetchImpl,
    console: { error: () => {} },
    JSON,
    Set,
    encodeURIComponent,
  };
  context.globalThis = context;

  vm.runInNewContext(scriptMatch[1], context, { filename: htmlPath });
  assert.equal(typeof context.window.ai_edge_gallery_get_result, 'function');
  return context.window.ai_edge_gallery_get_result;
}

async function invoke({ data, secret = 'test-secret', fetchImpl }) {
  const runSkill = await loadSkill(fetchImpl);
  const raw = await runSkill(data, secret);
  return JSON.parse(raw);
}

test('returns clear error when query is missing', async () => {
  const result = await invoke({
    data: JSON.stringify({}),
    fetchImpl: async () => {
      throw new Error('fetch should not be called');
    },
  });

  assert.deepEqual(result, { error: 'No query provided.' });
});

test('returns clear error when secret is missing', async () => {
  const result = await invoke({
    data: JSON.stringify({ query: 'latest Gemini news' }),
    secret: '',
    fetchImpl: async () => {
      throw new Error('fetch should not be called');
    },
  });

  assert.deepEqual(result, {
    error: 'No Gemini API key provided. Please enter your API key.',
  });
});

test('calls gemini-3.5-flash with Google Search grounding and preserves the literal query', async () => {
  const query = 'Can you search whether Italy will go to next World Cup or not?';
  const calls = [];

  const result = await invoke({
    data: JSON.stringify({ query }),
    secret: 'secret value',
    fetchImpl: async (url, options) => {
      calls.push({ url, options });
      return {
        ok: true,
        async json() {
          return {
            candidates: [
              {
                content: { parts: [{ text: 'Italy has not yet qualified.' }] },
                groundingMetadata: { groundingChunks: [] },
              },
            ],
          };
        },
      };
    },
  });

  assert.equal(result.result, 'Italy has not yet qualified.');
  assert.equal(calls.length, 1);
  assert.match(
    calls[0].url,
    /^https:\/\/generativelanguage\.googleapis\.com\/v1beta\/models\/gemini-3\.5-flash:generateContent\?key=secret%20value$/
  );

  const body = JSON.parse(calls[0].options.body);
  assert.deepEqual(body.tools, [{ googleSearch: {} }]);
  assert.equal(body.contents[0].role, 'user');
  assert.equal(body.contents[0].parts[0].text.includes(query), true);
  assert.equal(body.contents[0].parts[0].text.includes('Exact user question:'), true);
});

test('dedupes sources by URL and builds presentable output', async () => {
  const result = await invoke({
    data: JSON.stringify({ query: 'latest launch update' }),
    fetchImpl: async () => ({
      ok: true,
      async json() {
        return {
          candidates: [
            {
              content: { parts: [{ text: 'Launch remains on schedule.\n' }] },
              groundingMetadata: {
                groundingChunks: [
                  { web: { title: 'Source A', uri: 'https://example.com/a' } },
                  { web: { title: 'Source A duplicate', uri: 'https://example.com/a' } },
                  { web: { title: '', uri: 'https://example.com/b' } },
                  { ignored: true },
                ],
              },
            },
          ],
        };
      },
    }),
  });

  assert.deepEqual(result.sources, [
    { title: 'Source A', url: 'https://example.com/a' },
    { title: 'https://example.com/b', url: 'https://example.com/b' },
  ]);
  assert.equal(
    result.presentable,
    'Launch remains on schedule.\n\nSources:\n- Source A: https://example.com/a\n- https://example.com/b: https://example.com/b'
  );
});

test('returns API JSON error message on non-OK HTTP response', async () => {
  const result = await invoke({
    data: JSON.stringify({ query: 'latest rate decision' }),
    fetchImpl: async () => ({
      ok: false,
      status: 403,
      async text() {
        return JSON.stringify({ error: { message: 'API key invalid' } });
      },
    }),
  });

  assert.deepEqual(result, { error: 'API key invalid' });
});

test('falls back to HTTP status message when error body is not JSON', async () => {
  const result = await invoke({
    data: JSON.stringify({ query: 'latest rate decision' }),
    fetchImpl: async () => ({
      ok: false,
      status: 502,
      async text() {
        return 'bad gateway';
      },
    }),
  });

  assert.deepEqual(result, { error: 'HTTP error 502' });
});

test('returns a clear error when Gemini responds without candidates', async () => {
  const result = await invoke({
    data: JSON.stringify({ query: 'latest rate decision' }),
    fetchImpl: async () => ({
      ok: true,
      async json() {
        return { candidates: [] };
      },
    }),
  });

  assert.deepEqual(result, { error: 'No response from Gemini API.' });
});
