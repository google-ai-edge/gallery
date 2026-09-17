# Local API Server for Google AI Edge Gallery

Date: 2026-09-17
Status: Approved for implementation

## Problem

Google AI Edge Gallery runs open LLMs (Gemma 3/3n, etc.) fully on-device via
LiteRT-LM, for free, with no API key. That inference is only reachable through
the app's own chat UI. External tools — specifically the ARTEMIS mobile
automation agent, which currently pays for Gemini/OpenAI API calls to power
its Observe-Think-Act loop — cannot call into it.

ARTEMIS already has a built-in `OLLAMA` / `VLLM` / `CUSTOM` LLM provider
(`artemis/llm/router.py`) that talks to any OpenAI-compatible
`/v1/chat/completions` endpoint via a configurable `base_url`. If Gallery
exposes that same shape of endpoint from the phone itself, ARTEMIS can point
at it with a config change and no code changes on the ARTEMIS side.

## Goals

- Expose the model currently loaded in Gallery's chat UI over HTTP, in an
  OpenAI-compatible `/v1/chat/completions` shape (text + image input).
- Runs entirely on-device; no PC, cloud relay, or code changes required on
  the client side beyond pointing a base URL at the phone.
- User-controlled: off by default, toggled in Settings, LAN-reachable so
  ARTEMIS (or anything else on the same WiFi) can call it directly.

## Non-goals (v1)

- Serving multiple models concurrently, or loading a model by name per
  request. The API always serves whatever model the user has currently
  loaded in the chat UI.
- Streaming responses (`stream: true`). v1 returns one complete JSON
  response per request.
- Concurrent use of the API and the in-app chat UI at the same time (see
  "Conversation state" below) — this is a known, accepted limitation, not a
  bug to fix later unless it becomes a real problem.
- Audio input over the API (Gallery supports audio in-app; out of scope
  here to keep the request/response schema simple).

## Codebase context (google-ai-edge/gallery, cloned 2026-09-17)

- Single Android module: `Android/src/app`, package
  `com.google.ai.edge.gallery`, Kotlin + Jetpack Compose + Hilt DI.
- **`AgentRuntimeExecutor`** (`agent/AgentRuntimeExecutor.kt`) is the real
  seam to build on — it's a higher-level, already-session-aware wrapper
  around the LiteRT-LM engine that every chat surface in the app uses
  (`LlmChatTaskModule`, `LlmAskImageTask`, agent chat, etc. all inject the
  same Hilt singleton via `@AiChatExecutor`, provided in
  `agent/AgentExecutorModule.kt`):
  - `suspend fun initialize(context, config: AgentRuntimeConfig, onDone)` —
    loads the model, starts a session (called by whichever screen the user
    opens).
  - `suspend fun resetSession(config: AgentRuntimeConfig)` — closes the
    active session and opens a new one, seeded with
    `config.initialMessages: List<Message>` (litertlm's turn type; build one
    from plain text with the existing `Message.user(text)` /
    `Message.model(text)` factories — see `ui/common/chat/ChatMessage.kt:441`,
    `convertToLitertMessage`).
  - `suspend fun execute(context: AgentExecutionContext, request: AgentRequest): AgentResponse` —
    runs one turn (text + `Attachment.ImageBitmap` images) to completion and
    returns `AgentResponse(output: String, isSuccessful: Boolean)`. This is
    already the non-streaming, wait-for-full-response call we need — no new
    streaming/aggregation code required.
  - `DefaultAgentRuntimeExecutor` (the concrete impl,
    `agent/DefaultAgentRuntimeExecutor.kt`) keeps the current model + task
    config in an internal `AtomicReference<ActiveSession?>`
    (`activeSession`), cleared by `cleanUp()`. `LlmChatViewModel.kt:189-206`
    already shows this exact reset-then-run pattern (for a different edge
    case — resuming a session the model itself stopped).
  - This executor already exists as an app-wide singleton with the
    "currently active model" tracked internally — it replaces the need for
    a bespoke registry class or any hook into per-screen ViewModels.
- **One small addition needed**: `activeSession` isn't currently readable
  from outside `DefaultAgentRuntimeExecutor`. Add a read-only property to
  the `AgentRuntimeExecutor` interface, following the same
  default-null-getter pattern already used for `activeSessionId`:
  ```kotlin
  val activeModelInfo: ActiveModelInfo?
    get() = null
  ```
  with `data class ActiveModelInfo(val model: Model, val taskId: String, val supportImage: Boolean)`,
  and override it in `DefaultAgentRuntimeExecutor` as
  `activeSession.get()?.sessionConfig?.let { ActiveModelInfo(it.model, it.taskId, it.supportImage) }`.
  This is the only change to existing runtime code this feature needs.
