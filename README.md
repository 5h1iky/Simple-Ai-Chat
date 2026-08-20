# SAChat — AI聊天应用

>The README provides an English version, which can be found in the repository as[README.en.md](https://github.com/5h1iky/Simple-Ai-Chat/blob/main/README.en.md)— open it to view
>
>
>
>
> 一个轻量、开源、纯本地的 AI 聊天应用。

SAChat 是一款 Android 端的 AI 聊天应用：支持自定义 API Key 接入任意 OpenAI 兼容接口，内置了多个免费模型，无需密钥即可使用。

---

## 功能特性

| 分类 | 说明 |
| --- | --- |
| 自定义API | 同时保存多条 API 配置 |
| 免费模型预设 | 内置 OpenKilo、OpenCode Zen |
| 角色扮演 | 创建自己的角色：头像、性格、场景、开场白、示例对话、系统提示词 |
| 结构化角色卡 | 27 个结构化字段（基础信息/身份标签/外貌气质/性格特征/技能与经历/角色记忆），分组表单编辑 + Markdown 效果预览双模式，角色卡片列表选择 |
| 记忆封存 | 对话结束后让 AI 分析本次经历，确认后把关系变化、关键事件**写回角色卡**，会话随之锁定归档（酒馆官方都没有的能力） |
| 羁绊档案 | 按角色沉淀：关系概览、关键事件时间线、会话足迹，回顾每次互动留下的轨迹 |
| 世界设定 | 关键词触发的 World Info 系统，**行为对标 SillyTavern（酒馆）**：whole words 整词匹配、正则触发、次级关键词过滤（AND/ANY/NOT）、触发概率、常驻条目、token 预算、多位置注入（角色定义前/后、示例对话前/后、@深度） |
| 酒馆生态兼容 | 导入酒馆世界书（新旧两代格式全兼容）、**角色卡内嵌世界书自动建书**、导入酒馆聊天记录（.jsonl）、MuseAI 格式导入时明确提示 |
| 文字冒险 | 世界书 + 多角色卡开启 AI 跑团（DM/GM），支持 [语言]/[行为]/[剧情] 三种输入方式 |
| Tavern Card | 支持导入 TavernV2V3 角色卡（PNG 格式） |
| 多对话管理 | 新建 / 切换 / 删除对话，清空重来，失败重试，搜索跳转 |
| 聊天内 UI | 顶部绑定信息条（角色/世界书一键切换）、「系统提示词（已融合设定）」可折叠块、思考过程折叠块、`[[CARD:角色id]]` 标记渲染为聊天内角色卡卡片 |
| 附件上传 | 支持上传文本文件与图片（自动压缩） |
| Markdown对话 | 对话内容渲染 Markdown |
| 其他设置 | 字号调节、背景图片/颜色（等比裁剪/原比例平铺）、AI 昵称头像、系统提示词变量 `{{cur_date}}` 等、世界书 token 预算 |
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

**当前版本：v1.3.0**（仓库代码已包含更多新功能，见上方功能特性）

> 需要 Android 7.0（API 24）及以上设备。

---

## 快速开始

1. 下载并安装 APK（首次安装需允许「未知来源」）
2. 打开应用，在「设置」中选择：
   - **免费预设**：选择 OpenKilo / OpenCode Zen 等免费服务，选个模型
   - **自定义**：填入你自己的 API 地址、Key 和模型名称
3. 回到聊天页开始对话

**进阶玩法：**

- **角色扮演**：抽屉 → 角色卡 → 创建/导入角色，编辑结构化字段（分组表单），保存后开始角色对话；对话结束可在菜单「封存记忆到角色卡」沉淀关系变化，之后到「记录 → 羁绊档案」回顾
- **世界书**：抽屉 → 世界书 → 创建或导入酒馆格式世界书（关键词触发、正则、概率、深度注入），绑定到会话后自动生效
- **文字冒险**：抽屉 → 角色卡 → 冒险，选一本世界书和多张角色卡，输入开场剧情，开启 AI 跑团
- **导入酒馆数据**：世界书 JSON（新旧格式）、角色卡 PNG/JSON（含内嵌世界书）、聊天记录 .jsonl 均可直接导入

---

## 从源码构建

**环境要求：**

- JDK 17+（本机无 JDK 21 时可在 `gradle.properties` 中通过 `org.gradle.java.home` 指定本机 JDK 17）
- Android SDK 36（`compileSdk 36`，`minSdk 24`，`targetSdk 36`）
- Android Studio 或命令行 Gradle
- 国内网络建议使用阿里云 / 华为云 Maven 镜像（`settings.gradle` 已默认配置，依赖下载更稳定）

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
├── adapter/        # RecyclerView 适配器（会话列表）
├── importer/       # 导入器：Tavern 角色卡 / 世界书（酒馆新旧格式）/ 酒馆聊天记录 .jsonl
├── manager/        # 角色、世界书引擎（对标酒馆）、记忆封存、对话管理
├── model/          # 数据模型（消息、角色、世界书条目、对话等）
├── network/        # AI API 调用（OkHttp + SSE 流式 / 非流式 JSON）
├── ui/             # Compose 层：theme（主题）+ components（折叠块/卡片/标签/聊天气泡）
└── *.kt            # 各页面 Activity（聊天、角色列表/编辑、世界书列表/编辑、羁绊、冒险，Compose 渐进式）
```

---

## 免责声明

完整声明见应用内「关于 → 免责声明」。

---

## 技术栈

Kotlin · Jetpack Compose（渐进式）· AndroidX · Material Design 3 · OkHttp · SSE · Gson · Markwon · Coil · Glide · ViewBinding

---

## 关于作者

- 作者：5h1iky
- 个人主页：[Bilibili](https://space.bilibili.com/432122433)

有任何问题或建议，欢迎在 [Issues](https://github.com/5h1iky/Simple-Ai-Chat/issues) 中提出，或通过 Bilibili 联系作者。

---

## License

本项目基于 [MIT License](./LICENSE) 开源，你可以自由使用、修改和分发，仅需保留版权声明。

Copyright © 2026 5h1iky
