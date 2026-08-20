package www.cetool.com.manager

import com.google.gson.JsonParser
import www.cetool.com.model.ApiConfig
import www.cetool.com.model.Conversation
import www.cetool.com.model.Message
import www.cetool.com.model.TavernCard
import www.cetool.com.network.AiApiClient
import www.cetool.com.network.MessageItem

/**
 * 记忆封存：分析会话 → 提炼角色关系变化/关键事件 → 写回角色卡结构化字段。
 * 对应 MuseAI 的 analyze_character_memory 流程（酒馆官方没有此功能）。
 */
object MemoryArchiver {

    data class ArchiveResult(
        val sessionTitle: String,
        val userRelationType: String,
        val userInteractionModel: String,
        val userRelationBottomLine: String,
        val keyEvents: String
    )

    private const val SYSTEM_PROMPT = """你是角色档案管理员。请深入分析用户提供的对话记录，提炼「角色」与「用户」的关系变化、相处模式和共同经历的关键事件。

要求：
1. 只输出一个 JSON 对象，不要输出任何其他文字、解释或 markdown 代码块围栏。
2. JSON 字段（全部为字符串，key_events 用换行分隔的列表项，每项以 - 开头）：
{
  "session_title": "本场对话的建议标题（10字以内）",
  "user_relation_type": "更新后的与用户关系类型",
  "user_interaction_model": "更新后的与用户相处模式",
  "user_relation_bottom_line": "更新后的与用户关系底线",
  "key_events": "本场对话的关键事件，追加在已有事件之后"
}
3. 若对话中没有新的关系变化，保留输入中的旧值不变。
4. key_events 必须包含本场对话中新发生的重要事件，不要遗漏。"""

    /**
     * 分析会话并返回结构化结果。
     * @param retry 失败时是否重试一次
     */
    fun analyze(
        apiConfig: ApiConfig,
        conversation: Conversation,
        card: TavernCard,
        thinkingLevel: String? = null,
        providerMode: String = www.cetool.com.SettingsKeys.PROVIDER_CUSTOM,
        kiloModelOption: String = www.cetool.com.SettingsKeys.KILO_MODEL,
        zenModelOption: String = www.cetool.com.SettingsKeys.ZEN_MODEL_DEFAULT,
        retry: Boolean = true
    ): Result<ArchiveResult> {
        // 1. 整理对话历史
        val chatLines = mutableListOf<String>()
        for (m in conversation.messages) {
            if (m.role != Message.ROLE_USER && m.role != Message.ROLE_ASSISTANT) continue
            val sender = if (m.role == Message.ROLE_USER) "用户" else card.data.name
            val content = m.content.replace(Regex("\\[\\[THINKING:[^\\]]+\\]\\]"), "").trim()
            if (content.isNotEmpty()) {
                chatLines.add("$sender: $content")
            }
        }
        if (chatLines.isEmpty()) {
            return Result.failure(Exception("会话中没有可分析的消息"))
        }
        val chatHistory = chatLines.joinToString("\n\n")

        // 2. 组装输入（角色卡现状 + 已有关系/事件）
        val fields = card.data.getCharacterFields()
        val inputText = buildString {
            append("【角色卡】\n")
            append(CharacterCompiler.compileMarkdown(card.data.name, fields))
            append("\n\n【已有关系类型】\n").append(fields.userRelationType.ifBlank { "暂无" })
            append("\n【已有相处模式】\n").append(fields.userInteractionModel.ifBlank { "暂无" })
            append("\n【已有关系底线】\n").append(fields.userRelationBottomLine.ifBlank { "暂无" })
            append("\n【已有关键事件】\n").append(fields.keyEvents.ifBlank { "暂无共同经历的关键事件" })
            append("\n\n【本次对话记录】\n").append(chatHistory)
        }

        // 3. 请求模型
        val messages = listOf(
            MessageItem.text("system", SYSTEM_PROMPT),
            MessageItem.text("user", inputText)
        )
        val client = AiApiClient(apiConfig, thinkingLevel, providerMode, kiloModelOption, zenModelOption)
        var result = client.requestJson(messages, temperature = 0.0, maxOutputTokens = 8192)
        if (result.isFailure && retry) {
            result = client.requestJson(messages, temperature = 0.0, maxOutputTokens = 8192)
        }
        val content = result.getOrNull()
            ?: return Result.failure(result.exceptionOrNull() ?: Exception("记忆分析请求失败"))

        // 4. 解析 JSON（容忍 ```json 围栏与前后杂质）
        return parseArchiveJson(content)
    }

    fun parseArchiveJson(content: String): Result<ArchiveResult> {
        return try {
            var text = content.trim()
            // 剥除 markdown 代码块围栏
            text = text.replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*```$"), "")
                .trim()
            // 截取第一个 { 到最后一个 }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) {
                return Result.failure(Exception("模型输出不是 JSON 格式"))
            }
            text = text.substring(start, end + 1)

            val obj = JsonParser.parseString(text).asJsonObject
            val get = { key: String ->
                obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: ""
            }
            Result.success(
                ArchiveResult(
                    sessionTitle = get("session_title"),
                    userRelationType = get("user_relation_type"),
                    userInteractionModel = get("user_interaction_model"),
                    userRelationBottomLine = get("user_relation_bottom_line"),
                    keyEvents = get("key_events")
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