- Manifest already declares `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `INTERNET`, `POST_NOTIFICATIONS` — no new
  dangerous permissions needed, just a new `<service>` entry.
- Settings UI lives in `ui/home/SettingsDialog.kt`.
- Hilt singletons are provided in `di/AppModule.kt` and
  `agent/AgentExecutorModule.kt` (`@InstallIn(SingletonComponent::class)`).
- **Ktor is already a dependency** (`gradle/libs.versions.toml`: `ktor =
  "3.4.3"`, used today for the MCP client's `ktor-client-*` artifacts). We
  only need to add the server artifacts (`ktor-server-core`,
  `ktor-server-cio`, `ktor-server-content-negotiation`,
  `ktor-serialization-kotlinx-json`) under the same version ref — no new
  version to vet.
- `kotlinx.serialization.json` is already a dependency (used for
  request/response models).
- For the settings toggle/port/token, use a small **Preferences DataStore**
  (`androidx.datastore:datastore-preferences`, new artifact, same `dataStore`
  version), not the app's existing protobuf-based `Settings` — that proto is
  shared across many unrelated features and editing its schema is out of
  scope and unnecessarily wide blast radius for three primitive values.

## Architecture

One small interface addition, one new foreground service, one new settings
store:

```
                    ┌─────────────────────────┐
   ARTEMIS (PC) ───▶│  LocalApiForegroundService │
   POST /v1/chat/... │  (Ktor CIO, 0.0.0.0:port) │
                    └────────────┬────────────┘
                                 │ reads .activeModelInfo,
                                 │ calls resetSession()/execute()
                                 ▼
                    ┌─────────────────────────┐
                    │  AgentRuntimeExecutor     │  (existing Hilt singleton,
                    │  @AiChatExecutor          │   @AiChatExecutor,
                    │  (+1 property added)      │   already used by every
                    └─────────────────────────┘   chat screen in the app)
```

### 1. `AgentRuntimeExecutor` interface + `DefaultAgentRuntimeExecutor` (existing files, +1 property each)

See "Codebase context" above — add `activeModelInfo` (default `null` on the
interface, real implementation reading `activeSession` in
`DefaultAgentRuntimeExecutor`). This is the only change to existing runtime
code.

### 2. `LocalApiForegroundService` (new)

`com.google.ai.edge.gallery.apiserver.LocalApiForegroundService`, extends
`Service`, `@AndroidEntryPoint`, field-injects `@AiChatExecutor
AgentRuntimeExecutor` and the new settings DataStore (below).

Responsibilities:
- `onCreate`: build and start an embedded Ktor server, engine `CIO`, bound
  to `0.0.0.0:<port from settings>`.
- `onStartCommand`: `startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`.
  Notification text shows the LAN URL, e.g. "Serving http://192.168.1.42:8080".
- `onDestroy`: stop the Ktor server, release the port.
- All request handling runs on Ktor's own coroutine dispatcher; inference
  calls hop to `Dispatchers.Default`/IO as `LlmChatModelHelper` already
  does internally.
- Started/stopped from the Settings toggle via
  `context.startForegroundService(...)` / `context.stopService(...)`.

New Gradle dependencies (`Android/src/app/build.gradle.kts`):
`io.ktor:ktor-server-core`, `ktor-server-cio`,
`ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`.

### 3. Settings & auth token (new)

A small new Preferences `DataStore` (see "Codebase context" for why not the
existing protobuf `Settings`) holding:

```
enabled: Boolean = false
port: Int = 8080
authToken: String  // generated once (UUID) on first enable, regenerable
```

`SettingsDialog.kt` gets one new section:

```
[ Toggle ] Expose local API server
  Status: Running on http://192.168.1.42:8080
  Auth token: ******** [Show] [Copy] [Regenerate]
