# Neural Forge 🔥

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Transform Your Android Device Into a Mobile AI Powerhouse**

Neural Forge is a revolutionary mobile application that brings the full power of cutting-edge AI models directly to your Android device. Running entirely on-device with support for multiple model formats (TensorFlow Lite, ONNX, LiteRT-LM), Neural Forge transcends traditional limitations to give you unprecedented control over AI on your phone.

Built on the foundation of Google's AI Edge Gallery, Neural Forge adds universal model format support, intelligent download management, advanced optimization, and a vision for the future of mobile AI.

## 📲 Installation

Build and install Neural Forge from source:

```bash
cd Android/src
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> [!NOTE]
> Neural Forge is a fork of Google's AI Edge Gallery with enhanced capabilities. It requires Android 12 (API 31) or higher.

<img width="480" alt="01" src="https://github.com/user-attachments/assets/2a60c8d0-ef4e-4040-a948-fa73f6a622b4" />
<img width="480" alt="02" src="https://github.com/user-attachments/assets/d155d458-b822-415d-9252-7e825fe8c9c0" />
<img width="480" alt="03" src="https://github.com/user-attachments/assets/1977af6f-ee7e-41b3-aac1-a642c66c0058" />
<img width="480" alt="04" src="https://github.com/user-attachments/assets/a48be969-f57e-4497-9ecf-8feb35f2ba71" />
<img width="480" alt="05" src="https://github.com/user-attachments/assets/2a9679ea-f191-4ffd-87db-6726f7c1057d" />

## ✨ Neural Forge Features

### Phase 1 - Foundation (Current)
*   **🔥 Universal Format Support:** Load and run TensorFlow Lite, ONNX, and LiteRT-LM models natively
*   **📥 Enhanced Download Manager:** Chunked downloads with resume capability and intelligent progress tracking
*   **🎯 Smart Format Detection:** Automatic model format detection from files and magic bytes
*   **⚡ ONNX Runtime Integration:** Full ONNX model support with CPU optimizations
*   **📱 Run Locally, Fully Offline:** All processing happens on-device, no internet needed after download
*   **🤖 Choose Your Model:** Switch between different models from Hugging Face and compare performance
*   **🖼️ Image Understanding:** Upload images and ask questions about them
*   **🎙️ Audio Processing:** Transcribe and translate audio clips
*   **💬 AI Chat:** Multi-turn conversations with LLMs
*   **📊 Performance Insights:** Real-time benchmarks (TTFT, decode speed, latency)
*   **🧩 Bring Your Own Model:** Test local models in multiple formats

### Phase 2 - Expansion (Coming Soon)
*   **🔄 Model Conversion Pipeline:** Convert between formats (ONNX ↔ TFLite, PyTorch → ONNX)
*   **🎨 Enhanced UI:** Beautiful, intuitive interface with Lottie animations
*   **📦 Split Model Support:** Download and merge large models in chunks
*   **⚙️ Advanced Optimizations:** Quantization, pruning, and layer fusion
*   **🔍 Model Marketplace:** Browse and discover models with advanced filtering

### Phase 3 - Advanced (Future)
*   **🔗 Model Chaining:** Create complex pipelines by chaining models together
*   **📡 P2P Model Sharing:** Share models with nearby devices via Wi-Fi Direct
*   **🎓 On-Device Fine-Tuning:** Adapt models to your specific needs using LoRA/QLoRA
*   **🎤 Voice Commands:** Control model operations with voice
*   **🔋 Battery-Aware Execution:** Intelligent scheduling based on battery level
*   **🌐 Cross-App Integration:** Share models with other applications

## 🏁 Get Started in Minutes!

1. **Check OS Requirement**: Android 12 and up
2.  **Download the App:**
    - Install the app from [Google Play](https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery).
    - For users without Google Play access: install the apk from the [**latest release**](https://github.com/google-ai-edge/gallery/releases/latest/)
3.  **Install & Explore:** For detailed installation instructions (including for corporate devices) and a full user guide, head over to our [**Project Wiki**](https://github.com/google-ai-edge/gallery/wiki)!

## 🛠️ Technology Stack

*   **Google AI Edge & LiteRT:** Core APIs and lightweight runtime for on-device ML
*   **ONNX Runtime:** Microsoft's cross-platform inference engine for ONNX models
*   **TensorFlow Lite:** Google's mobile ML framework with GPU acceleration
*   **OkHttp:** Advanced HTTP client for robust model downloads
*   **Jetpack Compose:** Modern declarative UI framework
*   **Kotlin Coroutines:** Asynchronous programming for smooth performance
*   **Hilt/Dagger:** Dependency injection for clean architecture
*   **Hugging Face Integration:** Model discovery and download from the largest ML model hub

## 🏗️ Architecture

Neural Forge follows a modular architecture:

```
com.neuralforge.mobile/
├── core/              # Central engine and format detection
├── downloader/        # Enhanced download management
├── converter/         # Model format conversion (Phase 2+)
├── execution/         # Runtime inference engines (ONNX, TFLite)
└── ui/               # Jetpack Compose UI components
```

See [NEURAL_FORGE.md](NEURAL_FORGE.md) for detailed architecture documentation.

## ⌨️ Development

Check out the [development notes](DEVELOPMENT.md) for instructions about how to build the app locally.

## 🤝 Feedback

This is an **experimental Beta release**, and your input is crucial!

*   🐞 **Found a bug?** [Report it here!](https://github.com/google-ai-edge/gallery/issues/new?assignees=&labels=bug&template=bug_report.md&title=%5BBUG%5D)
*   💡 **Have an idea?** [Suggest a feature!](https://github.com/google-ai-edge/gallery/issues/new?assignees=&labels=enhancement&template=feature_request.md&title=%5BFEATURE%5D)

## 📄 License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.

## 🔗 Useful Links

*   [**Project Wiki (Detailed Guides)**](https://github.com/google-ai-edge/gallery/wiki)
*   [Hugging Face LiteRT Community](https://huggingface.co/litert-community)
*   [LLM Inference guide for Android](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
*   [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
*   [Google AI Edge Documentation](https://ai.google.dev/edge)
