# SAChat — AI Chat App

---The English version of the README was translated using AI. The original version is in Chinese, so this translation may have some issues---

> A lightweight, open-source, fully local AI chat app.

SAChat is an Android AI chat app: it supports custom API keys for any OpenAI-compatible endpoint, and ships with several free model providers, so you can use it without a key.

---

## Features

| Category | Description |
| --- | --- |
| Custom API | Save multiple API configurations at once |
| Free model presets | Built-in OpenKilo, OpenCode Zen |
| Roleplay | Create your own characters: avatar, personality, scenario, first message, example dialogues and system prompts |
| World Info | Keyword-triggered World Info system to inject worldviews and background settings into the AI |
| Tavern Card | Import Tavern V2/V3 character cards (PNG format) |
| Multi-conversation management | Create / switch / delete conversations, clear and restart, retry on failure |
| Attachments | Attach text files and images (auto-compressed) |
| Markdown chat | Renders Markdown in conversation content |
| Other settings | Font size, background image/color, AI nickname & avatar, system prompt variables like `{{cur_date}}` |
| Fully local | All data stays on your device |

---

## Screenshots

<img width="360" height="800" alt="e29229d5579b3fc7dd3ab3c35d6669bc" src="https://github.com/user-attachments/assets/5ea5a096-b61b-4be2-b377-599c4cbf7107" />  <img width="360" height="800" alt="49ef086f01ff331da15404006a2be6b8" src="https://github.com/user-attachments/assets/509a3d95-ad5c-47af-a119-2549f299d29d" />

<img width="360" height="800" alt="ef80ac36d82c6aeb8fab453571bff63b" src="https://github.com/user-attachments/assets/5e60ac4e-b7df-481f-8aea-16cad060ddcb" />

<img width="360" height="800" alt="7bf15fcc74b4c6ad86d2ac1e97c628fd" src="https://github.com/user-attachments/assets/67f2bec2-4ec6-4107-b65b-7a0c22ce3b14" />

> Follows the system's light/dark mode

---

## Download

Get the latest APK from the [Releases page](https://github.com/5h1iky/Simple-Ai-Chat/releases).

**Current version: v1.3.0**

> Requires Android 7.0 (API 24) or above.

---

## Quick Start

1. Download and install the APK (allow "Unknown sources" on first install)
2. Open the app and choose in Settings:
   - **Free presets**: pick a free service like OpenKilo / OpenCode Zen, select a model
   - **Custom**: enter your own API URL, Key and model name
3. Return to the chat page and start your conversation

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

See the full disclaimer in the app under "About → Disclaimer".

---

## Tech Stack

Kotlin · AndroidX · Material Design · OkHttp · SSE · Gson · Markwon · Glide · ViewBinding

---

## About the Author

- Author: 5h1iky
- Profile: [Bilibili](https://space.bilibili.com/432122433)

For questions or suggestions, feel free to open an [Issue](https://github.com/5h1iky/Simple-Ai-Chat/issues), or reach out via Bilibili.

---

## License

This project is open source under the [MIT License](./LICENSE). You are free to use, modify and distribute it, provided you retain the copyright notice.

Copyright © 2026 5h1iky
