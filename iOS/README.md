# Google AI Edge Gallery — iOS

[![App Store](https://img.shields.io/badge/App_Store-Available-blue?logo=apple)](https://apps.apple.com/us/app/google-ai-edge-gallery/id6749645337)

The iOS version of AI Edge Gallery is available on the **App Store**. It runs open-source
LLMs fully on-device using the
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) runtime — no cloud, no data sent
off your device.

> **Note:** The iOS app source code is not currently open-sourced. This folder documents
> the iOS-specific configuration that lives in this repository, primarily the model
> allowlist.

---

## Requirements

| Requirement | Minimum |
|-------------|---------|
| iOS version | 17.0 or later |
| Device RAM  | 4 GB (6–8 GB recommended for larger models) |
| Storage     | Up to 5 GB free space (depends on models downloaded) |

Supported devices include iPhone 12 and later (A14 Bionic chip or newer recommended for
GPU-accelerated inference).

---

## Installation

Download from the App Store:

<a href="https://apps.apple.com/us/app/google-ai-edge-gallery/id6749645337">
  <img src="https://toolbox.marketingtools.apple.com/api/v2/badges/download-on-the-app-store/black/en-us?releaseDate=1771977600"
       alt="Download on the App Store" height="60" />
</a>

---

## Supported Models

The iOS app uses the model allowlist defined in
[`model_allowlists/ios_1_0_0.json`](../model_allowlists/ios_1_0_0.json).

| Model | Size | Min RAM | Modalities | Best for |
|-------|------|---------|------------|----------|
| **Gemma-3n-E2B-it** | 3.4 GB | 6 GB | Text, Vision, Audio | Image & audio tasks |
| **Gemma-3n-E4B-it** | 4.6 GB | 8 GB | Text, Vision, Audio | High-quality multimodal |
| **Gemma3-1B-IT** | 584 MB | 4 GB | Text only | Fast chat on any device |

All models use 4-bit quantization (`.litertlm` format) and are downloaded directly from
Hugging Face on first use.

### Default inference parameters

| Parameter | Value |
|-----------|-------|
| Top-K | 64 |
| Top-P | 0.95 |
| Temperature | 1.0 |
| Max tokens | 4096 |

Gemma-3n models use GPU acceleration by default; Gemma3-1B-IT runs on CPU for maximum
compatibility.

---

## Model Allowlist Format

The file `model_allowlists/ios_1_0_0.json` controls which models appear in the iOS app.
Each entry has the following fields:

```jsonc
{
  "name": "Display name shown in the app",
  "modelId": "huggingface-org/repo-name",
  "modelFile": "filename.litertlm",
  "description": "Markdown description shown in the app",
  "sizeInBytes": 3388604416,
  "minDeviceMemoryInGb": 6,
  "commitHash": "exact HuggingFace commit hash for reproducibility",
  "llmSupportImage": true,   // optional — omit if false
  "llmSupportAudio": true,   // optional — omit if false
  "defaultConfig": {
    "topK": 64,
    "topP": 0.95,
    "temperature": 1.0,
    "maxTokens": 4096,
    "accelerators": "gpu"    // "gpu" or "cpu"
  },
  "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_ask_image", "llm_ask_audio"],
  "bestForTaskTypes": ["llm_ask_image"]  // optional — highlights recommended use cases
}
```

### Supported task types

| Task type | Description |
|-----------|-------------|
| `llm_chat` | Multi-turn AI Chat |
| `llm_prompt_lab` | Prompt Lab (single-turn with parameter control) |
| `llm_ask_image` | Ask Image (vision input) |
| `llm_ask_audio` | Audio Scribe (audio transcription/translation) |

---

## Proposing a New Model for iOS

To suggest adding a new model to the iOS allowlist:

1. Verify the model is available on Hugging Face in `.litertlm` format and compatible
   with [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM).
2. Note the exact `commitHash` from the Hugging Face model page (Files tab → commit
   history) to ensure reproducibility.
3. Measure or find the `sizeInBytes` and determine `minDeviceMemoryInGb` based on the
   model's quantized size (typically 1.5–2× the file size as a safe minimum).
4. Open a Pull Request editing `model_allowlists/ios_1_0_0.json` with your new entry.
5. In the PR description, include:
   - Link to the Hugging Face model page
   - Device(s) you tested on and iOS version
   - Benchmark results if available (tokens/second, memory usage)

---

## Feature Parity with Android

| Feature | Android | iOS |
|---------|---------|-----|
| AI Chat | ✅ | ✅ |
| Thinking Mode | ✅ | ✅ (Gemma 4 family) |
| Ask Image | ✅ | ✅ |
| Audio Scribe | ✅ | ✅ |
| Prompt Lab | ✅ | ✅ |
| Agent Skills | ✅ | Planned |
| Mobile Actions | ✅ | Planned |
| Tiny Garden | ✅ | Planned |
| Custom model loading | ✅ | Planned |

---

## Reporting iOS Bugs

Please use the bug report template and include:

- **iOS version** (Settings → General → About)
- **Device model** (e.g. iPhone 15 Pro)
- **App version** (shown in the app's settings screen)
- **Model being used** when the issue occurred
- Steps to reproduce

[Open a bug report](https://github.com/google-ai-edge/gallery/issues/new?assignees=&labels=bug,ios&template=bug_report.md&title=%5BBUG%5D%5BiOS%5D)

---

## Useful Links

- [App Store listing](https://apps.apple.com/us/app/google-ai-edge-gallery/id6749645337)
- [LiteRT-LM runtime](https://github.com/google-ai-edge/LiteRT-LM)
- [Google AI Edge documentation](https://ai.google.dev/edge)
- [Hugging Face LiteRT community](https://huggingface.co/litert-community)
- [Android build instructions](../DEVELOPMENT.md)
