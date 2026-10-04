# Live Video Demo — Build & Test Guide

## Prerequisites

- Cloudtop workspace with `hackathon_demo` CitC client
- Pixel 10 connected via [Pontis](https://pontis.corp.google.com) (adb at `localhost:33395`)
- Device has AICore with Gemma 4 E2B model downloaded

## Build

```bash
cd /google/src/cloud/xuejianr/hackathon_demo/google3

blaze build -c opt \
  --config=android_arm64-v8a \
  --define=libunwind=true \
  --define=xnnpack_use_latest_ops=true \
  --define=keep_litertlm_symbols=true \
  //third_party/ai_edge_gallery/Android/src/app/src/main:ai_edge_gallery_app_experimental
```

## Install & Launch

```bash
# Check device connection
pontis status
adb -s localhost:33395 devices

# If disconnected, reconnect at https://pontis.corp.google.com
# then verify: adb -s localhost:33395 devices

# Install
adb -s localhost:33395 install -r \
  blaze-bin/third_party/ai_edge_gallery/Android/src/app/src/main/ai_edge_gallery_app_experimental.apk

# Grant microphone permission (required for voice I/O)
adb -s localhost:33395 shell pm grant com.google.ai.edge.gallery.dev android.permission.RECORD_AUDIO

# Launch
adb -s localhost:33395 shell am start \
  -n com.google.ai.edge.gallery.dev/com.google.ai.edge.gallery.MainActivity
```

## Test the 3 Modes

Navigate to **Live Video** tab, select **Gemma 4 E2B** (or E4B).

### 1. Chat (Conversational Visual Assistant)

- Toggle **Voice** chip ON in the top bar
- Tap the **mic button** (bottom left) and speak a question about what the camera sees
- Model responds with text + spoken TTS
- Toggle **Auto** chip for continuous listen → infer → speak → listen loop
- Multi-turn: ask follow-up questions without restarting

### 2. Caption (Streaming Captioning)

- Switch to **Caption** mode
- Tap **Auto: OFF** chip to start → streams captions every ~1.5s
- Each inference is independent (no chat history accumulation)
- Tap again to stop

### 3. Fingers (Real-Time Counting)

- Switch to **Fingers** mode
- Tap **Start** → hold up different numbers of fingers
- Updates every ~1s with temperature=0.0 (deterministic)
- Each inference is independent with conversation reset

## Architecture

```
Camera (384x384, SL70) → FrameBuffer (64 frames)
    ↓
GemmaGaze TFLite (frame selection, optional)
    ↓
Bitmap scaling (per-mode imageMaxPx)
    ↓
AICore (ML Kit GenerativeModel, Gemma 4 E2B/E4B)
    ↓
Streaming tokens → UI + sentence-buffered TTS
```

### Key Design Decisions

| Decision | Rationale |
|---|---|
| SL70 baseline (384x384) | 70 soft tokens per image, fastest vision processing |
| `resetConversation()` before Caption/Fingers | Prevents chat history contamination (biased answers, growing latency) |
| Per-mode temperature | 0.0 for Fingers (deterministic), 0.3 for Caption, 0.8 for Chat |
| Sentence-buffered TTS | Accumulate tokens until `.!?\n`, then speak — natural phrasing |
| `maybeRestartListening()` | Single idempotent function called from all state transitions — prevents stuck states in voice loop |

### GemmaGaze Integration

GemmaGaze (2.8M param Conv3D model, `gemmagaze.tflite`) runs frame-level selection from the 64-frame rolling buffer. Currently selects the most visually important frame (1-of-8 sampling). The GemmaGaze overlay can be toggled to visualize cell-level attention scores.

**Note**: This is frame-level selection, not spatial token dropping. Real spatial dropping (pre-ViT grid cell selection with split positions) requires LiteRT-LM changes — see `demo_plans.md` in the infinite-gemma repo.

## Files

| File | Purpose |
|---|---|
| `LiveVideoScreen.kt` | Compose UI: camera, top bar chips, voice status, chat history, input bar |
| `LiveVideoViewModel.kt` | State management, inference, SpeechRecognizer, TTS, GemmaGaze |
| `LiveVideoConfigs.kt` | VideoMode enum (3 modes), SL70/SL280 constants |
| `LiveVideoTask.kt` | CustomTask registration, GemmaGaze init via LiveVideoGazeHolder |
| `GemmaGazeInterpreter.kt` | TFLite wrapper for gemmagaze.tflite |
| `FrameBuffer.kt` | 64-frame rolling buffer with sampling and sampleLatest() |
| `CameraController.kt` | CameraX zoom/capture control |
| `CameraEffects.kt` | Shutter flash, mode banner, crosshair overlays |

## CL

http://cl/922386678
