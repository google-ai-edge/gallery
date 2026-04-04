# Orchestration Module — Implementation Plan

## Context

The AI agent chat supports sequential tool calling via LiteRT-LM, but lacks planning, parallel execution, and self-evaluation. This module adds a fully agentic orchestration layer **decoupled from the app UI** — it exposes clean APIs and depends only on injected interfaces.

**Key constraint:** LiteRT-LM `Conversation` is single-threaded. Tool-only calls (JS, intents) CAN run in parallel.

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                   App Layer                      │
│  AgentChatScreen / ViewModel / UI                │
│  Calls orchestration APIs, observes state        │
└──────────────────┬──────────────────────────────┘
                   │  API calls
                   ▼
┌─────────────────────────────────────────────────┐
│            Orchestration Module                   │
│  ┌──────────┐ ┌──────────────┐ ┌─────────────┐  │
│  │ Planner  │ │ Orchestrator │ │  Evaluator  │  │
│  └──────────┘ └──────────────┘ └─────────────┘  │
│  ┌──────────────────────────────────────────┐    │
│  │       OrchestrationController            │    │
│  │  (wires modules, runs plan-exec-eval     │    │
│  │   loop, exposes StateFlow + APIs)        │    │
│  └──────────────────────────────────────────┘    │
└──────────────────┬──────────────────────────────┘
                   │  Uses injected interfaces
                   ▼
┌─────────────────────────────────────────────────┐
│           LLM / Tool Layer                       │
│  LlmInferenceProvider (interface)                │
│  ToolExecutor (interface)                        │
│  Implemented by app, injected into module        │
└─────────────────────────────────────────────────┘
```

---

## Module Location

```
gallery/orchestration/
├── OrchestrationTypes.kt        # Data models
├── LlmInferenceProvider.kt      # Interface: LLM calls
├── ToolExecutor.kt              # Interface: tool execution
├── Planner.kt                   # Module 1: planning
├── ExecutionOrchestrator.kt     # Module 2: execution
├── SelfEvaluator.kt             # Module 3: evaluation
└── OrchestrationController.kt   # Public API / loop controller
```

---

## Files to Create

### 1. `OrchestrationTypes.kt`

All data models. No external dependencies.

- `PlanStep(id, description, skillName?, toolName?, toolArgs, dependsOn)`
- `ExecutionPlan(goal, reasoning, steps)`
- `StepStatus` enum: PENDING, RUNNING, COMPLETED, FAILED, SKIPPED
- `StepResult(stepId, status, output, error?, durationMs)`
- `OrchestrationStatus` enum: IDLE, PLANNING, EXECUTING, EVALUATING, REPLANNING, COMPLETED, CANCELLED, ERROR
- `OrchestrationState(status, plan?, stepResults, evaluation?, iteration, maxIterations, finalOutput?, error?)`
- `EvaluationResult(goalAchieved, assessment, missingItems, shouldReplan)`
- `SkillSummary(name, description)`

### 2. `LlmInferenceProvider.kt`

Interface the app implements to give the module access to LLM inference.

```kotlin
interface LlmInferenceProvider {
    suspend fun generateResponse(prompt: String): String
    fun cancel()
}
```

### 3. `ToolExecutor.kt`

Interface the app implements to give the module access to tool execution.

```kotlin
interface ToolExecutor {
    suspend fun executeTool(toolName: String, args: Map<String, String>): ToolExecutionResult
    fun getAvailableSkills(): List<SkillSummary>
}

