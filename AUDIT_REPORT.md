# SAChat 代码审计报告（只读审计 · 修复交接单）

- 被审版本：`4a1c2c8`（versionCode 4 / versionName 2.1.2）
- 审计日期：2026-10-03
- 审计范围：`app/src/main/` 全部 Kotlin 源码（35 文件 / 约 6.5k 行）、`AndroidManifest.xml`、双语 `strings.xml`、`app/build.gradle`
- 审计方式：全量通读 + 跨文件调用链验证；关键结论与 AOSP 源码 / SillyTavern 规范对照
- **本次审计未修改任何代码文件**，本报告为唯一新增文件

## 给修复方（下一个 AI）的使用说明

1. 条目编号 `A/B/C/D/E` 对应优先级，**请按 A → B → C → D → E 顺序处理**，A 类必须修。
2. 每条包含：位置（`文件:行号`）、现状代码、问题、影响、验证方式、置信度（不含修法建议）。
3. **行号基于 `4a1c2c8`**。改动后行号会偏移，请以代码内容为准定位。
4. 术语：**酒馆 = SillyTavern**；本项目是酒馆生态的 Android 客户端（角色卡 / 世界书 / 聊天记录导入、酒馆语义的世界书引擎）。
5. 第 7 节是"已确认无问题"的负面清单，**不要重复排查**；第 8 节是背景机制，理解这些问题所必需。
6. `置信度` 标注含义：`已确认` = 已读代码/规范核实；`需复现` = 机制成立但触发依赖特定输入或设备。

---

## 1. 结论摘要

确认缺陷 68 处（A 类 5 / B 类 31 / C 类 7 / D 类 10 / E 类 15）。整体架构没有崩塌，问题集中在四类：

1. **一行错参数导致整块 UI 功能全灭**（头像）。
2. **缺输入校验 + 缺 try/catch 导致的必崩路径**（自定义 API 地址）。
3. **"重建对象"式写库导致静默数据丢失**（编辑角色卡、流式回复中途 `init()`）。
4. **功能写了但从未生效**（内嵌世界书、Web 搜索、API 条目编辑、角色卡预览、世界书 `enabled`、分组字段）。

### 必修清单（A 类，5 条）

| 编号 | 一句话 |
|---|---|
| A1 | 全应用头像永远不显示（解码长度传 0） | `ChatComponents.kt:330`、`CharacterListActivity.kt:341` |
| A2 | 填了不带 `http(s)://` 的 API 地址 → 一发消息必崩 | `SettingsActivity.kt:1194`、`AiApiClient.kt:198` |
| A3 | 编辑角色卡保存 = 永久删除 scenario / alternate_greetings / creator 等 5 个字段 | `CharacterEditActivity.kt:201` |
| A4 | 流式回复中打开世界书页 / 保存设置 / 转屏 → 回复被截断、`isStreaming` 失真 | `WorldInfoListActivity.kt:118`、`SettingsActivity.kt:509`、`ChatActivity.kt:316` |
| A5 | 角色列表删除/导入/新建后不刷新（`refreshTick` 是死代码） | `CharacterListActivity.kt:181`、`:206` |

---

## 2. A 类：必修（P0）

### A1 全应用头像永远不显示

- **位置**：`app/src/main/java/www/cetool/com/ui/components/ChatComponents.kt:326-332`（聊天气泡头像）、`app/src/main/java/www/cetool/com/CharacterListActivity.kt:337-351`（`CharacterAvatar`，被角色列表 / 羁绊档案 / 冒险选角复用）
- **现状代码**：

```kotlin
val bitmap = remember(avatarBase64) {
    if (avatarBase64 == null) null
    else try {
        BitmapFactory.decodeByteArray(Base64.decode(avatarBase64, Base64.DEFAULT), 0, 0)
    } catch (_: Exception) { null }
}
```

- **问题**：`decodeByteArray(data, offset, length)` 第三个参数是**要解析的字节数**，传 `0`。已对照 AOSP `frameworks/base/libs/hwui/jni/BitmapFactory.cpp` 核实：`nativeDecodeByteArray` 用它构造 `SkMemoryStream(ptr + offset, length)`，长度为 0 的流会让 `SkCodec::MakeFromStream` 失败，函数**返回 null 且不抛异常**，于是永远走占位分支。
- **影响**：聊天气泡、角色卡列表、羁绊档案、文字冒险选角的头像 100% 不显示（全员灰色占位块）。同文件 `:500` 与 `ChatActivity.kt:1759` 写的是正确形式 `bytes.size`，本次属复制粘贴漏改。
- **验证**：导入一张带头像的角色卡 → 角色列表与聊天页应显示头像。
- **置信度**：已确认（AOSP 源码级）。

### A2 自定义 API 地址未校验 → 发送消息必崩

- **位置**：`app/src/main/java/www/cetool/com/SettingsActivity.kt:1194-1199`（新增 API 弹窗只校验 label）、`:512-520`（写入 `ai_config`）；`app/src/main/java/www/cetool/com/model/ApiConfig.kt:21-23`（`isValid()` 只判空）；`app/src/main/java/www/cetool/com/network/AiApiClient.kt:185-208`（构造请求）；`app/src/main/java/www/cetool/com/ConversationManager.kt:283-284`；`app/src/main/java/www/cetool/com/ChatActivity.kt:1996-2017`
- **现状代码**：

```kotlin
// SettingsActivity.kt:1195
if (label.isBlank()) { /* toast */ } else { onConfirm(label.trim(), url.trim(), key.trim(), model.trim()) }
```
```kotlin
// AiApiClient.kt:198-208 —— 无 try/catch
val url = "${endpoint.url.trimEnd('/')}/chat/completions"
val request = requestBuilder.post(requestBody).build()   // Request.Builder().url(...) 在此抛
```

- **问题**：用户填 `api.openai.com/v1`（漏了 `https://`）会被原样保存并通过 `ApiConfig.isValid()`。发送时 OkHttp 抛 `IllegalArgumentException: Expected URL scheme 'http' or 'https' but no colon was found`。从 `sendChatStream` → `ConversationManager.sendMessage` → Compose 点击回调**全链路无 try/catch**，异常直穿主线程 → 进程被杀。
- **影响**：必崩。注：`AiApiClient.requestJson`（记忆封存走这条）整体包在 try 内，所以只有聊天发送崩。
- **验证**：把地址改成 `api.openai.com/v1` → 保存 → 发消息，应提示地址非法而不是崩溃。
- **置信度**：已确认。

### A3 编辑角色卡保存会永久清空 5 个字段

- **位置**：`app/src/main/java/www/cetool/com/CharacterEditActivity.kt:201-219`
- **现状代码**：

```kotlin
val data = TavernCardData(
    name = name, description = description, personality = personality,
    scenario = "",                       // ← 硬编码清空
    first_mes = ..., mes_example = ..., system_prompt = ..., post_history_instructions = ...,
    tags = state.legacyTags,
    extensions = oldExtensions            // 只有 extensions / tags 被保留
).withCharacterFields(fields)
val json = Gson().toJson(card)
CharacterManager.overwrite(this, it, json)   // 覆盖原文件
```

- **问题**：`TavernCardData` 是重新构造的，未列出的字段全部回落到模型默认值：`scenario=""`、`creator=""`、`creator_notes=""`、`character_version="1.0"`、`alternate_greetings=emptyList()`（`model/TavernCard.kt:14-28`）。编辑界面没有这些字段的入口，用户看不见也救不回；`CharacterManager.overwrite`（`manager/CharacterManager.kt:82-87`）直接覆盖文件。
- **影响**：不可逆数据丢失。`scenario` 还是有功能的字段——`manager/CharacterCompiler.kt:108` 会把它注入系统提示词（`[场景]`），所以"只改个名字"也会静默改变 AI 收到的设定。备选开场白在酒馆生态使用率极高，这里全灭。
- **验证**：导入一张带 scenario / alternate_greetings / creator_notes 的卡 → 只改名字保存 → 打开 JSON 确认这些字段仍在。
- **置信度**：已确认。

### A4 `ConversationManager.init()` 会截断正在流式的回复

