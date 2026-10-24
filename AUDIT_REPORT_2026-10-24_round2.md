# SAChat 代码逻辑审计报告（2026-10-24）

- **审计对象**：`www.cetools.com`（Simple AI Chat，Android / Kotlin / Compose），约 1.3 万行 Kotlin。
- **审计方式**：4 路并行逐文件通读（聊天/网络层、管理器/导入导出层、Activity UI 层、工具/基础设施层）+ 主审交叉复核。全程只读，未修改代码。
- **代码现状**：HEAD = `2cabf62`（v2.1.4）。该仓库历史上已做过多轮审计修复（`UPGRADE_PLAN.md` §14 记录 37 条、2.1.3/2.1.4 又修了 13 P1 + 24 P2），本报告只列**当前代码仍存在**的问题。
- **标记**：✅ = 主审已读源码核实；✔ = 子代理交叉验证、机制自洽；⚠️ = 触发条件待实机确认。

---

## 一、高危（数据丢失 / 可复崩）

### H1 ✅ 一条坏数据 → 整个会话库清空并被固化（永久丢失）
- **位置**：`ConversationManager.kt:655-711`（`load()`；清空在 :705-710），`ConversationManager.kt:815-832`（`ConversationEntry`/`MessageEntry` 必填字段无默认值），`ConversationManager.kt:645-653`（`isParsableConversationData` 只验顶层结构）
- **机制**：Gson 用 Unsafe 实例化，JSON **缺字段**时保持真正 `null`（`NullSafeTypeAdapterFactory` 只拦显式 `null` 字符面，拦不住缺失字段）。`Conversation(id = entry.id, …)` 触发 Kotlin 非空参数检查抛 `IllegalArgumentException`，或 `entry.messages.map` NPE。异常被 `catch`(:705) 吞掉后 `conversations.clear()` + 一条空白会话；随后 `ChatActivity.onPause → save()` 把空白库**写盘固化**。
- **触发**：conversations.json 中任一条目缺 `id`/`role`/`content`/`messages` 等字段（版本迁移、手工编辑、外部工具写坏）。
- **修复**：逐条容错解析（坏条目跳过并保留原文）；`catch` 中不得 `clear()`；写盘前备份原 JSON。

### H2 ✅ NullSafeTypeAdapterFactory 把 JSON `null` 翻译成零值，可空字段"未设置"语义被改写
- **位置**：`NullSafeTypeAdapterFactory.kt:43-49`（read 拦 NULL → 零值）、:71-82（zeroOf）；受害点：`TavernCard.kt:45-46`（`scan_depth: Int?`、`priority: Int?`）、`WorldInfoImporter.kt:197`（`e.priority ?: e.insertion_order`）、:207（`e.scan_depth ?: 4`）、`ConversationManager.kt:829-831`（`MessageEntry.attachment*`）、`ChatComponents.kt:113-119`
- **机制**：显式 `"priority": null` 被翻成 `0`，`?: 兜底` 永久失效 → 注入顺序错乱；`"scan_depth": null` → `0` → 引擎 `coerceAtLeast(1)` 后只扫最后 1 条消息，世界书大范围不触发。`String?` 的 null 变 `""`，`rememberAttachmentInfo` 的 `?: return null` 失效可渲染"幻影附件卡"。写读不对称（写 null 省略字段、读回零值），往返不保真。
- **注**：该适配器用于 `TavernCardImporter.kt:11` 与 `ConversationManager.kt:40`（已核实注册点）；SillyTavern 导出卡显式写 null 的普遍性待实机确认，但机制确定。
- **修复**：只对非空声明字段做零值兜底，可空类型保持 null 原样返回（Gson 反射写 null 进 `String?` 字段本就安全）。

### H3 ✅ 背景图全尺寸解码，`catch (Exception)` 抓不住 OutOfMemoryError → 进聊天页复崩
- **位置**：`ChatActivity.kt:1800`（`BitmapFactory.decodeByteArray` 全尺寸）、:1820（`catch (_: Exception) {}`）
- **机制**：OOM 是 `Error` 不是 `Exception`，穿透 catch 直接崩；旧版本写入 `KEY_BG_IMAGE` 的是全尺寸原图 base64，升级用户解码 8000×6000 ≈ 192MB 必炸，且每次进聊天页复崩。:1806 `centerCrop` 再复制一张抬高峰值。
- **修复**：走 `ImageDecoder` 采样解码 + `catch (Throwable)`；对存量数据按 maxEdge 重新压缩。

