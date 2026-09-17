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
- Inference seam: `LlmChatModelHelper` (object, in
  `ui/llmchat/LlmChatModelHelper.kt`) wraps the LiteRT-LM `Engine` /
  `Conversation` API:
  - `initialize(context, model, taskId, ...)` — loads the model file into an
    `Engine`, creates a `Conversation`, stores both on `model.instance`.
  - `resetConversation(model, ..., initialMessages: List<Message>)` — closes
    the current `Conversation` and opens a new one, optionally pre-seeded
    with prior turns.
  - `runInference(model, input: String, resultListener, images: List<Bitmap>, ...)` —
    sends one turn (text + optional images) to the active `Conversation` and
    streams tokens back via `resultListener(text, done, thinking)`.
  - `stopResponse(model)` — cancels an in-flight turn.
- Each `Model` object holds its own `instance` (engine + conversation); the
  engine is expensive (a multi-GB model loaded into RAM/GPU), so the app
  only ever keeps one chat model initialized at a time in practice.
- `ModelManagerViewModel` (`@HiltViewModel`) is the existing source of truth
  for which model is loaded, but as a `@HiltViewModel` it requires a
  `ViewModelStoreOwner` (the Activity) — it cannot be injected into a plain
  background `Service`. This is why the design introduces a separate
  `@Singleton` registry (below) instead of reusing it directly.
- Manifest already declares `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `INTERNET`, `POST_NOTIFICATIONS` — no new
  dangerous permissions needed, just a new `<service>` entry.
- Settings UI lives in `ui/home/SettingsDialog.kt`.
- Hilt singletons are provided in `di/AppModule.kt`
  (`@InstallIn(SingletonComponent::class)`); DataStore-backed prefs already
  follow a `Serializer<T>` + `DataStore<T>` pattern there (see
  `provideSettingsSerializer` / `provideSettingsDataStore`).
- No existing HTTP server dependency in the app; `kotlinx.serialization.json`
  is already a dependency (used for request/response models).

## Architecture

Four new pieces, one hook into existing code:

```
                    ┌─────────────────────────┐
   ARTEMIS (PC) ───▶│  LocalApiForegroundService │
   POST /v1/chat/... │  (Ktor CIO, 0.0.0.0:port) │
                    └────────────┬────────────┘
                                 │ reads
                                 ▼
                    ┌─────────────────────────┐
                    │   ActiveModelRegistry    │◀── setActive()/clear() ──┐
                    │  (Singleton StateFlow)   │                          │
                    └────────────┬────────────┘                          │
                                 │ Model + taskId                        │
                                 ▼                                       │
                    ┌─────────────────────────┐              ┌──────────┴─────────┐
                    │   LlmChatModelHelper     │              │  LlmChatViewModel   │
                    │ (existing, unchanged)    │              │ (existing, +1 hook) │
                    └─────────────────────────┘              └────────────────────┘
```

### 1. `ActiveModelRegistry` (new)

`com.google.ai.edge.gallery.apiserver.ActiveModelRegistry`, `@Singleton`,
provided from `AppModule.kt`.

```kotlin
data class ActiveChatModel(val model: Model, val taskId: String, val supportsImage: Boolean)

@Singleton
class ActiveModelRegistry @Inject constructor() {
  private val _current = MutableStateFlow<ActiveChatModel?>(null)
  val current: StateFlow<ActiveChatModel?> = _current.asStateFlow()

  fun setActive(model: Model, taskId: String, supportsImage: Boolean) {
    _current.value = ActiveChatModel(model, taskId, supportsImage)
  }

  fun clear(model: Model) {
    if (_current.value?.model?.name == model.name) _current.value = null
  }
}
```

### 2. Hook into `LlmChatViewModel.kt` (existing file, +2 call sites)

- After a successful `LlmChatModelHelper.initialize(...)` callback
  (`onDone("")` with no error), call
  `activeModelRegistry.setActive(model, taskId, supportImage)`.
- In the model cleanup path (wherever `LlmChatModelHelper.cleanUp` is
  invoked for that model, e.g. switching models or leaving the chat
  screen), call `activeModelRegistry.clear(model)`.

`LlmChatViewModel` needs `ActiveModelRegistry` field-injected (it's already
Hilt-constructed).

### 3. `LocalApiForegroundService` (new)

`com.google.ai.edge.gallery.apiserver.LocalApiForegroundService`, extends
`Service`, `@AndroidEntryPoint`, field-injects `ActiveModelRegistry` and the
new settings DataStore (below).

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

### 4. Settings & auth token (new)

A small new `DataStore<LocalApiServerSettings>` (own proto/serializer,
following the existing pattern in `AppModule.kt`) holding:

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

Reconciliation (per request):
1. `LlmChatModelHelper.resetConversation(model, ..., initialMessages = req.messages.dropLast(1).map(::toLiteRtMessage))`
2. Decode any `image_url` data-URIs in the last message to `Bitmap`.
3. `LlmChatModelHelper.runInference(model, input = lastMessage.text, images = decodedImages, resultListener = { text, done, _ -> accumulate })`.
4. On `done`, return the accumulated text as the response body.

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
| No model currently loaded (`ActiveModelRegistry.current == null`) | `503 {"error":{"message":"No model loaded in Gallery. Open the app and load a model first."}}` |
| Malformed JSON / missing `messages` | `400 {"error":{"message":"..."}}` |
| Inference engine error (`onError` callback) | `500 {"error":{"message":"<underlying message>"}}` |
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
