package www.cetool.com

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import okhttp3.sse.EventSource
import www.cetool.com.SettingsKeys
import www.cetool.com.manager.CharacterCompiler
import www.cetool.com.manager.CharacterManager
import www.cetool.com.manager.WorldInfoEngine
import www.cetool.com.manager.WorldInfoManager
import www.cetool.com.model.ApiConfig
import www.cetool.com.model.Conversation
import www.cetool.com.model.Message
import www.cetool.com.model.Message.Companion.ROLE_ASSISTANT
import www.cetool.com.model.Message.Companion.ROLE_USER
import www.cetool.com.network.AiApiClient
import www.cetool.com.network.MessageItem

object ConversationManager {

    /** 允许用于深度注入的消息角色（injectRole 可能来自不可信的酒馆文件） */
    private val VALID_ROLES = setOf("system", "user", "assistant")

    /** 冒险模式 DM/GM 系统指令（中英双语资源，跟随 UI 语言） */
    val ADVENTURE_DM_PROMPT: String
        get() = appContext.getString(R.string.adventure_dm_prompt)

    private const val PREFS_NAME = "conversations"
    private const val KEY_DATA = "conversation_data"
    private const val KEY_CURRENT_ID = "current_conversation_id"
    private const val KEY_SYSTEM_PROMPT = "system_prompt_default"

    // C2：会话库文件同样可能被手工编辑或截断，`"content": null` 会让非空 Kotlin 属性
    // 变成真正的 null，之后在 UI 或发送路径上 NPE。与其他持久化模型统一用空值兜底。
    private val gson = www.cetool.com.util.NullSafeTypeAdapterFactory.buildGson()
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    /** 是否已初始化过（A4：重复 init 会把正在流式的 Conversation 实例换掉） */
    private var initialized = false

    private val lock = Any()

    /**
     * D8：会话列表被跨线程访问 —— SSE 线程在 `save()` 里遍历，
     * 主线程同时可能 `createNew()` / `delete()` → `ConcurrentModificationException`
     * 被 `save()` 的 catch 吞掉 → **静默保存失败**（数据只在内存里）。
     * 用 CopyOnWriteArrayList 让遍历快照化，读多写少的场景也最合算。
     */
    private val conversations = java.util.concurrent.CopyOnWriteArrayList<Conversation>()
    private var currentIndex = 0

