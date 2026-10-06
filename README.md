# Google AI Edge Gallery ✨

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![GitHub release (latest by date)](https://img.shields.io/github/v/release/google-ai-edge/gallery)](https://github.com/google-ai-edge/gallery/releases)

**Explore, Experience, and Evaluate the Future of On-Device Generative AI with Google AI Edge.**

AI Edge Gallery is the premier destination for running the world's most powerful open-source Large Language Models (LLMs) on your mobile device. Experience high-performance Generative AI directly on your hardware—fully offline, private, and lightning-fast.

**Now Featuring: [EmbeddingGemma 2](https://developers.googleblog.com/google-ai-edge-with-embeddinggemma-2/)**

The latest version brings official support for the newly released EmbeddingGemma 2 model. With this release, we bring cutting edge, multimodal semantic search running 100% on your device. Try out these new capabilities in our new features Instant Media Search and Video Moment Finder.


| **Install the app today from Google Play** | **Install the app today from App Store** | **Download for macOS** |
| :--- | :--- | :--- |
| <a href='https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery'><img alt='Get it on Google Play' height="120" src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png'/></a> | <a href="https://apps.apple.com/us/app/google-ai-edge-gallery/id6749645337?itscg=30200&itsct=apps_box_badge&mttnsubad=6749645337" style="display: inline-block;"> <img src="https://toolbox.marketingtools.apple.com/api/v2/badges/download-on-the-app-store/black/en-us?releaseDate=1771977600" alt="Download on the App Store" style="width: 244px; height: 88px; vertical-align: middle; object-fit: contain;" /></a> | <a href="https://dl.google.com/google-ai-edge-gallery/macos/dmg/GoogleAIEdgeGallery-0.1.0.dmg"><img alt='Download for macOS' width="257" height="97" src="https://github.com/user-attachments/assets/29c70795-93b3-4e8b-8752-0cad4e413182" /></a> |

For users without Google Play access, install the apk from the [**latest release**](https://github.com/google-ai-edge/gallery/releases/latest/)


## App Preview

<img width="480" alt="01" src="https://github.com/user-attachments/assets/d6edaadf-42be-4c87-a902-3fc96b09efde" />
<img width="480" alt="02" src="https://github.com/user-attachments/assets/e597ae4c-8d32-4428-a267-8ac8f7e41a61" />
<img width="480" alt="03" src="https://github.com/user-attachments/assets/c05ccc3b-628e-47d1-a635-8516fced08fa" />
<img width="480" alt="04" src="https://github.com/user-attachments/assets/fc86b3f8-1eae-4199-be36-43b9f51c4f0b" />
<img width="480" alt="05" src="https://github.com/user-attachments/assets/a22b5e2c-c456-483e-bf52-fe30d64d64e9" />
<img width="480" alt="06" src="https://github.com/user-attachments/assets/4d0cf0ca-6806-40e2-990e-dc7d02d31b4e" />
<img width="480" alt="07" src="https://github.com/user-attachments/assets/3390ddc1-7e38-4f03-9df3-aa9d1fe05df0" />
<img width="480" alt="08" src="https://github.com/user-attachments/assets/222b66fd-6d39-4680-ab2c-cd695ceaeba4" />

## ✨ Core Features

* **Agent Skills**: Transform your LLM from a conversationalist into a proactive assistant. Use the Agent Skills tile to augment model capabilities with tools like Wikipedia for fact-grounding, interactive maps, and rich visual summary cards. You can even load modular skills from a URL or browse community contributions on GitHub Discussions.

* **AI Chat with Thinking Mode**: Engage in fluid, multi-turn conversations and toggle the new Thinking Mode to peek "under the hood." This feature allows you to see the model’s step-by-step reasoning process, which is perfect for understanding complex problem-solving. Note: Thinking Mode currently works with supported models, starting with the Gemma 4 family.

* **Instant Media Search**: Explore your photo library (or built-in sample media) with fast, search-as-you-type natural language queries and image-to-image similarity matching—with zero cloud roundtrips.

* **Video Moment Finder**: Pinpoint exact moments inside videos using natural language descriptions. Jump straight to highlighted timestamps on an interactive timeline, trim clips, and save or export your favorite moments

* **Ask Image**: Use multimodal power to identify objects, solve visual puzzles, or get detailed descriptions using your device’s camera or photo gallery.

* **Audio Scribe**: Transcribe and translate voice recordings into text in real-time using high-efficiency on-device language models.

* **Prompt Lab**: A dedicated workspace to test different prompts and single-turn use cases with granular control over model parameters like temperature and top-k.

* **Mobile Actions**: Unlock offline device controls and automated tasks powered entirely by a finetune of FunctionGemma 270m.

* **Tiny Garden**: A fun, experimental mini-game that uses natural language to plant and harvest a virtual garden using a finetune of FunctionGemma 270m.

* **Model Management & Benchmark**: Gallery is a flexible sandbox for a wide variety of open-source models. Easily download models from the list or load your own custom models. Manage your model library effortlessly and run benchmark tests to understand exactly how each model performs on your specific hardware.

* **100% On-Device Privacy**: All model inferences happen directly on your device hardware. No internet is required, ensuring total privacy for your prompts, images, and sensitive data.

## 🏁 Get Started in Minutes!

1. **Check OS Requirement**: Android 12 and up, and iOS 17 and up.
2.  **Download the App:**
    - Install the app from [Google Play](https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery) or [App Store](https://apps.apple.com/us/app/google-ai-edge-gallery/id6749645337).
    - For users without Google Play access: install the apk from the [**latest release**](https://github.com/google-ai-edge/gallery/releases/latest/)
3.  **Install & Explore:** For detailed installation instructions (including for corporate devices) and a full user guide, head over to our [**Project Wiki**](https://github.com/google-ai-edge/gallery/wiki)!

## 🛠️ Technology Highlights

*   **Google AI Edge:** Core APIs and tools for on-device ML.
*   **LiteRT:** Lightweight runtime for optimized model execution.
*   **Hugging Face Integration:** For model discovery and download.

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
*   [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
*   [Google AI Edge Documentation](https://ai.google.dev/edge)
