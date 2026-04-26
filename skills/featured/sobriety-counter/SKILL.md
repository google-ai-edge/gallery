---
name: sobriety-counter
description: Calculate days, months, and years of sobriety or clean time from a given start date. Shows a recovery milestone card with chip milestones highlighted.
metadata:
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/featured/sobriety-counter
---

# Sobriety Counter

Calculate clean time and recovery milestones. This skill takes a sobriety date and displays
a heartfelt milestone card showing total days, months, years, and the next chip milestone coming up.

"One day at a time."

## Examples

* "How long have I been sober if my date is March 15, 2019?"
* "Calculate my clean time from November 8, 2021."
* "How many days is 5 years sober?"
* "My sobriety date is January 1, 2020. Show me my milestones."

## Instructions

Call the `run_js` tool with the following exact parameters:

- data: A JSON string with the following fields:
  - sobriety_date: String. The sobriety/clean date in ISO 8601 format (YYYY-MM-DD). If the user gives a date in another format, convert it.
  - name: String, optional. The person's name or "friend" if not provided.

Return the webview and then add a warm, encouraging message about the milestone. Do not be clinical — be human and celebratory.