---

## 二、中危

### 网络 / 流式
| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| M1 ✅ | SSE 每分片只处理**第一个**思维链标签（`find` 而非 `findAll`） | `AiApiClient.kt:384-408` | 同分片含 `<thinking>…</thinking>` 时闭合标签与正文被整体吐进 reasoning，正文丢失。改为遍历 `findAll` 逐段分发 |
| M2 ✅ | `holdLen < fullInput.length` 使"整段恰为标签前缀"时缓存失效 | `AiApiClient.kt:379` | 分片边界含 `<` 等前缀时半截标签泄漏为字面文本、状态永不翻转。去掉该条件即可（流结束 flush 已保证不丢字） |
| M8 ✅ | 顶栏"正在获取模型…"多条异常路径不复位 | `ChatActivity.kt:2058`；`ConversationManager.kt:229-252`、:468-471、:537-541 | catch 与 CANCEL-WINDOW 提前 return 均不回调 `onModelInfo`，顶栏卡加载到下次发送。统一收尾补 `onModelInfo(null, null)` |

### 数据持久化
| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| M3 ✅ | 旧附件格式的**文件全文双份发送** | `Message.kt:40-45`；`ConversationManager.kt:382-389` | git 实证旧写入为 `"[文件: 名]\n${attachmentData}\n\n${content}"`（初始提交 ConversationManager.kt:213），而 `stripLegacyAttachmentPrefix` 只剥头行 → 全文仍在正文 + file 块各发一次，搜索/重试同样污染。剥离时应连同 `attachmentData` 段一并去除 |
| M4 ✅ | `save()` 快照在锁外构建，旧快照可后写覆盖新快照 | `ConversationManager.kt:565-587` | SSE 线程与主线程并发 save 时丢标题/丢末条消息。快照构建挪进 `synchronized(lock)` |
| M5 ✅ | 崩溃恢复 `.tmp` 对"非空但损坏"主文件永不生效 | `SafeFiles.kt:92-97`；`ConversationManager.kt:635-642` | rename 失败退化非原子写留下半截主文件 + 完整 .tmp；读取只在主文件缺失/空白时才看 .tmp，损坏内容直接回落 prefs → 进入 H1 清库。解析失败时也应回退 .tmp |
| M7 ✅ | 世界书保存忽略写盘结果：写失败仍提示"已保存"并 finish() | `WorldInfoEditActivity.kt:156-163`；`WorldInfoManager.kt:158-175` | `overwrite`/`saveNew` 返回值被丢弃；对照 `CharacterEditActivity.kt:219-238`（C4）会检查。saveNew 还吞掉失败仍返回 id |

### 状态 / 生命周期
| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| M6 ✅ | `ai_config` 残留旧预设：对话被发往错误网关 | `SettingsActivity.kt:493-500`、:598-600；`ChatActivity.kt:2231-2232` | CUSTOM 模式且条目为空时 `syncAiConfig` 什么都不写，Kilo/Zen 的 URL/密钥残留；主界面照读 `ai_config` 发请求。空条目时应清空 KEY_URL/KEY_KEY/KEY_MODEL |
| M9 ✅ | `cancelAllStreams()` 是死代码，流不随 Activity 销毁取消 | `ConversationManager.kt:556`（定义后全项目无调用）；`ChatActivity.kt:386-391`、:1936-1956、:2068-2081 | 生成中退出页面 → OkHttp 线程与回调持有已销毁 Activity（泄漏+销毁后写状态）；记忆封存裸 `Thread{}` 同样无生命周期。onDestroy 调 cancelAllStreams / 改 lifecycleScope |
| M15 ✔ | `itemsIndexed(messages)` 无 key，删除/重生成时组合状态错位 | `ChatComponents.kt:187` | FoldableSection 展开态等随位置迁移到相邻消息；传稳定 key |
| M16 ✔ | 自动滚动只以 `messages.size` 为 key，`LazyListState` 跨会话残留 | `ChatComponents.kt:140`、:150-154 | 流式增长不跟随滚动；切换到消息数相同的会话停在旧偏移。key 加会话 id / 监听末条长度 |
| M17 ✔ | "跟随系统"语言用进程级快照，系统改语言后过期 | `LocaleHelper.kt:31-54` | 快照只记一次，之后 `Locale.setDefault` 还把旧值写回全局。改读 `configuration.locales[0]` |

