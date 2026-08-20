# SAChat 升级方案 v2（定稿版）

> 状态：**方案定稿，待批准开工**（D1~D8 决策全部确认，尚未修改任何功能代码）
> 版本：v2 —— 相比 v1 的变更：**UI 技术路线改为 Jetpack Compose 渐进式**（基于同类高星项目 UI 调研结论修订）
> 基线：当前代码量 Kotlin **3,433 行**（24 文件）+ XML **2,331 行** = **5,764 行**

---

## 1. 背景

SAChat v1.3.0（Android 原生 AI 聊天 App，Kotlin + ViewBinding + Markwon + OkHttp SSE，MIT 协议）。
参考开源项目 [MuseAI](https://github.com/yejiming/MuseAI)（Tauri + React 桌面应用）的功能设计，
并结合 SillyTavern（酒馆）生态的兼容性需求，制定本升级方案。

核心思路：
- **结构化数据 + 编译层**（字段 ↔ Markdown 双表示，一份数据多处复用）
- **记忆闭环**（对话 → LLM 分析 → 写回角色卡）
- **对标酒馆**（世界书行为与格式对齐，生态互操作）
- **不做内嵌酒馆**（AGPL 许可证冲突 + 215MB 体积 + 双 UI 割裂，已论证否决）
- **UI 用 Compose 渐进式重写**（新页面全 Compose，老页面按需迁移，见 §7）

## 2. 需求与调研结论（对话记录摘要）

| 主题 | 结论 |
| --- | --- |
| MuseAI 角色卡/世界书实现 | 结构化 `fields` + 编译 `content` 双表示；聊天 UI 靠 Web 渲染管线；有记忆封存闭环 |
| 酒馆官方是否有记忆封存 | **没有**。官方只有 Summarize（滚动总结注入）+ Chat Vectorization（向量检索），均不写回角色卡；社区扩展也只做到"提取→检索" |
| 兼容酒馆插件难度 | 完整兼容 JS 插件不现实；原生插件 SDK 可行（排后期） |
| Termux 跑酒馆原理 | 酒馆是 Node.js Web 应用，Termux 提供 Linux 环境跑本地服务器，浏览器访问 |
| 内嵌酒馆可行性 | 技术上可行（nodejs-mobile + WebView），被 AGPL + 215MB + 双 UI 否决，改为**数据格式互操作** |
| **同类安卓项目 UI 调研** | **高星同类项目全部使用 Jetpack Compose + Material3**（见 §7.1），无一用 XML 写 UI |

## 3. 已确认决策（D1~D8）

| 决策点 | 选择 |
| --- | --- |
| D1 角色卡结构化字段存储 | ✅ 存 Tavern 规范 `extensions` 字段（旧卡零迁移、生态兼容） |
| D2 世界书注入方式 | ✅ **对标酒馆**：兼容导入酒馆世界书，行为与酒馆一致（核心对标） |
| D3 聊天内卡片 UI | ✅ 一期做顶部常驻折叠块（「系统提示词（已融合设定）」），标记协议预留 |
| D4 记忆封存交互 | ✅ AI 分析 + 确认弹窗（可微调再写回） |
| D5 冒险模式范围 | ✅ 基础 DM 对话（多角色卡 + 世界书），不带逐角色归档 |
| D6 三期创作工具（AI提取/去AI味/大纲） | ✅ 全部砍掉，聚焦聊天核心 |
| D7 酒馆互操作 | ✅ 世界书格式兼容 + 聊天记录（.jsonl）导入都做 |
| **D8 UI 技术路线** | ✅ **Jetpack Compose 渐进式**：新页面全部 Compose（ComposeView 嵌入现有 Activity），1.3 时迁移聊天消息区，老页面按需迁移 |

## 4. 已确认 Bug：世界书导入（第 0 步，待修）

**问题**（`ChatActivity.handleWorldInfoImport` → `Gson().fromJson` 裸解析）：

1. 保存格式 `{version, type, data:{...}}` 包裹结构，导入却期望扁平格式 → 自产文件导入失败（"无效的世界书文件"）
2. SillyTavern 标准格式 `entries` 是对象（uid→条目）不是数组 → `Expected BEGIN_ARRAY` 报错；字段名全对不上（`key`/`constant`/数字 `position`/`depth`）
3. Gson 直解 Kotlin data class：缺失字段为 null 而非默认值 → `id=null` 存成 `null.json` 互相覆盖、`entries=null` 触发 NPE

**修复方案**：新增 `WorldInfoImporter`（仿 `TavernCardImporter` 风格）
- 格式探测：扁平 / `data` 包裹 / SillyTavern entries 对象 / MuseAI 格式明确提示不兼容
- 酒馆字段全量映射并**完整保留**（即使引擎暂不实现某行为，导入不丢信息）：
  `key→keywords`、`constant→constantActive`、数字 `position`(0-4)→注入位置、`depth→injectDepth`、
  `selective`/`secondary_keys`、`probability`/`useProbability`、`group`、`exclude_recursion`/`prevent_recursion`/`delay_until_recursion`、`disable`、`automationId`、`score`
- id 重新生成（防 `null.json` 覆盖）
- 逐条容错 + 明确错误信息（区分"无效文件"/"格式不支持"/"部分条目失败"）

## 5. 工作包清单（工作量 = 代码行数估算，Compose 修订版）

> 行数为 Kotlin 为主的粗略区间（Compose 方案下 XML 布局大幅减少），不含测试。

| # | 工作包 | 内容 | 预估行数 |
| --- | --- | --- | --- |
| 0 | **世界书导入修复** | `WorldInfoImporter` + `WorldEntry` 模型扩全字段 + 管理/导入接入 + 报错优化 | 约 400~500 行 |
| A | **Compose 基建** | 引入 Compose BOM/navigation/coil 依赖、`Theme.kt`/`Color.kt`/`Typography.kt`、基础组件（卡片/折叠块/chip）、ComposeView 接入示例 | 约 200~300 行 |
| 1 | **1.1 角色卡结构化 + 编译层** | `TavernCard` extensions 字段、`CharacterCompiler`（字段→Markdown）、角色列表页（Compose 卡片列表）、角色编辑页（Compose 分组表单/chip/预览）、`CharacterCardView` | 约 1,100~1,400 行 |
| 2 | **1.4 记忆封存** | `AiApiClient.requestJson()`、`MemoryArchiver`（分析+解析+重试）、封存按钮 + 确认弹窗（Compose）、会话归档状态 | 约 550~700 行 |
| 3 | **1.2 世界书引擎对标酒馆** | 注入位置扩展（0/1/2/3/@D角色）、排序语义修正、token 预算、whole words、次级关键词、概率、多源策略（角色/会话/全局）、角色卡内嵌世界书、世界书编辑器（Compose：全局设置 + 条目列表 + 全屏条目编辑） | 约 1,200~1,600 行 |
| 4 | **1.3 聊天内 UI（含聊天区 Compose 迁移）** | `Message.blocks` 模型、`ChatBubble`/`ThinkingBlock`/`ChatMarkdown`（Compose，参考 gpt_mobile）、聊天顶部「系统提示词（已融合设定）」折叠块、绑定信息条、流式标记缓冲解析（`[[THINKING:]]`/`[[CARD:]]` 预留）、消息列表区迁入 Compose（删除 `MessageAdapter` + `item_message.xml`） | 约 800~1,100 行 |
| 5 | **2.1 羁绊档案页** | `BondScreen`（Compose）：角色列表 → 关系概览卡片 + 关键事件时间线 + 会话/冒险足迹 Tab | 约 400~550 行 |
| 6 | **2.2 文字冒险** | `AdventureScreen`（Compose：世界书单选 + 角色卡多选 + DM 对话页）、`AdventureManager`、会话模型扩展 | 约 700~900 行 |
| 7 | **酒馆聊天记录导入** | `STChatLogImporter`（.jsonl → 会话转换）+ 导入入口 | 约 280~400 行 |
| 8 | **导航重构** | 抽屉/底部导航分组：聊天 / 资料 / 记录 / 系统（随各页迁移逐步调整） | 约 80~160 行 |

### 合计

- **核心合计（0~8）：约 5,700 ~ 7,600 行**（Kotlin 为主），约为当前代码量（5,764 行）的 **1.0~1.3 倍**
- 同时**删除约 500~800 行旧 UI 代码**（`MessageAdapter.kt` 216 行、`item_message.xml`、`ChatActivity` 中消息区相关代码等）→ **净增约 5,000 ~ 7,000 行**
- 新增文件约 10~12 个，重写/大改文件约 8 个：
  - 新增：`WorldInfoImporter.kt`、`CharacterCompiler.kt`、`ui/theme/*.kt`、`CharacterListScreen.kt`、`CharacterEditScreen.kt`、`MemoryArchiver.kt`、`WorldInfoEditScreen.kt`、`EntryEditScreen.kt`、`BondScreen.kt`、`AdventureScreen.kt`、`AdventureManager.kt`、`STChatLogImporter.kt`、`ChatBubble.kt`、`ThinkingBlock.kt`、`ChatMarkdown.kt` 等
  - 大改：`ChatActivity.kt`（现 949 行，消息区迁出后大幅瘦身）、`CreateCharacterActivity.kt`（现 135 行）、`CreateWorldInfoActivity.kt`（现 151 行）、`WorldInfoEngine.kt`（现 93 行）、`WorldInfoManager.kt`（现 125 行）、`ConversationManager.kt`（现 379 行）、`AiApiClient.kt`（现 170 行）
  - 删除：`MessageAdapter.kt`、`item_message.xml` 等旧消息 UI

### UI 占比说明

| 工作包 | UI 部分占比 | 备注 |
| --- | --- | --- |
| 0 导入修复 | ~10% | 报错提示优化 |
| 1.1 角色卡 | ~50% | 分组表单、chip、卡片列表、预览模式 |
| 1.4 记忆封存 | ~40% | 封存按钮 + 确认弹窗 |
| 1.2 世界书引擎 | ~40% | 编辑器全屏化、全局设置、位置徽标 |
| 1.3 聊天内 UI | ~70% | 本身即 UI 工作（含 Compose 迁移） |
| 2.1 羁绊页 | ~80% | 全新页面 |
| 2.2 冒险页 | ~60% | 配置页 + DM 对话页 |
| 7 聊天记录导入 | ~20% | 导入对话框 |

## 6. 世界书对标酒馆清单（核心对标范围）

基于 SillyTavern 官方 World Info 文档核实：

### 匹配层
- [✅] 关键词触发（不区分大小写默认）；[🔧] 全局 + 条目级大小写覆盖
- [🔧] **Match whole words**（默认开；中文场景默认关——酒馆文档自身建议）
- [🔧] Regex 触发：导入时识别 `/.../` 格式自动启用
- [🔧] **Optional Filter 次级关键词**（AND ANY / AND ALL / NOT ANY / NOT ALL）
- [🔧] Include Names（扫描文本带 `角色名:` 前缀）
- [🔧] Character Filter（按角色名/标签；保留现有 `role` 消息角色过滤作为 SAChat 独有扩展）
- [✅] 扫描深度（全局 + 条目级覆盖）
- [⚪] 触发器类型（Normal/Continue/Swipe/Regenerate）——可选

### 注入层
- [🔧] 注入位置 0/1/2/3（Before/After Char Defs、Before/After Example Messages）
- [🔧] @ D 深度注入（可指定 system/user/assistant 角色消息）
- [🔧] Insertion Order 语义修正（**大 order 更靠后、影响更大**；现 `sortedByDescending` 语义相反）
- [🔧] 多源策略：Chat Lore / Character Lore / 全局，Sorted Evenly（默认）/ Char First / Global First
- [🔧] 角色卡内嵌世界书（Tavern `extensions.world_book`，导入角色卡时自动建书）
- [⚪] Outlet（`{{outlet::Name}}`）——可选，依赖宏系统扩展
- [❌] Author's Note 位置——SAChat 无 AN 概念，暂不做

### 预算层
- [🔧] Token 预算（Context % 或固定 Budget，中英文混合启发式估算）；现为固定 1500 字符
- [🔧] 常驻条目优先，其次按 order 排序

### 状态层（可选，排后期）
- [🔧] Probability（触发概率 %）——简单，纳入核心
- [⚪] Inclusion Group（同组互斥 + 权重）
- [⚪] Timed Effects（Sticky / Cooldown / Delay，按消息计数）
- [⚪] 递归扫描（Recursive Scan + 条目级控制）
- [❌] 向量匹配（Vector Storage）——需要 embedding + 本地向量库，暂不做

> 原则：**模型层先全字段存储**（导入不丢信息），引擎按实现进度消费字段。

## 7. UI 方案：Jetpack Compose 渐进式（v2 修订核心）

### 7.1 同类高星项目 UI 调研（本路线的依据）

| 项目 | 星数 | 技术栈 | 参考价值 |
| --- | --- | --- | --- |
| [skydoves/chatgpt-android](https://github.com/skydoves/chatgpt-android) | 3,869★ | 纯 Compose，多模块（core-designsystem + feature-*），0 XML 布局 | 模块化与设计系统分层 |
| [Taewan-P/gpt_mobile](https://github.com/Taewan-P/gpt_mobile) | 1,200★ | 纯 Compose + Material3，`ui/chat/` 含 `ChatBubble.kt`/`ThinkingBlock.kt`/`ChatMarkdown.kt`/`ToolTraceBlock.kt`，UI 逻辑带单元测试 | **聊天 UI 直接参考模板**（折叠块约 80 行含动画） |
| [Starkka15/PocketTavern](https://github.com/Starkka15/PocketTavern) | 28★ | 纯 Compose，SillyTavern 兼容安卓端（WorldInfo/群聊/扩展系统/酒馆导入/酒馆主题解析），80+ Screen+ViewModel | 酒馆生态原生安卓化的架构参照 |
| [minitavern/MiniTavern_Android](https://github.com/minitavern/MiniTavern_Android) | 22★ | SillyTavern 安卓替代客户端 | 产品形态参照 |

**共性架构**：单 Activity + Compose Navigation（NavGraph/Routes）；每页一对 Screen+ViewModel；共享 `components/` 层（ChatBubble、CharacterListItem、Dialogs）；独立 `theme/` 层（Color/Theme/Typography，甚至解析酒馆主题文件）；UI 逻辑可单测、可 @Preview。

### 7.2 渐进式迁移策略

```
阶段 A：新增 Compose 基建（依赖 + 主题 + 基础组件）
阶段 B：新页面全部 Compose —— 角色列表/角色编辑/世界书编辑/条目编辑/羁绊/冒险
        （通过 ComposeView 嵌入现有 XML Activity，或独立 Compose Activity）
阶段 C：1.3 时把 ChatActivity 消息区迁到 Compose（ChatBubble/ThinkingBlock/ChatMarkdown），
        删除 MessageAdapter + item_message.xml
阶段 D：老页面（设置等）按需迁移
```

- 新老页面共用一套 Material3 主题（色板对齐现有深浅色体系），视觉不割裂
- 不一次性重写，每步可独立构建验证

### 7.3 依赖与镜像方案（已实测验证，非记忆）

> 2026 年实测：以下所有版本均已通过 HTTP 直接请求镜像仓库的 maven-metadata.xml / .pom 确认存在，且**从本机网络可达**。

**选型原则**：项目锁定 Kotlin 1.9.24 + AGP 8.7.0，**不升级 Kotlin**（升级到 2.x 需新的 compose 编译器插件，阿里云镜像仅有 2.0.0 版，且改动面大），因此采用**旧式 compose 编译器**（`composeOptions.kotlinCompilerExtensionVersion`），用与 Kotlin 1.9.24 匹配的 Compose 1.7.x 稳定线。

**已验证依赖清单**（全部可在阿里云镜像下载）：

| 依赖 | 版本 | 验证方式 |
| --- | --- | --- |
| `androidx.compose:compose-bom` | **2024.09.00** | ✅ 阿里云 google 元数据 + pom 均 HTTP 200 |
| `androidx.compose.ui:ui` / `foundation` / `runtime` 等 | 1.7.0（BOM 锁定） | ✅ BOM pom 实测锁定 |
| `androidx.compose.material3:material3` | 1.3.0（BOM 锁定，稳定版 1.3.2 亦存在） | ✅ BOM pom 实测锁定 |
| `androidx.compose.compiler:compiler`（旧式） | **1.5.14**（`kotlinCompilerExtensionVersion`） | ✅ 官方 dl.google.com 与阿里云均存在（1.5.1~1.5.15） |
| `androidx.navigation:navigation-compose` | **2.8.5** | ✅ 阿里云 pom 200 |
| `androidx.activity:activity-compose` | **1.10.1** | ✅ 阿里云元数据存在（与现有 activity-ktx 1.10.0 同线） |
| `androidx.lifecycle:lifecycle-runtime-compose` | **2.8.7** | ✅ 阿里云元数据存在（与现有 lifecycle 2.8.7 一致） |
| `io.coil-kt:coil-compose` | **2.7.0** | ✅ 阿里云 central pom 200 |

> 注意：`androidx.compose:compose-compiler`（无 `.compiler.` 段）是错误坐标——官方元数据实测只到 1.0.0-alpha03；**正确坐标是 `androidx.compose.compiler:compiler`**（1.5.x 全系存在）。此坑已实测排除。

**镜像源实测结论**（从本机网络）：

| 镜像 | 可达性 | 说明 |
| --- | --- | --- |
| `maven.aliyun.com/repository/{google,central,gradle-plugin,public}` | ✅ 可达 | 主源，所有所需组件齐全 |
| `repo.huaweicloud.com/repository/maven/` | ✅ 可达 | 备用源（含 google 组件） |
| `mirrors.cloud.tencent.com/nexus/repository/google/` | ❌ 不可达 | 当前 settings.gradle 里配置了但连不上，建议移除，避免构建卡顿 |
| `mirrors.cloud.tencent.com/gradle/` | ✅ 可用 | Gradle 发行包镜像（wrapper 已在用 gradle-9.3.1） |

**settings.gradle 建议镜像配置**（阶段 A 时应用）：

```gradle
pluginManagement {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/central' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://repo.huaweicloud.com/repository/maven/' }  // 备用
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/central' }
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://repo.huaweicloud.com/repository/maven/' }
        google()
        mavenCentral()
    }
}
```

（移除不可达的腾讯 nexus，其余保留）

### 7.4 页面清单（Compose 实现）

| 页面 | 实现 |
| --- | --- |
| 聊天主界面 | 绑定信息条（角色头像+名字、世界书名，可切换）；「封存记忆」按钮；消息列表顶部「系统提示词（已融合设定）」折叠块；封存确认弹窗（关系变化/事件分析 + 可编辑字段）；消息区 `ChatBubble`/`ThinkingBlock`/`ChatMarkdown` |
| 角色管理（新） | Compose 卡片网格/列表（头像+名字+身份标签 chips+描述），替换文本弹窗 |
| 角色编辑（新） | 分组折叠表单（基础/标签/外貌/性格/技能经历/记忆）、Chip 标签输入、编辑⇄预览双模式 |
| 世界书管理（新） | 卡片列表（名称+描述+条目数+启用/静态注入徽标） |
| 世界书编辑（新） | 头部全局设置折叠组（扫描深度/token预算/大小写/whole words/Include Names）；条目列表卡片带位置徽标；**条目编辑全屏页**（字段太多） |
| 羁绊档案（新） | 关系概览卡片 + 关键事件时间线 + 足迹 Tab |
| 文字冒险（新） | 配置页（世界书单选卡片 + 角色卡多选勾选）+ DM 对话页（语言/行为/剧情快捷 chip） |
| 设置 | 新增「世界书」设置组 + 「记忆封存」设置组（分析模型/温度） |
| 导航 | 分组：聊天 / 资料 / 记录 / 系统（随迁移逐步调整） |

## 8. 执行顺序

```
0  世界书导入修复（含模型扩字段）          ← 不依赖 UI 路线，最先做
→ A  Compose 基建（依赖 + 主题 + 基础组件）
→ 1  1.1 角色卡结构化+编译层（含 Compose 列表/编辑页）
→ 2  1.4 记忆封存（依赖 1，收益最大）
→ 3  1.2 世界书引擎对标（依赖 0 的模型，含 Compose 编辑器）
→ 4  1.3 聊天内 UI（含聊天区 Compose 迁移，独立攻坚）
→ 5  2.1 羁绊档案页
→ 6  2.2 文字冒险
→ 7  酒馆聊天记录导入
→ 8  导航重构（随迁移穿插）
```

原则：每步改动前先与用户确认，改后 `./gradlew assembleDebug` 构建验证；旧数据零迁移（新字段带默认值）。

## 9. 明确不做（已论证）

- ❌ 内嵌 SillyTavern：AGPL 传染（整个 App 必须转 AGPL 开源）+ 215MB 体积 + 双 UI 割裂
- ❌ 兼容酒馆 JS 插件生态：等于在 Android 里重造迷你酒馆（PocketTavern 做了但体量是我们的 5 倍以上）
- ❌ MuseAI 创作工具（作品管理/大纲/去 AI 味/AI 提取）：D6 决策全砍
- ❌ 向量检索记忆（Vector Storage）：需要 embedding API + 本地向量库
- ❌ 一次性全量 Compose 重写：采用渐进式（D8），避免大爆炸式重构风险

## 10. 风险与注意

1. **1.3 流式标记解析**：标记可能跨 chunk 切割（如 `[[CARD:` 分两段到达），需要类似现有 `tagBuffer` 的缓冲逻辑，单独攻坚、单独测试
2. **1.4 依赖 1.1**：没有结构化字段就没有"写回"目标，顺序不能反
3. **免费模型 JSON 输出稳定性**：封存记忆/导入转换建议可配置独立模型 + temperature=0 + 重试
4. **Gson 直解 Kotlin data class 的坑**（本次 Bug 根因之一）：后续所有新增模型反序列化统一走专用 importer/自定义适配器，禁止裸 `Gson().fromJson` 映射含默认值的模型
5. **世界书全字段保留**：引擎未实现的行为字段也要随文件往返保存，保证"导入不丢信息"
6. **Compose 迁移风险**：新老 UI 样式统一（共用 Material3 主题色解决）；Kotlin 1.9.24 / AGP 8.7 / minSdk 24 均兼容 Compose，无需升级构建链；Compose 依赖会增加 APK 体积约 5~10MB（可在 release 开启 R8 压缩缓解）
7. **依赖下载**：已实测阿里云/华为镜像从本机可达且组件齐全（见 §7.3 清单），阶段 A 应用镜像配置并移除不可达的腾讯 nexus；新增依赖一律先按 §7.3 实测确认存在，禁止凭记忆写版本

## 11. 执行记录（全部完成）

- [x] **0 世界书导入修复** —— ✅ 完成：`WorldInfoImporter`（格式探测/新旧两代字段名兼容/正则定界符/id 重生成/容错警告）+ `WorldEntry` 扩 21 个酒馆字段 + 往返保存 + `saveNew` id 防呆
- [x] **A Compose 基建** —— ✅ 完成：settings.gradle 镜像调整（移除腾讯 nexus、加华为备用）；app/build.gradle 启用 Compose（BOM 2024.09.00 / compiler 1.5.14）；`ui/theme/`（Color/Theme/Typography，与现有 md_theme 色板对齐）；`ui/components/BasicComponents.kt`（FoldableSection/SectionCard/TagRow）
- [x] **1.1 角色卡结构化 + 编译层** —— ✅ 完成：`TavernCardData` extensions 存 `sachat_fields`（CharacterFields 27 字段）；`CharacterCompiler`（字段→Markdown + 定义/示例分段）；`CharacterListActivity`/`CharacterEditActivity`（Compose 卡片列表 + 分组表单/chip/预览）；顺带修复头像持久化 bug（TavernCardImporter 丢 avatarBase64）
- [x] **1.4 记忆封存** —— ✅ 完成：`AiApiClient.requestJson`（非流式，stream=false + max_tokens）；`MemoryArchiver`（对话→分析→严格 JSON 解析→重试）；菜单「封存记忆到角色卡」+ 确认弹窗（可编辑）+ 写回角色卡 + 会话锁定（Conversation.isArchived）
- [x] **1.2 世界书引擎对标酒馆** —— ✅ 完成：whole words（中文降级）、次级关键词（AND ANY/ALL、NOT ANY/ALL）、概率过滤、常驻优先 + order 升序（修正排序语义）、token 预算（可配置）、位置 0/1/2/3 分段注入 + @D 深度注入、多源（会话 + 角色卡内嵌世界书自动建书）；`WorldInfoListActivity`/`WorldInfoEditActivity`（Compose 列表 + 编辑器 + 全屏条目编辑）；设置页新增 token 预算
- [x] **1.3 聊天内 UI** —— ✅ 完成：消息区迁 Compose（`ChatComponents.kt`：ChatBubble/思考折叠块/ChatMarkdown/绑定信息条/系统提示词折叠块）；标记协议 `[[CARD:id]]` → 聊天内卡片组件；删除 `MessageAdapter` + `item_message.xml`；搜索跳转/清空/重试适配新状态流
- [x] **2.1 羁绊档案页** —— ✅ 完成：`BondActivity`（Compose）：角色列表 → 关系概览卡片 + 关键事件时间线 + 会话足迹；抽屉新增「记录」分组入口
- [x] **2.2 文字冒险** —— ✅ 完成：`AdventureActivity`（世界书单选 + 角色卡多选 + 开场剧情）；`Conversation.adventureRoleIds`（兼容旧数据）；DM 系统指令 + 多角色卡定义组装；聊天页 [语言]/[行为]/[剧情] 快捷 chip
- [x] **7 酒馆聊天记录导入** —— ✅ 完成：`STChatLogImporter`（.jsonl → 会话，时间戳解析）；菜单「导入酒馆聊天记录」
- [x] **8 导航重构** —— ✅ 完成：抽屉分组（聊天 / 角色卡 / 世界书 / 记录 / 其他），羁绊档案独立成组；「记录」分组**默认收起**（预留后续扩展）

**构建验证**：全部工作包 `assembleDebug` 通过（增量约 6~18s/次）。
**环境**：`gradle.properties` 指定本机 Temurin JDK 17；删除 AS 生成的 `gradle-daemon-jvm.properties`（要求 JDK 21 且自动下载被网络拦截）。

### 11.1 真机问题修复（用户反馈后）

- [x] **封存记忆确认弹窗按钮不可见** —— 弹窗内容过高把底部按钮挤出屏幕：内容区固定 400dp 高（内部滚动），移除冗余 setMessage，角色卡加载失败时明确 Toast
- [x] **世界书条目单击/长按都触发删除** —— `EntryCard` 链式写了两个 `.clickable()` 后者覆盖前者：改用 `combinedClickable(onClick, onLongClick)`（单击编辑、长按删除）
- [x] **冒险模式 chip 栏被悬浮 FAB 遮挡** —— FAB 锚点改为 `adventureChipBar` 顶部（chip 栏隐藏时约束自动回落）
- [x] **聊天背景图「适应」失效 +「原比例」太小** —— 根因：用未测量的 `rootLayout.width/height`（为 0）；「适应」改为等比居中裁剪（cover），「原比例」不足屏宽时等比放大后平铺；尺寸改用 displayMetrics
- [x] **抽屉「记录」分组默认收起** —— 用户要求（后续将扩充该分组）：新增 chevRecords 折叠箭头 + groupRecords 容器，`setupDrawerGroup(expanded = false)`
- [x] **README 重写** —— `README.md` / `README.en.md` 按最终功能同步重写（结构化角色卡/记忆封存/羁绊/酒馆兼容/冒险/聊天内 UI 等 18 项功能表 + 进阶玩法 + 构建说明）

**遗留（排后期按需）**：D2 高级选项（Inclusion Group / Timed Effects / 递归扫描 / Outlet / 触发器类型 / 向量匹配）；聊天标记协议中 `[[CARD:]]` 的 AI 主动触发提示词调优。
