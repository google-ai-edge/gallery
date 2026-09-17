# API Server Mode: Independent Model Lifecycle + Model Picker

Date: 2026-09-17
Status: Implemented, verified on-device 2026-09-17 (see "Verified on-device" below)

## Problem

The local API server shipped in
`docs/superpowers/specs/2026-09-17-local-api-server-design.md` shares the
app's single `AgentRuntimeExecutor` session with every chat screen. On-device
verification (see that spec's "Verified on-device" section) found a real bug
this causes: leaving the AI Chat screen at all — not just opening a second
chat — deinitializes the model via the app's existing screen-exit cleanup,
which clears `activeModelInfo` and makes the API return 503. **The AI Chat
screen currently has to stay open in the foreground for the API to have
anything to serve**, which defeats the point of a background API server for
a tool like ARTEMIS that drives the phone without needing the Gallery UI on
screen.

Two related gaps also need closing:
- There's no way to pick *which* downloaded model the API server should
  serve — it just serves whatever happens to be active from the chat UI.
- Turning the server on doesn't load a model itself; it only works if a
  chat screen happened to load one first.

## Goals

- Turning on "Expose local API server" loads a chosen model itself,
  immediately, independent of whether any chat screen is ever opened.
- Closing/backgrounding the AI Chat screen (or any other chat-capable
  screen) does not unload a model the API server still needs.
- A new Settings control lets the user pick which downloaded model the API
  server serves.

## Non-goals (this iteration)

- **True concurrent independent sessions** (one model loaded for chat, a
  different one loaded for the API server, simultaneously). These models
  are 2.6–4GB+; loading two at once risks OOM on many phones. Explicitly
  rejected in favor of the single-shared-engine approach below (see
  "Decision" below for the trade-off this implies).
- A dedicated model-catalog refactor. This introduces the smallest new
  shared-state seam needed (see `ModelCatalogCache` below) rather than
  extracting `ModelManagerViewModel`'s ~1500-line model-loading logic into
  a full repository.

## Decision: shared engine, safe hand-off

One model stays loaded at a time — same as today, zero extra memory cost.
What changes is *whose exit clears it*:

- Closing AI Chat (or Ask Image, Agent Chat, Tiny Garden, etc. — any screen
  using the shared `AgentRuntimeExecutor`) no longer tears down a model the
  API server still wants. This is the actual bug fix.
- If a **different** model gets loaded anywhere — chat picks another model,
  or the API server's picker selection changes — it replaces the one
  shared session everywhere. This is the same trade-off as switching
  models in chat today; it's just now an intentional, documented behavior
  instead of an accidental side effect of screen lifecycle.

## Architecture

Four pieces. Three are new, small, single-purpose files; one is a guard
added to the existing shared executor (the same seam
`docs/superpowers/specs/2026-09-17-local-api-server-design.md` already
established as the right integration point).

**Revised after on-device verification**: the executor-level guard alone
was not sufficient. `ModelManagerViewModel.cleanupModel()` — the actual
caller behind every screen's exit path — has its own independent
`model.resetInitialization()` side effect that fires unconditionally once
`onDone()` returns, regardless of whether real teardown happened. A second,
identical `ApiServerSessionHold` check was added at the top of
`cleanupModel()`, before it calls into the executor at all. See "Verified
on-device" below for how this was found.

```
Settings toggle ON
       │
       ▼
LocalApiForegroundService.onStartCommand()
  1. look up selected model via ModelCatalogCache
  2. executor.initialize(model, taskId="llm_chat", ...)
  3. apiServerSessionHold.heldModelName = model.name
       │
       ▼
DefaultAgentRuntimeExecutor (existing, +1 guard)
  cleanUp() called by ANY screen's exit path:
    if apiServerSessionHold.heldModelName == activeSession.model.name
      → skip real teardown, fire onDone() as success
    else
      → tear down as it does today
```

### 1. `ApiServerSessionHold` (new)

`com.google.ai.edge.gallery.apiserver.ApiServerSessionHold`, `@Singleton`.
A single in-memory field, not a DataStore round-trip — `cleanUp()` is not
suspend-safe and must answer synchronously:

```kotlin
@Singleton
class ApiServerSessionHold @Inject constructor() {
  @Volatile var heldModelName: String? = null
}
```

Set by `LocalApiForegroundService` when it successfully initializes a
model; cleared in `onDestroy()` when the server is toggled off.

### 2. `DefaultAgentRuntimeExecutor.cleanUp()` (existing file, guarded)

Inject `ApiServerSessionHold`. Before the existing teardown logic:

```kotlin
override fun cleanUp(onDone: () -> Unit) {
  val session = activeSession.get()
  if (session != null && apiServerSessionHold.heldModelName == session.sessionConfig.model.name) {
    onDone()
    return
  }
  // ...existing teardown unchanged below this point
}
```

This is the only change to existing runtime code this feature needs — same
pattern as the read-only `activeModelInfo` addition in the previous spec.

### 3. `ModelCatalogCache` (new)

`com.google.ai.edge.gallery.apiserver.ModelCatalogCache`, `@Singleton`. The
Service can't inject `ModelManagerViewModel` (a `@HiltViewModel` needs a
`ViewModelStoreOwner`, which a `Service` isn't — established in the previous
spec). Instead of refactoring the model catalog out of the ViewModel, this
adds one small shared cache the ViewModel publishes into once it loads its
model list, which the Service reads from:

```kotlin
@Singleton
class ModelCatalogCache @Inject constructor() {
  private val _models = MutableStateFlow<List<Model>>(emptyList())
  val models: StateFlow<List<Model>> = _models.asStateFlow()

  fun update(models: List<Model>) {
    _models.value = models
  }

  /** Downloaded, chat-capable models, for the API server's model picker. */
  fun downloadedChatModels(): List<Model> =
    _models.value.filter { it.isLlm && !it.isVariant && /* downloaded check */ }
}
```

`ModelManagerViewModel` calls `modelCatalogCache.update(...)` at the same
point it currently populates `builtInModels`/`importedModels` (see
`GlobalModelManager.kt`'s `LaunchedEffect(uiState.modelImportingUpdateTrigger)`,
or the equivalent point inside the ViewModel itself). This cache is a
read model only — it does not own loading/refreshing the list.

### 4. Settings model picker + `LocalApiServerPreferences` (existing file, +1 field)

Add `selectedModelName: String?` (read/save) to
`LocalApiServerPreferences`, alongside the existing enabled/port/token
fields, same Preferences DataStore.

Settings UI: a dropdown next to the existing toggle, populated from
`modelManagerViewModel`'s already-loaded model list (the Settings dialog
already receives this ViewModel — no need to go through
`ModelCatalogCache` here, that cache exists only for the Service's
out-of-ViewModel-scope lookup), filtered to downloaded + chat-capable.
Selecting a model:
- persists it to `LocalApiServerPreferences`
- if the server is currently on, triggers `LocalApiForegroundService` to
  re-`initialize()` with the newly selected model (a brief reload)

```
Settings → Expose local API server [ON]
  Model to serve: [ Gemma-4-E2B-it ▾ ]
  Port: 8080
  Token: a2b1...
```

### `LocalApiForegroundService` changes (existing file)

