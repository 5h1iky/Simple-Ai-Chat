# SAChat 代码逻辑审计报告（2026-10-24 · HEAD `9778377` / v2.1.3）

- 审计范围：`app/src/main/` 全部 45 个 Kotlin 文件（约 1 万行）+ AndroidManifest + 网络层依赖语义（okhttp 4.12.0 RealEventSource / Gson 2.11）
- 审计方式：5 个模块并行全量读码（会话核心 / 聊天 UI / 世界书 / 网络设置 / 导入导出）+ 关键发现逐条人工复核
- 与历史 `AUDIT_REPORT.md`（2026-10-03，`4a1c2c8`）的关系：该报告的 A 类 5 条、B 类主要条目经复核**均已正确修复**（头像解码、URL 校验、编辑丢字段、流式 init、列表刷新、深度注入 off-by-one、预算 break→continue、滚动索引映射等），本报告为**新一轮全量审计**，与历史条目不重复。
- 标记：✅ = 审计方人工复核已坐实；☑ = 多路独立命中交叉印证

---

## 一、P1 严重问题（13 条，建议优先修复）

### P1-1 ☑✅ 附件消息使宏替换抛异常，带图片/文本附件的发送必然失败
- 位置：[ConversationManager.kt:400-407](app/src/main/java/www/cetool/com/ConversationManager.kt#L400-L407)；[ChatRequest.kt:46](app/src/main/java/www/cetool/com/network/ChatRequest.kt#L46)、[:81](app/src/main/java/www/cetool/com/network/ChatRequest.kt#L81)
- `MessageItem.multimodal/withFile` 的 `content` 是 **JsonArray**；宏替换循环对每条 item 直接调 `item.content.asJsonPrimitive`，Gson 2.11 对非原始元素抛 `IllegalStateException("Not a JSON Primitive: ...")`（`?.` 不拦异常）。
- 触发：发送任何带图片/文本附件的消息（当前用户消息本身就是多模态条目）；或历史窗口含旧附件消息后再发任意消息。
- 影响：请求根本没发出，气泡显示 `[错误: Not a JSON Primitive: ...]`——**附件/图片聊天整条链路不可用**。全项目其余 20 处均正确写法 `takeIf { it.isJsonPrimitive }`，唯此处漏改。
- 修法方向：`val text = item.content.takeIf { it.isJsonPrimitive }?.asString ?: return@replaceAll item`

### P1-2 ✅ 编辑角色卡保存会无条件清空原卡 personality（数据丢失）
- 位置：[CharacterEditActivity.kt:183-184](app/src/main/java/www/cetool/com/CharacterEditActivity.kt#L183-L184)、[:191-199](app/src/main/java/www/cetool/com/CharacterEditActivity.kt#L191-L199)；对照 [:98-104](app/src/main/java/www/cetool/com/CharacterEditActivity.kt#L98-L104)
- 编辑表单没有 personality 输入；`personality` 仅由结构化「外在+内在性格」拼接（两者常为空），保存时 `copy(personality = personality)` **无条件覆盖** `old.personality`。导入的手写 personality 卡"打开编辑器点保存即被清空"。
- 影响：数据不可逆丢失；[CharacterCompiler.kt:107](app/src/main/java/www/cetool/com/manager/CharacterCompiler.kt#L107) 的 `[性格]` 提示词段随之消失，AI 直接忘掉性格；导出卡同样丢失。对照 `description` 的"先载入原值再兜底"处理可知是遗漏。

### P1-3 ✅ 「编辑系统提示词」对话框从不加载当前值，保存即覆盖
- 位置：[ChatActivity.kt:209](app/src/main/java/www/cetool/com/ChatActivity.kt#L209)（状态初值 `""`）、[:609](app/src/main/java/www/cetool/com/ChatActivity.kt#L609)（打开时不赋值）、[:1212](app/src/main/java/www/cetool/com/ChatActivity.kt#L1212)（写回）
- `editSystemPromptText` 全文件无任何一处从 `conv.systemPrompt` 初始化（对比 RenameDialog 有 `renameText = conv.title`）。打开对话框永远是空文本框或上次残留；点保存直接覆盖并落盘。
- 影响：数据丢失（非角色会话的系统提示词被清空）+ 跨会话污染（A 会话残留文本写进 B 会话）。

### P1-4 ✅ onFailure 把传输层失败/取消一律显示为 "HTTP 200: OK"
- 位置：[AiApiClient.kt:341-348](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L341-L348)
- okhttp 4.12 `RealEventSource` 在读流中途异常 / `cancel()` / Content-Type 非 SSE 三种情况下都以**非空 response**（200）回调 `onFailure(this, t, response)`；本代码 `response != null` 时只报 `"HTTP 200: OK"`，把 `t`（真实原因）整个丢掉。
- 触发：生成中途断网 / 60s readTimeout / 用户取消 / 网关把 SSE 改写成 JSON。
- 影响：错误分类完全失效，排障被误导（B12 修复被抵消）。

### P1-5 ✅ SSE 错误事件分支不走 failStream：状态错乱
- 位置：[AiApiClient.kt:290-291](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L290-L291)（对照 :248-269）
- `choices` 为空且带 `error` 时直接调 `onError`，但 `finished` 不置位、不收尾：① 顶栏卡在「正在获取模型…」；② 流未取消，后续分片继续追加到错误文本之后；③ `onClosed→finishStream→onFinish` 又"成功收尾"；④ 多个 error chunk 重复追加错误。
- 影响：UI 状态错乱、重复保存、错误与正文混排。

### P1-6 ✅ 免密钥端点（Ollama/LM Studio）保存后被弹回设置页，死循环
- 位置：[SettingsActivity.kt:1371-1384](app/src/main/java/www/cetool/com/SettingsActivity.kt#L1371-L1384)（只校验 label+url）vs [ApiConfig.kt:53-55](app/src/main/java/www/cetool/com/model/ApiConfig.kt#L53-L55)（`isValid()` 要求 key/model 非空）vs [ChatActivity.kt:330-337](app/src/main/java/www/cetool/com/ChatActivity.kt#L330-L337)（入口拦截）
- 设置页允许保存空 key/model，ChatActivity 却用 `isValid()` 拦截 → toast + 跳设置 + finish；保存后回来的新实例再判失败再弹回。
- 影响：该类端点完全无法进入聊天界面。

### P1-7 ✅ 正则关键词被 lowercase() 破坏语义
- 位置：[WorldInfo.kt:87-88](app/src/main/java/www/cetool/com/model/WorldInfo.kt#L87-L88)（模式串先小写化）+ [:104-108](app/src/main/java/www/cetool/com/model/WorldInfo.kt#L104-L108)（再按 IGNORE_CASE 编译）；同病灶 [WorldInfoEngine.kt:120-127](app/src/main/java/www/cetool/com/manager/WorldInfoEngine.kt#L120-L127) 次级键
- `caseSensitive=false`（默认）+ `useRegex=true` 时：`\S`→`\s`、`\D`→`\d`、`\W`→`\w`、`\B`→`\b`、`[A-Z]`→`[a-z]`，语义反转；`\p{Lu}` 之类直接编译失败被 catch 成永不命中。IGNORE_CASE 本已负责大小写，小写化纯属破坏。
- 影响：世界书关键词误触发/漏触发，结果完全错乱。

### P1-8 ✅☑ scanDepth 窗口被流式空占位占用：扫描深度 -1，scanDepth=1 永不命中
- 位置：[WorldInfoEngine.kt:147-154](app/src/main/java/www/cetool/com/manager/WorldInfoEngine.kt#L147-L154) + [ConversationManager.kt:192-193](app/src/main/java/www/cetool/com/ConversationManager.kt#L192-L193)、[:312](app/src/main/java/www/cetool/com/ConversationManager.kt#L312)
- scan 时 `conv.messages` 末尾是刚追加的**空 assistant 占位**，`messages.size - maxScan` 窗口把它计入名额：scanDepth=1 的条目窗口 = [空占位]，非常驻条目永不触发；默认 4 实际只扫 3 条真实消息。
- 附带：scan 的第三参数 `promptItems` 恒为空列表（[ConversationManager.kt:263](app/src/main/java/www/cetool/com/ConversationManager.kt#L263) 创建、:333 才填充），[WorldInfoEngine.kt:156-161](app/src/main/java/www/cetool/com/manager/WorldInfoEngine.kt#L156-L161) 是死代码。

### P1-9 ✅ 示例对话前后槽条目：无示例对话时不注入，却白占 token 预算
- 位置：[ConversationManager.kt:324-328](app/src/main/java/www/cetool/com/ConversationManager.kt#L324-L328) + [WorldInfoEngine.kt:177](app/src/main/java/www/cetool/com/manager/WorldInfoEngine.kt#L177)
- `beforeExamples/afterExamples` 只在 `charExamples` 非空时拼入；角色无 mes_example 或纯 systemPrompt 会话时条目静默消失，但 `sortAndBudget` 只排除 outlet，它们照样吃预算挤掉可注入条目。

### P1-10 ✅ 酒馆 `extensions.use_regex` 不被导入读取（对象格式世界书）
- 位置：[WorldInfoImporter.kt:225](app/src/main/java/www/cetool/com/importer/WorldInfoImporter.kt#L225)（只读顶层 `useRegex`）；对照 [SillyTavernExporter.kt:79](app/src/main/java/www/cetool/com/manager/SillyTavernExporter.kt#L79) 自述"酒馆自身放在 extensions.use_regex"
- 酒馆世界书没有顶层 `useRegex` 键，勾了 Regex 的裸正则键（`\bdragon\b` 等）全按字面子串匹配，条目通常永不触发。（character_book 数组条目路径 :165 已正确读取 `use_regex`，仅对象格式缺失。）

### P1-11 ✅ outlet 条目（position 7）永久失效：`{{outlet::名称}}` 全项目未实现
- 位置：[WorldInfoEngine.kt:177](app/src/main/java/www/cetool/com/manager/WorldInfoEngine.kt#L177)、[:240-241](app/src/main/java/www/cetool/com/manager/WorldInfoEngine.kt#L240-L241)
- 引擎两处注释宣称"由 `{{outlet::名称}}` 手动拉取"，但宏替换只实现了 `{{user}}/{{char}}` 等，无任何 outlet 解析（grep 确认全项目仅注释）。outlet 条目导入后既不自动注入也无法手动拉取，内容 100% 消失且无提示。

### P1-12 ✅ 删卡时卡内 `sachat_worldbook_id` 未消毒，可路径穿越删除 .json 数据
- 位置：[CharacterManager.kt:111-125](app/src/main/java/www/cetool/com/manager/CharacterManager.kt#L111-L125) → [WorldInfoManager.kt:165-166](app/src/main/java/www/cetool/com/manager/WorldInfoManager.kt#L165-L166)
- id 完全来自被导入卡的 JSON（外部可控），直接拼 `File(dir, "$id.json").delete()`；`filesDir/worlds` 与 `filesDir/characters` 同级，`"../characters/xxxx"` 可删其他 .json 数据。`isBookReferencedByOtherCard` 只做字符串比较，不防穿越。
- 触发：导入恶意/畸形卡（extensions.sachat_worldbook_id 含 `../`）→ 长按删除该卡。破坏面限应用沙箱。

### P1-13 附件图片解码漏 catch OutOfMemoryError（崩溃，条件触发）
- 位置：[ChatComponents.kt:519-526](app/src/main/java/www/cetool/com/ui/components/ChatComponents.kt#L519-L526)
- `catch (_: Exception)` 抓不住 `OutOfMemoryError`；同文件 :338 与 ImageDecoder 均为双 catch，唯此处漏改。旧数据大图/低端机多图同屏可 OOM，且 remember 在组合期抛异常 → 该会话每次打开复崩。

---

## 二、P2（34 条，按模块分组）

### 会话核心
| # | 位置 | 问题 |
|---|---|---|
| 1 | [ConversationManager.kt:372-380](app/src/main/java/www/cetool/com/ConversationManager.kt#L372-L380) ✅ | 多条 @D 注入互相挤位：`size` 随插入增长，第 k 条实际深度偏浅 k-1 位；同深度条目还倒序 |
| 2 | [MemoryArchiver.kt:114](app/src/main/java/www/cetool/com/manager/MemoryArchiver.kt#L114) ✅ | 降级重试 `take(length/2)` 保留前半段 = 角色卡+最旧对话，恰好丢掉最新对话，与注释意图相反 |
| 3 | [Conversation.kt:53-58](app/src/main/java/www/cetool/com/model/Conversation.kt#L53-L58) | 首条用户消息为空（纯附件）→ 标题生成空串并永久固化；且用 `content` 未用 `displayText()` |
| 4 | [ConversationManager.kt:598-663](app/src/main/java/www/cetool/com/ConversationManager.kt#L598-L663) | 单条坏条目 → 整库重置为空白会话，下次 save 落盘并删 prefs 旧备份（两份同失） |
| 5 | [ConversationManager.kt:301-308](app/src/main/java/www/cetool/com/ConversationManager.kt#L301-L308) | 内嵌书与会话绑定书同 id 时无去重，条目重复注入、预算翻倍扣 |

### 聊天 UI
| # | 位置 | 问题 |
|---|---|---|
| 6 | [ChatActivity.kt:581-586](app/src/main/java/www/cetool/com/ChatActivity.kt#L581-L586) | 联网搜索 Toast 文案与刚执行的动作相反 |
| 7 | [ChatActivity.kt:2110-2120](app/src/main/java/www/cetool/com/ChatActivity.kt#L2110-L2120) | 「重试」非错误分支丢弃待发附件（与 B10② 自述语义不符） |
| 8 | [ChatComponents.kt:140-187](app/src/main/java/www/cetool/com/ui/components/ChatComponents.kt#L140-L187) | 滚底 LaunchedEffect key 不含会话 id + itemsIndexed 无 key：切换同消息数会话不滚到底、折叠/图片状态跨会话串台 |
| 9 | [AndroidManifest.xml:58](app/src/main/AndroidManifest.xml#L58) | configChanges 不含 uiMode/locale：深色模式切换重建 Activity，草稿输入与待发附件丢失 |
| 10 | [ChatActivity.kt:2106](app/src/main/java/www/cetool/com/ChatActivity.kt#L2106) ✅ | 重试错误判定用字面 `contains("[错误:")`，AI 台词含该字面量会被误判 → 删掉正常回复并自动重发 |
| 11 | 角色/冒险会话 | 「编辑系统提示词」保存后提示"已更新"但上下文组装用角色卡编译结果/DM 指令，实际不生效（与 P1-3 叠加） |

### 网络 / 设置
| # | 位置 | 问题 |
|---|---|---|
| 12 | [AiApiClient.kt:311-320](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L311-L320) | `<\|end_of_thought\|>` 无 `/` 被判成开标签，正文全进 reasoning 折叠区 |
| 13 | [AiApiClient.kt:240](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L240)、[:303-304](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L303-L304) | `tagBuffer` 死代码：跨分片切断的标签不识别，碎片/思维链泄漏进正文 |
| 14 | [AiApiClient.kt:297](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L297) | `message?.content?.asJsonPrimitive` 对 JsonNull/JsonArray 抛 ISE，整块 chunk 被静默丢弃 |
| 15 | [AiApiClient.kt:275](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L275) | `event: error` 帧被 `type != "message"` 静默丢弃，随后按"成功"收尾 |
| 16 | [AiApiClient.kt:225](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L225) | Base URL 带 query/fragment 时 `/chat/completions` 拼进查询串/片段（Azure 风格地址 404） |
| 17 | [AiApiClient.kt:338](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L338) + ConversationManager onError | cancel() 必走 onFailure → 追加 `[错误: HTTP 200: OK]`，可误伤新一轮请求的句柄/状态（窄竞态） |
| 18 | [SettingsActivity.kt:592-594](app/src/main/java/www/cetool/com/SettingsActivity.kt#L592-L594) | 删光 API 条目后保存跳过 syncAiConfig，ai_config 残留已删除的端点/密钥 |
| 19 | [SettingsActivity.kt:281-287](app/src/main/java/www/cetool/com/SettingsActivity.kt#L281-L287) | 快照 JSON 解析无 try/catch，prefs 损坏 → 设置页 onCreate 崩溃 |
| 20 | [NullSafeTypeAdapterFactory.kt:43-49](app/src/main/java/www/cetool/com/util/NullSafeTypeAdapterFactory.kt#L43-L49) | 显式 `null` 被转零值覆盖 Kotlin 默认值：`"enabled": null` → false（条目静默禁用）、`insertion_order` 默认 100 → 0 |
| 21 | [AiApiClient.kt:62-68](app/src/main/java/www/cetool/com/network/AiApiClient.kt#L62-L68) | readTimeout=60s 对 SSE 生效，长思考 >60s 无字节输出即断流（且报 "HTTP 200: OK"） |

### 世界书
| # | 位置 | 问题 |
|---|---|---|
| 22 | [WorldInfoImporter.kt:432-444](app/src/main/java/www/cetool/com/importer/WorldInfoImporter.kt#L432-L444) ✅ | `looksLikeRegex` 把字面斜杠关键词 `/api/v1` 截成 `api`、`//x` 剥成空串；一条触发则整条目强制按正则 |
| 23 | [WorldInfoImporter.kt:43-52](app/src/main/java/www/cetool/com/importer/WorldInfoImporter.kt#L43-L52) ✅ | ANTop/ANBottom(2/3) 被映射进示例对话槽（规范里 2/3 是 Author's Note 块），导出又把示例槽写回 2/3，AN/EM 无法区分 |
| 24 | [WorldInfoEngine.kt:48-53](app/src/main/java/www/cetool/com/manager/WorldInfoEngine.kt#L48-L53) | 概率按"每条命中消息"重复掷骰，触发率随命中数膨胀（10% × 4 命中 ≈ 34%） |
| 25 | [WorldInfoEngine.kt:110-130](app/src/main/java/www/cetool/com/manager/WorldInfoEngine.kt#L110-L130) | AND ALL/NOT ALL 路径用纯 contains，忽略 wholeWords，与 ANY 路径匹配口径不一致 |
| 26 | [WorldInfoImporter.kt:193-215](app/src/main/java/www/cetool/com/importer/WorldInfoImporter.kt#L193-L215) | character_book 导入丢字段：`injectDepth = scan_depth` 错配、probability 硬编码、extensions/group/triggers 不读 |
| 27 | [WorldInfoEditActivity.kt:665-687](app/src/main/java/www/cetool/com/WorldInfoEditActivity.kt#L665-L687) | 非正则关键词按逗号/顿号切分：含逗号的字面键打开编辑器点保存即被切碎 |
| 28 | [WorldInfoListActivity.kt:413-415](app/src/main/java/www/cetool/com/WorldInfoListActivity.kt#L413-L415) | 删书无二次确认、不清理会话绑定引用（悬空 id 残留） |
| 29 | [WorldInfoEditActivity.kt:111-152](app/src/main/java/www/cetool/com/WorldInfoEditActivity.kt#L111-L152) | load 失败返回 null 时按空书打开，保存即覆盖原文件（局部损坏文件被清零） |
| 30 | WorldInfoEditActivity | selectiveLogic（NOT ANY 等）被引擎消费但无任何 UI 入口 |

### 导入导出
| # | 位置 | 问题 |
|---|---|---|
| 31 | [CharacterEditActivity.kt:202](app/src/main/java/www/cetool/com/CharacterEditActivity.kt#L202) ✅ | 编辑保存把 spec/spec_version 重置为 chara_card_v3/3.0，v1/v2 卡元数据被篡改 |
| 32 | [SillyTavernExporter.kt:34-45](app/src/main/java/www/cetool/com/manager/SillyTavernExporter.kt#L34-L45) | 不导出书级 enabled，停用的书导出→导入后复活 |
| 33 | [TavernCardImporter.kt:40-43](app/src/main/java/www/cetool/com/importer/TavernCardImporter.kt#L40-L43) | PNG 卡导入后头像丢失（图片字节即头像但被丢弃）；:160 空白 chara 文本 `return null` 放弃后续 chunk |
| 34 | [STChatLogImporter.kt:33-83](app/src/main/java/www/cetool/com/importer/STChatLogImporter.kt#L33-L83) | 时间戳格式与酒馆 `create_date`（"2024-02-11 @13h 20m…"）不匹配且不读 `send_date`，全部 fallback 成 +1s |

---

## 三、待确认 / 低优先级（摘选）

1. `cancel()` / 清空会话与 SSE 回调的窄竞态：迟到的 onError 可能摘掉新流句柄、错误文本写进新占位消息（窗口极小）。
2. `Message.displayText()` 前置条件：若旧数据是"正文含 `[文件: 名]` 前缀但无附件字段"，三处宣称的剥离均不生效（需真实旧数据样本）。
3. ST role 数值型（0/1/2）注入角色会被退化为 system；递归/sticky/cooldown 字段导入但引擎零消费（自述已知缺口）。
4. 组胜出为确定性最大权重，酒馆实际是加权随机。
5. `Choice.index: Int` 非空原语遇 `"index": null` 整块丢弃；iTf/zTXt 压缩 chunk 不识别（缺功能）。
6. Display 侧：`syncStreamingUi` 每 token 全量读盘（性能）；`showDeleteConfirm` 死代码；空消息渲染成"思考中…"占位。
7. `UUID.take(8)` 文件名理论碰撞覆盖旧卡（概率极低）。

---

## 四、修复优先级建议

1. **先修 P1-1、P1-2、P1-3**（附件链路全废 + 两处数据丢失，用户伤害最大、修法最小）。
2. **再修 P1-4/5/6**（错误报告与配置链路——排障体验与可用性）。
3. **世界书 P1-7~11 一批修**（匹配语义 + 扫描窗口 + 注入槽），这几条共同决定"世界书到底灵不灵"。
4. P1-12/13 顺手修（消毒一行、双 catch 一行）。
5. P2 按上表顺序；其中 #1（@D 挤位）、#22（斜杠关键词）、#23（AN/EM 映射）与酒馆语义直接对齐，建议同批处理。

---

## 五、2.1.4 修复状态（2026-10-24）

**已修复并随 v2.1.4（versionCode 6）发布**：

- 全部 13 条 P1：宏替换 ISE（附件全废）、personality 清空、系统提示词覆盖、onFailure "HTTP 200"、SSE 错误分支收尾、免密钥端点死循环、正则 lowercase、scanDepth 占位、EM 槽不注入白占预算、use_regex 导入、{{outlet::}} 拉取实现、路径穿越消毒、附件 OOM 双 catch。
- P2 已修 24 条：@D 挤位（改深→浅插入）、MemoryArchiver 截反、标题空串、内嵌书重复注入（按 id 去重）、Toast 反向、重试丢附件、`[错误:` 误判收窄、uiMode 重建丢草稿、`<|end_of_thought|>` 判定、tagBuffer 跨分片缓存、message.content null 安全、`event: error` 帧处理、URL query/fragment 拼接（HttpUrl 路径段追加）、迟到取消回调守卫、快照解析兜底、斜杠关键词截断、AN/EM 导出映射（示例槽导出 5/6）、概率单次掷骰、ALL/NOT ALL 走统一匹配、character_book injectDepth 错配、编辑器关键词按行切分、load 失败覆盖保护、spec/spec_version 保留、PNG 卡头像、书级 enabled 导出、ST 时间戳解析。

**本次未修（已知遗留，均为 P2/边缘）**：

| 项 | 原因 |
|---|---|
| 「编辑系统提示词」对角色/冒险会话不生效 | 产品语义决策：角色会话上下文以角色卡为准，需产品确认期望行为 |
| 删光 API 条目后 ai_config 残留 | 清空/保留两种期望均合理，需产品定义 |
| NullSafeTypeAdapterFactory 显式 null 覆盖默认值 | 涉及 Gson 反射语义，需要版本行为实测后设计 |
| 消息列表 itemsIndexed 缺 key、滚底 key 不含会话 id | UI 状态串台（轻微），需配合消息唯一 id 设计 |
| 删除世界书无二次确认 | 新增交互需设计稿/文案确认 |
| 坏条目 → 整库重置（需"形状合法但条目非法"的损坏文件） | 触发需损坏数据；2.1.4 已加固世界书侧同类路径，会话库留待后续 |
| SSE 取消窄竞态（CANCEL-WINDOW 残余） | 时序窗口极小，需并发测试基建配合验证 |
