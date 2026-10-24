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

    /**
     * 酒馆注入位置编号 → 本项目位置字符串。
     *
     * 酒馆真实枚举（`SillyTavern/public/scripts/world-info.js`）：
     * `0 before / 1 after / 2 ANTop / 3 ANBottom / 4 atDepth / 5 EMTop / 6 EMBottom / 7 outlet`
     *
     * B2 修复：原实现把 AN 与 EM 两组搞反（注释「5/6=AN」本身即误解），
     * 且把 7=Outlet 映射成 `at_depth` —— 酒馆里 Outlet 条目**从不自动注入**
     * （只由 `{{outlet::名称}}` 手动拉取），这里映射为「不注入」的独立槽位。
     */
    private val ST_POSITION_MAP = mapOf(
        0 to "before_char_defs",          // before char defs
        1 to "after_system_prompt",       // after char defs
        2 to "before_example_messages",   // AN top
        3 to "after_example_messages",    // AN bottom
        4 to "at_depth",                  // @D at depth
        5 to "before_example_messages",   // EM top
        6 to "after_example_messages",    // EM bottom
        7 to "outlet"                     // B2：不自动注入（{{outlet::}} 手动拉取）
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

    /**
     * 解析**角色卡内嵌世界书**（`data.character_book`）。
     *
     * B1：酒馆把角色卡自带的世界书放在 `data.character_book`（不是 `extensions.world_book`），
     * 且其 `entries` 是**数组**，字段用 `keys` / `secondary_keys` / `constant` /
     * `insertion_order` / `enable` 等命名 —— 与本项目自有数组格式的同名字段含义不同。
     * 若直接走 `parseOwnEntry`（只读 `keywords`、读 `constantActive`），
     * 每条都会变成「0 关键词 + 非常驻」，永远不可能触发。
     */
    fun parseCharacterBook(book: www.cetool.com.model.CharacterBook, fallbackName: String): ImportResult {
        val warnings = mutableListOf<String>()
        val entries = mutableListOf<WorldEntry>()

        for ((index, el) in book.entries.withIndex()) {
            val entry = parseCharacterBookEntry(el)
            if (entry != null) entries.add(entry)
            else warnings.add("第 ${index + 1} 个内嵌条目内容为空且无关键词，已跳过")
        }

        val info = WorldInfo(
            id = UUID.randomUUID().toString().take(8),
            name = book.name?.takeIf { it.isNotBlank() } ?: "$fallbackName 的世界书",
            description = book.description ?: "",
            enabled = true,
            entries = entries
        )
        return ImportResult.Success(info, warnings)
    }

    private fun parseCharacterBookEntry(e: www.cetool.com.model.CharacterBookEntry): WorldEntry? {
        val keywords = e.keys.map { it.trim() }.filter { it.isNotBlank() }.toMutableList()

        // 内联正则：角色书允许 "/pattern/flags" 写法
        var useRegex = e.use_regex
        if (!useRegex && keywords.any { looksLikeRegex(it) }) useRegex = true
        val finalKeywords = (if (useRegex) keywords.map { stripRegexDelimiters(it) } else keywords)
            .map { it.trim() }.filter { it.isNotBlank() }.toMutableList()

        val secondaryKeys = e.secondary_keys.map { it.trim() }.filter { it.isNotBlank() }
        val finalSecondary = (if (useRegex) secondaryKeys.map { stripRegexDelimiters(it) } else secondaryKeys)
            .map { it.trim() }.filter { it.isNotBlank() }.toMutableList()

        // 角色书的开关字段是 enabled（部分生成器写作 disable）
        val isEnabled = e.enabled

        val content = e.content
        if (content.isBlank() && finalKeywords.isEmpty() && !e.constant) return null

        // B3：角色书条目的 position 是字符串 "before_char" / "after_char"，
        // 直接落到 buildInjection 的 when 里没有匹配分支 → 条目会静默消失。
        val (position, stPosition) = when (e.position?.trim()?.lowercase()) {
            "before_char" -> "before_char_defs" to 0
            "after_char" -> "after_system_prompt" to 1
            else -> "after_system_prompt" to 1
        }

        val displayName = e.name?.takeIf { it.isNotBlank() }
            ?: e.comment.takeIf { it.isNotBlank() }
            ?: finalKeywords.firstOrNull()
            ?: "未命名条目"

        return WorldEntry(
            id = UUID.randomUUID().toString().take(8),
            name = displayName,
            enabled = isEnabled,
            priority = e.priority ?: e.insertion_order,
            position = position,
            content = content,
            // P2：scan_depth 是「扫描深度」，绝不能塞进 injectDepth（@D 注入深度）——
            // 角色书条目只落 before/after_char 两个槽，注入深度用默认 0。
            injectDepth = 0,
            role = null,
            keywords = finalKeywords,
            useRegex = useRegex,
            caseSensitive = e.case_sensitive ?: false,
            scanDepth = e.scan_depth ?: 4,
            constantActive = e.constant,
            stPosition = stPosition,
            wholeWords = e.match_whole_words ?: false,   // 内嵌角色书默认按子串匹配
            secondaryKeys = finalSecondary.toMutableList(),
            selective = e.selective,
            selectiveLogic = e.selectiveLogic,
            probability = 100,
            useProbability = false,
            comment = e.comment
        )
    }

    // ─── SillyTavern 条目解析（兼容新旧两代字段名） ──────────────────────────

    private fun parseStEntry(entry: JsonObject, uid: String, warnings: MutableList<String>): WorldEntry? {
        val ext = entry.get("extensions")?.takeIf { it.isJsonObject }?.asJsonObject

        // 关键词：当前版 keys / 旧版 key / 兜底 keywords
        var keywords = strList(first(entry, "keys", "key", "keywords"))
        // P1：酒馆把正则标志放在 extensions.use_regex（顶层 useRegex 是本项目自有写法，
        // 酒馆文件没有）。原实现只读顶层 → 酒馆里勾了 Regex 的裸正则键全被按字面匹配。
        var useRegex = bool(extOrTop(ext, entry, "use_regex", "useRegex"), false)
        // 酒馆行为：以 /.../ 形式书写的 key 自动视为正则，去掉定界斜杠
        if (!useRegex && keywords.any { looksLikeRegex(it) }) {
            useRegex = true
        }
        if (useRegex) {
            keywords = keywords.map { stripRegexDelimiters(it) }.toMutableList()
        }
        // B5：次级键的原始值先取出，剥离时机与主键保持一致（见下方 secondaryKeys=）
        val rawSecondaryKeys = strList(first(entry, "secondary_keys", "keysecondary", "secondaryKeys"))

        val content = str(entry.get("content")) ?: ""
        val comment = str(entry.get("comment")) ?: ""
        // 往返完整性：本项目的导出器会额外写一个 `name` 字段（WorldEntry 的 name 与
        // comment 是两个独立字段，而酒馆格式只有 comment）。优先用它，否则回落 comment。
        val name = str(entry.get("name"))?.takeIf { it.isNotBlank() }
            ?: comment.ifBlank { keywords.firstOrNull() ?: "未命名条目" }

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
            // M1：`role`（按消息角色过滤）在酒馆格式里没有对应字段，
            // 原名 `role` 被酒馆占用为 @D 注入角色（见下方 injectRole），
            // 所以本项目用扩展键 sachat_role 携带，这里读回以便无损往返。
            role = str(ext?.get("sachat_role"))?.takeIf { it.isNotBlank() },
            keywords = keywords.toMutableList(),
            useRegex = useRegex,
            caseSensitive = bool(extOrTop(ext, entry, "case_sensitive", "caseSensitive"), false),
            scanDepth = scanDepth,
            constantActive = bool(entry.get("constant"), false),
            stPosition = stPosition,
            wholeWords = bool(extOrTop(ext, entry, "match_whole_words", "matchWholeWords"), true),
            // B5：useRegex 为真时次级键也要剥 /.../ 定界符。
            // 原实现原样保留 "/敏感词/"，引擎按正则编译后只会匹配字面的 /敏感词/，
            // 配合默认 AND ANY 逻辑 → passesSelective 恒为 false → 条目永不激活。
            secondaryKeys = rawSecondaryKeys
                .let { if (useRegex) it.map { k -> stripRegexDelimiters(k) } else it }
                .map { it.trim() }.filter { it.isNotBlank() }.toMutableList(),
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
        // 自有格式：keywords 里的 "/pat/" 也是内联正则写法
        val ownUseRegex = bool(entry.get("useRegex"), false)

        val guardKeywords = keywordsOf(entry, ownUseRegex)
        if (name.isBlank() && content.isBlank() && guardKeywords.isEmpty()) {
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
            keywords = guardKeywords.toMutableList(),
            useRegex = ownUseRegex || guardKeywords.any { looksLikeRegex(it) },
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

    private fun strList(e: JsonElement?): List<String> {
        // B6：数组分支原本 mapNotNull 后不再过滤空串（字符串分支是过滤了的），
        // 数组里的 "" 会一路留到 WorldEntry.keywords → matches() 里 contains("") 恒真。
        val out = when {
            e == null || e.isJsonNull -> emptyList()
            e.isJsonArray -> e.asJsonArray.mapNotNull { str(it) }
            else -> str(e)?.split(',', '，') ?: emptyList()
        }
        return out.map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * 自有数组格式的关键词。
     *
     * B6：原实现无条件 `stripRegexDelimiters`，会把字面关键词 `/api/v1` 改成 `api/v1`；
     * 更糟的是 `//x` 被剥成空串（substring(1,1)）。现在只在确实识别为内联正则时才剥。
     */
    private fun keywordsOf(entry: JsonObject, useRegex: Boolean = false): List<String> {
        val raw = strList(entry.get("keywords"))
        val stripped = if (useRegex || raw.any { looksLikeRegex(it) }) {
            raw.map { stripRegexDelimiters(it) }
        } else raw
        return stripped.map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * 判断关键词是否为正则形式："/pattern/" 或 "/pattern/flags"。
     *
     * P2：原判定「以 / 开头且内部还有 /」会把字面关键词 `/api/v1` 误判为正则并截断
     *（B6 注释点名要保护的例子实际未修）。真正的内联正则必须以收尾斜杠
     *（或斜杠 + 合法 flags）结束，中间的斜杠是模式内容：
     * `/api/v1` 尾部 "v1" 不是 flags → 字面关键词；`/foo/i`、`/\d+/` → 正则。
     */
    private fun looksLikeRegex(keyword: String): Boolean {
        if (keyword.length < 3 || !keyword.startsWith("/")) return false
        val lastSlash = keyword.lastIndexOf('/')
        if (lastSlash <= 0) return false
        val tail = keyword.substring(lastSlash + 1)
        return tail.isEmpty() || tail.all { it.lowercaseChar() in "imsuxd" }
    }

    /**
     * 去掉 /.../ 定界符与尾部 flags（如 "/pattern/i" → "pattern"）。
     * 大小写敏感性由 caseSensitive 字段控制，不依赖正则内联 flags。
     */
    private fun stripRegexDelimiters(keyword: String): String {
        if (!looksLikeRegex(keyword)) return keyword
        val lastSlash = keyword.lastIndexOf('/')
        val stripped = keyword.substring(1, lastSlash)
        // `//x` 之类剥完为空 → 保留原文，避免关键词被静默丢弃（B6）
        return stripped.ifBlank { keyword }
    }
}
