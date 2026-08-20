package www.cetool.com

import android.content.Context
import android.content.SharedPreferences
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

    /** 冒险模式 DM/GM 系统指令 */
    const val ADVENTURE_DM_PROMPT = """你是一名经验丰富的故事主持人（DM/GM）。你将主持一场文字冒险（跑团）：
1. 根据世界设定与角色卡，推进剧情、描写场景、扮演所有 NPC 与角色。
2. 用语言、行为、剧情推动互动，每次回复保持沉浸感，不要跳出角色。
3. 玩家可以用「语言」「行为」「剧情」三种方式输入：
   - [语言]：说一段话（角色会回应）
   - [行为]：做一个动作（描述结果）
   - [剧情]：引导剧情走向（控制故事节奏）
4. 重要剧情节点给出选择支（A/B/C）让玩家决策。
5. 保持世界观一致，善用世界书中的设定。"""

    private const val PREFS_NAME = "conversations"
    private const val KEY_DATA = "conversation_data"
    private const val KEY_CURRENT_ID = "current_conversation_id"
    private const val KEY_SYSTEM_PROMPT = "system_prompt_default"

    private val gson = Gson()
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val conversations = mutableListOf<Conversation>()
    private var currentIndex = 0

    private val activeStreams = mutableMapOf<String, EventSource>()

    var onConversationsChanged: (() -> Unit)? = null
    var onCurrentMessageUpdated: ((conversationId: String) -> Unit)? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        load()
    }

    val current: Conversation
        get() = if (conversations.isEmpty()) {
            createNew()
            conversations[currentIndex]
        } else {
            conversations[currentIndex]
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
        if (currentIndex >= conversations.size) {
            currentIndex = (conversations.size - 1).coerceAtLeast(0)
        }
        if (conversations.isEmpty()) {
            createNew()
        }
        save()
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

        val historyItems = mutableListOf<MessageItem>()

        // ─── 系统提示词组装（1.2 对标酒馆：分段 + 世界书按位置注入 + 多源） ───
        var charDefinition = ""
        var charExamples = ""
        var extraSystemPrompt = ""
        var characterLoreId: String? = null

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

        // 世界书多源：会话绑定（Chat Lore）+ 角色卡内嵌（Character Lore）
        val worldSources = mutableListOf<www.cetool.com.model.WorldInfo>()
        if (conv.worldInfoId != null) {
            WorldInfoManager.load(appContext, conv.worldInfoId!!)?.let { worldSources.add(it) }
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

        val settingsPrefs = appContext.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val userName = settingsPrefs.getString(SettingsKeys.KEY_USER_NAME, "用户") ?: "用户"
        historyItems.replaceAll { item ->
            val text = item.content.asJsonPrimitive?.asString ?: return@replaceAll item
            if (text.contains("{{user}}")) {
                MessageItem.text(item.role, text.replace("{{user}}", userName))
            } else item
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
                    val mimeType = if (msg.attachmentName?.lowercase()?.endsWith(".png") == true) "image/png" else "image/jpeg"
                    historyItems.add(MessageItem.multimodal(msg.role, msg.content, msg.attachmentData, mimeType))
                } else {
                    var textContent = msg.content
                    if (msg.attachmentType == Message.ATTACH_TYPE_TEXT && msg.attachmentData != null) {
                        textContent = "[文件: ${msg.attachmentName}]\n${msg.attachmentData}\n\n${msg.content}"
                    }
                    historyItems.add(MessageItem.text(msg.role, textContent))
                }
            }
        }

        // @D 深度注入：从末尾数第 injectDepth 条消息的位置插入 system 消息（酒馆语义，depth 0 = 提示词底部）
        if (injection != null && injection.atDepth.isNotEmpty()) {
            for (entry in injection.atDepth) {
                if (entry.content.isBlank()) continue
                val depth = entry.injectDepth.coerceAtLeast(0)
                val insertIndex = (historyItems.size - 1 - depth).coerceIn(0, historyItems.size)
                historyItems.add(insertIndex, MessageItem.text("system", entry.content))
            }
        }

        if (conv.characterId != null) {
            val card = CharacterManager.loadCard(appContext, conv.characterId!!)
            if (card != null && card.data.post_history_instructions.isNotBlank()) {
                historyItems.add(MessageItem.text("system", card.data.post_history_instructions))
            }
        }

        // Free Gateway: OpenKilo / OpenCode Zen 从 SharedPreferences 读取模式与模型选择并传给网络层
        val providerPrefs = appContext.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val providerMode = providerPrefs.getString(SettingsKeys.KEY_PROVIDER_MODE, SettingsKeys.PROVIDER_CUSTOM)
            ?: SettingsKeys.PROVIDER_CUSTOM
        val kiloModelOption = providerPrefs.getString(SettingsKeys.KEY_KILO_MODEL, SettingsKeys.KILO_MODEL)
            ?: SettingsKeys.KILO_MODEL
        val zenModelOption = providerPrefs.getString(SettingsKeys.KEY_ZEN_MODEL, SettingsKeys.ZEN_MODEL_DEFAULT)
            ?: SettingsKeys.ZEN_MODEL_DEFAULT
        val client = AiApiClient(apiConfig, thinkingLevel, providerMode, kiloModelOption, zenModelOption)
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
        activeStreams[conversationId] = eventSource
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
            prefs.edit().putString(KEY_DATA, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load() {
        if (!::prefs.isInitialized) return
        try {
            val json = prefs.getString(KEY_DATA, null)
            if (json.isNullOrBlank()) {
                conversations.clear()
                conversations.add(Conversation())
                currentIndex = 0
                return
            }
            val data = gson.fromJson(json, ConversationData::class.java)
            conversations.clear()
            data.conversations.forEach { entry ->
                val conv = Conversation(
                    id = entry.id, title = entry.title, systemPrompt = entry.systemPrompt,
                    createdAt = entry.createdAt, updatedAt = entry.updatedAt,
                    isStreaming = false, characterId = entry.characterId, worldInfoId = entry.worldInfoId,
                    isArchived = entry.isArchived ?: false,
                    adventureRoleIds = entry.adventureRoleIds ?: emptyList(),
                    messages = entry.messages.map { msg ->
                        Message(
                                role = msg.role, content = msg.content, timestamp = msg.timestamp,
                                reasoningContent = msg.reasoningContent ?: "",
                                attachmentType = msg.attachmentType, attachmentData = msg.attachmentData,
                                attachmentName = msg.attachmentName
                            )
                    }.toMutableList()
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

    fun getDefaultSystemPrompt(): String {
        if (!::prefs.isInitialized) return ""
        // 未保存过自定义提示词时，返回内置默认（语言规则）
        return prefs.getString(KEY_SYSTEM_PROMPT, null)
            ?: appContext.getString(R.string.default_system_prompt)
    }

    fun setDefaultSystemPrompt(prompt: String) {
        if (!::prefs.isInitialized) return
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