```

Toggling on: generate a token if none exists, start the foreground service.
Toggling off: stop the service.

## API contract

### `POST /v1/chat/completions`

Request (OpenAI-compatible subset):
```json
{
  "model": "ignored-serves-whatever-is-loaded",
  "messages": [
    {"role": "user", "content": "hello"},
    {"role": "assistant", "content": "hi, how can I help?"},
    {"role": "user", "content": [
      {"type": "text", "text": "what's in this screenshot?"},
      {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}}
    ]}
  ]
}
```

Response:
```json
{
  "id": "gallery-<uuid>",
  "object": "chat.completion",
  "choices": [
    {"index": 0, "message": {"role": "assistant", "content": "<full generated text>"}, "finish_reason": "stop"}
  ]
}
```

Headers: `Authorization: Bearer <authToken>` required.

### `GET /v1/models`

Response:
```json
{"data": [{"id": "<Model.name>"}]}
```
or `{"data": []}` if nothing is loaded.

### Conversation state handling

LiteRT-LM's `Conversation` is stateful and tied to one `Engine`/model
instance (kept single to avoid loading the multi-GB model twice into
limited phone RAM). ARTEMIS/LangChain's `ChatOpenAI` client is stateless
per call — it resends the full `messages` history every time and expects a
fresh answer, not accumulation on the server side.

Reconciliation (per request), using the executor described in Architecture:
1. Read `executor.activeModelInfo` → 503 if `null` (see Error handling).
2. Build `AgentRuntimeConfig(model, taskId, supportImage, initialMessages = req.messages.dropLast(1).map(::toLiteRtMessage))`,
   where `toLiteRtMessage` maps `role: "user"` → `Message.user(text)` and
   `role: "assistant"` → `Message.model(text)` (same mapping as the
   existing `convertToLitertMessage` in `ChatMessage.kt`).
3. `executor.resetSession(config)`.
4. Decode any `image_url` data-URIs in the last message to `Bitmap`, wrap as
   `Attachment.ImageBitmap`.
5. `executor.execute(AgentExecutionContext(), AgentRequest(query = lastMessage.text, attachments = images))`
   → `AgentResponse(output, isSuccessful)`.
6. Return `output` as the response body (200 if `isSuccessful`, 500
   otherwise — see Error handling).

This makes each API call behave like a stateless OpenAI call. **Accepted
limitation**: this reset also wipes whatever conversation is active in the
on-screen chat UI, and vice versa. The API and the manual chat UI cannot be
used at the same time without one clobbering the other's context. This is
acceptable for v1 — the phone is meant to act as a dedicated inference
server for ARTEMIS while the feature is in use.

### Error handling

| Condition | Response |
|---|---|
| Missing/invalid `Authorization` header | `401 {"error":{"message":"Invalid or missing API token"}}` |
| No model currently loaded (`executor.activeModelInfo == null`) | `503 {"error":{"message":"No model loaded in Gallery. Open the app and load a model first."}}` |
| Malformed JSON / missing `messages` | `400 {"error":{"message":"..."}}` |
| `AgentResponse.isSuccessful == false` (engine/tool error surfaced by the executor) | `500 {"error":{"message":"<AgentResponse.output>"}}` |
| Port already bound at service start | Service logs the failure, shows a notification ("Could not start local API server: port in use"), stops itself; app itself keeps working normally |

Requests are serialized behind a `Mutex` in the service — the on-device
engine only runs one turn at a time regardless.

## Testing

- Unit tests (JVM, no device): OpenAI-request-JSON → internal request model
  → LiteRT `Message`/`Content` mapping, and internal response → OpenAI-shaped
  JSON. Pure functions, fully testable off-device.
- Manual on-device test (v1 acceptance):
  1. Load a model in Gallery's chat UI.
  2. Enable the API server toggle in Settings; confirm the notification and
     shown URL.
  3. `curl` a text-only request from the PC to the phone's LAN URL; confirm
     a valid response and matching bearer-token enforcement (401 without
     it).
  4. Repeat with a request containing a base64 image; confirm the model
     responds about the image content.
- ARTEMIS integration test: set ARTEMIS's `.env`/`artemis.jsonc` to a custom
  preset pointing `api_base` at `http://<phone-ip>:<port>/v1`, and run one
  simple Flash-tier `mobile_run_task` end to end, confirming ARTEMIS gets
  and acts on usable responses from the on-device model.

## Open risks (informational, not blocking v1)

- Small on-device models (Gemma 3n E2B/E4B class) are noticeably weaker at
  structured tool-calling/grounding than Gemini Flash/Pro — expect degraded
  ARTEMIS accuracy, especially on Pro-tier multi-step tasks. This was known
  going in; not something this feature can fix.
- LAN-only auth (bearer token, no TLS) is adequate for a home network but
  should not be exposed beyond the LAN without additional hardening.