data class ToolExecutionResult(val success: Boolean, val output: String, val error: String? = null)
```

### 4. `Planner.kt`

Module 1 — understands the ask, produces a structured execution plan.

- `buildPlanningPrompt(userMessage, skills)` — crafts prompt instructing LLM to output JSON plan with steps, dependencies, parallelism
- `buildReplanPrompt(userMessage, prevPlan, results, eval)` — crafts prompt for re-planning after failed evaluation
- `parsePlan(llmOutput, originalGoal)` — parses JSON into `ExecutionPlan`, with regex fallback for malformed output
- `getExecutionBatches(plan)` — topological sort on `dependsOn` graph, groups independent steps into parallel batches

### 5. `ExecutionOrchestrator.kt`

Module 2 — executes the plan.

- `executePlan(plan, batches, onStepUpdate)` — iterates batches:
  - Tool-only steps (`runJs`, `runIntent`): launch as **parallel coroutines**
  - LLM-needing steps: serialize via **Mutex** (single Conversation constraint)
  - Injects prior step results as context into dependent steps
  - Calls `onStepUpdate(StepResult)` callback per step (drives UI without depending on it)
- `cancel()` — sets flag checked between steps

### 6. `SelfEvaluator.kt`

Module 3 — evaluates whether the goal is met.

- `buildEvaluationPrompt(goal, plan, results)` — asks LLM: "Is the goal achieved? What's missing? Should we replan?"
- `parseEvaluation(llmOutput)` — parses into `EvaluationResult`

### 7. `OrchestrationController.kt`

Public API. The only class the app needs to interact with.

```kotlin
class OrchestrationController(
    llmProvider: LlmInferenceProvider,
    toolExecutor: ToolExecutor,
    maxIterations: Int = 3,
) {
    val state: StateFlow<OrchestrationState>
    suspend fun run(userMessage: String)
    fun cancel()
}
```

**The `run()` loop:**

```
1. status = PLANNING
   plan = planner.plan(userMessage, toolExecutor.getAvailableSkills())
   emit state with plan

2. for iteration in 1..maxIterations:
     if cancelled → break

     status = EXECUTING
     results = orchestrator.executePlan(plan)
     emit state with results

     status = EVALUATING
     eval = evaluator.evaluate(goal, plan, results)
     emit state with eval

     if eval.goalAchieved → status = COMPLETED, break
     if !eval.shouldReplan → status = COMPLETED, break

     status = REPLANNING
     plan = planner.replan(goal, plan, results, eval)
     emit state with new plan

