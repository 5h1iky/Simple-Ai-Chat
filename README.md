# 🚀 SAChat — 简单 AI 聊天

> 一个轻量、开源、纯本地的 AI 聊天应用。你的数据，只属于你。

SAChat 是一款 Android 端的 AI 聊天应用：支持自定义 API Key 接入任意 OpenAI 兼容接口，也内置了多个免费模型，无需密钥即可开聊。整个应用没有任何服务器，所有配置、角色、世界设定和聊天记录全部存储在本地。

---

## ✨ 功能特性

| 分类 | 说明 |
| --- | --- |
| 🧠 多 API 支持 | 自定义 API 地址 / Key / 模型，可同时保存多条 API 配置随时切换 |
| 🆓 免费模型预设 | 内置 OpenKilo、OpenCode Zen 等免费路由，免密钥开箱即用，支持自动路由 |
| 🎭 角色扮演 | 创建自己的角色：头像、性格、场景、开场白、示例对话、系统提示词，让 AI 扮演任何你想扮演的人 |
| 🌍 世界设定 | 关键词触发的 World Info 系统，为 AI 注入世界观与背景设定 |
| 📇 Tavern Card | 支持导入 Tavern 角色卡（PNG 格式），社区角色直接拿来用 |
| 💬 多对话管理 | 新建 / 切换 / 删除对话，清空重来，失败重试 |
| 🖼️ 附件上传 | 支持文本文件与图片（自动压缩），多模态模型可直接看图 |
| 📝 Markdown 渲染 | 对话内容完整渲染 Markdown，代码块、表格、列表清晰可读 |
| 🎨 个性化设置 | 字号调节、背景图片/颜色、AI 昵称、系统提示词变量 `{{cur_date}}` 等 |
| 🔒 完全本地 | 所有数据仅存于设备本地，无账号、无云同步、无数据收集 |

---

## 📦 下载

前往 [Releases 页面](https://github.com/5h1iky/Simple-Ai-Chat/releases) 下载最新版 APK 安装包。

**当前版本：v1.3.0**

> 需要 Android 7.0（API 24）及以上设备。

---

## 🚦 快速开始

1. 下载并安装 APK（首次安装需允许「未知来源」）
2. 打开应用，在「设置」中选择：
   - **免费预设**：选择 OpenKilo / OpenCode Zen 等免费服务，选个模型（或自动路由），即可开聊
   - **自定义**：填入你自己的 API 地址、Key 和模型名称
3. 回到聊天页，开始你的第一次对话吧 🎉

---

## 🔧 从源码构建

**环境要求：**

- JDK 17+
- Android SDK 36（`compileSdk 36`，`minSdk 24`，`targetSdk 36`）
- Android Studio 或命令行 Gradle

**构建命令：**

```bash
# 调试版
./gradlew assembleDebug

# 发布版
./gradlew assembleRelease
```

生成的 APK 位于 `app/build/outputs/apk/` 目录下。

---

## 📂 项目结构

```
app/src/main/java/www/cetool/com/
├── adapter/        # RecyclerView 适配器
├── importer/       # Tavern Card 导入
├── manager/        # 角色、世界设定、对话管理
├── model/          # 数据模型（消息、角色、世界设定等）
├── network/        # AI API 调用（OkHttp + SSE）
└── *.kt            # 各页面 Activity
```

---

## ⚠️ 免责声明

- 本项目为个人开发者维护的开源公益项目，不收取任何费用，不提供商业担保或技术支持。
- 「免费模型」功能依赖第三方服务商（如 OpenKilo、OpenCode Zen）提供的 API，对话内容将传输至所选第三方服务器，请谨慎输入隐私信息。
- AI 生成内容可能存在错误或偏见，不代表开发者观点，使用者需自行承担后果。
- 免费 API 不保证 7×24 小时可用，可能随时因上游政策调整而中断。

完整声明见应用内「关于 → 免责声明」。

---

## 📚 技术栈

Kotlin · AndroidX · Material Design · OkHttp + SSE · Gson · Markwon · Glide · ViewBinding

---

## 👤 关于作者

- 作者：5h1iky
- 个人主页：[Bilibili](https://space.bilibili.com/432122433)

有任何问题或建议，欢迎在 [Issues](https://github.com/5h1iky/Simple-Ai-Chat/issues) 中提出，或通过 Bilibili 联系作者。

---

## 📄 License

本项目基于 [MIT License](./LICENSE) 开源，你可以自由使用、修改和分发，仅需保留版权声明。

Copyright © 2026 5h1iky
