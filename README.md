# Google AI Edge Gallery ✨

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/google-ai-edge/gallery)](https://github.com/google-ai-edge/gallery/releases)

**Explore, Experience, and Evaluate the Future of On-Device Generative AI with Google AI Edge.**

The Google AI Edge Gallery is an experimental app that puts the power of cutting-edge Generative AI models directly into your hands, running entirely on your Android *(available now)* and iOS *(coming soon)* devices. Dive into a world of creative and practical AI use cases, all running locally, without needing an internet connection once the model is loaded. Experiment with different models, chat, ask questions with images, explore prompts, and more!

**Overview**
<img width="1532" alt="Overview" src="https://github.com/user-attachments/assets/4f2702d7-91a0-4eb3-aa76-58bc8e7089c6" />

**Ask Image**
<img width="1532" alt="Ask Image" src="https://github.com/user-attachments/assets/e2b5b41b-fed0-4a7c-9547-2abb1c10962c" />

**Prompt Lab**
<img width="1532" alt="Prompt Lab" src="https://github.com/user-attachments/assets/22e459d0-0365-4a92-8570-fb59d4d1e320" />

**AI Chat**
<img width="1532" alt="AI Chat" src="https://github.com/user-attachments/assets/edaa4f89-237a-4b84-b647-b3c4631f09dc" />

## 🔌 Toggle Server

The "Toggle Server" feature runs a local HTTP server on your mobile device that allows you to interact with the on-device AI models from your laptop using `curl`, with all communication tunneled exclusively over a USB cable connection.

### Usage

1.  **Enable USB Debugging**:
    *  Follow these [steps](https://developer.android.com/studio/debug/dev-options) to enable ADB port forwarding between your device and computer.

2.  **Connect Device to Computer & Enable Port Forwarding**:
    ```bash
    adb -d forward tcp:8080 tcp:8080
    ```

3.  **Start the Server in the App**:
    *   Navigate to the "Toggle Server" screen.
    *   Tap the "Start In-App Server" button.

4.  **Send Requests with `curl`**:
    *   **Prompt only**:
        ```bash
        curl -X POST -F "prompt=Hello, world!" http://localhost:8080
        ```
    *   **Image and prompt**:
        ```bash
        curl -X POST -F "prompt=What is in this image?" -F "image=@/path/to/your/image.jpg" http://localhost:8080
        ```

## ✨ Core Features

*   **📱 Run Locally, Fully Offline:** Experience the magic of GenAI without an internet connection. All processing happens directly on your device.
*   **🤖 Choose Your Model:** Easily switch between different models from Hugging Face and compare their performance.
*   **🖼️ Ask Image:** Upload an image and ask questions about it. Get descriptions, solve problems, or identify objects.
*   **✍️ Prompt Lab:** Summarize, rewrite, generate code, or use freeform prompts to explore single-turn LLM use cases.
*   **💬 AI Chat:** Engage in multi-turn conversations.
*   **📊 Performance Insights:** Real-time benchmarks (TTFT, decode speed, latency).
*   **🧩 Bring Your Own Model:** Test your local LiteRT `.task` models.
*   **🔗 Developer Resources:** Quick links to model cards and source code.

## 🏁 Get Started in Minutes!

1.  **Download the App:** Grab the [**latest APK**](https://github.com/google-ai-edge/gallery/releases/latest/download/ai-edge-gallery.apk).
2.  **Install & Explore:** For detailed installation instructions (including for corporate devices) and a full user guide, head over to our [**Project Wiki**](https://github.com/google-ai-edge/gallery/wiki)!

## 🛠️ Technology Highlights

*   **Google AI Edge:** Core APIs and tools for on-device ML.
*   **LiteRT:** Lightweight runtime for optimized model execution.
*   **LLM Inference API:** Powering on-device Large Language Models.
*   **Hugging Face Integration:** For model discovery and download.

## 🤝 Feedback

This is an **experimental Alpha release**, and your input is crucial!

*   🐞 **Found a bug?** [Report it here!](https://github.com/google-ai-edge/gallery/issues/new?assignees=&labels=bug&template=bug_report.md&title=%5BBUG%5D)
*   💡 **Have an idea?** [Suggest a feature!](https://github.com/google-ai-edge/gallery/issues/new?assignees=&labels=enhancement&template=feature_request.md&title=%5BFEATURE%5D)

## 📄 License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.

## 🔗 Useful Links

*   [**Project Wiki (Detailed Guides)**](https://github.com/google-ai-edge/gallery/wiki)
*   [Hugging Face LiteRT Community](https://huggingface.co/litert-community)
*   [LLM Inference guide for Android](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
*   [Google AI Edge Documentation](https://ai.google.dev/edge)