- **位置**：`app/src/main/java/www/cetool/com/WorldInfoListActivity.kt:118`、`app/src/main/java/www/cetool/com/SettingsActivity.kt:509`、`app/src/main/java/www/cetool/com/ChatActivity.kt:316`（三处调用 `ConversationManager.init(this)`）；受害逻辑在 `ConversationManager.kt:43-47`、`:111-145`、`:287-341`、`:390-434`
- **现状代码**：

```kotlin
// ConversationManager.kt:122 —— 流式回调捕获"旧对象"
val conv = getById(conversationId) ?: return
...
onText = { chunk ->
    val updated = lastMsg.copy(content = lastMsg.content + chunk)
    conv.messages[lastIndex] = updated      // 写进旧对象
}
```
```kotlin
// ConversationManager.kt:400-419 —— init() → load() 重建全部对象
val data = gson.fromJson(json, ConversationData::class.java)
conversations.clear()
data.conversations.forEach { entry -> conversations.add(Conversation(... isStreaming = false ...)) }
```

- **问题**：`init()` 会 `conversations.clear()` 并从磁盘**重建全新的 Conversation 实例**。在飞的 SSE 回调仍持有旧对象，后续分片全部写进孤儿对象：界面看不到、`save()` 也不会存；`load()` 还把 `isStreaming` 强制置 false，于是发送守卫失效——用户可在旧流仍在运行时再发一条，而 `activeStreams[id]` 已被覆盖，旧流再也取消不掉（继续消耗 token）。
- **影响**：回复从切换那一刻被砍断（`onPause` 会先保存已收到的部分，所以不是全丢）。三个现实触发路径：① 流式中点消息区顶部的世界书 chip / 抽屉里"世界书"；② 流式中进设置页按保存；③ **流式中转屏**（manifest 无 `configChanges`，转屏走 onCreate）。
- **验证**：发一条长回复，在流式进行中打开世界书页再返回 → 剩余分片应继续出现在界面上；`isStreaming` 状态与顶栏转圈保持一致。
- **置信度**：已确认（另一路独立审计也复现了同一机制）。

### A5 角色列表删除/导入/新建后不刷新

- **位置**：`app/src/main/java/www/cetool/com/CharacterListActivity.kt:181-190`（`refresh()`/`bumpRefresh()`）、`:206-207`（Compose 侧）、调用点 `:82`、`:143`、`:171`、`:279`
- **现状代码**：

```kotlin
private fun refresh() { uiRefreshCount++ }          // 普通 Int，不是 snapshot state
companion object { var uiRefreshCount = 0; fun bumpRefresh() { uiRefreshCount++ } }
```
```kotlin
var refreshTick by remember { mutableStateOf(CharacterListActivity.uiRefreshCount) }  // 只赋值一次
val characters = remember(refreshTick) { loadCharacters() }                            // 永久缓存
```

- **问题**：`uiRefreshCount` 变更不会触发重组，且 `refreshTick` 初始化后再也没有被赋值 → `remember(refreshTick)` 永不失效。
- **影响**：删除的角色仍显示在列表里、导入或新建的角色看不见，必须退出 Activity 重进。对照 `WorldInfoListActivity.kt:124/:196` 用的是 `mutableIntStateOf(0)` + `remember(tick)`，那条路径是正确的，说明这是漏改而非设计。
- **验证**：进入管理页删除一张卡 → 该行应立即消失；导入一张卡 → 应立即出现。
- **置信度**：已确认。

---

## 3. B 类：功能失效 / 明显错误行为（P1）

### B1 角色卡内嵌世界书：三重失效，功能等于不存在

- **位置**：`manager/CharacterManager.kt:58-80`（探测器）、`model/TavernCard.kt:14-28`（数据模型）、`importer/WorldInfoImporter.kt:93-107`、`:197-253`（数组条目解析）、`:300-302`
- **现状代码**：

```kotlin
// CharacterManager.kt:70
val wb = card.data.extensions["world_book"] ?: return card
```
```kotlin
// WorldInfoImporter.kt:225 —— 数组条目走这里
keywords = keywordsOf(entry).toMutableList(),      // keywordsOf 只读 "keywords"
```
```kotlin
// WorldInfoImporter.kt:300-302
private fun keywordsOf(entry: JsonObject): List<String> =
    strList(entry.get("keywords")).map { stripRegexDelimiters(it) }
```

- **问题**（三层，逐层都要修）：
  1. **键名错**：酒馆规范把内嵌世界书放在 `data.character_book`；`world_book` 不是酒馆的键，全项目 grep 也找不到 `character_book`。
  2. **模型缺字段**：`TavernCardData` 没有 `character_book`，Gson 反序列化直接丢弃，随后 `CharacterManager.save`（`:55-63`）用 `TavernCardImporter.export` 重写文件 → **内嵌世界书在导入瞬间永久丢失**。
  3. **即使前两条修好也是空的**：`character_book.entries` 是**数组**，会走 `parseOwnEntry`，而它只读 `keywords`；酒馆数组条目用的是 `keys`、常驻用 `constant`（不是 `constantActive`）、顺序用 `insertion_order` → 每条都是"0 关键词 + 非常驻"，永远不可能触发。
- **影响**："导入角色卡自动建书并绑定"对合规卡片永不触发，且无任何警告；导入还会报"成功"并显示条目数（`WorldInfoListActivity.kt:161-165`），用户无法察觉。
- **验证**：导入一张带 `data.character_book`（数组条目、含 `keys`）的 v2 卡 → 世界书列表应出现同名书、条目有键；对话中命中关键词应能注入。
- **置信度**：已确认。

### B2 世界书注入位置映射与酒馆枚举不符（Outlet 被自动注入）

- **位置**：`importer/WorldInfoImporter.kt:33-43`、模型注释 `model/WorldInfo.kt:27`
- **现状代码**：

```kotlin
private val ST_POSITION_MAP = mapOf(
    0 to "before_char_defs", 1 to "after_system_prompt",
    2 to "before_example_messages", 3 to "after_example_messages",
    4 to "at_depth",
    5 to "after_system_prompt", // Top of AN（暂降级）
    6 to "after_system_prompt", // Bottom of AN（暂降级）
    7 to "at_depth"             // Outlet（暂降级）
)
```
- **问题**：酒馆实际枚举是 `0 before / 1 after / 2 ANTop / 3 ANBottom / 4 atDepth / 5 EMTop / 6 EMBottom / 7 outlet`。代码把 **AN 与 EM 两组搞反**（注释"5/6=AN"本身即误解）。后果：AN 条目被塞进示例对话槽；EM 条目降级到角色定义后；最严重的是 **7=Outlet 被映射为 `at_depth`** —— 酒馆设计上 Outlet 条目**从不自动注入**（只由 `{{outlet::名称}}` 手动拉取），这里会被关键词命中后直接注入提示词。
- **影响**：导入的酒馆世界书注入位置错误；Outlet 条目产生酒馆里不会出现的内容。`stPosition` 原值虽保留但引擎不读，错误位置会随保存/导出固化。
- **验证**：导入含 `position: 7` 且关键词命中的条目 → 不应出现在提示词里。
- **置信度**：已确认（酒馆枚举已核对；`stPosition` 注释与实现自相矛盾亦可佐证）。

### B3 `character_book` 的字符串位置被静默丢弃

- **位置**：`importer/WorldInfoImporter.kt:201-207`、`manager/WorldInfoEngine.kt:175-184`
- **问题**：数组条目（character_book）的 `position` 是字符串 `"before_char"` / `"after_char"`，代码走 `else -> str(rawPosition)` 原样保存。而 `WorldInfoEngine.buildInjection` 的 `when(entry.position)` 只认 `before_char_defs` / `after_system_prompt` / `after_char_defs` / `before_example_messages` / `after_example_messages` / `at_depth`，**没有 else 分支** → 该条目从提示词里消失且无任何提示。
- **验证**：导入数组条目书 → 条目的位置应可正常注入。
- **置信度**：已确认（与 B1 联动，B1 修好后才会真正暴露）。

### B4 `enabled` / `disable` 有判断、有写入，但全应用没有任何 UI 能改

