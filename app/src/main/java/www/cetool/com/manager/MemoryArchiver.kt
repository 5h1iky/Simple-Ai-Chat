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

    /**
     * D10：单次封存请求里对话记录的最大字符数。
     * 超出部分从**最旧的**消息开始丢弃（保留最近发生的事，对"关系变化/关键事件"更相关）。
     */
    private const val MAX_HISTORY_CHARS = 24000

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
        retry: Boolean = true,
        appContext: android.content.Context? = null
    ): Result<ArchiveResult> {
        // 1. 整理对话历史
        val chatLines = mutableListOf<String>()
        for (m in conversation.messages) {
            if (m.role != Message.ROLE_USER && m.role != Message.ROLE_ASSISTANT) continue
            val sender = if (m.role == Message.ROLE_USER) "用户" else card.data.name
            // C1：用 displayText() 剥离旧格式消息里内嵌的附件全文，否则文件内容会整段进请求
            val content = m.displayText().replace(Regex("\\[\\[THINKING:[^\\]]+\\]\\]"), "").trim()
            if (content.isNotEmpty()) {
                chatLines.add("$sender: $content")
            }
        }
        if (chatLines.isEmpty()) {
            return Result.failure(Exception("会话中没有可分析的消息"))
        }
        // D10：超长会话会把全部原文塞进单次请求（可能直接超上下文）。
        // 从**最新**往前累计，只保留最近 MAX_HISTORY_CHARS 个字符。
        val chatHistory = buildString {
            val kept = mutableListOf<String>()
            var total = 0
            for (line in chatLines.asReversed()) {
                if (total + line.length > MAX_HISTORY_CHARS && kept.isNotEmpty()) break
                kept.add(line)
                total += line.length
            }
            append(kept.asReversed().joinToString("\n\n"))
            if (kept.size < chatLines.size) {
                append("\n\n（更早的 ${chatLines.size - kept.size} 条消息因长度限制已省略）")
            }
        }

        // 2. 组装输入（角色卡现状 + 已有关系/事件）
        val fields = card.data.getCharacterFields()
        val headerText = buildString {
            append("【角色卡】\n")
            append(CharacterCompiler.compileMarkdown(card.data.name, fields))
            append("\n\n【已有关系类型】\n").append(fields.userRelationType.ifBlank { "暂无" })
            append("\n【已有相处模式】\n").append(fields.userInteractionModel.ifBlank { "暂无" })
            append("\n【已有关系底线】\n").append(fields.userRelationBottomLine.ifBlank { "暂无" })
            append("\n【已有关键事件】\n").append(fields.keyEvents.ifBlank { "暂无共同经历的关键事件" })
        }
        val inputText = headerText + "\n\n【本次对话记录】\n" + chatHistory

        // 3. 请求模型
        val systemMessage = MessageItem.text("system", SYSTEM_PROMPT)
        val client = AiApiClient(apiConfig, thinkingLevel, providerMode, kiloModelOption, zenModelOption, appContext)
        var result = client.requestJson(
            listOf(systemMessage, MessageItem.text("user", inputText)),
            temperature = 0.0,
            maxOutputTokens = 8192
        )
        if (result.isFailure && retry) {
            // D10：原实现的重试是**完全相同的请求** —— 若失败原因是上下文超长，
            // 重试必然同样失败。这里做真正的降级：把对话记录截到一半再试一次。
            //
            // P2：截断必须丢「最旧」而不是「最新」—— 原实现 `inputText.take(length/2)`
            // 保留头部 + **最旧**的半段对话，恰好丢掉最新的关系变化/关键事件，
            // 与 D10「保留最近发生的事」的意图相反。保留角色卡头部，
            // 只把对话记录截到最近的一半。
            val shortened = headerText + "\n\n【本次对话记录】\n" +
                chatHistory.takeLast((chatHistory.length / 2).coerceAtLeast(1)).trimStart('\n') +
                "\n\n（对话记录因长度限制已省略较早部分）"
            result = client.requestJson(
                listOf(systemMessage, MessageItem.text("user", shortened)),
                temperature = 0.0,
                maxOutputTokens = 8192
            )
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
