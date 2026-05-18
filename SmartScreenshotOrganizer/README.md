# Smart Screenshot Organizer

An offline-first Android app that automatically organizes screenshots using on-device AI inference.

## Features

- **Automatic screenshot detection** via MediaStore ContentObserver + WorkManager
- **AI-powered analysis** with on-device Gemini Nano (AI Core) or local HTTP LLM server
- **OCR text extraction** using ML Kit Text Recognition (fully offline)
- **Intelligent classification** into 11 categories (Shopping, Receipts, Chat, Banking, etc.)
- **Full-text search** powered by Room FTS4 across titles, summaries, and extracted text
- **Category filtering** with visual chips
- **Material 3** design with dark mode and Dynamic Color support
- **Privacy-first**: all processing happens on-device, no cloud APIs, no telemetry

## Architecture

```
UI (Jetpack Compose) → ViewModel → Repository → Room DB
                                  ↗
WorkManager → OCR (ML Kit) → AI (InferenceProvider) → Repository
```

- **MVVM** with Hilt dependency injection
- **Clean Architecture** with interface-driven abstractions
- **Coroutines + Flow** for reactive data streams
- **WorkManager** for reliable background processing

## Prerequisites

- Android Studio Iguana (2024.1.1) or later
- JDK 17
- Android SDK 35
- Device or emulator running Android 9 (API 28) or higher

### For AI Core (Gemini Nano):
- Pixel 8 Pro, Pixel 9 series, or device with Tensor G3+ chip
- Android 14+ with AI Core module installed

### For HTTP Fallback:
- Local LLM server (e.g., [Ollama](https://ollama.com)) running on the same network
- Default endpoint: `http://localhost:11434/api/generate`

## Build & Run

```bash
# Clone and open in Android Studio
cd SmartScreenshotOrganizer

# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

## Project Structure

```
app/src/main/java/com/smartscreenshot/organizer/
├── SmartScreenshotApp.kt          # Hilt Application
├── MainActivity.kt                 # Single-activity host
├── di/                             # Hilt DI modules
├── data/
│   ├── db/                         # Room database, entities, DAO
│   ├── repository/                 # Data access abstraction
│   └── model/                      # Domain models
├── ai/                             # AI inference providers
│   ├── InferenceProvider.kt        # Interface
│   ├── AICoreInferenceProvider.kt  # Gemini Nano
│   ├── HttpInferenceProvider.kt    # Local HTTP server
│   └── InferenceProviderFactory.kt # Auto-selection
├── ocr/                            # Text extraction
├── worker/                         # Background processing
├── search/                         # Search engine
├── settings/                       # DataStore preferences
└── ui/                             # Jetpack Compose screens
    ├── theme/
    ├── navigation/
    ├── home/
    ├── detail/
    ├── settings/
    └── components/
```

## Configuration

### HTTP Inference Server

1. Install [Ollama](https://ollama.com) on your local machine
2. Pull a model: `ollama pull llama3.2`
3. Start the server: `ollama serve`
4. In the app Settings, set the endpoint to `http://<your-ip>:11434/api/generate`

### AI Core

AI Core is auto-detected on supported devices. No configuration needed.
The app falls back to HTTP when AI Core is unavailable.

## Database Schema

| Table | Purpose |
|---|---|
| `screenshots` | Screenshot metadata + AI analysis results |
| `screenshots_fts` | FTS4 virtual table for full-text search |

## Key Design Decisions

| Decision | Reason |
|---|---|
| Single module | Build simplicity for a focused app |
| Room FTS4 | Broad Android API support vs FTS5 |
| WorkManager | Battery-friendly, survives process death |
| ML Kit over Tesseract | Better accuracy, zero-config |
| DataStore | Type-safe, coroutine-native preferences |
| Moshi over Gson | Kotlin-first codegen, smaller footprint |

## Testing

```bash
# Unit tests
./gradlew test

# Instrumented tests
./gradlew connectedAndroidTest
```

### Test Coverage Targets

| Layer | Framework | Focus |
|---|---|---|
| ViewModels | JUnit + Turbine | State transitions |
| Repository | JUnit + Mockk | CRUD, search |
| AI Providers | MockWebServer | JSON parsing, errors |
| Database | Room testing | FTS queries, migrations |
| Workers | WorkManager testing | Enqueue, constraints |
| UI | Compose UI Test | Navigation, rendering |

## License

Apache License 2.0