- **位置**：判断 `manager/WorldInfoEngine.kt:32`（书级）、`:37`（条目级）；写入 `manager/WorldInfoManager.kt:52`、`importer/WorldInfoImporter.kt:158`；界面 `WorldInfoEditActivity.kt:104-149`（书级开关已被删除）、`:312-462`（条目编辑器无开关）、`WorldInfoListActivity.kt`（列表不显示启用态）
- **现状代码**：

```kotlin
// WorldInfoImporter.kt:158 —— 酒馆 disable=true 的条目导进来就是禁用
enabled = bool(entry.get("enabled"), true) && !bool(entry.get("disable"), false),
```
- **问题**：书级 `enabled` 全项目没有任何一处置 false（`WorldInfoEngine.kt:32` 的判断实际是死代码）；条目级 `disable: true`（酒馆中很常见）导入后为 false，而编辑器和列表都没有开关或禁用标识 → **永久失效且用户无从发现**。`WorldInfoEditActivity.kt:137` 的注释"启用状态由对话侧控制"不准确：对话侧控制的是**绑定**，不是这个标志。
- **验证**：导入含 `"disable": true` 条目的酒馆书 → 列表中该条目标记为禁用，且可一键启用。
- **置信度**：已确认。

### B5 次级关键词不剥正则定界符 → selective 条目永不触发

- **位置**：`importer/WorldInfoImporter.kt:129-137`（主键剥了）vs `:172`（次级键没剥）；`manager/WorldInfoEngine.kt:71-82`
- **现状代码**：

```kotlin
// :129-137 主键：会剥 /.../
if (useRegex) keywords = keywords.map { stripRegexDelimiters(it) }.toMutableList()
// :172 次级键：原样保留
secondaryKeys = strList(first(entry, "secondary_keys", "keysecondary", "secondaryKeys")).toMutableList(),
```
- **问题**：`useRegex` 为 true 时，引擎把次级键按正则编译，`/foo/` 只会匹配字面 `/foo/`；配合默认 AND ANY 逻辑，`passesSelective` 恒为 false → 条目永不激活。
- **验证**：导入 `secondary_keys: ["/敏感词/"]` + `selective: true` 的条目，主键命中后应能触发。
- **置信度**：已确认。

### B6 `keywordsOf` 无条件剥离定界符 + 空关键词 = 匹配一切

- **位置**：`importer/WorldInfoImporter.kt:294-302`、`:315-319`；受害者 `model/WorldInfo.kt:62-101`
- **现状代码**：

```kotlin
private fun keywordsOf(entry: JsonObject): List<String> =
    strList(entry.get("keywords")).map { stripRegexDelimiters(it) }   // 不看 useRegex，一律剥
```
```kotlin
// WorldInfo.kt:72-79
} else if (wholeWords && keyword.isNotBlank() && ...) { /* 边界匹配 */ }
else { compareText.contains(keyword) }      // keyword 为空串时恒为 true
```
- **问题**：数组条目（自有格式）的字面关键词 `/api/v1` 会被改成 `api/v1`；`//x` 会被剥成 `""`（`stripRegexDelimiters` 的 `substring(1, 1)`）。而空关键词未被过滤 → `contains("")` 恒真 → **该条目匹配一切消息**，且 `WorldInfo.matches` 的空值保护只存在于 whole-words 分支。`strList`（`:294-298`）也不过滤数组里的空字符串元素（对比 `WorldInfoEditActivity.splitKeywords` 是过滤的）。
- **验证**：构造含关键词 `""` 的条目 → 不应命中任何消息。
- **置信度**：已确认。

### B7 世界书预算裁剪用 `break`，一条大条目会丢弃其后全部

- **位置**：`manager/WorldInfoEngine.kt:121-138`
- **现状代码**：

```kotlin
for (entry in ordered) {
    val cost = estimateTokens(entry.content) + 30
    if (used + cost > maxTokens) break        // ← 应为 continue
    result.add(entry); used += cost
}
```
- **问题**：酒馆语义是逐条尝试、装不下就跳过该条继续试后续；这里一旦遇到超预算条目就整体终止，后面所有能装下的小条目全部被丢弃。默认预算 1500，一条 2000 token 的条目就会让整本世界书失效。
- **验证**：造一条超大条目 + 数条小条目 → 小条目仍应注入。
- **置信度**：已确认。

### B8 `@D` 深度注入比酒馆整体差一位

- **位置**：`ConversationManager.kt:258-266`
- **现状代码**：

```kotlin
val insertIndex = (historyItems.size - 1 - depth).coerceIn(0, historyItems.size)
historyItems.add(insertIndex, MessageItem.text("system", entry.content))
```
- **问题**：此处 `historyItems` 的末尾是**最新的用户消息**（构造循环 `for (i in startIndex until conv.messages.size - 1)` 排除了尾部空 assistant 占位），所以 `size-1-depth` 使 `depth=0` 插在用户消息**之前**；而酒馆 `depth=0` 是提示词最底部（`splice(chat.length, 0, msg)`），注释 `:258` 也自称"depth 0 = 提示词底部"，实现与注释不符，整体差一位。
- **验证**：插入一条 depth=0 的条目 → 应位于最后一条用户消息之后。
- **置信度**：已确认（与酒馆实现语义对照）。

### B9 消息列表索引 off-by-one：搜索跳转与自动滚到底都错一格

- **位置**：`ui/components/ChatComponents.kt:140-194`（列表）、`ChatActivity.kt:1249-1253`（搜索结果索引）、`:1289`（`scrollTargetState.value = position`）
- **现状代码**：

```kotlin
LazyColumn(state = listState) {
    if (systemPromptPreview.isNotBlank()) { item(key = "system-prompt-fold") { ... } }   // 占 index 0
    itemsIndexed(messages) { _, message -> ChatMessageRow(...) }                          // 消息从 index 1 起
}
LaunchedEffect(messages.size) { listState.animateScrollToItem(messages.size - 1) }        // 差一格
LaunchedEffect(scrollTarget) { ... listState.animateScrollToItem(target) ... }            // 差一格
```
- **问题**：系统提示折叠项使 LazyColumn 项数为 `messages.size + 1`，消息 i 位于 index `i+1`。`scrollTarget` 来自 `conv.messages` 的下标，直接传给 `animateScrollToItem` **永远跳到上一条**；`messages.size - 1` 也只会滚到倒数第二条。只要系统提示非空（角色对话必然如此）就 100% 发生。
- **验证**：搜索一条靠中间的消息 → 应精确跳到该条。
- **置信度**：已确认（另一路审计独立命中）。

### B10 "重试"丢附件，且旧格式消息会把文件全文当正文重发

- **位置**：`ChatActivity.kt:2038-2058`
- **现状代码**：

```kotlin
val lastUserText = conv.messages[lastUserIdx].content      // ← 用 content 而非 displayText()
conv.messages.removeAt(conv.messages.size - 1)
conv.messages.removeAt(lastUserIdx)
performSend(lastUserText)                                  // 附件（pendingAttachment*）未恢复
```
- **问题**：① 取的是 `content` 而非 `displayText()`：旧格式消息的 `"[文件: 名]\n<全文>"` 前缀会被当成用户输入重发，同时附件丢失 → 巨量重复内容进上下文；② 图片/文本附件被丢弃，重试等于换了一条消息。
- **验证**：带图片附件发一条 → 触发错误 → 重试，附件应仍在。
- **置信度**：已确认。

### B11 图片一律压成 JPEG，却按原文件名判定 MIME

- **位置**：`ChatActivity.kt:2091-2124`（压缩写死 JPEG）、`ConversationManager.kt:237-239`（按扩展名判 MIME）
- **现状代码**：

```kotlin
scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)   // 永远是 JPEG 字节
pendingAttachmentName = fileName                                       // 可能是 a.png
```
```kotlin
val mimeType = if (msg.attachmentName?.lowercase()?.endsWith(".png") == true) "image/png" else "image/jpeg"
```
- **问题**：`a.png` 会生成 `data:image/png;base64,<JPEG 字节>`。严格校验 MIME 的网关会拒收或解码失败。
- **验证**：上传 .png → 检查请求体里 data URL 的 MIME 与字节头一致。
- **置信度**：已确认。

