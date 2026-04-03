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
    const query = (input.query || '').trim();
    const limit = Math.max(1, Math.min(10, parseInt(input.limit, 10) || 5));

    if (!query) {
      return JSON.stringify({ error: 'query is required. Provide a title, author name, or topic.' });
    }

    const url = `https://openlibrary.org/search.json?q=${encodeURIComponent(query)}&limit=${limit}&fields=title,author_name,first_publish_year,subject,edition_count,cover_i,key`;
    const response = await fetch(url);

    if (!response.ok) {
      return JSON.stringify({ error: `Open Library API error: HTTP ${response.status}. Please try again.` });
    }

    const data = await response.json();
    const docs = data.docs || [];

    if (docs.length === 0) {
      return JSON.stringify({
        error: `No books found for "${query}". Try a different search term, author name, or genre.`,
        query,
      });
    }

    const books = docs.map(doc => ({
      title: doc.title || 'Unknown title',
      authors: doc.author_name ? doc.author_name.slice(0, 3) : ['Unknown author'],
      first_published: doc.first_publish_year || null,
      editions: doc.edition_count || null,
      subjects: doc.subject ? doc.subject.slice(0, 5) : [],
      open_library_key: doc.key || null,
    }));

    // Build a readable summary for the LLM
    const lines = [`Found ${data.numFound} results for "${query}". Showing top ${books.length}:\n`];
    books.forEach((b, i) => {
      const authors = b.authors.join(', ');
      const year = b.first_published ? ` (${b.first_published})` : '';
      const editions = b.editions ? ` · ${b.editions} editions` : '';
      lines.push(`${i + 1}. **${b.title}** by ${authors}${year}${editions}`);
      if (b.subjects.length > 0) {
        lines.push(`   Topics: ${b.subjects.slice(0, 3).join(', ')}`);
      }
    });

    return JSON.stringify({
      result: lines.join('\n'),
      query,
      total_found: data.numFound,
      books,
    });
  } catch (e) {
    if (e.name === 'TypeError' && e.message.includes('fetch')) {
      return JSON.stringify({ error: 'Could not reach Open Library. Please check your internet connection.' });
    }
    return JSON.stringify({ error: `Book search failed: ${e.message}` });
  }
};
