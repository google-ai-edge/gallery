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

window['ai_edge_gallery_get_result'] = async (dataStr) => {
  try {
    const input = JSON.parse(dataStr);
    const word = (input.word || '').trim().toLowerCase();

    if (!word) {
      return JSON.stringify({ error: 'No word provided. Please specify a word to look up.' });
    }

    const url = `https://api.dictionaryapi.dev/api/v2/entries/en/${encodeURIComponent(word)}`;
    const response = await fetch(url);

    if (response.status === 404) {
      return JSON.stringify({
        error: `"${word}" was not found in the dictionary. Check the spelling or try a different form of the word.`,
        word,
      });
    }

    if (!response.ok) {
      return JSON.stringify({ error: `Dictionary API error: HTTP ${response.status}. Please try again.` });
    }

    const data = await response.json();
    if (!Array.isArray(data) || data.length === 0) {
      return JSON.stringify({ error: `No results found for "${word}".` });
    }

    const entry = data[0];

    // Phonetics
    const phonetic = entry.phonetic
      || entry.phonetics?.find(p => p.text)?.text
      || null;

    // Meanings: collect up to 3 parts of speech, 2 definitions each
    const meanings = (entry.meanings || []).slice(0, 3).map(m => ({
      part_of_speech: m.partOfSpeech,
      definitions: (m.definitions || []).slice(0, 2).map(d => ({
        definition: d.definition,
        example: d.example || null,
      })),
      synonyms: (m.synonyms || []).slice(0, 6),
      antonyms: (m.antonyms || []).slice(0, 4),
    }));

    // Build a summary string for the LLM
    const lines = [`**${entry.word}**${phonetic ? ` /${phonetic}/` : ''}`];
    meanings.forEach(m => {
      lines.push(`\n*${m.part_of_speech}*`);
      m.definitions.forEach((d, i) => {
        lines.push(`${i + 1}. ${d.definition}`);
        if (d.example) lines.push(`   *"${d.example}"*`);
      });
      if (m.synonyms.length) lines.push(`Synonyms: ${m.synonyms.join(', ')}`);
      if (m.antonyms.length) lines.push(`Antonyms: ${m.antonyms.join(', ')}`);
    });

    return JSON.stringify({
      result: lines.join('\n'),
      word: entry.word,
      phonetic: phonetic,
      origin: entry.origin || null,
      meanings,
    });
  } catch (e) {
    if (e.name === 'TypeError' && e.message.includes('fetch')) {
      return JSON.stringify({ error: 'Could not reach the dictionary service. Please check your internet connection.' });
    }
    return JSON.stringify({ error: `Dictionary lookup failed: ${e.message}` });
  }
};
