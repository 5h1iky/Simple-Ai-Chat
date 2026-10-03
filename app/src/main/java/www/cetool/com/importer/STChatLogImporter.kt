package www.cetool.com.importer

import com.google.gson.JsonParser
import www.cetool.com.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 酒馆（SillyTavern）聊天记录导入器。
 * 格式：.jsonl，每行一个消息对象：
 * {"name": "角色名", "is_user": false, "is_system": false, "create_date": "YYYY-MM-DD HH:mm:ss", "mes": "内容", ...}
 */
object STChatLogImporter {

    fun parse(jsonl: String): Result<List<Message>> = runCatching {
        val messages = mutableListOf<Message>()
        var lastTimestamp = System.currentTimeMillis()

        jsonl.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val obj = try {
                JsonParser.parseString(trimmed).asJsonObject
            } catch (_: Exception) {
                return@forEach
            }
            val isUser = obj.get("is_user")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val isSystem = obj.get("is_system")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val mes = obj.get("mes")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            if (isSystem && mes.isBlank()) return@forEach

            val createDate = obj.get("create_date")?.takeIf { it.isJsonPrimitive }?.asString
            val ts = parseDate(createDate) ?: (lastTimestamp + 1000)
            lastTimestamp = ts

            val role = if (isUser) Message.ROLE_USER else Message.ROLE_ASSISTANT
            // B30：原本只跳过「空白的 system」，非空白的旁白/系统条目被直接记成角色发言；
            // 且 `name` 字段（文件头注释里明确提到）从未读取 ——
            // 群聊记录里其他角色的台词全部被记到当前角色名下，且事后不可分辨。
            //
            // 这里保留 assistant 角色（否则该条会从发给模型的 history 里被整体丢弃），
            // 但把说话者写进正文，使归属可辨认、不再混淆。
            val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val content = when {
                isSystem && name.isNotBlank() -> "（系统/$name）${mes.trim()}"
                isSystem -> "（系统）${mes.trim()}"
                !isUser && name.isNotBlank() -> "$name：${mes.trim()}"
                else -> mes.trim()
            }

            messages.add(
                Message(
                    role = role,
                    content = content,
                    timestamp = ts
                )
            )
        }

        if (messages.isEmpty()) {
            throw IllegalArgumentException("文件中没有可导入的聊天消息（不是酒馆 .jsonl 聊天记录？）")
        }
        messages
    }

    private fun parseDate(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            val formats = listOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm"
            )
            for (f in formats) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.getDefault())
                    return sdf.parse(text)?.time
                } catch (_: Exception) {}
            }
            null
        } catch (_: Exception) { null }
    }
}
