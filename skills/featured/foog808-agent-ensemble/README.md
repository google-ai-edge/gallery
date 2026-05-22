# FooG808 Agent Ensemble

A combined skill that unites Goose, Agency Agents, and Gallery into one interactive collaboration workflow.

This skill uses a GRPO-style candidate evaluation process to generate 23 alternative responses, grade them, and choose the best answer.

## What it does

- Simulates three cooperating agents:
  - Goose for technical execution and validation
  - Agency Agents for strategy and domain expertise
  - Gallery for presentation and UX quality
- Generates 23 candidate answers for each request
- Grades each candidate on correctness, completeness, UX, risk, and clarity
- Uses group relative policy optimization (GRPO) to compare candidates against the full set
- Assigns medals to the top-performing candidates
- Returns a structured best answer plus detailed notes, action items, and a learning summary

## How to use

Call the skill through the Gallery `run_js` tool:

- `script name`: `index.html`
- `data`: `{ "request": "your task description" }`

The response includes `final_result`, `best_candidate`, `candidates`, `goose_notes`, `agency_agents_notes`, `gallery_notes`, `action_items`, `confidence`, `learning_summary`, `discussion_log`, and `medal_counts`.

Local development helpers:

- `scripts/ensemble.js`: shared ensemble logic for both browser and Node runtimes.
- `scripts/index.node.js`: Node harness that mirrors the browser `index.html` logic and returns the same output schema.
- `validate_runtime.js`: runs the harness with a self-improvement request and writes `.last_run.json`.
- `package.json`: adds `npm run validate` and `npm test` commands for easy local validation.
- `scripts/run_log.json`: appended with runs when using the Node harness.

Run locally:

```bash
npm run validate
npm test
cat .last_run.json
```