3. status = COMPLETED (or CANCELLED)
```

---

## Existing Files to Modify

### `LlmChatViewModel.kt`

Add `generateInternalResponse(model, input): String` — suspending function that runs inference and returns complete text **without** touching chat UI. Used by the app's `LlmInferenceProvider` implementation.

### `ChatMessage.kt`

- Add `ORCHESTRATION_PLAN` and `ORCHESTRATION_EVALUATION` to `ChatMessageType` enum
- Add `ChatMessageOrchestrationPlan` and `ChatMessageOrchestrationEvaluation` classes

### `Types.kt`

- Add `ORCHESTRATION_PLAN_READY`, `ORCHESTRATION_STEP_UPDATE`, `ORCHESTRATION_EVALUATION` to `AgentActionName`
- Add corresponding `AgentAction` subclasses

### `AgentChatScreen.kt`

- Implement `LlmInferenceProvider` wrapping `viewModel.generateInternalResponse()`
- Implement `ToolExecutor` wrapping `agentTools`
- Create `OrchestrationController` with those implementations
- Observe `controller.state` → render plan/progress/evaluation as chat messages
- Route user message to `controller.run()` when orchestration is enabled
- Add "Stop" button when orchestration is running

### Chat message rendering

- Add composable rendering for `ORCHESTRATION_PLAN` (collapsible step list with status icons) and `ORCHESTRATION_EVALUATION` (assessment card)

---

## Parallelism Strategy

| Step type | Execution | Why |
|---|---|---|
| Tool-only (`runJs`, `runIntent`) | Parallel coroutines | Independent of LLM Conversation |
| LLM inference steps | Serialized via Mutex | Single Conversation constraint |
| Mixed (LLM → tool) | LLM serial, tool overlaps next LLM | Maximize throughput |

---

## Safety & UX

- **Max iterations**: default 3, configurable
- **Manual stop**: `controller.cancel()` checked between steps
- **Context window**: summarize intermediate results to stay within on-device model limits
- **JSON parsing**: regex fallback for malformed LLM output
- **Orchestration toggle**: opt-in; simple queries bypass orchestration

---

## Implementation Stages

### Stage 1: Core Module (no UI, no app changes)
Create the orchestration module as a standalone, testable library.

1. `OrchestrationTypes.kt` — all data models
2. `LlmInferenceProvider.kt` + `ToolExecutor.kt` — interfaces
3. `Planner.kt` — planning prompt construction + JSON parsing + topological sort
4. `SelfEvaluator.kt` — evaluation prompt construction + parsing
5. `ExecutionOrchestrator.kt` — batch execution, parallelism via coroutines + Mutex
6. `OrchestrationController.kt` — wire modules, implement plan-execute-evaluate loop

**Gate:** All unit tests pass (see Stage 1 tests below). Module compiles independently.

### Stage 2: App Bridge
Connect the orchestration module to the existing app infrastructure.

7. `LlmChatViewModel.kt` — add `generateInternalResponse()` (suspending, no UI side effects)
8. Create `LlmInferenceProviderImpl` wrapping the ViewModel
9. Create `ToolExecutorImpl` wrapping `AgentTools` + `SkillManagerViewModel`

**Gate:** Can call `orchestrationController.run()` from a test harness and get results back through the real LLM on device.

### Stage 3: UI Integration
Wire orchestration into the chat UI.

10. `ChatMessage.kt` + `Types.kt` — new message types + agent actions
11. `OrchestrationPlanView.kt` — composable for plan + evaluation rendering
12. `AgentChatScreen.kt` — integrate controller, observe state, add stop button, orchestration toggle

**Gate:** End-to-end flow works on device — user sends message, plan appears, steps execute with progress, evaluation shows, loop terminates.

### Stage 4: Polish & Edge Cases
13. Context window management — summarize intermediate results for on-device model limits
14. Error recovery — handle tool failures, malformed JSON, timeouts gracefully
15. Cancel behavior — clean stop at any phase
16. Orchestration toggle UX — settings, default off

---

## Test Plan

### Stage 1: Unit Tests (offline, no device needed)

Run with `./gradlew test` or IDE.

| Test | What it verifies |
|---|---|
| `Planner.parsePlan()` — valid JSON | Correctly parses a well-formed plan JSON into `ExecutionPlan` |
| `Planner.parsePlan()` — malformed JSON | Regex fallback extracts plan from messy LLM output |
| `Planner.parsePlan()` — empty/garbage | Returns sensible error, doesn't crash |
| `Planner.getExecutionBatches()` — linear chain | Steps with A→B→C deps produce 3 sequential batches |
| `Planner.getExecutionBatches()` — parallel | Independent steps grouped into same batch |
| `Planner.getExecutionBatches()` — diamond DAG | A→{B,C}→D produces correct 3-level batching |
| `Planner.getExecutionBatches()` — cycle detection | Circular deps handled gracefully |
| `SelfEvaluator.parseEvaluation()` — goal achieved | Parses `goalAchieved: true` correctly |
| `SelfEvaluator.parseEvaluation()` — needs replan | Parses missing items and `shouldReplan: true` |
| `ExecutionOrchestrator` — mock parallel | Tool-only steps run concurrently (verify with timing) |
| `ExecutionOrchestrator` — mock serial | LLM steps serialize through Mutex (verify ordering) |
| `ExecutionOrchestrator` — cancel mid-batch | Cancel flag stops execution between steps |
| `OrchestrationController` — happy path | Mock LLM returns good eval on iteration 1 → COMPLETED |
| `OrchestrationController` — replan loop | Mock LLM fails eval twice, succeeds third → 3 iterations |
| `OrchestrationController` — max iterations | After N failed evals, stops with COMPLETED (not infinite) |
| `OrchestrationController` — cancel | Cancel during execution → CANCELLED state |

### Stage 2: On-Device Integration Tests

Use the existing test infrastructure in `orchestration/`. Leverage:
- `lib-device-helpers.sh` — ADB helpers (`dump_ui`, `tap_element`, `send_prompt`, `poll_ui`, `navigate_to_chat`, `fresh_app`, `reset_session`, `import_skill`, etc.)
- `run-device-test.sh` — single-skill device test pattern (push skill → baseline → install → test → verify)
- `run-multi-device-test.sh` — parallel multi-device runner (auto-detects devices, splits skills, runs in parallel)

#### New script: `run-orchestration-test.sh`

Extends the existing `run-device-test.sh` pattern for orchestration-specific flows:

```bash
# Usage: ./run-orchestration-test.sh [-d <device-serial>] <test-scenario>
```

Key differences from `run-device-test.sh`:
- Enables orchestration mode toggle before sending prompt
- Polls for orchestration-specific UI elements: plan card, step status updates, evaluation card
- Longer timeouts (orchestration has multiple LLM round-trips)
- Captures screenshots at each phase (plan, execution, evaluation)

#### On-Device Test Scenarios

| # | Scenario | Skills Needed | Test Prompt | Pass Criteria |
|---|----------|--------------|-------------|---------------|
| 1 | Single-skill plan | wikipedia | "Look up the population of Tokyo" | Plan shows 1 step (loadSkill + runJs), executes, evaluation passes on iteration 1 |
| 2 | Multi-skill chain | wikipedia, qr-code | "Look up the capital of France and generate a QR code for it" | Plan shows 2+ dependent steps, executes in sequence, both skills called |
| 3 | Parallel execution | uuid-generator, password-generator | "Generate 3 UUIDs and a password" | Plan shows 2 independent steps, both tools called, results combined |
| 4 | Self-evaluation loop | wikipedia | "Find 3 interesting facts about Mars and summarize them in exactly 3 bullet points" | May take >1 iteration if model output doesn't match format on first try |
| 5 | Cancel mid-execution | wikipedia | "Research a complex topic" + tap Stop | Orchestration stops cleanly, UI shows CANCELLED, no hanging state |
| 6 | Orchestration off | any | "Hello, how are you?" | Toggle off → message goes through direct chat, no planning phase |
| 7 | Error recovery | (broken skill) | "Run the broken skill" | Tool failure captured in StepResult, evaluation reports failure, no crash |

#### Verification checklist (extends existing `SCENARIO-TEST.md` pattern)

For each scenario, verify on device:
- [ ] Plan message appears in chat (collapsible, shows steps)
- [ ] Step status updates in real-time (PENDING → RUNNING → COMPLETED)
- [ ] "Calling JS script" / "Called JS script" messages appear for tool steps
- [ ] Evaluation message appears after execution
- [ ] If re-planning: new plan appears, execution resumes
- [ ] Final output matches expected format
- [ ] Stop button works at any phase
- [ ] No UI freeze or ANR during orchestration
- [ ] Model response time reasonable (<30s per LLM call on Gemma-4-2B-it)

#### Multi-device testing

Use `run-multi-device-test.sh` pattern to run orchestration scenarios across devices in parallel:
```bash
./run-multi-device-test.sh -s orchestration-tests
```

### Stage 3: JS Skill Regression Tests (existing infrastructure)

Orchestration must not break existing skill execution. Run existing tests:

```bash
# JSDOM-based unit tests
cd orchestration && node run-tests.js

# Puppeteer-based scenario tests (Chrome)
cd orchestration && node run-scenario-tests.js
```

### Stage 4: Recording Tests

Use the existing `run-skill-recording.sh` pattern to create video recordings of orchestration flows:

```bash
./run-skill-recording.sh <skill-name> "orchestration prompt 1" "orchestration prompt 2"
```

Record key scenarios for demo/review:
- Single-skill orchestration flow
- Multi-skill chain with parallel steps
- Self-evaluation replan loop

For bulk recording across devices:
```bash
./run-all-recordings.sh
```
