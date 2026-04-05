---
name: book-finder
description: Finds and recommends books using the free Open Library API. Use when the user wants book recommendations, wants to search for books by title or author, or asks what to read next.
metadata:
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/featured/book-finder
---

# Book Finder

Searches for books using the [Open Library](https://openlibrary.org) API — free, no API key required.

## Examples

* "Find books by Dostoevsky"
* "Recommend me a mystery novel"
* "Find books similar to Harry Potter"
* "Search for books about mindfulness"
* "What books has Agatha Christie written?"
* "Find me a good sci-fi book"

## Instructions

Call the `run_js` tool with the following exact parameters:

- **script name**: `index.html`
- **data**: A JSON string with the following fields:
  - `query`: String — the search query (title, author, topic, genre, or keyword)
  - `limit`: Number (Optional) — max results to return, 1–10. Default: 5.

### Search query tips
- Author search: `"Agatha Christie"`, `"Dostoevsky"`, `"J.K. Rowling"`
- Title search: `"Lord of the Rings"`, `"Pride and Prejudice"`
- Topic/genre: `"mindfulness meditation"`, `"mystery thriller"`, `"science fiction space"`
- For recommendations: use the genre or mood as the query (e.g. `"cozy mystery"`, `"epic fantasy"`)

### After receiving results
Present the books in a readable list format:
- Title (bold)
- Author(s)
- First published year
- A brief description if available
Then ask the user if they'd like more details on any specific book.
