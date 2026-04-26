# Mobile Server

Welcome to **Mobile Server**! This project turns any modern Android phone into a completely free, 100% private, OpenAI-compatible local AI server.

> **Note:** This project was built by forking the incredible [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery). We took their powerful on-device inference engine and extended it with a built-in Ktor server and tunneling capabilities!

---

## 🚀 What Does This Do?

With this app, you can run Large Language Models (like Google's Gemma 4) directly on your phone's hardware, and securely expose that model to the internet using **Ngrok** or **Cloudflare Tunnels**. 

Because the server perfectly mimics the standard OpenAI API structure (`/v1/models`, `/v1/chat/completions`), you can seamlessly integrate it into existing AI tools.

**Use Cases:**
1. **Free AI Coding Assistant:** Point the [Continue.dev](https://continue.dev) VS Code extension to your phone's Cloudflare URL to get $0/mo local code autocomplete and chat.
2. **Private Smart Home Brain:** Connect it to Home Assistant for a completely private, offline-capable smart home AI.
3. **The "EdgeNode" Web App:** Use our provided HTML/JS frontend to chat with your phone from any web browser in the world.

---

## ✨ Features

- **OpenAI-Compatible API:** Zero-configuration drop-in replacement for OpenAI endpoints.
- **Built-in Tunneling:** One-tap toggle to instantly expose your server to the internet via Ngrok or Cloudflare Tunnels (bypasses NATs and firewalls).
- **Foreground Service:** The server and the LLM remain running in the background even when your screen is off.
- **100% Private:** Your prompts and data never leave the phone (except via your own encrypted tunnel).

---

## 📦 Installation

You don't need to build this from source! 

👉 **[Download the Latest Release APK Here](https://github.com/your-username/custom-edge-gallery/releases/latest)** *(Link will be active once the release is published)*

Simply download the APK, install it on your Android device, download a model from the list, and turn the server on.

---

## 💻 Web App Demo

Want to test it out without writing any code? We have hosted a sleek, glassmorphism-styled web interface for you!

🌐 **[Try the Live Web App Demo](https://mobile-server-app.web.app)**

Just open the link, paste in your phone's Cloudflare or Ngrok tunnel URL, and start chatting instantly! (The source code for this web app is also available in the `/webapp-demo` folder).

---

## 🛠️ Development

If you want to build the project from source:

1. Clone this repository.
2. Open the `/Android` folder in Android Studio.
3. Build the `Debug` or `Release` variant. 
*(Note: We have explicitly configured `android:extractNativeLibs="true"` so that the tunneling binaries extract properly in Release builds).*

## 📄 License

Licensed under the Apache License, Version 2.0. See the [LICENSE](LICENSE) file for details.