### B12 请求失败时顶栏永远停在"模型：正在获取模型…"

- **位置**：`network/AiApiClient.kt:284-298`（`onFailure` 不调用 `finishStream()`）、`:216-220`；`ChatActivity.kt:1993-2013`
- **问题**：`finishStream()` 只在 `[DONE]`、`finish_reason == "stop"`、`onClosed` 里调用。OkHttp 的 `onFailure` 是终止路径，之后不会再走 `onClosed` → `onModelInfo(null, null)` 永不回调 → `modelInfoText` 停在"正在获取模型…"（错误信息只出现在气泡里）。
- **验证**：把 API 地址改成不可达地址 → 顶栏应显示"获取失败"。
- **置信度**：已确认（需与 B13 一起改）。

### B13 `finishStream()` 会被调用 2~3 次

- **位置**：`network/AiApiClient.kt:217-228`、`:250`、`:280`、`:295-297`
- **问题**：`[DONE]` 分支、`finish_reason == "stop"` 分支、`onClosed` 回调三处都会调 `finishStream()`，正常一轮至少触发两次。每次 `onFinish` 都会 `save()` 整个会话库并触发两次 UI 全量刷新（见 D1）。
- **验证**：一轮流式结束后 `save()` 只被调用一次（打日志确认）。
- **置信度**：已确认。

### B14 已保存的 API 条目无法编辑（`apiDialogIndex` 是死变量）

- **位置**：`SettingsActivity.kt:128`（声明）、`:585`（唯一赋值，写 `null`）、`:816-822`（消费）、`:1135-1145`（`settings_edit_api` 文案）
- **现状代码**：

```kotlin
apiDialogIndex = null          // 全项目唯一赋值点
showApiDialog = true
...
val idx = apiDialogIndex       // 永远是 null
if (idx != null && idx < apiEntries.size) apiEntries[idx] = newEntry else apiEntries.add(newEntry)
```
- **问题**：卡片点击只设 `activeApiIndex`（`:574`），没有任何路径给 `apiDialogIndex` 赋非空值 → 更新分支、编辑文案、以及只在编辑态出现的 `provider_free_hint` 块全部是死代码。用户想改 URL/key 只能删了重建。
- **验证**：点已有条目 → 弹窗应带出原值，保存后原地更新而不是新增。
- **置信度**：已确认（已 grep 全仓库复核）。

### B15 删除 API 条目后"当前条目"索引错位 → 用错端点/密钥

- **位置**：`SettingsActivity.kt:570-581`（同样的问题也存在于 `ConversationManager.kt:84-98`，见 B19）
- **现状代码**：

```kotlin
onDelete = {
    apiEntries.removeAt(index)
    if (activeApiIndex >= apiEntries.size) { activeApiIndex = (apiEntries.size - 1).coerceAtLeast(0) }
}
```
- **问题**：只处理"越界"，不处理"删除项在活动项之前"。`[A,B,C,D]` 且 B 是当前项，删掉 A → 列表变 `[B,C,D]`，`activeApiIndex` 仍是 1 → 高亮跑到 **C**；保存后 `ai_config`（`SettingsActivity.kt:512-520`）被写成 C 的 url/key/model —— 静默换端点、发错密钥。
- **验证**：三个条目，选中间那个，删掉第一个 → 高亮应保持在原来那一条。
- **置信度**：已确认。

### B16 切换服务商模式不同步 `ai_config`

- **位置**：`SettingsActivity.kt:391-417`（`persistProviderState`，切模式即写）vs `:477-525`（`saveSettings` 才写 `ai_config`）；读取方 `ChatActivity.kt:2147-2154`；相关 `model/ApiConfig.kt:12-16`、`SplashActivity.kt:151-169`
- **问题**：`ai_config`（`ai_config` prefs：`api_url`/`api_key`/`model`）**只在 `saveSettings()` 里写**，而切换模式/模型的 `persistProviderState()` 只写 `app_settings`。复现：填了自定义 key → 切 OpenKilo（活动条目被预设 URL 覆写、key 清空）→ 保存 → 切回"自定义"（UI 恢复了条目）→ **按返回而不是保存** → 下次启动 `providerMode=CUSTOM` 但 `ai_config` 仍是 Kilo 的地址和空 key → `isValid()` 为 false → 被弹回设置页，而用户明明看到有条目。若之前用的是 Zen（key 固定串 `"public"`），`isValid()` 反而为 true → 请求静默打到 Zen 端点，且自定义模式不附带 Zen 必需的 `x-session-id`（`AiApiClient.kt:152-164`）。
- **验证**：自定义 → Kilo → 保存 → 切回自定义 → 返回 → 杀掉进程重开，应仍使用自定义条目而不是被弹回设置页。
- **置信度**：已确认。

### B17 设置页"保存"叠加第二个 ChatActivity，并让旧实例的回调失效

- **位置**：`SettingsActivity.kt:523-524`、`ChatActivity.kt:314-369`、`AndroidManifest.xml:30-34`
- **现状代码**：

```kotlin
startActivity(Intent(this, ChatActivity::class.java))   // 无 FLAG_ACTIVITY_CLEAR_TOP/SINGLE_TOP，manifest 无 launchMode
finish()
```
- **问题**：返回栈变成 `[Chat#1, Chat#2]`。`ConversationManager.onConversationsChanged` / `onCurrentMessageUpdated` 只在 `ChatActivity.onCreate` 注册、在 `onDestroy` 置 null，且 `onResume` 不重注册 → 从 Chat#2 返回后，Chat#1 已恢复显示但两个回调都是 null（抽屉/历史不再自动刷新），且 Chat#1 的 `apiConfig`/`providerMode`/字号等都是 onCreate 时的旧值。同文件语言切换用的是 `FLAG_ACTIVITY_CLEAR_TASK`（`:925-932`），说明这里更像漏改。
- **验证**：聊天页 → 设置 → 保存 → 按返回：应直接回到原来的聊天页（只有一个实例），且流式刷新正常。
- **置信度**：已确认。

### B18 当前会话恢复不一致（`KEY_CURRENT_ID` 只在 `switchTo` 写）

- **位置**：`ConversationManager.kt:26-29`（键常量）、`:73-82`（`switchTo` → `saveCurrentId`）、`:436-440`（`saveCurrentId`）、`:421-423`（`load` 的读取顺序）
- **现状代码**：

```kotlin
val savedCurrentId = prefs.getString(KEY_CURRENT_ID, null)
val idx = conversations.indexOfFirst { it.id == (savedCurrentId ?: data.currentId) }
```
- **问题**：`save()` 每次都写 `data.currentId`，但 `KEY_CURRENT_ID` 只在 `switchTo()` 里写，且 `load()` **优先读 `KEY_CURRENT_ID`**。于是：切过一次历史会话 → 再新建会话 → 杀进程重开，回到的是那个历史会话而不是正在用的新会话。`delete()` 也不更新该键，删掉当前会话后它会指向已删除 id 并回落到第 0 条。
- **验证**：新建会话 → 杀进程重开 → 应回到该新会话。
- **置信度**：已确认。

### B19 删除会话后 `currentIndex` 不下移 → 界面跳到别的会话

- **位置**：`ConversationManager.kt:84-98`
- **现状代码**：

```kotlin
conversations.removeAt(index)
if (currentIndex >= conversations.size) { currentIndex = (conversations.size - 1).coerceAtLeast(0) }
```
- **问题**：`[A,B,C]`，当前是 B（`currentIndex=1`），删掉 A → 列表变 `[B,C]`，`currentIndex` 仍是 1 → 当前会话变成 **C**（用户正在看的 B 被切走）。
- **验证**：三个会话，停在第二个，删掉第一个 → 仍应停在原来那个（现在在索引 0）。
- **置信度**：已确认。

### B20 Web 搜索是死功能

- **位置**：`ChatActivity.kt:352-353`、`:562-569`（开关与菜单）、`SettingsActivity.kt:193`、`:502`（URL 配置）、`SettingsKeys.kt:17-18`
- **问题**：`KEY_WEB_SEARCH_URL` / `KEY_WEB_SEARCH_ENABLED` 只被"读写"和用来显示菜单文案，**全项目没有任何一处发起搜索请求**，也没有把搜索结果拼进提示词的代码。开关点了只改变自己和 prefs。
- **验证**：开启开关并发送消息 → 请求体里应出现搜索结果（若实现）。
- **置信度**：已确认。

