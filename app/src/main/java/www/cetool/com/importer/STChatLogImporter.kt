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

    /** M18：导入结果 —— 消息 + 被跳过的坏行数 + 告警（无告警为 null） */
    data class ImportReport(
        val messages: List<Message>,
        val skippedBadLines: Int = 0,
        val warning: String? = null
    )

    /**
     * M18：最近一次 parse / parseDetailed 的告警（无则为 null），例如"已跳过 N 行无法解析的内容"。
     * 旧调用点（ChatActivity 等）只消费 Result<List<Message>>，可经此取到坏行告警。
     */
    @Volatile
    var lastImportWarning: String? = null
        private set

    /** 兼容旧签名：只要消息列表；坏行告警见 [lastImportWarning]，或改用 [parseDetailed] */
    fun parse(jsonl: String): Result<List<Message>> = parseDetailed(jsonl).map { it.messages }

    /** M18：完整导入结果（含坏行统计与告警） */
    fun parseDetailed(jsonl: String): Result<ImportReport> = runCatching {
        val messages = mutableListOf<Message>()
        var lastTimestamp = System.currentTimeMillis()
        // M18：坏行不再静默丢弃 —— 统计数量并在结果里带出告警
        var skippedBadLines = 0

        // M18：文件首行常带 UTF-8 BOM（\uFEFF），原来会让第一条消息解析失败被无声吞掉 —— 先剥掉
        val text = jsonl.removePrefix("\uFEFF")

        text.lineSequence().forEach { line ->
            // M18：trim 时容忍残留 BOM（BOM 可能出现在行首）
            val trimmed = line.trim { it == '\uFEFF' || it.isWhitespace() }
            if (trimmed.isEmpty()) return@forEach
            val obj = try {
                JsonParser.parseString(trimmed).asJsonObject
            } catch (_: Exception) {
                skippedBadLines++
                return@forEach
            }
            val isUser = obj.get("is_user")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val isSystem = obj.get("is_system")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val mesRaw = obj.get("mes")?.takeIf { it.isJsonPrimitive }?.asString
            if (mesRaw == null) {
                // 没有 mes 字段的行同样算坏行（解析不出可用内容）
                skippedBadLines++
                return@forEach
            }
            // 空白消息导入进来只是空气泡，没有信息量，直接跳过
            if (mesRaw.isBlank()) return@forEach
            val mes = mesRaw

            // P2：酒馆真实导出的 create_date 是 "2024-02-11 @13h 20m 19s 942ms" 风格、
            // send_date 是本地化字符串 —— 原实现三种 pattern 都匹配不上且不读 send_date，
            // 时间戳全部 fallback 成「导入时刻起每秒一条」。这里补上 ST 风格解析与 send_date 兜底。
            val createDate = obj.get("create_date")?.takeIf { it.isJsonPrimitive }?.asString
            val sendDate = obj.get("send_date")?.takeIf { it.isJsonPrimitive }?.asString
            val ts = parseDate(createDate) ?: parseDate(sendDate) ?: (lastTimestamp + 1000)
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
            // M18：全被跳过时把坏行数量带进错误信息（调用方经 e.message 展示，复用现有提示途径）
            throw IllegalArgumentException(
                if (skippedBadLines > 0) {
                    "文件中没有可导入的聊天消息（跳过 $skippedBadLines 行无法解析的内容）"
                } else {
                    "文件中没有可导入的聊天消息（不是酒馆 .jsonl 聊天记录？）"
                }
            )
        }
        // M18：坏行统计随结果带出，不再无声丢失
        val warning = if (skippedBadLines > 0) "已跳过 $skippedBadLines 行无法解析的内容" else null
        ImportReport(messages, skippedBadLines, warning).also { lastImportWarning = it.warning }
    }

    /** 酒馆 create_date 的典型形态："2024-02-11 @13h 20m 19s 942ms"（也覆盖 ISO/常见分隔符） */
    private val ST_DATE_REGEX = Regex("""(\d{4})-(\d{2})-(\d{2})\D+(\d{1,2})\D+(\d{1,2})\D+(\d{1,2})""")

    private fun parseDate(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        // 1) 酒馆 "@Xh Ym Zs Wms" 风格优先
        ST_DATE_REGEX.find(text)?.let { m ->
            return try {
                val (y, mo, d, h, mi, s) = m.destructured
                val cal = java.util.Calendar.getInstance()
                cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            } catch (_: Exception) { null }
        }
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
