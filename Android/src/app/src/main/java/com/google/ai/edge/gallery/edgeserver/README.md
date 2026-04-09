# Edge Server

On-device OpenAI-compatible HTTP API server for AI Edge Gallery.

## What is it?

Edge Server exposes the loaded on-device LLM as a local REST API on
`http://127.0.0.1:8888/v1`, making it accessible to **any OpenAI-compatible
client** — curl, Open WebUI, custom scripts, IDE plugins, etc.

All inference runs locally on the device GPU via LiteRT-LM. No data leaves the
device. No API keys needed.

## Endpoints

| Method | Path                     | Description                          |
|--------|--------------------------|--------------------------------------|
| GET    | `/health`                | Server status & loaded model info    |
| GET    | `/v1/models`             | List currently loaded models         |
| POST   | `/v1/chat/completions`   | Chat completions (streaming & sync)  |

## Quick Start

1. Open AI Edge Gallery and load a model (e.g., Gemma 4 E4B)
2. Open the drawer → tap **Edge Server** → toggle ON
3. Use any OpenAI-compatible client:

```bash
curl http://127.0.0.1:8888/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "auto",
    "messages": [{"role": "user", "content": "Hello!"}],
    "stream": false
  }'
```

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Any OpenAI Client (curl, Open WebUI, etc.)     │
└──────────────────────┬──────────────────────────┘
                       │ HTTP (localhost:8888)
┌──────────────────────▼──────────────────────────┐
│  EdgeServer (NanoHTTPD)                          │
│  ├── /v1/chat/completions  →  Gemma prompt fmt  │
│  ├── /v1/models            →  model list        │
│  └── /health               →  status check      │
├─────────────────────────────────────────────────┤
│  EdgeServerManager (singleton)                   │
│  └── Binds model + manages lifecycle             │
├─────────────────────────────────────────────────┤
│  EdgeServerService (ForegroundService)           │
│  └── Keeps server alive in background            │
├─────────────────────────────────────────────────┤
│  LlmModelHelper.runInference()                   │
│  └── LiteRT-LM GPU-accelerated inference         │
└─────────────────────────────────────────────────┘
```

## Features

- **OpenAI-compatible API**: Works with any client that speaks the OpenAI chat
  completions protocol.
- **Streaming (SSE)**: Real-time token-by-token responses via Server-Sent
  Events when `stream: true`.
- **Multi-modal content**: Handles OpenAI's content formats (string, array of
  `{type, text}` objects, single object).
- **Prompt truncation**: Automatically truncates oversized prompts to fit the
  model's context window (configurable via `maxPromptChars`).
- **Background keep-alive**: Runs as a Foreground Service so the server stays
  accessible even when the app is backgrounded.
- **Auto model discovery**: When a request arrives with no model bound, the
  server can automatically find and initialize a downloaded model.

## Files

| File                    | Purpose                                    |
|-------------------------|--------------------------------------------|
| `EdgeServer.kt`         | HTTP server + OpenAI API implementation    |
| `EdgeServerService.kt`  | Android Foreground Service                 |
| `EdgeServerManager.kt`  | Singleton lifecycle & state management     |
| `EdgeServerScreen.kt`   | Jetpack Compose control panel UI           |

## Dependencies

- [NanoHTTPD 2.3.1](https://github.com/NanoHttpd/nanohttpd) — Lightweight
  embeddable HTTP server (single JAR, zero transitive deps).