### B21 `{{char}}` 未实现，历史消息里的 `{{user}}` 不替换

- **位置**：`ConversationManager.kt:216-226`（只对系统文本做 `{{user}}` 替换）、`model/Conversation.kt:56-70`（只支持 3 个时间变量）
- **现状代码**：

```kotlin
val systemText = systemParts.filter { it.isNotBlank() }.joinToString("\n\n")
if (systemText.isNotBlank()) historyItems.add(MessageItem.text("system", Conversation.resolveSystemPrompt(systemText)))
...
historyItems.replaceAll { item -> ... 替换 {{user}} ... }     // 此刻 historyItems 里只有 system 一条
```
- **问题**：① `{{char}}` 全项目无实现 → 角色卡的 `first_mes`/`description`/`scenario` 里的 `{{char}}` 会以字面量进入上下文（酒馆卡片大量使用）；② `{{user}}` 的替换发生在历史消息入列**之前**，所以 `first_mes` 与用户自己输入里的 `{{user}}` 都不会被替换。
- **验证**：导入含 `{{char}}`/`{{user}}` 的卡 → 发出的请求体里应无残留占位符。
- **置信度**：已确认。

### B22 世界书条目正则被逗号切碎（编辑器往返损坏）

- **位置**：`WorldInfoEditActivity.kt:319`（join）、`:341-342`（save）、`:477-482`（split）
- **现状代码**：

```kotlin
var keywords by remember { mutableStateOf(entry.keywords.joinToString(", ")) }
...
keywords = splitKeywords(keywords)
...
private fun splitKeywords(text: String) = text.split(',', '，', '、', '\n')...
```
- **问题**：酒馆允许正则键里含逗号（`a{1,3}`、`\d{2,4}`、`(foo|bar){2,3}`）。打开条目再保存，一个键会被切成两个，且原键永久损坏。
- **验证**：含 `a{1,3}` 的条目 → 打开编辑 → 保存 → 键应保持一个。
- **置信度**：已确认。

### B23 长按行为与界面文案不符，且删除无二次确认

- **位置**：`WorldInfoEditActivity.kt:238-244`（`onLongClick = { onDeleteEntry(index) }`，无确认）、`WorldInfoListActivity.kt:244-251`（`onLongClick = { pendingDelete = info }`）、`:266-271`（行内提示）、`res/values/strings.xml:337`（`world_list_longpress_hint` = "长按对话启用"）、`:411`（"点击条目编辑，长按删除"）
- **问题**：① 条目卡片长按**立刻删除**，在可滚动列表里极易误触（虽有"未保存"可挽回，但用户按了保存就永久丢失）；② 世界书列表每一行都显示"长按对话启用"，而该行长按实际弹出的是 编辑/导出/删除 菜单 —— 文案与行为不符，且误触路径尽头就是删除。
- **验证**：长按条目 → 应弹确认框；世界书列表文案不再误导。
- **置信度**：已确认。

### B24 角色身份标签只能加不能删（且第 9 个起不可见）

- **位置**：`CharacterEditActivity.kt:373`（`TagRow(tags = state.identityTags)`，未传 `onTagClick`）、`ui/components/BasicComponents.kt:138-167`（`TagRow` 只渲染 `take(8)`，无删除入口）
- **问题**：打错的标签永久留在卡里，超过 8 个的标签完全不可见也无法管理。
- **验证**：加 10 个标签 → 能看到"还有 2 个"并能逐个删除。
- **置信度**：已确认。

### B25 角色卡"效果预览"永不更新

- **位置**：`CharacterEditActivity.kt:287-308`
- **现状代码**：

```kotlin
val compiled = remember(state) { ...CharacterCompiler.compileMarkdown(...)... }
```
- **问题**：`state` 是 `onCreate` 里创建的**同一个对象实例**（`:93`），`remember(key)` 用 `equals` 比较，永远相等 → lambda 只执行一次。字段编辑会触发重组（读的是 snapshot state），但 `compiled` 始终是 Activity 创建时的内容；新建角色时永远是空白。整个预览功能是死的。
- **验证**：编辑姓名/字段 → 预览应实时变化。
- **置信度**：已确认。

### B26 世界书"加载丢坏条目 + 保存固化"导致条目永久消失

- **位置**：`manager/WorldInfoManager.kt:64-111`（`parseEntries` 逐条 `catch (_: Exception) {}` 静默跳过）、`:113-128`（`saveNew`/`overwrite` 用内存列表全量重写）；`WorldInfoEditActivity.kt:110-149`
- **问题**：加载时任何解析失败的条目被静默丢弃，用户看不到；一旦在编辑页保存，丢失就被固化到文件里。同类问题：`WorldInfoManager.load` 遇到整体解析失败返回 null（`:44-55`），书直接从列表消失（`list` 的 `mapNotNull` 也吞掉，`:31-36`）。
- **验证**：手工把某条目的 `content` 改成非法类型 → 列表应提示有坏条目，保存后原始 JSON 字段仍在（不被静默删除）。
- **置信度**：已确认（机制清晰；触发依赖文件损坏）。

### B27 世界书保存/导出会变成自有格式，无法再导回酒馆

- **位置**：`manager/WorldInfoManager.kt:134-193`
- **问题**：`buildJson` 写出的是 `{version:1, type:"lorebook", data:{..., entries:[...]}}`（数组 + camelCase 字段），而酒馆世界书是 `{entries:{uid:{keys:[...], insertion_order:...}}}`（对象 + snake_case）。编辑一次后导出，再导入酒馆会失败/丢键。
- **验证**：导入酒馆书 → 编辑 → 导出 → 用酒馆导入该文件。
- **置信度**：已确认。

### B28 角色卡模型缺字段 → 导入即丢（character_book 之外还有一批）

- **位置**：`model/TavernCard.kt:14-28`（`TavernCardData` 字段列表）、`importer/TavernCardImporter.kt:24`（`gson.fromJson(dataObj, ...)`）
- **问题**：Gson 只反序列化已建模字段，未建模的 JSON 成员被丢弃，随后 `CharacterManager.save` / `overwrite` 会重写文件 → 永久丢失。除 `character_book`（见 B1）外，v3 的 `assets`、`nickname`、`creator_notes_multilingual`、`source`、`group_only_greetings`、`creation_date`、`modification_date`，v2 的 `alternate_greetings`（已建模但编辑页不收，见 A3）都会受影响。
- **验证**：导入带 `assets`/`nickname` 的 v3 卡 → 保存后这些字段仍在。
- **置信度**：已确认（字段清单与酒馆规范对照）。

### B29 v1 角色卡与 PNG 角色卡不支持

- **位置**：`importer/TavernCardImporter.kt:11-33`（要求 `spec` 为 `chara_card_v2`/`v3` 且必须有 `data`）
- **问题**：① 酒馆 v1 卡是扁平结构（无 `spec`/`data`）→ 直接报"不支持的格式"；② PNG 卡（生态里最主流的分发格式，JSON 藏在 `tEXt`/`iTXt` 的 `chara` 块）全项目无解析代码；导入入口允许选 `*/*`（`CharacterListActivity.kt:144`、`ChatActivity.kt:1652`），用户选中 .png 后会按 UTF-8 读二进制再解析失败，只得到"格式无效"。
- **验证**：导入 v1 JSON 卡与酒馆 PNG 卡。
- **置信度**：已确认。

### B30 酒馆聊天记录的 `is_system` 消息被当成角色发言

- **位置**：`importer/STChatLogImporter.kt:20-51`
- **现状代码**：

```kotlin
if (isSystem && mes.isBlank()) return@forEach           // 只跳过空白
val role = if (isUser) Message.ROLE_USER else Message.ROLE_ASSISTANT   // system 也走 assistant
```
- **问题**：非空白的 system/旁白条目被记成角色发言；`name` 字段（文件头注释里明确提到）从未读取，群聊记录里其他角色的台词全部记到当前角色名下，且不可恢复。
- **验证**：导入含 system 行的 .jsonl → 对话里不应出现被误归属的旁白。
- **置信度**：已确认。

