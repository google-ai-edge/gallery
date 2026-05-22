---
name: foog808-agent-ensemble
description: Coordinate Goose, Agency Agents, and Gallery as a single multi-agent collaboration skill.
metadata:
  homepage: https://github.com/foog808/goose
  source: https://github.com/foog808/agency-agents
---

# FooG808 Agent Ensemble

This skill unifies the three repositories `goose`, `agency-agents`, and `gallery` into one coordinated workflow. It is implemented as a single meta-skill that simulates three collaborating agents and evaluates multiple candidate answers using GRPO-style ranking.

## Agent roles

- **Goose**: technical execution, feasibility validation, command planning, and risk assessment.
- **Agency Agents**: specialist expertise, persona-driven reasoning, domain recommendations, and strategy.
- **Gallery**: output presentation, UX review, user-facing formatting, and final synthesis.

## Instructions

When this skill is used, perform a structured multi-agent collaboration and candidate evaluation:

1. Analyze the user request and break it down into a clear plan.
2. Simulate each agent independently:
   - Goose creates a concrete technical plan, checks assumptions, and flags risks.
   - Agency Agents provides domain-aware reasoning, design recommendations, and process guidance.
   - Gallery produces the final formatted answer, ensures clarity, and defines next actions.
3. Generate 23 candidate answers or response alternatives.
4. Grade each candidate against multiple criteria:
   - technical correctness
   - completeness
   - UX and clarity
   - alignment with the user's request
5. Select the best answer, assign medals to top candidates, discard the weakest candidates, and return the chosen response.

## How to invoke

Call the `run_js` tool with the following exact parameters:

- script name: `index.html`
- data: A JSON string with the following field
  - `request`: String. The user's request text.

The script returns a JSON object containing a `result` object with these fields:

- `final_result`: String. Summary of the selected answer.
- `best_candidate`: Object. The chosen candidate with `id`, `summary`, `details`, `score_components`, `score`, `rank`, and `medal`.
- `candidates`: Array of 23 sorted candidate objects.
- `goose_notes`: String.
- `agency_agents_notes`: String.
- `gallery_notes`: String.
- `action_items`: Array of next-step strings.
- `confidence`: Numeric confidence score.
- `learning_summary`: String describing the GRPO evaluation.
- `discussion_log`: Array of candidate summary entries.
- `medal_counts`: Object with counts for Gold, Silver, and Bronze.

## Example prompts

- "Combine Goose, Agency Agents, and Gallery into one working ensemble skill."
- "Create a multi-agent plan for using Goose, Agency Agents, and Gallery together."
- "Use the FooG808 Agent Ensemble to coordinate a cross-repo task."

## Collaboration rules

- Always compare the 23 candidate answers and select the strongest combined answer.
- Favor correctness, completeness, and clear UX when resolving conflicts.
- Assign a `Gold` medal to the top candidate, `Silver` medals to the next best, and `Bronze` medals for strong alternatives.
- Discard the weakest candidate variants.
- If a request is ambiguous, ask a clarifying follow-up question before finalizing the plan.

