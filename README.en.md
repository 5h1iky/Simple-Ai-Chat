# SAChat — AI Chat App

---The English version of the README was translated using AI. The original version is in Chinese, so this translation may have some issues---

> A lightweight, open-source, fully local AI chat app.

SAChat is an Android AI chat app: it supports custom API keys for any OpenAI-compatible endpoint, and ships with several free model providers, so you can use it without a key.

**v2.1.1**: The UI has been fully migrated to Jetpack Compose (modern Material 3 visuals: rounded cards, gradient primary color, unified icon set), with major conversation & world-book enhancements (long-press conversation management, multiple world books at once, character card / world book export).

> **v2.1.1** — Added **UI language switching (Chinese / English)** in Settings, follows the system language by default.

---

## Features

| Category | Description |
| --- | --- |
| Custom API | Save multiple API configurations at once |
| Free model presets | Built-in OpenKilo, OpenCode Zen |
| Roleplay | Create your own characters: avatar, personality, scenario, first message, example dialogues and system prompts |
| Structured character cards | 27 structured fields (basic info / identity tags / appearance / personality / skills & history / relationship memory), grouped-form editing with a Markdown preview mode, and a card-style character picker |
| Memory archiving | After a chat, the AI analyzes the session; on confirmation, relationship changes and key events are **written back into the character card**, and the session is locked & archived (a capability even SillyTavern doesn't have officially) |
| Bond archive | Per-character records: relationship overview, key-event timeline, and session footprints |
| World Info | Keyword-triggered lorebook system, **behavior aligned with SillyTavern**: whole-word matching, regex triggers, secondary-key filtering (AND/ANY/NOT), trigger probability, constant entries, token budget, and multi-position injection (before/after character defs, around example messages, at-depth) |
| **Multiple world books at once** | A conversation can **enable several world books simultaneously** (long-press conversation → World Info settings → multi-select), each shown in the chat and all injected |
| **Long-press conversation management** | Long-press any conversation in the drawer: delete / clear / rename / World Info settings (decide per conversation which world books are enabled) |
| **Linked usage hints** | The World Info manager shows "Used by: conversation A, B" on every book card, so you always know which conversations use which books |
| **Export** | Long-press a character card or world book in the manager to export the raw JSON (Tavern / SillyTavern-compatible) to any location via the system file picker |
| SillyTavern ecosystem | Import SillyTavern world books (both legacy and current formats), **auto-create lorebooks embedded in character cards**, import SillyTavern chat logs (.jsonl), and clear feedback when importing unsupported formats (e.g. MuseAI) |
| Text adventure | AI dungeon master (DM/GM) with one world book + multiple character cards; input via [Speech] / [Action] / [Plot] |
| Tavern Card | Import Tavern V2/V3 character cards (PNG format) |
| Multi-conversation management | Create / switch / delete conversations, clear and restart, retry on failure, search & jump (history list auto-scrolls past 5 entries) |
| Chat UI | Binding bar (one-tap character/world-book switching, multiple world books shown side by side), collapsible "system prompt (merged settings)" block, collapsible thinking block, and `[[CARD:characterId]]` markers rendered as in-chat character card chips |
| Attachments | Attach text files and images (auto-compressed) |
| Markdown chat | Renders Markdown in conversation content |
| Other settings | Font size, background image/color (cover-crop or tile at original ratio), AI nickname & avatar, system prompt variables like `{{cur_date}}`, World Info token budget |
| All-new Compose UI | Every screen in Jetpack Compose + Material 3: rounded card system, gradient primary, unified Material Icons (zero emoji), faster splash (~6.6s → 3.3s with identical visuals) |
| **Multi-language UI** | Switch **Chinese / English** in Settings (default: follow system); the default system prompt and adventure DM prompt are also bilingual |
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

**Current version: v2.1.1**

> Requires Android 7.0 (API 24) or above.

---

## Quick Start

1. Download and install the APK (allow "Unknown sources" on first install)
2. Open the app and choose in Settings:
   - **Free presets**: pick a free service like OpenKilo / OpenCode Zen, select a model
   - **Custom**: enter your own API URL, Key and model name
3. Return to the chat page and start your conversation

**Going further:**

- **Roleplay**: Drawer → Characters → create/import a character, edit the structured fields (grouped form), save and start chatting; after a session, use "Archive memory to character card" from the menu to persist relationship changes, then review them under "Records → Bond Archive"
- **World Info**: Drawer → World Info → create or import a SillyTavern-format world book; **long-press a conversation → World Info settings** to tick the books you want (multiple allowed), see them live in the chat, and they take effect automatically
- **Manage conversations**: long-press any history conversation in the drawer → delete / clear / rename / World Info settings
- **Export**: in the character card / world book manager, long-press a card → export raw JSON anywhere
- **Text adventure**: Drawer → Characters → Adventure, pick one world book and multiple character cards, enter an opening plot, and start an AI-run tabletop session
- **Import SillyTavern data**: world book JSON (both formats), character card PNG/JSON (including embedded world books), and chat logs (.jsonl) can all be imported directly

---

## Building from Source

**Requirements:**

- JDK 17+ (if you don't have a JDK installed, the Android Studio bundled JBR works; or point `org.gradle.java.home` / `JAVA_HOME` to a local JDK 17)
- Android SDK 36 (`compileSdk 36`, `minSdk 24`, `targetSdk 36`)
- Android Studio or Gradle CLI
- In mainland China, Aliyun / Huawei Cloud Maven mirrors are pre-configured in `settings.gradle` for more reliable dependency downloads

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
├── importer/       # Importers: Tavern character cards / world books (legacy & current formats) / SillyTavern chat logs (.jsonl)
├── manager/        # Characters, World Info engine (SillyTavern-aligned), memory archiving, conversations
├── model/          # Data models (messages, characters, world info entries, conversations, etc.)
├── network/        # AI API client (OkHttp + SSE streaming / non-streaming JSON)
├── ui/             # Compose layer: theme + components (fold blocks / cards / tags / chat bubbles)
└── *.kt            # All Activity pages (chat, settings, about, splash, characters, world info, bond archive, adventure — fully Compose)
```

---

## Disclaimer

See the full disclaimer in the app under "About → Disclaimer".

---

## Tech Stack

Kotlin · Jetpack Compose (full) · Material Design 3 · Material Icons · AndroidX · OkHttp · SSE · Gson · Markwon · Coil · Glide

---

## About the Author

- Author: 5h1iky
- Profile: [Bilibili](https://space.bilibili.com/432122433)

For questions or suggestions, feel free to open an [Issue](https://github.com/5h1iky/Simple-Ai-Chat/issues), or reach out via Bilibili.

---

## License

This project is open source under the [MIT License](./LICENSE). You are free to use, modify and distribute it, provided you retain the copyright notice.

Copyright © 2026 5h1iky