### 导入 / 图像
| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| M10 ✔ | PNG 角色卡只认 `chara` 块，不认 V3 规范的 `ccv3`；空 chara 块中止扫描 | `TavernCardImporter.kt:165-198` | 纯 V3 PNG 报"未找到角色卡数据"；双块时丢 V3 字段。优先 ccv3 回退 chara，空块 continue（[V3 规范](https://raw.githubusercontent.com/kwaroran/character-card-spec-v3/main/SPEC_V3.md)） |
| M11 ✅ | 显式 `"字段": null` 让世界书/卡"静默消失" | `WorldInfoManager.kt:73-78`；`TavernCardImporter.kt:71`、:94 | `JsonNull.asString/asBoolean` 抛异常被 `catch{null}` 吞掉 → 书从列表消失、绑定失效。用 `WorldInfoImporter.str/bool` 式安全取值 |
| M12 ✅ | 崩溃处理器可二次崩溃；`previousHandler` 不在 finally | `CrashReporter.kt:71-89` | 处理器内 OOM 时异常逃逸 → 杀进程处理器永不执行（僵尸态）；`ConversationManager.init`(:71) 还在安装处理器(:75)之前。整体 try/catch(Throwable) + finally 调 previousHandler |
| M13 ✔ | 头像全尺寸解码、每条消息各解一份 | `ChatComponents.kt:335-337`；`CharacterListActivity.kt:374-384` | 千像素导入卡头像 ×N 条 → 内存抖动/OOM（已知 #35 的漏网路径）。走 `ImageDecoder` + 进程级 LruCache |
| M14 ✅ | `calculateInSampleSize` 违反自身契约，解码达 2×maxEdge | `ImageDecoder.kt:89-98` | 4000px + maxEdge=1024 → 解出 2000px（4 倍内存预算）。decode 后精确缩放一次 |
| M18 ✔ | `.jsonl` 导入静默丢行；BOM 吃掉首条 | `STChatLogImporter.kt:20-32` | 与历史已知 #19 同域。剥 BOM + 坏行计数告警 |
| M19 ✔ | AdventureActivity 主线程同步解析全部卡/书（含 base64 头像）→ ANR | `AdventureActivity.kt:68-69` | D4 已修其余列表页，唯独此页漏掉。改 produceState + IO |
| M20 ✔ | 世界书数值字段非法输入被静默重置默认值并持久化 | `WorldInfoEditActivity.kt:481-484`、:572-577 | "12 "、"0.5"、清空都触发 `toIntOrNull() ?: 默认值` 覆写文件；probability 无 0..100 钳制。保存前校验并提示 |

---

## 三、低危 / 待确认

| # | 问题 | 位置 | 标记 |
|---|------|------|------|
| L1 | 宏替换跳过 JsonArray（带附件）消息正文，`{{user}}/{{char}}` 以字面量发送 | `ConversationManager.kt:437` | ✅ |
| L2 | apiKey 为空仍发 `Authorization: Bearer `（空令牌），部分服务端 401 | `AiApiClient.kt:220-227` | ✔ |
| L3 | `requestJson` content 非原始类型时绕过 delta 兜底；服务端 `error.message` 被丢弃 | `AiApiClient.kt:488-497` | ✔ |
| L4 | WebSearch 解析失败时把整个原始 JSON 当"搜索结果"注入提示词 | `WebSearchClient.kt:100-116` | ✔ |
| L5 | `openInputStream` 返回 null 时静默附加空附件并提示成功 | `ChatActivity.kt:2155-2168` | ✔ |
| L6 | 切换到空会话不清空 `messagesState`，旧消息残留 | `ChatActivity.kt:1893-1896` | ✔ |
| L7 | `finishStream/failStream` 冲刷 tagBuffer 时不看 `inReasoning`，通道错误 | `AiApiClient.kt:290-294`、:311-315 | ✔ |
| L8 | SSE `catch` 吞掉回调自身异常，与注释"透出给上层"矛盾 | `AiApiClient.kt:411-416` | ✅ |
| L9 | `readTextWithRecovery` 把"合法但空白"的目标当损坏，用陈旧 .tmp 顶替 | `SafeFiles.kt:92-97` | ✔ |
| L10 | `writeAtomic` 固定 tmp 文件名、无跨调用方互斥，并发写互踩 | `SafeFiles.kt:27`、:44-45 | ⚠️ |
| L11 | `characterId` 拼路径无穿越校验（WorldInfoManager 有校验，此处没有） | `CharacterManager.kt:42`、:51、:104、:115 | ⚠️ |
| L12 | 明文流量全局放行（`base-config cleartextTrafficPermitted="true"`） | `network_security_config.xml:17` | ✔（已知取舍，范围偏宽） |
| L13 | 崩溃详情全文复制进系统剪贴板（低版本任意前台应用可读） | `CrashReporter.kt:42-48` | ✔ |
| L14 | MIME 为空的图片被误判"文件不支持"（无扩展名兜底） | `ChatActivity.kt:2148-2201` | ✔ |
| L15 | 列表长按菜单"删除"无二次确认，一击不可恢复（连带删内嵌世界书） | `WorldInfoListActivity.kt:397-423`；`CharacterListActivity.kt:293-321` | ✔ |
| L16 | BondActivity 聚合线程不随生命周期取消，销毁后仍读盘写状态 | `BondActivity.kt:82-107` | ✔ |
| L17 | 编辑页状态不持久化，进程回收/语言切换时未保存内容丢失 | `WorldInfoEditActivity.kt:105-109` 等 | ⚠️（产品取舍） |
| L18 | AdventureActivity 角色勾选行游离于 SectionCard 之外（结构错乱） | `AdventureActivity.kt:171-202` | ✔ |
| L19 | CharacterEdit `overwrite` 失败静默回落 `save()` 产生重复卡 | `CharacterEditActivity.kt:219-226` | ⚠️ |
| L20 | Zen 免费判定 `id.contains("free")` 把可用模型 big-pickle 误滤，UI 误标"已不可用" | `ModelCatalog.kt:93`、:103-109；`SettingsKeys.kt:76` | ✔ |
| L21 | `wholeWords` 对含空格关键词退化为子串匹配 | `WorldInfo.kt:110` | ⚠️ |
| L22 | 世界书组互斥只在单本书内生效，跨书同名组可同时注入 | `WorldInfoEngine.kt:62`、:77-107 | ⚠️ |
| L23 | legacy `extensions.world_book` 分支导入不去重，重复导入产生重复世界书 | `CharacterManager.kt:79-98` | ✔ |
| L24 | `saveNew` 吞写失败仍返回 id；`overwrite` 文件不存在时写错目标 | `WorldInfoManager.kt:158-175` | ✔ |
| L25 | `scan` 的 `promptItems` 恒为空（调用时 historyItems 尚空），死代码 | `ConversationManager.kt:336-339`；`WorldInfoEngine.kt:157-162` | ⚠️ |
| L26 | 递归激活 / sticky / cooldown / delay / triggers 解析后不消费（功能缺口） | `WorldInfoEngine.kt:27-63`；`WorldInfo.kt:45-47` | ✔（非回归） |

---

## 四、与历史审计记录的关系

- `UPGRADE_PLAN.md` §14 的 37 条历史条目中，一部分已在 §15 批次与 v2.1.3/2.1.4（commits `206e725`/`f820cd2`/`ef1d7af`/`2cabf62`）修复；本报告**未重复**列已修项，也不与 §14.3"已核实正确"清单冲突。
- 本报告新增（历史清单未覆盖）的重点：**H1（load 清库固化）、H2（null→零值语义改写）、M1/M2（SSE 标签解析残留）、M3（旧附件双发）、M4（save 竞态）、M5（.tmp 恢复失效）、M6（ai_config 残留）、M9（流不取消）**。
- H3、M13 属历史 #35（图片解码 OOM）的漏网路径。

## 五、建议修复顺序

1. **第一批（数据安全）**：H1、M5、M4、M7、M3 —— 全是"静默丢数据"，且互相叠加（M5 → H1）。
2. **第二批（崩溃/稳定）**：H2、H3、M12、M9、M13、M14。
3. **第三批（功能正确性）**：M1、M2、M6、M8、M10、M11、M19、M20。
4. **第四批（体验/低危）**：L 系列按需。
