# SAChat — Simple AI Chat

> A lightweight, open-source, fully local AI chat app. Your data belongs to you only.

---The English version of the README was translated using AI. The original version is in Chinese, so this translation may have some issues---

SAChat is an Android AI chat app: it supports custom API keys for any OpenAI-compatible endpoint, and also ships with several free model providers, so you can start chatting without a key. The app has no servers — all configurations, characters, world info and chat history are stored locally.

---

## Features

| Category | Description |
| --- | --- |
| Multiple API support | Custom API URL / Key / Model; save multiple API entries and switch anytime |
| Free model presets | Built-in free routers , ready to use without a key |
| Roleplay | Create your own characters: avatar, personality, scenario, first message, example dialogues and system prompts — let the AI play anyone you want |
| World Info | Keyword-triggered World Info system to inject worldviews and background settings into the AI |
| Tavern Card | Import Tavern character cards (PNG format) — use community characters directly |
| Multi-conversation management | Create / switch / delete conversations, clear and restart, retry on failure |
| Attachments | Attach text files and images (auto-compressed); multimodal models can read images directly |
| Markdown rendering | Full Markdown rendering in chat — code blocks, tables and lists are clearly readable |
| Personalization | Font size, background image/color, AI nickname, system prompt variables like `{{cur_date}}` |
| Fully local | All data stays on your device: no accounts, no cloud sync, no data collection |

---

## Download

Get the latest APK from the [Releases page](https://github.com/5h1iky/Simple-Ai-Chat/releases).

**Current version: v1.3.0**

> Requires Android 7.0 (API 24) or above.

---

## Quick Start

1. Download and install the APK (allow "Unknown sources" on first install)
2. Open the app and choose in Settings:
   - **Free presets**: pick a free service like OpenKilo / OpenCode Zen, select a model (or auto-routing), and start chatting
   - **Custom**: enter your own API URL, Key and model name
3. Return to the chat page and start your first conversation

---

## Building from Source

**Requirements:**

- JDK 17+
- Android SDK 36 (`compileSdk 36`, `minSdk 24`, `targetSdk 36`)
- Android Studio or Gradle CLI

**Build commands:**

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

The generated APK is located in the `app/build/outputs/apk/` directory.

---

## Project Structure

```
app/src/main/java/www/cetool/com/
├── adapter/        # RecyclerView adapters
├── importer/       # Tavern Card importer
├── manager/        # Character, World Info and conversation management
├── model/          # Data models (messages, characters, world info, etc.)
├── network/        # AI API client (OkHttp + SSE)
└── *.kt            # Activity pages
```

---

## Disclaimer

- This project is a non-commercial open-source project maintained by an individual developer. It charges no fees and provides no commercial warranty or technical support.
- The "free model" feature relies on API interfaces provided by third-party services (such as OpenKilo and OpenCode Zen). Conversation content is transmitted to the third-party servers you select, so please be cautious when entering personal or private information.
- AI-generated content may contain errors or biases and does not represent the developer's views. Users are solely responsible for any consequences arising from their use of the AI.
- Free APIs are not guaranteed to be available 24/7 and may be interrupted or rate-limited at any time due to upstream policy changes.

See the full disclaimer in the app under "About → Disclaimer".

---

## Tech Stack

Kotlin · AndroidX · Material Design · OkHttp + SSE · Gson · Markwon · Glide · ViewBinding

---

## About the Author

- Author: 5h1iky
- Profile: [Bilibili](https://space.bilibili.com/432122433)

For questions or suggestions, feel free to open an [Issue](https://github.com/5h1iky/Simple-Ai-Chat/issues), or reach out via Bilibili.

---

## License

This project is open source under the [MIT License](./LICENSE). You are free to use, modify and distribute it, provided you retain the copyright notice.

Copyright © 2026 5h1iky
