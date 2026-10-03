# Google AI Edge Gallery (Windows Community Port)

This is an experimental Windows desktop port of the [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) Android application, built using **Compose Multiplatform** and **LiteRT-LM**.

## Features & Status

Currently, this is a proof-of-concept port to bring local LLM inference to Windows using the same UI and Compose logic as the Android app.

### 🟢 What Works
* **AI Chat (Text-to-Text)**: Standard text-based chats with Gemma and other LiteRT models work successfully on the CPU backend.
* **Agent Skills (WebViews)**: Fully functional. Powered by JavaFX `WebView` embedded inside Compose Multiplatform via `SwingPanel`. Agent javascript execution works.
* **Model Downloads**: In-app downloading and extraction of `.litertlm` models directly from the UI works.
* **Audio Scribe (Voice Input)**: Fully functional. Uses `javax.sound.sampled` for microphone recording and playback.
* **Ask Image (Camera/Gallery)**: Fully functional. Uses `java.awt.image.BufferedImage` and `java.awt.FileDialog` for native file picking and rendering.
* **HuggingFace OAuth Integration**: Login and token retrieval for gated models.
* **Compose UI**: The Material 3 UI renders correctly on desktop using Skiko.

### 🔴 Known Limitations
* **WebGPU / DirectX12 Acceleration**: The GPU backend currently crashes on some Windows machines due to DirectX12 device creation failures (`dxil.dll` missing/incompatible). The app gracefully catches this and falls back to CPU inference.

## Building and Running

You need JDK 17+ installed.

### Run in Development Mode
```bash
cd src
./gradlew :desktop:run
```

### Build Windows Installer (.msi)
```bash
cd src
./gradlew :desktop:packageMsi
```
The MSI will be generated at `src/desktop/build3/compose/binaries/main/msi/`.

## Architecture
This port maps Android app source directories (`app/src/main/java`) directly into a JVM desktop module (`desktop/src/main/kotlin`). Missing Android framework classes (like `Context`, `Intent`, `WebView`, etc.) are provided via stub shims in the desktop module to satisfy the compiler.

## Contributing
Contributions are welcome, especially for:
* Implementing a real WebView (e.g., JCEF/KCEF) to enable Agent Skills.
* Replacing fake `android.*` shims with proper Java AWT/Swing or pure Kotlin implementations.
* Fixing WebGPU/DirectX12 integration for hardware acceleration.
