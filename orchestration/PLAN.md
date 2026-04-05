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

## Test Plan — Fully Automated

All tests run automatically via scripts or CI. No manual steps.

---

### Test Layer 1: Kotlin Unit Tests (offline, no device)

**Location:** `Android/src/app/src/test/java/com/google/ai/edge/gallery/orchestration/`

**Run:** `cd Android/src && ./gradlew test` (also runs in CI via `build_android.yaml`)

Test files to create:

#### `PlannerTest.kt`

| Test | What it verifies |
|---|---|
| `parsePlan_validJson` | Parses well-formed JSON into `ExecutionPlan` with correct steps, deps, goal |
| `parsePlan_malformedJson` | Regex fallback extracts steps from messy LLM output with markdown fences |
| `parsePlan_emptyGarbage` | Returns single fallback step, doesn't crash |
| `parsePlan_missingFields` | Handles missing optional fields (skillName, toolArgs) gracefully |
| `getExecutionBatches_linearChain` | A→B→C deps → 3 sequential batches of 1 |
| `getExecutionBatches_parallel` | 3 independent steps → 1 batch of 3 |
| `getExecutionBatches_diamondDag` | A→{B,C}→D → 3 batches: [A], [B,C], [D] |
| `getExecutionBatches_cycleDetection` | Circular deps don't hang; remaining steps placed in final batch |
| `buildPlanningPrompt_includesSkills` | Prompt contains all skill names and descriptions |
| `buildReplanPrompt_includesPriorResults` | Re-plan prompt contains previous step results and eval feedback |

#### `SelfEvaluatorTest.kt`

| Test | What it verifies |
|---|---|
| `parseEvaluation_goalAchieved` | JSON with `goalAchieved: true` → `EvaluationResult(goalAchieved=true)` |
| `parseEvaluation_needsReplan` | JSON with missing items + `shouldReplan: true` → correct fields |
| `parseEvaluation_malformedFallback` | Non-JSON with positive keywords → `goalAchieved=true` via heuristic |
| `parseEvaluation_negativeFallback` | Non-JSON with "failed", "missing" → `goalAchieved=false` |

#### `ExecutionOrchestratorTest.kt`

Uses mock `LlmInferenceProvider` and `ToolExecutor`.

| Test | What it verifies |
|---|---|
| `executePlan_toolsRunParallel` | 3 tool-only steps in same batch → all complete, wall time < 3x single step |
| `executePlan_llmStepsSerial` | 2 LLM steps → execute sequentially (verify ordering via timestamps) |
| `executePlan_mixedBatch` | LLM + tool steps → LLM serialized, tools parallel |
| `executePlan_dependencyInjection` | Step B depends on A → B's context includes A's output |
| `executePlan_cancelMidBatch` | `cancel()` called → remaining steps get SKIPPED status |
| `executePlan_stepFailure` | Tool returns error → step marked FAILED, execution continues |

#### `OrchestrationControllerTest.kt`

Uses mock `LlmInferenceProvider` (returns canned plan/eval JSON) and mock `ToolExecutor`.

| Test | What it verifies |
|---|---|
| `run_happyPath` | Plan → execute → eval passes → COMPLETED in 1 iteration |
| `run_replanLoop` | Eval fails twice, passes third → 3 iterations, final state COMPLETED |
| `run_maxIterations` | Eval never passes → stops at maxIterations, state COMPLETED (not infinite) |
| `run_cancel` | `cancel()` during execution → state CANCELLED |
| `run_stateTransitions` | Collect all state emissions → verify IDLE→PLANNING→EXECUTING→EVALUATING→COMPLETED |
| `run_planningError` | LLM returns garbage → state ERROR with message |

**CI integration:** Add to `.github/workflows/build_android.yaml`:

```yaml
- name: Unit Tests
  run: ./gradlew test
```

---

### Test Layer 2: JS Skill Regression Tests (offline, no device)

**Existing scripts — no changes needed.** Orchestration must not break skill execution.

**Run all automatically:**

```bash
# Single command — runs both test suites
cd orchestration && node run-tests.js && node run-scenario-tests.js
```

- `run-tests.js` — JSDOM-based. Auto-discovers all skills with `testing/test-input.json`. Runs each test case, checks assertions (`expected`, `expected_pattern`, `expected_contains`, etc.). Supports single-shot, multi-round (games), and batch tests. Exit code 1 on any failure.
- `run-scenario-tests.js` — Puppeteer-based (headless Chrome). Same test cases but in a real browser environment. Catches DOM/rendering issues JSDOM misses.

**CI integration:** Add to `.github/workflows/build_android.yaml`:

```yaml
- name: JS Skill Tests (JSDOM)
  working-directory: ./orchestration
  run: |
    npm install jsdom
    node run-tests.js

- name: JS Skill Tests (Puppeteer)
  working-directory: ./orchestration
  run: |
    npm install puppeteer-core
    node run-scenario-tests.js
```

---

### Test Layer 3: On-Device Integration Tests (automated via ADB)

All on-device tests are fully automated — no manual taps, no hardcoded coordinates. Uses `lib-device-helpers.sh` for XML-based UI element discovery.

#### Existing infrastructure used:

| Script | Role |
|---|---|
| `lib-device-helpers.sh` | Shared helpers: `dump_ui`, `find_element`, `tap_element`, `send_prompt`, `poll_ui`, `poll_ui_count`, `navigate_to_chat`, `fresh_app`, `reset_session`, `import_skill`, `take_screenshot`, `ensure_app_installed` |
| `run-device-test.sh` | Single-skill test: push → baseline (disabled) → install → test → verify `Called JS script` → screenshot → PASS/FAIL |
| `run-multi-device-test.sh` | Parallel runner: auto-detect devices → split skills → run `run-device-test.sh` per device → aggregate results |
| `TEST-PLAN.md` | Test prompt table — `run-device-test.sh` reads prompts from here |