    /**
     * S2：发送流程的后台执行器。
     * 组装上下文（读角色卡/世界书）与 Web 搜索都涉及 I/O，不能放在主线程。
     */
    private val SEND_EXECUTOR: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "sachat-send").apply { isDaemon = true }
        }

    /** S2：流被后台线程登记/移除，必须用并发容器 */
    private val activeStreams = java.util.concurrent.ConcurrentHashMap<String, EventSource>()

    var onConversationsChanged: (() -> Unit)? = null
    var onCurrentMessageUpdated: ((conversationId: String) -> Unit)? = null

    /**
     * A4：幂等初始化。
     *
     * 原实现每次调用都会 `load()` → `conversations.clear()` 并从磁盘**重建全新实例**，
     * 而在飞的 SSE 回调仍持有旧对象 → 后续分片全部写进孤儿对象（界面看不到、save 也不保存），
     * `load()` 还顺带把 `isStreaming` 置 false 让发送守卫失效。
     * 现在重复调用只保证 prefs/context 就绪，不再打断进行中的流式请求。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(lock) {
            if (initialized) return
            initialized = true
        }
        load()
    }

    val current: Conversation
        get() = if (conversations.isEmpty()) {
            createNew()
            currentIndex = 0
            conversations[0]
        } else {
            // 防御性钳制：currentIndex 若因任何路径越界，
            // 直接下标访问会抛 IndexOutOfBoundsException 并崩在 UI 线程上
            val safeIndex = currentIndex.coerceIn(0, conversations.size - 1)
            if (safeIndex != currentIndex) currentIndex = safeIndex
            conversations[safeIndex]
        }

    val all: List<Conversation>
        get() = conversations.toList()

    val currentIndex_: Int
        get() = currentIndex

    fun createNew(): Conversation {
        val defaultSystemPrompt = getDefaultSystemPrompt()
        val conv = Conversation(systemPrompt = defaultSystemPrompt)
        conversations.add(conv)
        currentIndex = conversations.size - 1
        save()
        saveCurrentId()          // B18：新建也要更新 KEY_CURRENT_ID，否则杀进程后回到旧会话
        onConversationsChanged?.invoke()
        return conv
    }

    fun switchTo(id: String): Boolean {
        val index = conversations.indexOfFirst { it.id == id }
        if (index >= 0) {
            currentIndex = index
            saveCurrentId()
            onConversationsChanged?.invoke()
            return true
        }
        return false
    }

    fun delete(id: String): Boolean {
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return false
        cancelStream(id)
        conversations.removeAt(index)
        // B19：删除项在当前项「之前」时，索引要跟着下移，否则当前会话被悄悄换成下一条
        currentIndex = when {
            index < currentIndex -> currentIndex - 1
            index == currentIndex -> index.coerceAtMost((conversations.size - 1).coerceAtLeast(0))
            else -> currentIndex
        }.coerceIn(0, (conversations.size - 1).coerceAtLeast(0))
        if (conversations.isEmpty()) {
            createNew()
        }
        save()
        saveCurrentId()          // B18：删除后该键不能仍指向已删除的 id
        onConversationsChanged?.invoke()
        return true
    }

    fun rename(id: String, title: String) {
        val conv = getById(id) ?: return
        conv.title = title
        save()
        onConversationsChanged?.invoke()
    }

    fun getById(id: String): Conversation? {
        return conversations.firstOrNull { it.id == id }
    }

    fun sendMessage(
        apiConfig: ApiConfig,
        conversationId: String,
        inputText: String,
        attachmentType: String? = null,
        attachmentData: String? = null,
        attachmentName: String? = null,
        thinkingLevel: String? = null,
        onModelInfo: ((model: String?, provider: String?) -> Unit)? = null,
        onUiUpdate: (() -> Unit)? = null
    ) {
        val conv = getById(conversationId) ?: return
        if (conv.isStreaming) return
        // 记忆已封存的会话锁定，禁止继续发送
        if (conv.isArchived) return

        val userMsg = Message(
            role = ROLE_USER,
            content = inputText,
            attachmentType = attachmentType,
            attachmentData = attachmentData,
            attachmentName = attachmentName
        )
        conv.messages.add(userMsg)

        if (conv.title == Conversation.DEFAULT_TITLE) {
            conv.title = Conversation.generateTitle(conv.messages)
        }

        conv.updatedAt = System.currentTimeMillis()
        conv.isStreaming = true

        val aiMsg = Message(ROLE_ASSISTANT, "")
        conv.messages.add(aiMsg)

        onUiUpdate?.invoke()
        onCurrentMessageUpdated?.invoke(conversationId)

        // S2：把「组装历史 + （可能的）Web 搜索 + 发起请求」整体放到后台线程执行。
        //
        // 原先这些都在主线程上同步跑：CharacterManager.loadCard / WorldInfoManager.load
        // 是磁盘 I/O，而 B20 接上的 Web 搜索是**同步网络请求** ——
        // 主线程 execute() 必然抛 NetworkOnMainThreadException（被 WebSearchClient 自己的
        // catch 吞掉 → 搜索结果永远注入不进去，B20 依旧等于死功能），
        // 若该机制不生效则是最长 45 秒的 UI 冻结/ANR。
        //
        // 注意：isStreaming / messages 的写入在**上面**已同步完成（仍在调用方线程），
        // 因此并发二次发送的守卫不受影响。
        SEND_EXECUTOR.execute {
            try {
                buildAndSend(
                    conv = conv,
                    conversationId = conversationId,
                    inputText = inputText,
                    apiConfig = apiConfig,
                    thinkingLevel = thinkingLevel,
                    onModelInfo = onModelInfo,
                    onUiUpdate = onUiUpdate
                )
            } catch (e: Throwable) {
                // 后台线程的异常绝不能静默消失：必须把会话从流式态放出来，否则永久卡住
                e.printStackTrace()
                val existing = getById(conversationId)
                if (existing != null) {
                    existing.isStreaming = false
                    activeStreams.remove(conversationId)
                    save()
                    onUiUpdate?.invoke()
                    onCurrentMessageUpdated?.invoke(conversationId)
                }
            }
        }
    }

    /** sendMessage 的后半段（后台线程执行）：组装上下文 → Web 搜索 → 发起流式请求 */
    private fun buildAndSend(
        conv: Conversation,
        conversationId: String,
        inputText: String,
        apiConfig: ApiConfig,
        thinkingLevel: String?,
        onModelInfo: ((model: String?, provider: String?) -> Unit)?,
        onUiUpdate: (() -> Unit)?
    ) {
        val historyItems = mutableListOf<MessageItem>()

        // ─── 系统提示词组装（1.2 对标酒馆：分段 + 世界书按位置注入 + 多源） ───
        var charDefinition = ""
        var charExamples = ""
        var extraSystemPrompt = ""
        var characterLoreId: String? = null
        // {{char}} 的角色名来源；顺带避免下面 post_history 重复读盘（193/289 原本各读一次）
        var activeCard: www.cetool.com.model.TavernCard? = null

        if (conv.adventureRoleIds.isNotEmpty()) {
            // 冒险模式（2.2）：DM 指令 + 多角色卡定义
            val cards = conv.adventureRoleIds.mapNotNull { CharacterManager.loadCard(appContext, it) }
            val cardDefs = cards.joinToString("\n\n") { card ->
                CharacterCompiler.compileCharacter(card).definition
            }
            charDefinition = ADVENTURE_DM_PROMPT +
                (if (cardDefs.isNotBlank()) "\n\n【本场角色卡】\n$cardDefs" else "")
            extraSystemPrompt = ""
            charExamples = ""
        } else if (conv.characterId != null) {
            val card = CharacterManager.loadCard(appContext, conv.characterId!!)
            if (card != null) {
                activeCard = card
                val compiled = CharacterManager.compileCharacter(card)
                charDefinition = compiled.definition
                charExamples = compiled.examples
                extraSystemPrompt = card.data.system_prompt
                // 角色卡内嵌世界书（导入时自动建书，见 CharacterManager.processEmbeddedWorldBook）
                characterLoreId = card.data.extensions["sachat_worldbook_id"]
                    ?.takeIf { it.isJsonPrimitive }?.asString
            } else {
                charDefinition = conv.systemPrompt
            }
        } else {
            charDefinition = conv.systemPrompt
        }

        // 世界书多源：会话绑定（可多本，Chat Lore）+ 角色卡内嵌（Character Lore）
        val worldSources = mutableListOf<www.cetool.com.model.WorldInfo>()
        for (wid in conv.boundWorldIds()) {
            WorldInfoManager.load(appContext, wid)?.let { worldSources.add(it) }
        }
        if (characterLoreId != null) {
            WorldInfoManager.load(appContext, characterLoreId!!)?.let { worldSources.add(it) }
        }

        val allHits = mutableListOf<www.cetool.com.model.WorldEntry>()
        for (source in worldSources) {
            allHits.addAll(WorldInfoEngine.scan(conv.messages, source, historyItems))
        }

        val budgetPrefs = appContext.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val budget = budgetPrefs.getInt(SettingsKeys.KEY_WORLDINFO_BUDGET, WorldInfoEngine.DEFAULT_BUDGET)
        val budgeted = WorldInfoEngine.sortAndBudget(allHits, budget)
        val injection = if (budgeted.isNotEmpty()) WorldInfoEngine.buildInjection(budgeted) else null

        val systemParts = mutableListOf<String>()
        injection?.beforeCharDefs?.takeIf { it.isNotBlank() }?.let { systemParts.add(it) }
        if (charDefinition.isNotBlank()) systemParts.add(charDefinition)
        injection?.afterCharDefs?.takeIf { it.isNotBlank() }?.let { systemParts.add(it) }
        if (charExamples.isNotBlank()) {
            injection?.beforeExamples?.takeIf { it.isNotBlank() }?.let { systemParts.add(it) }
            systemParts.add(charExamples)
            injection?.afterExamples?.takeIf { it.isNotBlank() }?.let { systemParts.add(it) }
        }
        if (extraSystemPrompt.isNotBlank()) systemParts.add(extraSystemPrompt)

        val systemText = systemParts.filter { it.isNotBlank() }.joinToString("\n\n")
        if (systemText.isNotBlank()) {
            historyItems.add(MessageItem.text("system", Conversation.resolveSystemPrompt(systemText)))
        }

        // 记忆历史消息轮数（全局设置，钳制在 5~100 范围内）
        val maxHistory = appContext.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(SettingsKeys.KEY_MAX_HISTORY, Conversation.MAX_HISTORY)
            .coerceIn(SettingsKeys.MAX_HISTORY_MIN, SettingsKeys.MAX_HISTORY_MAX)

        val startIndex = (conv.messages.size - maxHistory * 2).coerceAtLeast(0)
        for (i in startIndex until conv.messages.size - 1) {
            val msg = conv.messages[i]
            if (msg.role == ROLE_USER || msg.role == ROLE_ASSISTANT) {
                if (msg.attachmentType == Message.ATTACH_TYPE_IMAGE && msg.attachmentData != null) {
                    // B11：图片一律被压缩成 JPEG 字节，但此处原本按文件名后缀判 MIME，
                    // 于是 a.png 会生成 data:image/png;base64,<JPEG 字节>，
                    // 严格校验 MIME 的网关会拒收。MIME 以真实编码格式为准。
                    val mimeType = guessImageMime(msg.attachmentData!!)
                    historyItems.add(MessageItem.multimodal(msg.role, msg.content, msg.attachmentData, mimeType))
                } else if (msg.attachmentType == Message.ATTACH_TYPE_TEXT && msg.attachmentData != null) {
                    // 文本附件作为独立 file 内容块发送，用户消息正文保持纯净。
                    // displayText() 会剥离旧版遗留在正文里的 "[文件: 名]\n<全文>" 前缀，
                    // 否则旧数据会把文件全文既当正文又当 file 块发送两次。
                    historyItems.add(
                        MessageItem.withFile(
                            role = msg.role,
                            text = msg.displayText(),
                            fileName = msg.attachmentName ?: "attachment.txt",
                            fileData = msg.attachmentData
                        )
                    )
                } else {
                    historyItems.add(MessageItem.text(msg.role, msg.content))
                }
            }
        }

        // @D 深度注入：从末尾数第 injectDepth 条消息的位置插入 system 消息（酒馆语义，depth 0 = 提示词底部）。
        // 此时 historyItems 的末尾是**最新的用户消息**（构造循环排除了尾部空的 assistant 占位），
        // 要让 depth=0 落在它之后，插入点应是 size - depth（原实现 size-1-depth 整体差一位）。
        if (injection != null && injection.atDepth.isNotEmpty()) {
            for (entry in injection.atDepth) {
                if (entry.content.isBlank()) continue
                val depth = entry.injectDepth.coerceAtLeast(0)
                val insertIndex = (historyItems.size - depth).coerceIn(0, historyItems.size)
                // 酒馆：@D 条目可指定注入角色（system/user/assistant），默认 system
                val role = entry.injectRole?.takeIf { it in VALID_ROLES } ?: "system"
                historyItems.add(insertIndex, MessageItem.text(role, entry.content))
            }
        }

        if (conv.characterId != null && activeCard == null) {
            activeCard = CharacterManager.loadCard(appContext, conv.characterId!!)
        }
        activeCard?.let { card ->
            if (card.data.post_history_instructions.isNotBlank()) {
                historyItems.add(MessageItem.text("system", card.data.post_history_instructions))
            }
        }

        // B21：宏替换必须在**所有 historyItems 装配完成之后**执行。
        // 原实现放在历史消息入列之前，那时 historyItems 里只有 system 一条，
        // 于是 first_mes、世界书内容、用户自己输入的 {{user}} 都替换不到；
        // {{char}} 此前全项目无实现，导致角色卡里大量使用的 {{char}} 以字面量进入上下文。
        val settingsPrefs = appContext.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val macroUserName = settingsPrefs.getString(SettingsKeys.KEY_USER_NAME, "用户")?.ifBlank { "用户" } ?: "用户"
        // {{char}}：角色卡名字优先，退回 AI 显示名
        val macroCharName = charDisplayName(activeCard)
        historyItems.replaceAll { item ->
            val text = item.content.asJsonPrimitive?.asString ?: return@replaceAll item
            if (!text.contains("{{")) return@replaceAll item
            MessageItem.text(
                item.role,
                text.replace("{{user}}", macroUserName).replace("{{char}}", macroCharName)
            )
        }

        // B20：Web 搜索此前是死功能（开关和 URL 只被读写，从不发请求）。
        // 这里在发送前真正查一次，把结果作为独立的系统消息放进上下文。
        injectWebSearchResult(appContext, inputText, historyItems)

        // Free Gateway: OpenKilo / OpenCode Zen 从 SharedPreferences 读取模式与模型选择并传给网络层
        val providerPrefs = appContext.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val providerMode = providerPrefs.getString(SettingsKeys.KEY_PROVIDER_MODE, SettingsKeys.PROVIDER_CUSTOM)
            ?: SettingsKeys.PROVIDER_CUSTOM
        val kiloModelOption = providerPrefs.getString(SettingsKeys.KEY_KILO_MODEL, SettingsKeys.KILO_MODEL)
            ?: SettingsKeys.KILO_MODEL
        val zenModelOption = providerPrefs.getString(SettingsKeys.KEY_ZEN_MODEL, SettingsKeys.ZEN_MODEL_DEFAULT)
            ?: SettingsKeys.ZEN_MODEL_DEFAULT
        val client = AiApiClient(apiConfig, thinkingLevel, providerMode, kiloModelOption, zenModelOption, appContext)
        val eventSource = client.sendChatStream(
            messages = historyItems,
            onModelInfo = onModelInfo,
            onText = { chunk ->
                val lastIndex = conv.messages.size - 1
                if (lastIndex >= 0) {
                    val lastMsg = conv.messages[lastIndex]
                    if (lastMsg.role == ROLE_ASSISTANT) {
                        val updated = lastMsg.copy(content = lastMsg.content + chunk)
                        conv.messages[lastIndex] = updated
                        onUiUpdate?.invoke()
                        onCurrentMessageUpdated?.invoke(conversationId)
                    }
                }
            },
            onReasoning = { chunk ->
                val lastIndex = conv.messages.size - 1
                if (lastIndex >= 0) {
                    val lastMsg = conv.messages[lastIndex]
                    if (lastMsg.role == ROLE_ASSISTANT) {
                        val updated = lastMsg.copy(reasoningContent = lastMsg.reasoningContent + chunk)
                        conv.messages[lastIndex] = updated
                        onUiUpdate?.invoke()
                        onCurrentMessageUpdated?.invoke(conversationId)
                    }
                }
            },
            onFinish = {
                val existing = getById(conversationId) ?: return@sendChatStream
                existing.isStreaming = false
                existing.updatedAt = System.currentTimeMillis()
                activeStreams.remove(conversationId)
                save()
                onUiUpdate?.invoke()
                onCurrentMessageUpdated?.invoke(conversationId)
            },
            onError = { errorMsg ->
                val existing = getById(conversationId) ?: return@sendChatStream
                val lastIndex = existing.messages.size - 1
                if (lastIndex >= 0) {
                    val lastMsg = existing.messages[lastIndex]
                    if (lastMsg.role == ROLE_ASSISTANT) {
                        val updated = lastMsg.copy(content = lastMsg.content + "\n\n[错误: $errorMsg]")
                        existing.messages[lastIndex] = updated
                        onUiUpdate?.invoke()
                        onCurrentMessageUpdated?.invoke(conversationId)
                    }
                }
                existing.isStreaming = false
                activeStreams.remove(conversationId)
                save()
                onUiUpdate?.invoke()
                onCurrentMessageUpdated?.invoke(conversationId)
            }
        )
        // 注意：onError/onFinish 可能**同步**触发（例如 A2 的地址非法路径在
        // sendChatStream 内部就直接回调了 onError，此时它已经把自己从 activeStreams
        // 里移除过）。无条件写回会留下一个永远取消不掉的陈旧条目，
        // 因此只在会话仍处于流式状态时才登记。
        if (conv.isStreaming) {
            activeStreams[conversationId] = eventSource
        }
        save()
    }

    fun cancelStream(conversationId: String) {
        val es = activeStreams.remove(conversationId)
        es?.cancel()
        val conv = getById(conversationId)
        if (conv != null) {
            conv.isStreaming = false
            save()
        }
    }

    fun cancelAllStreams() {
        activeStreams.values.forEach { it.cancel() }
        activeStreams.clear()
        conversations.forEach { it.isStreaming = false }
    }

    fun save() {
        if (!::prefs.isInitialized) return
        try {
            val data = ConversationData(
                conversations = conversations.map { conv ->
                    ConversationEntry(
                        id = conv.id, title = conv.title, systemPrompt = conv.systemPrompt,
                        createdAt = conv.createdAt, updatedAt = conv.updatedAt,
                        isStreaming = conv.isStreaming, characterId = conv.characterId, worldInfoId = conv.worldInfoId,
                        worldInfoIds = conv.worldInfoIds,
                        isArchived = conv.isArchived,
                        adventureRoleIds = conv.adventureRoleIds,
                        messages = conv.messages.map { msg ->
                            MessageEntry(
                                role = msg.role, content = msg.content, timestamp = msg.timestamp,
                                reasoningContent = msg.reasoningContent ?: "",
                                attachmentType = msg.attachmentType, attachmentData = msg.attachmentData,
                                attachmentName = msg.attachmentName
                            )
                        }
                    )
                },
                currentId = conversations.getOrNull(currentIndex)?.id ?: ""
            )
            val json = gson.toJson(data)
            synchronized(lock) {
                // D2：整个会话库（含 base64 附件，单会话可达 27MB）原本序列化成一个大 JSON
                // 写进 SharedPreferences，每次 save 全量重写、加载时整份 XML 全量解析，
                // 带图片附件时启动即卡。改为写独立文件（原子写入），prefs 只留轻量索引。
                val file = dataFile() ?: return
                var wroteOk = false
                try {
                    wroteOk = www.cetool.com.util.SafeFiles.writeAtomic(file, json) { it.printStackTrace() }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val editor = prefs.edit()
                // M5：只有**确认写入成功**才能删掉 prefs 里的旧副本 ——
                // 否则磁盘满/权限异常时新旧两份同时消失，数据彻底归零。
                if (wroteOk && prefs.contains(KEY_DATA)) {
                    // 首次迁移完成后清掉 prefs 里的大字符串，避免继续拖慢每次 prefs 读写
                    editor.remove(KEY_DATA)
                }
                // B18：KEY_CURRENT_ID 只在 switchTo() 里写，而 load() 又优先读它。
                // 于是「切历史会话 → 新建会话 → 杀进程」会回到那个历史会话。
                // 让每次 save 同步该键，两个来源才不会打架。
                editor.putString(KEY_CURRENT_ID, data.currentId).apply()

                if (!wroteOk) {
                    android.util.Log.e("ConversationManager", "会话库写入失败，已保留 prefs 旧副本")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** D2：会话库文件（替换原先存在 SharedPreferences 里的大 JSON 字符串） */
    private fun dataFile(): java.io.File? {
        if (!::appContext.isInitialized) return null
        val dir = appContext.filesDir ?: return null
        return java.io.File(dir, "conversations.json")
    }

    /** D2：迁移期的读取 —— 优先新文件，回落到旧的 SharedPreferences */
    private fun readStoredJson(): String? {
        dataFile()?.let { file ->
            if (file.exists()) {
                www.cetool.com.util.SafeFiles.readText(file)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return prefs.getString(KEY_DATA, null)
    }

    fun load() {
        if (!::prefs.isInitialized) return
        try {
            val json = readStoredJson()
            if (json.isNullOrBlank()) {
                conversations.clear()
                conversations.add(Conversation())
                currentIndex = 0
                return
            }
            val data = gson.fromJson(json, ConversationData::class.java)
            // D2：损坏/被清空的旧数据不应让整个会话库消失
            if (data?.conversations == null) {
                conversations.clear()
                conversations.add(Conversation())
                currentIndex = 0
                return
            }
            conversations.clear()
            data.conversations.forEach { entry ->
                val conv = Conversation(
                    id = entry.id, title = entry.title, systemPrompt = entry.systemPrompt,
                    createdAt = entry.createdAt, updatedAt = entry.updatedAt,
                    isStreaming = false, characterId = entry.characterId, worldInfoId = entry.worldInfoId,
                    worldInfoIds = entry.worldInfoIds ?: emptyList(),
                    isArchived = entry.isArchived ?: false,
                    adventureRoleIds = entry.adventureRoleIds ?: emptyList(),
                    // D8：必须显式用 CopyOnWriteArrayList —— 原来这里是 .toMutableList()，
                    // 会绕开 Conversation 的默认值，导致**从磁盘加载回来的会话**
                    // 又变回普通 ArrayList，线程安全修复只在新建会话上生效。
                    messages = java.util.concurrent.CopyOnWriteArrayList(
                        entry.messages.map { msg ->
                            Message(
                                role = msg.role, content = msg.content, timestamp = msg.timestamp,
                                reasoningContent = msg.reasoningContent ?: "",
                                attachmentType = msg.attachmentType, attachmentData = msg.attachmentData,
                                attachmentName = msg.attachmentName
                            )
                        }
                    )
                )
                conversations.add(conv)
            }
            val savedCurrentId = prefs.getString(KEY_CURRENT_ID, null)
            val idx = conversations.indexOfFirst { it.id == (savedCurrentId ?: data.currentId) }
            currentIndex = if (idx >= 0) idx else 0
            if (conversations.isEmpty()) {
                conversations.add(Conversation())
                currentIndex = 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            conversations.clear()
            conversations.add(Conversation())
            currentIndex = 0
        }
    }

    private fun saveCurrentId() {
        if (!::prefs.isInitialized) return
        val id = conversations.getOrNull(currentIndex)?.id ?: ""
        prefs.edit().putString(KEY_CURRENT_ID, id).apply()
    }

    /**
     * {{char}} 的取值：角色卡名字优先，其次是 AI 显示名。
     * 冒险模式无单一主角时退回 AI 显示名。
     */
    /**
     * B20：Web 搜索注入。
     *
     * `KEY_WEB_SEARCH_URL` / `KEY_WEB_SEARCH_ENABLED` 此前只用来显示菜单文案，
     * 从未发起过任何请求。这里真正接上：开关打开 + 配置了 URL 时，
     * 用用户本条输入去搜一次，结果作为系统消息加入上下文（放在最前面）。
     */
    private fun injectWebSearchResult(
        context: Context,
        query: String,
        historyItems: MutableList<www.cetool.com.network.MessageItem>
    ) {
        val sp = context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (!sp.getBoolean(SettingsKeys.KEY_WEB_SEARCH_ENABLED, false)) return
        val url = sp.getString(SettingsKeys.KEY_WEB_SEARCH_URL, "")?.trim().orEmpty()
        if (url.isBlank() || query.isBlank()) return

        val model = context.getSharedPreferences(ApiConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ApiConfig.KEY_MODEL, "")?.trim().orEmpty().ifBlank { null }

        val result = runCatching { www.cetool.com.network.WebSearchClient.search(url, model, query) }
            .getOrNull() ?: return
        val text = result.getOrNull()?.takeIf { it.isNotBlank() } ?: return

        historyItems.add(
            0,
            www.cetool.com.network.MessageItem.text(
                "system",
                "[Web Search 结果 · 供参考]\n$text"
            )
        )
    }

    /**
     * 按**实际字节头**判断图片 MIME（B11）。
     *
     * 附件一律被压成 JPEG 写出，但历史上 MIME 是按文件名后缀判定的，
     * 于是 `a.png` 会生成 `data:image/png;base64,<JPEG 字节>`，
     * 严格校验 MIME 的网关会拒收或解码失败。
     */
    private fun guessImageMime(base64: String): String {
        val header = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (_: Exception) {
            return "image/jpeg"
        }
        fun hex(i: Int) = header.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return when {
            hex(0) == 0x89 && hex(1) == 0x50 && hex(2) == 0x4E && hex(3) == 0x47 -> "image/png"
            hex(0) == 0xFF && hex(1) == 0xD8 && hex(2) == 0xFF -> "image/jpeg"
            hex(0) == 0x47 && hex(1) == 0x49 && hex(2) == 0x46 -> "image/gif"
            hex(0) == 0x52 && hex(1) == 0x49 && hex(2) == 0x46 &&
                hex(8) == 0x57 && hex(9) == 0x45 && hex(10) == 0x42 && hex(11) == 0x50 -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /** {{char}} 的取值：角色卡名字优先，退回设置里的 AI 显示名 */
    private fun charDisplayName(card: www.cetool.com.model.TavernCard? = null): String {
        val aiName = appContext.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsKeys.KEY_AI_NAME, "AI")?.ifBlank { "AI" } ?: "AI"
        val cardName = card?.data?.name?.trim().orEmpty()
        return cardName.ifBlank { aiName }
    }

    fun getDefaultSystemPrompt(): String {
        if (!::prefs.isInitialized) return ""
        // 未保存过自定义提示词时，返回内置默认（语言规则）
        return prefs.getString(KEY_SYSTEM_PROMPT, null)
            ?: appContext.getString(R.string.default_system_prompt)
    }

    fun setDefaultSystemPrompt(prompt: String) {
        if (!::prefs.isInitialized) return
        // C7：原实现无条件保存用户当前系统提示词。而该值默认来自
        // getString(R.string.default_system_prompt)，是**语言相关**的资源：
        // 中文版是「禁止使用英文回复」、英文版是「Default output language: English」（语义相反）。
        // 用户切语言后按一次保存，就把英文规则持久化进 prefs（已保存 pref 优先于资源），
        // 切回中文也不会恢复。若内容与当前语言的默认值一致则不落库。
        val localeDefault = runCatching { appContext.getString(R.string.default_system_prompt) }.getOrNull()
        if (prompt.trim() == localeDefault?.trim()) {
            prefs.edit().remove(KEY_SYSTEM_PROMPT).apply()
            return
        }
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply()
    }

    data class ConversationData(
        val conversations: List<ConversationEntry>,
        val currentId: String
    )

    data class ConversationEntry(
        val id: String, val title: String, val systemPrompt: String,
        val createdAt: Long, val updatedAt: Long, val isStreaming: Boolean,
        val characterId: String? = null,
        val worldInfoId: String? = null,
        val worldInfoIds: List<String>? = null,
        val isArchived: Boolean? = null,
        val adventureRoleIds: List<String>? = null,
        val messages: List<MessageEntry>
    )

    data class MessageEntry(
        val role: String, val content: String, val timestamp: Long,
        val reasoningContent: String? = "",
        val attachmentType: String? = null,
        val attachmentData: String? = null,
        val attachmentName: String? = null
    )
}