### B31 世界书的"分组"等字段在编辑器里可填但引擎不读

- **位置**：`WorldInfoEditActivity.kt:416-437`（`groupName` 输入框、`groupWeight` 无入口但卡片显示相关字段）、`manager/WorldInfoEngine.kt`（全文件只消费 `enabled/constantActive/keywords/selective*/probability/scanDepth/role/priority/position/injectDepth`）
- **问题**：`groupName`、`groupWeight`、`sticky`、`cooldown`、`delay`、`excludeRecursion`、`preventRecursion`、`delayUntilRecursion`、`triggers`、`injectRole`、`score`、`vectorized`、`automationId`、`displayIndex`、`stPosition` 全部只存不用（已 grep 核实：在引擎与 `ConversationManager` 里 0 命中）。其中**分组名是界面上可见可填的**，用户会以为同组互斥生效。
- **验证**：给两条同组条目 → 若实现了分组，应只注入一条。
- **置信度**：已确认（grep 级）。

---

## 4. C 类：数据一致性与边界（P2）

### C1 附件与消息正文会双份进入上下文（旧数据路径）

- **位置**：`model/Message.kt:21-45`（`displayText`/`stripLegacyAttachmentPrefix`）、`ConversationManager.kt:240-251`（发文件块）、`ChatActivity.kt:1250`（搜索用 `content`）、`:2044`（重试用 `content`）
- **问题**：新写入路径已经把附件与正文分离，但**消费端有几处仍在读 `content`**：搜索（`:1250`）、重试（`:2044`）。旧数据（`"[文件: 名]\n<全文>"` 前缀）在这些地方会带出文件全文；`WorldInfoEngine.buildSearchSpace`（`:104`）同样用 `msg.content`，让旧附件全文参与关键词匹配。
- **置信度**：已确认。

### C2 Gson 会把 JSON `null` 写进 Kotlin 非空属性 → NPE 风险

- **位置**：`model/TavernCard.kt:14-28`、`model/WorldInfo.kt:11-25`、受害者 `manager/CharacterManager.kt:31`（`.take(100)`）、`:70`（`extensions["world_book"]`）、`CharacterListActivity.kt:317`（`char.tags.isNotEmpty()`）
- **问题**：Gson 反射赋值不做 Kotlin 空检查，`"description": null` / `"tags": null` / `"extensions": null` 都会把非空类型字段置为 null。可达路径：`extensions == null` → `CharacterManager.kt:70` 立刻 NPE；`description == null` → `:31` 抛异常后被 `catch (_: Exception) { null }` 吞掉 → **卡片从列表静默消失**；`tags == null` → 赋值给非空属性抛 NPE。
- **置信度**：需复现（机制确定，触发需要含 `null` 的卡文件）。

### C3 删除世界书/角色卡不清理引用

- **位置**：`WorldInfoListActivity.kt:136`（只删文件）、`Conversation.kt:19-21`（会话仍保存 `worldInfoId(s)`）、`CharacterManager.kt:89-92`（删卡不删内嵌书）、`:74`（重复导入重复建书）
- **影响**：删掉世界书后会话里留着死 id（当前因两处 `mapNotNull` 过滤而无害）；删掉角色卡后它创建的内嵌世界书永远留在世界书列表里；重复导入同一张卡会产生多本重复世界书。
- **置信度**：已确认。

### C4 世界书编辑页/角色编辑页保存路径无 IO 保护

- **位置**：`WorldInfoEditActivity.kt:145-149`、`CharacterEditActivity.kt:217-219`、`manager/WorldInfoManager.kt:113-128`（`writeText`）、`manager/CharacterManager.kt:82-87`
- **问题**：`File.writeText` 在磁盘满/权限异常时抛 IOException，编辑器没有 try/catch（列表页的导入路径是有的）→ 崩溃，且用户编辑内容全丢。写入也不是原子的（无临时文件 + rename），中途进程被杀会留下半个文件；而 `load` 遇到坏文件返回 null → 书/卡从列表消失且无提示。
- **置信度**：已确认。

### C5 编辑器把界面文案写进数据

- **位置**：`WorldInfoEditActivity.kt:337-359`（`name = name.trim().ifBlank { context.getString(R.string.world_edit_unnamed_entry) }`）
- **问题**：本地化的"未命名条目"/"Unnamed entry"被写进 JSON 并持久化，切语言后仍是旧语言的假名字；用户也无法清除该名字（保存时又会被填回）。
- **置信度**：已确认。

### C6 知识/预算等数值输入静默钳制，界面仍显示未保存的值

- **位置**：`SettingsActivity.kt:486`（字号无下限，可存 0 → 聊天文字全部不可见）、`:488-497`（历史轮数/世界书预算 `coerceIn` 但输入框不回填）、`:665`/`:675`（`take(3)`/`take(5)` 允许 999/99999）
- **置信度**：已确认。

### C7 保存设置会把"当前语言的默认系统提示词"写死

- **位置**：`SettingsActivity.kt:190` + `:510`、`ConversationManager.kt:442-452`
- **问题**：`getDefaultSystemPrompt()` 回落到语言相关资源（中文版"禁止使用英文回复"、英文版"Default output language: English"，语义相反）。切到英文后按一次保存，就把英文规则持久化进 prefs；切回中文也不会恢复（已保存的 pref 优先于资源）。
- **置信度**：已确认。

---

## 5. D 类：性能与稳定性（P3）

### D1 每个流式分片都在做磁盘 I/O（最严重）

- **位置**：`ChatActivity.kt:2014-2016`（`onUiUpdate` → `refreshCurrentConversation()`）、`ConversationManager.kt:287-310`（每个 chunk 回调）、`ChatActivity.kt:1799-1833`（`refreshCurrentConversation` 内部）
- **现状代码**：

```kotlin
onUiUpdate = { runOnUiThread { refreshCurrentConversation() } }
```
```kotlin
// refreshCurrentConversation 内部每次都会：
val card = CharacterManager.loadCard(this, conv.characterId!!)        // 读文件 + JSON 解析（含 base64 头像）
bindingWorldState.value = conv.boundWorldIds().mapNotNull { WorldInfoManager.load(this, it)?.name }  // 每本一次读盘
drawerConversations = ConversationManager.all                          // 复制全部会话
messagesState.value = conv.messages.toList()
```
- **问题**：每个 chunk 触发一次全量刷新 → 多次读盘 + 多次 JSON 解析 + 复制整个会话列表；而且同一次 chunk 还会再触发 `onCurrentMessageUpdated`（`ChatActivity.kt:328-334`）第二次全量刷新。**每个 token 两次全量刷新 + 多次文件读取**，是卡顿/ANR 的主要来源。
- **验证**：发一条长回复，观察是否仍有逐 token 的磁盘读取（StrictMode 或日志）。
- **置信度**：已确认。

### D2 整个会话库（含 base64 附件）存 SharedPreferences，每次全量重写

- **位置**：`ConversationManager.kt:359-388`（`save()`）、`:390-434`（`load()`）、`ChatActivity.kt:2114`（附件上限 20MB → base64 约 27MB）
- **问题**：`save()` 把所有会话序列化成一个大 JSON 写进 `prefs`，触发点是流结束/报错/onPause/切页；加载时整份 XML 全量解析。带图片附件的单个会话就能让 prefs 到几十 MB，启动即卡。
- **置信度**：已确认。

### D3 原图全尺寸解码 + OOM 抓不到

- **位置**：`ChatActivity.kt:2093`、`SettingsActivity.kt:424`、`:453`、`CharacterEditActivity.kt:139`、`CharacterListActivity.kt:341`
- **问题**：全部直接 `decodeStream`/`decodeByteArray` 全尺寸解码后再缩放，没有 `inSampleSize`/`inJustDecodeBounds`；且 `catch (e: Exception)` **抓不住 `OutOfMemoryError`（它是 Error）** → 选图即崩。8000×6000 的照片约 192MB（ARGB_8888）。
- **验证**：选一张 100MP 图片作为背景/头像/附件。
- **置信度**：已确认（OOM 未被捕获是语言级事实）。