#### New script: `run-orchestration-test.sh`

Follows `run-device-test.sh` pattern but for orchestration-specific flows.

```bash
# Usage: ./run-orchestration-test.sh [-d <device-serial>] <scenario-name>
# Runs a single orchestration scenario on a connected device.
# Exit 0 = PASS, Exit 1 = FAIL
```

**Flow per scenario (all automated):**

1. **Setup** — `fresh_app`, `navigate_to_chat`
2. **Install skills** — `import_skill` for each required skill (read from scenario config)
3. **Enable orchestration** — `tap_element "Orchestration"` toggle
4. **Send prompt** — `send_prompt "$PROMPT"` (read from `ORCHESTRATION-TEST-PLAN.md`)
5. **Poll for plan** — `poll_ui "Plan" 30 3` — verify plan card appears
6. **Poll for execution** — `poll_ui "Completed" 120 5` or `poll_ui "Called JS script" 120 5`
7. **Poll for evaluation** — `poll_ui "Goal achieved" 120 5` or `poll_ui "Evaluation" 120 5`
8. **Capture screenshots** — `take_screenshot` at each phase
9. **Verify pass criteria** — `ui_has` checks per scenario
10. **Report** — PASS/FAIL with screenshots saved to `screenshots/orchestration/`

**Cancel test variant:** Steps 1-4, then `tap_element "Stop"`, then `poll_ui "CANCELLED"`.

#### `ORCHESTRATION-TEST-PLAN.md` (new file, same format as `TEST-PLAN.md`)

```markdown
| # | Scenario | Skills | Test Prompt | Pass Pattern | Timeout |
|---|----------|--------|-------------|-------------|---------|
| 1 | single-skill | wikipedia | Look up the population of Tokyo | Goal achieved | 120 |
| 2 | multi-skill-chain | wikipedia,qr-code | Look up the capital of France and generate a QR code for it | Called JS script | 180 |
| 3 | parallel-exec | uuid-generator,password-generator | Generate 3 UUIDs and a password | Goal achieved | 120 |
| 4 | self-eval-loop | wikipedia | Find 3 interesting facts about Mars and summarize them in exactly 3 bullet points | Goal achieved | 240 |
| 5 | cancel | wikipedia | Research the history of quantum computing in detail | CANCELLED | 30 |
| 6 | orchestration-off | blackjack | Play blackjack | Called JS script | 60 |
| 7 | error-recovery | (none) | Use the broken-test-skill to look up weather | Failed | 120 |
```

#### Run all orchestration tests on one device:

```bash
./run-orchestration-test.sh          # runs all scenarios sequentially
./run-orchestration-test.sh single-skill  # run one scenario
```

#### Run across multiple devices in parallel:

```bash
./run-multi-device-test.sh -s orchestration-tests
```

This reuses `run-multi-device-test.sh` — it auto-detects connected devices, splits scenarios across them, runs in parallel, aggregates PASS/FAIL.

---

### Test Layer 4: Build Verification (CI)

**`.github/workflows/build_android.yaml`** — updated to run all automated tests:

```yaml
name: Build and Test

on:
  workflow_dispatch:
  push:
    branches: [ "main" ]
    paths: [ 'Android/**', 'orchestration/**' ]
  pull_request:
    branches: [ "main" ]
    paths: [ 'Android/**', 'orchestration/**' ]

jobs:
  build_and_test:
    name: Build, Unit Test, JS Test
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: ./Android/src
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      - uses: actions/setup-node@v4
        with:
          node-version: '20'

      # 1. Build
      - name: Build APK
        run: ./gradlew assembleRelease

      # 2. Kotlin unit tests (orchestration + all existing)
      - name: Kotlin Unit Tests
        run: ./gradlew test

      # 3. JS skill regression tests
      - name: JS Skill Tests (JSDOM)
        working-directory: ./orchestration
        run: |
          npm install jsdom
          node run-tests.js
```

**On-device tests** run locally (require physical device). Single command:

```bash
# Full automated suite: build → install → run all orchestration scenarios
cd orchestration && ./run-orchestration-test.sh
```

---

### Test Layer 5: Recording Tests (automated capture for review)

Automated video/screenshot capture of orchestration flows using existing scripts:

```bash
# Record a single orchestration flow
./run-skill-recording.sh wikipedia "Look up the population of Tokyo"

# Record all orchestration scenarios across 2 devices
./run-all-recordings.sh
```

Output: MP4 recordings + timestamped screenshots in `screenshots/orchestration/`.

---

### Summary: How to Run Everything

| What | Command | Where | Automated |
|---|---|---|---|
| Kotlin unit tests | `cd Android/src && ./gradlew test` | CI + local | Yes — CI on every PR |
| JS skill tests (JSDOM) | `cd orchestration && node run-tests.js` | CI + local | Yes — CI on every PR |
| JS skill tests (Puppeteer) | `cd orchestration && node run-scenario-tests.js` | Local (needs Chrome) | Yes — single command |
| On-device: single scenario | `cd orchestration && ./run-orchestration-test.sh <name>` | Local (needs device) | Yes — single command |
| On-device: all scenarios | `cd orchestration && ./run-orchestration-test.sh` | Local (needs device) | Yes — single command |
| On-device: multi-device | `cd orchestration && ./run-multi-device-test.sh -s orchestration-tests` | Local (needs devices) | Yes — single command |
| Recordings | `cd orchestration && ./run-all-recordings.sh` | Local (needs devices) | Yes — single command |
