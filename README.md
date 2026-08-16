# SAChat — AI聊天应用

>The README provides an English version, which can be found in the repository as[README.en.md](https://github.com/5h1iky/Simple-Ai-Chat/blob/main/README.en.md)— open it to view
>
>
>
>
> 一个轻量、开源、纯本地的 AI 聊天应用。

SAChat 是一款 Android 端的 AI 聊天应用：支持自定义 API Key 接入任意 OpenAI 兼容接口，内置了多个免费模型，无需密钥即可使用

---

## 功能特性

| 分类 | 说明 |
| --- | --- |
| 自定义API | 同时保存多条 API 配置 |
| 免费模型预设 | 内置 OpenKilo、OpenCode Zen  |
| 角色扮演 | 创建自己的角色：头像、性格、场景、开场白、示例对话、系统提示词 |
| 世界设定 | 关键词触发的 World Info 系统，为 AI 注入世界观与背景设定 |
| Tavern Card | 支持导入 TavernV2V3 角色卡（PNG 格式） |
| 多对话管理 | 新建 / 切换 / 删除对话，清空重来，失败重试 |
| 附件上传 | 支持上传文本文件与图片（自动压缩） |
| Markdown对话 | 对话内容渲染 Markdown |
| 其他设置 | 字号调节、背景图片/颜色、AI 昵称头像、系统提示词变量 `{{cur_date}}` 等 |
| 完全本地 | 所有数据存于设备本地 |

---

## 软件截图

<img width="360" height="800" alt="e29229d5579b3fc7dd3ab3c35d6669bc" src="https://github.com/user-attachments/assets/5ea5a096-b61b-4be2-b377-599c4cbf7107" />  <img width="360" height="800" alt="49ef086f01ff331da15404006a2be6b8" src="https://github.com/user-attachments/assets/509a3d95-ad5c-47af-a119-2549f299d29d" />

<img width="360" height="800" alt="ef80ac36d82c6aeb8fab453571bff63b" src="https://github.com/user-attachments/assets/5e60ac4e-b7df-481f-8aea-16cad060ddcb" />

<img width="360" height="800" alt="7bf15fcc74b4c6ad86d2ac1e97c628fd" src="https://github.com/user-attachments/assets/67f2bec2-4ec6-4107-b65b-7a0c22ce3b14" />

>支持随系统深浅色模式变化而变化

---


## 下载

前往 [Releases 页面](https://github.com/5h1iky/Simple-Ai-Chat/releases) 下载最新版 APK 安装包。

**当前版本：v1.3.0**

> 需要 Android 7.0（API 24）及以上设备。

---

## 快速开始

1. 下载并安装 APK（首次安装需允许「未知来源」）
2. 打开应用，在「设置」中选择：
   - **免费预设**：选择 OpenKilo / OpenCode Zen 等免费服务，选个模型
   - **自定义**：填入你自己的 API 地址、Key 和模型名称
3. 回到聊天页开始对话

---

## 从源码构建

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

## 项目结构

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

## 免责声明

完整声明见应用内「关于 → 免责声明」。

---

## 技术栈

Kotlin AndroidX aterial Design OkHttp SSE Gson Markwon Glide ViewBinding

---

## 关于作者

- 作者：5h1iky
- 个人主页：[Bilibili](https://space.bilibili.com/432122433)

有任何问题或建议，欢迎在 [Issues](https://github.com/5h1iky/Simple-Ai-Chat/issues) 中提出，或通过 Bilibili 联系作者。

---

## License

本项目基于 [MIT License](./LICENSE) 开源，你可以自由使用、修改和分发，仅需保留版权声明。

Copyright © 2026 5h1iky