### D4 主线程磁盘 I/O

- **位置**：`CharacterListActivity.kt:207`（**组合期**调用 `CharacterManager.list()` → 逐文件读取+解析，`manager/CharacterManager.kt:19-39`）、`WorldInfoListActivity.kt:196`（同样在组合期 `WorldInfoManager.list()`）、`BondActivity.kt:75-89`（onCreate 里对每个封存角色 `loadCard`）、`ChatActivity.kt:1812`/`:1824`、`ConversationManager.kt:186-196`（每次发送重新读盘加载全部绑定世界书，且 `WorldInfoManager` 完全没有内存缓存）
- **置信度**：已确认。

### D5 消息列表重组成本高

- **位置**：`ui/components/ChatComponents.kt:668`（每个气泡 `remember { Markwon.builder(...).build() }` → N 条消息 N 个解析器）、`:421`（`parseContentBlocks` 每次重组整段跑正则）、`:527-528`（附件全文 O(n) 统计）、`:468`（每次 new `SimpleDateFormat`）、`:635-639`（"按需"全文查看器实际把全文塞进一个非惰性 `verticalScroll` 的 `Text`）
- **置信度**：已确认。

### D6 每次请求新建 OkHttpClient

- **位置**：`network/AiApiClient.kt:32`（每个实例一个）、`:119-122`（`fetchModels` 又建一个）、`:338`（`requestJson` 再 `newBuilder`）；调用方每次发送都 `AiApiClient(...)`（`ConversationManager.kt:283`）
- **问题**：OkHttpClient 自带连接池与线程池，频繁新建等于频繁丢弃连接池与线程。
- **置信度**：已确认。

### D7 SSE 解析整块空 catch，吞掉一切异常

- **位置**：`network/AiApiClient.kt:231-281`（`catch (_: Exception) {}` 包住 chunk 解析**与所有回调**）
- **问题**：任何异常（含 `onText`/`onReasoning`）都被静默吞掉，线上问题无法定位；解析失败的分片也会被无声丢弃。
- **置信度**：已确认。

### D8 线程安全：`conversations` / `conv.messages` 跨线程读写，`save()` 可能静默失败

- **位置**：`ConversationManager.kt:35`（普通 `mutableListOf`）、`:287-310`（SSE 线程写）、`:359-388`（`save()` 在 SSE 线程遍历 `conversations`，整体包 try/catch）、`ChatActivity.kt:331`（主线程 `toList()`）
- **问题**：SSE 线程遍历 `conversations` 的同时主线程 `createNew()`/`delete()` 会修改它 → `ConcurrentModificationException` 被 `save()` 的 catch 吞掉 → **静默保存失败**（数据只在内存里）。`messages` 的跨线程读写同理（UI 侧 `runOnUiThread` 是对的，缺的是数据结构同步）。
- **置信度**：需复现（并发窗口小，但机制成立）。

### D9 旋转/进程死亡丢失全部未保存状态

- **位置**：`AndroidManifest.xml`（所有 Activity 无 `configChanges`）、`CharacterEditActivity.kt:86-134`、`WorldInfoEditActivity.kt:100-157`、`ChatActivity.kt:159-235`（大量 `mutableStateOf` 字段）、`CharacterListActivity.kt:78`/`WorldInfoListActivity.kt:75`（`exportPendingJson`）、`ChatActivity.kt:235`（`selectWorldOnReturn`）
- **问题**：输入的文本、打开的对话框、编辑页未保存内容全部丢失；导出流程跨配置变更后 `json == null` → **不写文件也不提示**（成功/失败 toast 都在 `if` 分支内）。
- **置信度**：已确认。

### D10 记忆封存无长度控制

- **位置**：`manager/MemoryArchiver.kt:56-80`（拼全部消息）、`:88-91`（失败原样重试一次）
- **问题**：超长会话会把全部原文塞进单次请求（可能超上下文），且重试是同样的请求（不是降级/截断）。
- **置信度**：已确认。

---

## 6. E 类：安全 / i18n / 清洁度（P4）

| 编号 | 位置 | 问题 |
|---|---|---|
| E1 | `ChatActivity.kt:950-953`、`strings.xml:159` | **发布版仍带"测试崩溃上报"入口**，点击直接 `throw NullPointerException` |
| E2 | `AndroidManifest.xml:8` | `allowBackup="true"` 且无 `fullBackupContent`/`dataExtractionRules`（`res/xml` 不存在）→ `ai_config` 里的明文 API Key 与全部会话数据进云备份/设备迁移 |
| E3 | `AndroidManifest.xml:13` | 全局 `usesCleartextTraffic="true"`，而 API 地址是用户自由输入、Key 走 `Authorization` 头 → 指向 `http://` 局域网端点时 token 明文外发 |
| E4 | `AndroidManifest.xml:25-28` | `SettingsActivity` 为 `exported="true"` 且无 intent-filter，任何应用可拉起密钥填写页（它不读任何 extra，改 false 无副作用） |
| E5 | `SettingsActivity.kt:1164-1174` | API Key 明文回显（无 `PasswordVisualTransformation`），明文存 `SharedPreferences` |
| E6 | `ui/components/BasicComponents.kt:87` | `contentDescription = if (expanded) "收起" else "展开"` —— 全项目唯一漏网硬编码中文（英文模式 TalkBack 念中文） |
| E7 | `SettingsActivity.kt:471`/`:787`/`:793`/`:615` | 把 `"我的"`/`"AI "` 拼进 `%s头像已选择` → 英文界面显示 "我的avatar selected"；`:615` 往英文串插全角冒号 |
| E8 | `AboutActivity.kt:142`、`SettingsActivity.kt:676`（`strings.xml:310`/`:284`） | 字符串里的 `%%` 在不传参数直接 `stringResource`/`getString` 取用时**原样显示**（`%%` 只在 `String.format` 里还原）→ 页面显示 "99%% 纯天然 AI 制作" |
| E9 | `LocaleHelper.kt:28`/`:35` | "跟随系统"无法在进程存活期间恢复：`resolveLocale` 用 `Locale.getDefault()`，而 `apply()` 会 `Locale.setDefault(...)` 覆盖它；语言切换只重启任务（`FLAG_ACTIVITY_CLEAR_TASK`）不重启进程 |
| E10 | `CrashReporter.kt:34-35`、`AboutActivity.kt:82` | 隐式 `ACTION_VIEW` 无 `ActivityNotFoundException` 保护 → 无浏览器设备上崩溃（前者恰在崩溃上报流程里） |
| E11 | `SplashActivity.kt:61`/`:65`（`CrashReporter.kt:22-25`） | 崩溃日志在**组合期**读盘（主线程），且在用户点按前就删除 → 转屏/被杀即丢报告 |
| E12 | `ChatActivity.kt:1812`/`:1824`、`BondActivity.kt:75`、`settings/世界书列表` | `ConversationManager.init` 不是由 Application 调用，`BondActivity` 等入口未初始化 → 进程被回收后从这些页面恢复会显示"空列表"（无崩溃） |
| E13 | `SettingsActivity.kt:295`/`:299`/`:353-366` | Zen 模式首帧用 Kilo 的兜底模型列表；"刷新列表"在加载中静默无响应；`fillPresetIntoActiveEntry` 在条目已存在时丢弃新 label |
| E14 | 全项目 | 死代码/无效字段：`TavernCardData.isVaild()` 从未调用（也无处校验卡名，空名卡可导入成功）、`WorldInfo.enabled` 永远为 true、`SectionCard` 里的 `Spacer(Modifier.width(0.dp))`、`AiApiClient.FetchModels` 的临时 client |
| E15 | `ChatComponents.kt:646-652` | 全文查看器"复制"按钮 1.5 秒复位，连点两次会提前复位 |

---

## 7. 已确认无问题（不要重复排查）

