package www.cetool.com

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import okhttp3.sse.EventSource
import www.cetool.com.SettingsKeys
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

        if (conv.characterId != null) {
            val card = CharacterManager.loadCard(appContext, conv.characterId!!)
            if (card != null) {
                val assembled = CharacterManager.assembleSystemPrompt(card)
                val resolved = Conversation.resolveSystemPrompt(assembled)
                if (resolved.isNotBlank()) {
                    historyItems.add(MessageItem.text("system", resolved))
                }
            } else {
                val resolved = Conversation.resolveSystemPrompt(conv.systemPrompt)
                if (resolved.isNotBlank()) {
                    historyItems.add(MessageItem.text("system", resolved))
                }
            }
        } else {
            val resolved = Conversation.resolveSystemPrompt(conv.systemPrompt)
            if (resolved.isNotBlank()) {
                historyItems.add(MessageItem.text("system", resolved))
            }
        }

        if (conv.worldInfoId != null) {
            val worldInfo = WorldInfoManager.load(appContext, conv.worldInfoId!!)
            if (worldInfo != null) {
                val hits = WorldInfoEngine.scan(conv.messages, worldInfo, historyItems)
                val budgeted = WorldInfoEngine.sortAndBudget(hits)
                if (budgeted.isNotEmpty()) {
                    val injection = WorldInfoEngine.buildInjection(budgeted, "")
                    if (injection.prefix.isNotBlank()) {
                        val lastSystem = historyItems.indexOfLast { it.role == "system" }
                        if (lastSystem >= 0) {
                            val existing = historyItems[lastSystem]
                            val oldText = existing.content.asJsonPrimitive?.asString ?: ""
                            historyItems[lastSystem] = MessageItem.text("system", oldText + injection.prefix)
                        } else {
                            historyItems.add(0, MessageItem.text("system", injection.prefix.trim()))
                        }
                    }
                    if (injection.suffix.isNotBlank()) {
                        historyItems.add(MessageItem.text("system", injection.suffix.trim()))
                    }
                }
            }
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