- `onStartCommand`: after reading port/token, also read
  `selectedModelName` and resolve it via
  `modelCatalogCache.downloadedChatModels()`. If no model is selected yet
  (first-ever enable) or the selected model is no longer downloaded, fall
  back to the first available downloaded chat model; if none exist, show a
  notification ("No downloaded model to serve — download one in AI Chat
  first") and stop itself rather than silently doing nothing.
- Calls `executor.initialize(context, AgentRuntimeConfig(model = resolved, taskId = BuiltInTaskId.LLM_CHAT, supportImage = true), onDone = { setHold... })`
  before starting the Ktor server, so `/v1/models` and
  `/v1/chat/completions` have something to serve immediately.
- `onDestroy`: clears `apiServerSessionHold.heldModelName`, then calls
  `executor.cleanUp()` for real (this time the guard in
  `DefaultAgentRuntimeExecutor` won't intercept it, since the hold was just
  cleared).

## Error handling

| Condition | Behavior |
|---|---|
| No downloaded chat-capable model exists when toggled on | Notification "No downloaded model to serve — download one in AI Chat first"; service stops itself; toggle in Settings reflects off |
| Selected model gets deleted from disk while the server is on | `executor.initialize()` fails via its existing error path; service logs and stops itself with a notification, same as a port-bind failure in the previous spec |
| User changes the model picker while server is on | Re-`initialize()` with the new model; existing in-flight requests against the old model may fail with a 500 (acceptable, rare, matches "switching models mid-chat" today) |

## Testing

- Unit test `DefaultAgentRuntimeExecutor.cleanUp()`'s new guard: held model
  name matches active session → `cleanUp` is a no-op (session survives);
  doesn't match / hold is null → real teardown proceeds. Same fake-executor
  pattern already used in `DefaultAgentRuntimeExecutorActiveModelInfoTest.kt`.
- Unit test `ModelCatalogCache.downloadedChatModels()` filtering logic with
  a handful of constructed `Model` fixtures (llm/non-llm, variant/non-variant,
  downloaded/not).
- Manual on-device (extends the previous spec's Task 7 script):
  1. Pick a model in the new Settings dropdown, enable the server, confirm
     `/v1/models` shows it immediately — without ever opening AI Chat.
  2. Open AI Chat with a *different* model; confirm `/v1/models` now shows
     that one (documented takeover).
  3. Close AI Chat; confirm `/v1/models` still shows that same model (the
     actual bug fix — this used to 503 here).
  4. Toggle the server off; confirm the model is released (no lingering
     foreground service, no stale hold).

## Verified on-device (2026-09-17)

Steps 1-4 of the Testing section above were run on the same physical device
as the previous spec's verification (only one model downloaded, so the
"different model" takeover in step 2 wasn't separately exercised).

Step 3 failed on the first pass: `/v1/models` correctly survived closing AI
Chat (no 503), but the actual chat completion call returned
`500 {"error":{"message":"Model not initialized."}}`. Root cause: a second,
independent cleanup path in `ModelManagerViewModel.cleanupModel()` (see the
Architecture section's revision note above). Fixed by adding the same
`ApiServerSessionHold` guard there; re-verified end to end afterward with a
200 response. Full before/after `adb logcat` detail is in the implementation
plan's Task 7.

Step 4 confirmed: toggling off drops RSS memory by ~220MB and the port
stops accepting connections, confirming genuine teardown (the guard
correctly does not intercept this path, since the hold is cleared before
`cleanUp()` is called).

## Follow-up fix: serve a model's own declared capabilities (2026-09-18)

While testing the ARTEMIS-side Local profile against other downloaded
models, selecting `MobileActions-270M` (a text-only, no-vision-encoder
model) in the picker made the server fail engine creation outright:
`Failed to create engine: NOT_FOUND: TF_LITE_VISION_ENCODER not found in
the model.` `LocalApiForegroundService.onStartCommand()` hardcoded
`supportImage = true` (and, once that was fixed, the same failure recurred
for `supportAudio = true`: `TF_LITE_AUDIO_ENCODER_HW not found`) regardless
of which model was actually selected. Fixed to use the model's own
`supportImage`/`supportAudio` fields instead — the same fields the native
Mobile Actions task (`MobileActionsViewModel.resetEngine`) already reads
when loading this exact model with `supportImage = false, supportAudio =
false`.

Note: even with this fixed, `MobileActions-270M` cannot serve as a general
chat-completions backend the way `Gemma-4-E2B-it`/`Gemma-4-E4B-it` do — it
refuses any request without a real function-calling `tools` schema
(`"I am FunctionGemma, a model optimized for function calls..."`), which
this server's `ChatCompletionsModels.kt` does not parse or forward at all.
Adding that would be its own feature, not a follow-up fix.

## Related but out of scope here

- **B (from the parent conversation)**: some models fail to download in
  this locally-built app while working in the Play Store release —
  suspected stale `model_allowlists/1_0_19.json` (manually pushed as a
  workaround for a 404 on the version this build actually requests,
  `1_0_20.json`, which isn't published upstream yet) missing newer model
  entries. Needs its own quick diagnosis, not a redesign; not addressed by
  this spec.
- **C (from the parent conversation)**: an ARTEMIS-side `mobile_run_task_local`
  driver loop that talks to this API server via `mobile_observe`/`mobile_act`
  plus JSON-in-content structured decisions. Its own spec, in ARTEMIS's
  repo, once this one is built and stable.
