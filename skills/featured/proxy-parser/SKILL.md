---
name: proxy-parser
description: Fetch DOM of a remote URL and extract data from it using a custom CSS selector. For example if you want to extract all the paragraphs from a chosen URL, you would use the CSS selector "p". You can use that returned text to do whatever you want with it. If you wanted to select just the first paragraph you could use the CSS selector "p:nth-of-type(1)", and so on.
---

# proxy-parser

This skill fetches the DOM of a remote URL and extracts data from it using a custom CSS selector of your choice.

## Examples

If the user asks:

"Search Wikipedia for information on Cats and return the first 3 paragraphs"

You could call this tool with the following JSON data:

{
"url": "https://en.wikipedia.org/wiki/Cat",
"domSelector": "p:nth-of-type(1), p:nth-of-type(2), p:nth-of-type(3)"
}

This would extract the first 3 paragraphs from the Wikipedia page on Cats,
which you could then use to answer the user's question.

## Instructions

Call the `run_js` tool with the following exact parameters:

- script name: `index.html`
- data: A JSON string with the following fields
  - url: the URL to fetch
  - domSelector: the CSS selector to extract data from the URL page content.
