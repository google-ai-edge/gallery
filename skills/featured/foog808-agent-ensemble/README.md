# FooG808 Agent Ensemble

A combined skill that unites Goose, Agency Agents, and Gallery into one interactive collaboration workflow.

This skill uses a GRPO-style candidate evaluation process to generate 23 alternative responses, grade them, and choose the best answer.

## What it does

- Simulates three cooperating agents:
  - Goose for technical execution and validation
  - Agency Agents for strategy and domain expertise
  - Gallery for presentation and UX quality
- Generates 23 candidate answers for each request
- Grades each candidate on correctness, completeness, and usability
- Assigns medals to the top-performing candidates
- Returns a structured best answer plus detailed notes and action items

## How to use

Call the skill through the Gallery `run_js` tool:

- `script name`: `index.html`
- `data`: `{ "request": "your task description" }`

The response includes `final_result`, `best_candidate`, `candidates`, `goose_notes`, `agency_agents_notes`, `gallery_notes`, `action_items`, `confidence`, and `discussion_log`.

Local development helpers:

- `scripts/index.node.js`: Node harness that mirrors the browser `index.html` logic and returns the same output schema.
- `validate_runtime.js`: runs the harness with a self-improvement request and writes `.last_run.json`.
- `scripts/run_log.json`: appended with runs when using the Node harness (manual append by developers).

Run locally:

```bash
node validate_runtime.js
cat .last_run.json
```