1. **字符串资源完整性**：`values/strings.xml` 与 `values-en/strings.xml` 各 388 条，**键集完全一致、无重复**；42 个带格式符的键在两个语言下占位符列表一致；不存在把裸 `%` 当格式串且有参数的调用 → 不会抛 `UnknownFormatConversionException`。
2. **SharedPreferences 键与类型**：`SettingsKeys` 的定义、`SettingsActivity` 的读写、`ChatActivity`/`ConversationManager`/`SplashActivity` 的读取全部一致，无键名/类型错配；各处的默认值也一致（15 / 20 / 1500 / "color" / "fit" / "用户" / "AI" / `PROVIDER_CUSTOM`）。
3. **`worldInfoId` 与 `worldInfoIds` 同步**：三个写入点都走 `Conversation.setBoundWorlds`，`boundWorldIds()` 有旧数据兜底，保存/加载两端都持久化 → 不存在不一致。
4. **`WorldEntry` 保存/加载往返完整**：34 个字段逐一对照，全部写入且全部读回（仅 `role`/`injectRole` 条件写入，两侧默认值一致为 null）。
5. **酒馆世界书 v1/v2「对象型 entries」文件导入是正确的**：`keys`/`key`/`keysecondary`/`insertion_order`/`order`/`comment`/`constant`/`disable`/`extensions.*` 新旧字段名都兼容，逐条容错并收集 warnings。
6. **引擎的 selective 四种逻辑、常驻优先 + order 升序 + 预算算法、scanDepth 窗口语义**与酒馆一致（仅 B5/B6/B7 三处偏差）。
7. **权限与组件声明**：只需 `INTERNET`（全部文件访问走 SAF，无需存储权限）；10 个被 `startActivity` 的 Activity 全部在 manifest 声明且类存在；`exported` 均已显式声明；主题 / 图标 / `values-night` 均可解析；未使用任何 minSdk 24 以上且无版本守卫的 API。
8. **`LocaleHelper` 的注入方式本身正确**（`attachBaseContext` + `createConfigurationContext`，10 个 Activity + Application 全覆盖），不存在重启死循环。
9. **Compose 侧没有"组合期写状态"问题**；每个流式分片的 UI 写入都正确地经 `runOnUiThread` 进入主线程（缺的是数据结构同步，见 D8）。
10. **`NoItalicPlugin`** 行为符合类名意图（去掉斜体样式并保留文字）。
11. **导入/导出路径的资源关闭**（`use {}`）、**LazyColumn 的 key 唯一性**、**删除目标的 id 正确性**、**`CharacterCompiler` 的旧字段去重逻辑**均无问题。

---

## 8. 背景机制（理解下列问题所必需）

### 8.1 ConversationManager 是全局单例 + 全内存状态

- `object ConversationManager` 持有 `conversations: MutableList<Conversation>`，`Conversation.messages` 是 `MutableList<Message>`。
- **持久化模型**：整个会话库序列化成一个 JSON 字符串存在 `SharedPreferences("conversations")` 的 `conversation_data` 键下（`save()`/`load()`），**没有数据库、没有分文件**。
- **`init(context)` 会清空并重建内存对象**（`load()`），有三处调用：`ChatActivity.kt:316`（onCreate）、`SettingsActivity.kt:509`、`WorldInfoListActivity.kt:118`。**这是 A4 的根因**，任何新增调用点都要小心。
- Compose 侧通过 `onConversationsChanged` / `onCurrentMessageUpdated` 两个全局回调 + 每个 Activity 自己的 `runOnUiThread` 得到更新；回调只在 `ChatActivity.onCreate` 注册、`onDestroy` 注销（B17）。

### 8.2 流式请求的线程模型

- `AiApiClient.sendChatStream` 内部 `EventSources.createFactory(client).newEventSource(...)`，**所有回调（`onEvent`/`onFailure`/`onClosed`）都跑在 OkHttp 的线程上，不是主线程**。
- `ConversationManager.onText/onReasoning` 因此在后台线程直接写 `conv.messages[i]`，再通过 `onUiUpdate`/`onCurrentMessageUpdated` 让 Activity `runOnUiThread` 刷新 UI。
- `onText` 与 `finishStream` 的调用次数、`onFailure` 是否回调 `onFinish`，都是 A2/B12/B13/D8 的上下文。

### 8.3 世界书的两套数据形态（这是 B1/B2/B3/B5/B6 的关键）

- **形态 1：酒馆世界书文件** → `{ name, entries: { "0": {key:[...], keysecondary:[...], insertion_order, comment, constant, disable, position: <数字>, extensions:{...}} } }`（**对象**包条目，snake_case，数字 position）。
- **形态 2：角色卡内嵌 character_book** → `data.character_book.entries` 是**数组**，字段为 `keys`/`secondary_keys`/`insertion_order`/`constant`/`case_sensitive`/`position: "before_char"|"after_char"`（字符串）。
- 本项目自有格式是**数组 + `keywords`/`constantActive`/`priority`/`position: "after_system_prompt"` 这类字符串**（`WorldInfoManager.buildJson`）。
- **`WorldInfoImporter.parse` 目前按"entries 是对象 → 走酒馆解析；是数组 → 走自有解析"分流**（`:79-107`），所以形态 2 落进了自有解析 → 键全丢（B1/B6）。改这里之前先把三种形态的区别理清。
- 酒馆 position 枚举：`0 before / 1 after / 2 ANTop / 3 ANBottom / 4 atDepth / 5 EMTop / 6 EMBottom / 7 outlet`（B2）。

---

## 9. 回归验证清单（改完按此逐项过）

- [ ] 导入带头像的角色卡 → 角色列表 + 聊天气泡显示头像（A1）
- [ ] API 地址填 `api.openai.com/v1` → 提示非法，不崩（A2）
- [ ] 导入含 scenario/alternate_greetings/creator_notes 的卡 → 只改名字保存 → 字段仍在（A3）
- [ ] 发长回复，流式中打开世界书页并返回 → 回复继续输出完整（A4）
- [ ] 流式中转屏 → 回复不被截断、输入框内容保留（A4/D9）
- [ ] 管理页删除角色 → 行立即消失；导入 → 立即出现（A5）
- [ ] 导入带 `data.character_book`（数组条目）的 v2 卡 → 自动建书且条目有键、能命中（B1）
- [ ] 导入含 `position: 7`(Outlet) 的条目 → 不自动注入（B2）
- [ ] 导入含 `"disable": true` 的条目 → 列表可见禁用态且可启用（B4）
- [ ] 含 `/…/ ` 次级键的 selective 条目能触发（B5）
- [ ] 空关键词条目不匹配任何消息（B6）
- [ ] 超大条目 + 若干小条目 → 小条目仍注入（B7）
- [ ] depth=0 的世界书条目落在最后一条用户消息之后（B8）
- [ ] 搜索命中项 → 精确跳到该条（B9）
- [ ] 带附件发送 → 报错 → 重试 → 附件与文字都保留（B10）
- [ ] 上传 .png → 请求体 MIME 与字节头一致（B11）
- [ ] API 不可达 → 顶栏显示"获取失败"（B12）
- [ ] 一轮流式结束 `save()` 只调一次（B13）
- [ ] 编辑已保存的 API 条目 → 原地更新（B14）
- [ ] 三条 API 条目选中间那条、删第一条 → 高亮不变（B15）
- [ ] 自定义 → Kilo → 保存 → 切回自定义 → 返回 → 杀进程重开：仍用自定义且不被弹回设置页（B16）
- [ ] 设置保存后按返回 → 只有一个 ChatActivity，流式刷新正常（B17）
- [ ] 新建会话 → 杀进程重开 → 回到该新会话（B18）
- [ ] 停在第二个会话，删掉第一个 → 仍停在原来那个（B19）
- [ ] 角色卡含 `{{char}}`/`{{user}}` → 请求体无残留占位符（B21）
- [ ] 编辑含 `a{1,3}` 的条目 → 键不被切开（B22）
- [ ] 长按世界书条目 → 弹确认框（B23）
- [ ] 编辑字段时角色卡预览实时更新（B25）
- [ ] 导入 v1 JSON 卡与 PNG 卡（B29）
- [ ] 100MP 图片作为背景/头像/附件 → 不崩（D3）
- [ ] 长对话流式期间无逐 token 读盘、无掉帧（D1）
- [ ] 语言切"跟随系统"后立即生效（E9）
- [ ] 关于页不再显示 `%%`（E8）
- [ ] 发布版抽屉里没有"测试崩溃上报"（E1）