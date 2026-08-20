package www.cetool.com.importer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo
import java.util.UUID

/**
 * 世界书导入器：格式探测 + 容错解析 + id 重新生成。
 *
 * 支持的格式：
 * 1. SAChat 自身格式：扁平 `{name, description, enabled, entries: [...]}`
 *    或包裹格式 `{version, type: "lorebook", data: {...}}`（此前自产文件导不回的根因）
 * 2. SillyTavern 标准 World Info：`{name?, entries: {uid: {...}}}`，
 *    同时兼容**新旧两代字段名**（当前版 `keys`/`insertion_order`/`extensions.*`，
 *    旧版 `key`/`order`/`position`/`depth`/`probability`/`disable` 等顶层字段）
 * 3. MuseAI 格式（museai.partner-items）：明确提示不兼容
 *
 * 设计原则：
 * - 酒馆字段全量保留到 WorldEntry（引擎未实现的字段也随文件往返保存，导入不丢信息）
 * - 所有 id 重新生成，杜绝旧实现把缺失 id 写成 "null.json" 互相覆盖的问题
 * - 逐条容错：坏条目跳过并计入警告，不中断整体导入
 */
object WorldInfoImporter {

    sealed class ImportResult {
        data class Success(val info: WorldInfo, val warnings: List<String>) : ImportResult()
        data class Failure(val message: String) : ImportResult()
    }

    /** 酒馆注入位置编号 → 本项目位置字符串（5/6/7 暂不支持，降级到最接近的位置，原始编号保留在 stPosition） */
    private val ST_POSITION_MAP = mapOf(
        0 to "before_char_defs",
        1 to "after_system_prompt",
        2 to "before_example_messages",
        3 to "after_example_messages",
        4 to "at_depth",
        5 to "after_system_prompt", // Top of AN（暂降级）
        6 to "after_system_prompt", // Bottom of AN（暂降级）
        7 to "at_depth"             // Outlet（暂降级）
    )

    fun parse(json: String): ImportResult {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return ImportResult.Failure("文件内容不是合法 JSON，无法导入")
        }
        if (!root.isJsonObject) {
            return ImportResult.Failure("文件根节点不是 JSON 对象，无法导入")
        }
        val obj = root.asJsonObject

        // 1. MuseAI 格式识别（明确提示，避免用户困惑）
        val schema = str(obj.get("schema"))
        if (schema == "museai.partner-items") {
            return ImportResult.Failure(
                "这是 MuseAI 世界书格式（museai.partner-items），其结构是「结构化字段+全量注入」文档，" +
                    "与 SAChat 的关键词触发式 lorebook 不兼容，无法直接导入。"
            )
        }

        // 2. 包裹格式 {version, type, data:{...}}（本项目早期保存格式）
        val dataEl = obj.get("data")
        val dataObj = if (dataEl != null && dataEl.isJsonObject) dataEl.asJsonObject else null
        val source = if (dataObj != null && (dataObj.has("entries") || dataObj.has("name"))) dataObj else obj

        // 3. 必须有 entries
        val entriesEl = source.get("entries")
        if (entriesEl == null || entriesEl.isJsonNull) {
            return ImportResult.Failure("无法识别的世界书格式：缺少 entries 字段（不是 SillyTavern 或 SAChat 世界书文件）")
        }

        val warnings = mutableListOf<String>()
        val entries = mutableListOf<WorldEntry>()

        when {
            // SillyTavern 格式：entries 为 uid→条目 的对象
            entriesEl.isJsonObject -> {
                val entriesObj = entriesEl.asJsonObject
                for ((uid, el) in entriesObj.entrySet()) {
                    if (!el.isJsonObject) {
                        warnings.add("条目「$uid」不是合法对象，已跳过")
                        continue
                    }
                    val entry = parseStEntry(el.asJsonObject, uid, warnings)
                    if (entry != null) entries.add(entry)
                }
            }
            // SAChat 格式：entries 为数组
            entriesEl.isJsonArray -> {
                val arr = entriesEl.asJsonArray
                for ((index, el) in arr.withIndex()) {
                    if (!el.isJsonObject) {
                        warnings.add("第 ${index + 1} 个条目不是合法对象，已跳过")
                        continue
                    }
                    val entry = parseOwnEntry(el.asJsonObject, warnings)
                    if (entry != null) entries.add(entry)
                }
            }
            else -> {
                return ImportResult.Failure("无法识别的世界书格式：entries 字段既不是对象也不是数组")
            }
        }

        val name = str(source.get("name"))?.takeIf { it.isNotBlank() } ?: "未命名世界书"
        val description = str(source.get("description")) ?: ""
        val enabled = bool(source.get("enabled"), true)

