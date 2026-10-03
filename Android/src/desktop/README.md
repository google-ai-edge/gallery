# Google AI Edge Gallery (Windows Desktop Port)

This module provides Windows Desktop support for [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) using **Compose Multiplatform (Desktop)** and **LiteRT-LM**.

## Architecture Overview

The desktop module compiles alongside upstream Android source code (`app/src/main/java`) on JVM 21:
- **UI Framework:** JetBrains Compose Multiplatform Desktop rendering with Skiko.
- **Inference Runtime:** LiteRT-LM (`litertlm-jvm`) with native Windows libraries and automated GPU-to-CPU fallback handling.
- **Agent Skills:** Full JavaFX WebView integration embedded in Compose via `SwingPanel` for executing skill scripts and visual cards.
- **Multimedia & IO:** Audio recording/playback using standard Java Sound (`javax.sound.sampled`), image parsing via `BufferedImage`, and native Windows file dialogs.

## Prerequisites

- **OS:** Windows 10 / Windows 11 (64-bit)
- **JDK:** OpenJDK 21 or higher
- **DirectX:** Direct3D 12 (falls back automatically to CPU if GPU initialization is unavailable)

## Quick Start

### 1. Run in Development Mode
From the `Android/src` directory:
```bash
./gradlew :desktop:run
```

### 2. Package Standalone MSI Installer
To produce a standalone Windows MSI installer:
```bash
./gradlew :desktop:packageMsi
```
The installer is generated under `desktop/build/compose/binaries/main/msi/`.

## Features Status

| Feature | Windows Support | Implementation |
| :--- | :---: | :--- |
| **AI Chat (Text-to-Text)** | ✅ Supported | Local LiteRT-LM CPU inference |
| **Gemma 4 Models** | ✅ Supported | Full support for Gemma 4 & LiteRT models |
| **Agent Skills** | ✅ Supported | JavaFX WebView with JavaScript bindings |
| **Model Downloads** | ✅ Supported | Direct download to `%LOCALAPPDATA%` |
| **Audio Scribe** | ✅ Supported | `javax.sound.sampled` recording & playback |
| **Ask Image** | ✅ Supported | Native file picker & `BufferedImage` |
| **Hugging Face OAuth** | ✅ Supported | Token authentication workflow |
| **WebGPU / Direct3D 12** | 🔄 Auto Fallback | Tries GPU, automatically falls back to CPU |