        val info = WorldInfo(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            description = description,
            enabled = enabled,
            entries = entries.toMutableList()
        )
        return ImportResult.Success(info, warnings)
    }

    // ─── SillyTavern 条目解析（兼容新旧两代字段名） ──────────────────────────

    private fun parseStEntry(entry: JsonObject, uid: String, warnings: MutableList<String>): WorldEntry? {
        val ext = entry.get("extensions")?.takeIf { it.isJsonObject }?.asJsonObject

        // 关键词：当前版 keys / 旧版 key / 兜底 keywords
        var keywords = strList(first(entry, "keys", "key", "keywords"))
        var useRegex = bool(entry.get("useRegex"), false)
        // 酒馆行为：以 /.../ 形式书写的 key 自动视为正则，去掉定界斜杠
        if (!useRegex && keywords.any { looksLikeRegex(it) }) {
            useRegex = true
        }
        if (useRegex) {
            keywords = keywords.map { stripRegexDelimiters(it) }.toMutableList()
        }

        val content = str(entry.get("content")) ?: ""
        val comment = str(entry.get("comment")) ?: ""
        val name = comment.ifBlank { keywords.firstOrNull() ?: "未命名条目" }

        // 跳过完全空白的条目（无内容且无关键词且无常驻）
        if (content.isBlank() && keywords.isEmpty() && !bool(entry.get("constant"), false)) {
            warnings.add("条目「$name」(uid=$uid) 内容为空且无关键词，已跳过")
            return null
        }

        val stPosition = int(extOrTop(ext, entry, "position", "position"), -1)
        val position = ST_POSITION_MAP[stPosition] ?: "after_system_prompt"
        val injectDepth = int(extOrTop(ext, entry, "depth", "depth"), 4)
        val scanDepth = int(extOrTop(ext, entry, "scan_depth", "scanDepth"), 4)
        val probability = int(extOrTop(ext, entry, "probability", "probability"), 100)

        return WorldEntry(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            enabled = bool(entry.get("enabled"), true) && !bool(entry.get("disable"), false),
            priority = int(first(entry, "insertion_order", "order", "priority"), 1000),
            position = position,
            content = content,
            injectDepth = injectDepth,
            // 酒馆的 role 是 @D 深度注入时的消息角色（见 injectRole），不是匹配角色过滤，这里不映射
            role = null,
            keywords = keywords.toMutableList(),
            useRegex = useRegex,
            caseSensitive = bool(extOrTop(ext, entry, "case_sensitive", "caseSensitive"), false),
            scanDepth = scanDepth,
            constantActive = bool(entry.get("constant"), false),
            stPosition = stPosition,
            wholeWords = bool(extOrTop(ext, entry, "match_whole_words", "matchWholeWords"), true),
            secondaryKeys = strList(first(entry, "secondary_keys", "keysecondary", "secondaryKeys")).toMutableList(),
            selective = bool(entry.get("selective"), false),
            selectiveLogic = int(entry.get("selectiveLogic"), 0),
            probability = probability,
            useProbability = bool(extOrTop(ext, entry, "use_probability", "useProbability"), false),
            groupName = str(entry.get("group")) ?: "",
            groupWeight = int(extOrTop(ext, entry, "group_weight", "groupWeight"), 100),
            excludeRecursion = bool(extOrTop(ext, entry, "exclude_recursion", "excludeRecursion"), false),
            preventRecursion = bool(extOrTop(ext, entry, "prevent_recursion", "preventRecursion"), false),
            delayUntilRecursion = bool(extOrTop(ext, entry, "delay_until_recursion", "delayUntilRecursion"), false),
            automationId = str(extOrTop(ext, entry, "automation_id", "automationId")) ?: "",
            score = int(entry.get("score"), 0),
            sticky = int(extOrTop(ext, entry, "sticky", "sticky"), 0),
            cooldown = int(extOrTop(ext, entry, "cooldown", "cooldown"), 0),
            delay = int(extOrTop(ext, entry, "delay", "delay"), 0),
            displayIndex = int(extOrTop(ext, entry, "display_index", "displayIndex"), 0),
            comment = comment,
            vectorized = bool(extOrTop(ext, entry, "vectorized", "vectorized"), false),
            triggers = strList(extOrTop(ext, entry, "triggers", "triggers")).toMutableList(),
            injectRole = str(extOrTop(ext, entry, "role", "role"))
        )
    }

    // ─── SAChat 自身条目解析（扁平/包裹格式的 entries 数组） ────────────────

    private fun parseOwnEntry(entry: JsonObject, warnings: MutableList<String>): WorldEntry? {
        val name = str(entry.get("name")) ?: ""
        val content = str(entry.get("content")) ?: ""

        // 兼容旧文件：position 可能是酒馆数字编号
        val rawPosition = entry.get("position")
        val position = when {
            rawPosition != null && rawPosition.isJsonPrimitive && rawPosition.asJsonPrimitive.isNumber ->
                ST_POSITION_MAP[rawPosition.asInt] ?: "after_system_prompt"
            else -> str(rawPosition) ?: "after_system_prompt"
        }

        val stPosition = int(entry.get("stPosition"), -1)

        if (name.isBlank() && content.isBlank() && keywordsOf(entry).isEmpty()) {
            warnings.add("存在内容为空且无关键词的条目，已跳过")
            return null
        }

        return WorldEntry(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "未命名条目" },
            enabled = bool(entry.get("enabled"), true),
            priority = int(entry.get("priority"), 1000),
            position = position,
            content = content,
            injectDepth = int(entry.get("injectDepth"), 4),
            role = str(entry.get("role")),
            keywords = keywordsOf(entry).toMutableList(),
            useRegex = bool(entry.get("useRegex"), false),
            caseSensitive = bool(entry.get("caseSensitive"), false),
            scanDepth = int(entry.get("scanDepth"), 4),
            constantActive = bool(entry.get("constantActive"), false),
            stPosition = stPosition,
            wholeWords = bool(entry.get("wholeWords"), true),
            secondaryKeys = strList(entry.get("secondaryKeys")).toMutableList(),
            selective = bool(entry.get("selective"), false),
            selectiveLogic = int(entry.get("selectiveLogic"), 0),
            probability = int(entry.get("probability"), 100),
            useProbability = bool(entry.get("useProbability"), false),
            groupName = str(entry.get("groupName")) ?: "",
            groupWeight = int(entry.get("groupWeight"), 100),
            excludeRecursion = bool(entry.get("excludeRecursion"), false),
            preventRecursion = bool(entry.get("preventRecursion"), false),
            delayUntilRecursion = bool(entry.get("delayUntilRecursion"), false),
            automationId = str(entry.get("automationId")) ?: "",
            score = int(entry.get("score"), 0),
            sticky = int(entry.get("sticky"), 0),
            cooldown = int(entry.get("cooldown"), 0),
            delay = int(entry.get("delay"), 0),
            displayIndex = int(entry.get("displayIndex"), 0),
            comment = str(entry.get("comment")) ?: "",
            vectorized = bool(entry.get("vectorized"), false),
            triggers = strList(entry.get("triggers")).toMutableList(),
            injectRole = str(entry.get("injectRole"))
        )
    }

    // ─── 工具函数 ────────────────────────────────────────────────────────────

    /** 依次取第一个非 null 的顶层字段 */
    private fun first(entry: JsonObject, vararg names: String): JsonElement? {
        for (n in names) {
            val e = entry.get(n)
            if (e != null && !e.isJsonNull) return e
        }
        return null
    }

    /** 先取 extensions 内字段，再取顶层字段 */
    private fun extOrTop(ext: JsonObject?, entry: JsonObject, extName: String, topName: String): JsonElement? {
        val e = ext?.get(extName)
        if (e != null && !e.isJsonNull) return e
        val t = entry.get(topName)
        return if (t != null && !t.isJsonNull) t else null
    }

    private fun str(e: JsonElement?): String? = when {
        e == null || e.isJsonNull -> null
        e.isJsonPrimitive -> e.asString // 数字/布尔转字符串兜底
        else -> null
    }

    private fun bool(e: JsonElement?, def: Boolean): Boolean = when {
        e == null || e.isJsonNull -> def
        e.isJsonPrimitive && e.asJsonPrimitive.isBoolean -> e.asBoolean
        e.isJsonPrimitive && e.asJsonPrimitive.isString -> e.asString.toBooleanStrictOrNull() ?: def
        else -> def
    }

    private fun int(e: JsonElement?, def: Int): Int = when {
        e == null || e.isJsonNull -> def
        e.isJsonPrimitive && e.asJsonPrimitive.isNumber -> e.asInt
        e.isJsonPrimitive && e.asJsonPrimitive.isString -> e.asString.toIntOrNull() ?: def
        else -> def
    }

    private fun strList(e: JsonElement?): List<String> = when {
        e == null || e.isJsonNull -> emptyList()
        e.isJsonArray -> e.asJsonArray.mapNotNull { str(it) }
        else -> str(e)?.split(',', '，')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    }

    private fun keywordsOf(entry: JsonObject): List<String> {
        return strList(entry.get("keywords")).map { stripRegexDelimiters(it) }
    }

    /**
     * 判断关键词是否为正则形式：以 "/" 开头且内部还有 "/"（支持 "/pattern/" 与 "/pattern/flags" 两种写法）
     */
    private fun looksLikeRegex(keyword: String): Boolean {
        return keyword.length > 2 && keyword.startsWith("/") && keyword.lastIndexOf('/') > 0
    }

    /**
     * 去掉 /.../ 定界符与尾部 flags（如 "/pattern/i" → "pattern"）。
     * 大小写敏感性由 caseSensitive 字段控制，不依赖正则内联 flags。
     */
    private fun stripRegexDelimiters(keyword: String): String {
        if (!looksLikeRegex(keyword)) return keyword
        val lastSlash = keyword.lastIndexOf('/')
        return if (lastSlash > 0) keyword.substring(1, lastSlash) else keyword.substring(1, keyword.length - 1)
    }
}
