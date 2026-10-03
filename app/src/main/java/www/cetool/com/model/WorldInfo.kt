package www.cetool.com.model

data class WorldInfo(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    var name: String = "",
    var description: String = "",
    var enabled: Boolean = true,
    var entries: MutableList<WorldEntry> = mutableListOf()
)

data class WorldEntry(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    var name: String = "",
    var enabled: Boolean = true,
    var priority: Int = 1000,
    var position: String = "after_system_prompt",
    var content: String = "",
    var injectDepth: Int = 4,
    var role: String? = null,
    var keywords: MutableList<String> = mutableListOf(),
    var useRegex: Boolean = false,
    var caseSensitive: Boolean = false,
    var scanDepth: Int = 4,
    var constantActive: Boolean = false,

    // ─── 酒馆(SillyTavern)对标字段（导入保留，引擎按实现进度消费） ───
    /** 酒馆原始注入位置编号（0=角色定义前 1=角色定义后 2=示例前 3=示例后 4=@深度 5/6=AN 7=Outlet），-1 表示非酒馆来源 */
    var stPosition: Int = -1,
    /** Match whole words（酒馆默认开；中文建议关） */
    var wholeWords: Boolean = true,
    /** 次级关键词（Optional Filter） */
    var secondaryKeys: MutableList<String> = mutableListOf(),
    /** 是否启用次级关键词过滤（酒馆 selective） */
    var selective: Boolean = false,
    /** 次级关键词逻辑：0=AND ANY 1=AND ALL 2=NOT ANY 3=NOT ALL */
    var selectiveLogic: Int = 0,
    /** 触发概率 %（100=每次触发） */
    var probability: Int = 100,
    /** 是否启用概率过滤 */
    var useProbability: Boolean = false,
    /** 包含组（同组互斥） */
    var groupName: String = "",
    /** 组权重 */
    var groupWeight: Int = 100,
    var excludeRecursion: Boolean = false,
    var preventRecursion: Boolean = false,
    var delayUntilRecursion: Boolean = false,
    var automationId: String = "",
    var score: Int = 0,
    var sticky: Int = 0,
    var cooldown: Int = 0,
    var delay: Int = 0,
    var displayIndex: Int = 0,
    /** 酒馆 memo/备注（常作条目标题） */
    var comment: String = "",
    var vectorized: Boolean = false,
    /** 触发器类型（Normal/Continue/Swipe/Regenerate/...） */
    var triggers: MutableList<String> = mutableListOf(),
    /** @D 深度注入时的角色消息类型（system/user/assistant） */
    var injectRole: String? = null
) {
    /**
     * 关键词是否命中文本。
     *
     * B6：空关键词必须过滤掉 —— `compareText.contains("")` 恒为 true，
     * 会让该条目匹配一切消息。老 better-in-old-data：
     * `stripRegexDelimiters` 把 `//x` 剥成空串，正好触发这条。
     */
    private fun List<String>.usableKeywords(): List<String> =
        map { it.trim() }.filter { it.isNotBlank() }

    fun matches(text: String): Boolean {
        if (!enabled) return false
        return keywords.usableKeywords().any { rawKw ->
            matchOne(normalizeKeyword(rawKw), text)
        }
    }

    /** 次级关键词是否命中（与主键同规则） */
    fun matchesSecondary(text: String): Boolean {
        if (secondaryKeys.isEmpty()) return false
        return secondaryKeys.usableKeywords().any { rawKw ->
            matchOne(normalizeKeyword(rawKw), text)
        }
    }

    private fun normalizeKeyword(raw: String): String =
        if (caseSensitive) raw else raw.lowercase()

    /**
     * 单条关键词匹配：正则 / 整词边界 / 子串，三种模式共用一条实现。
     *
     * 只接收 (关键词, 原文) 两个参数，大小写处理在内部完成 ——
     * 早先的版本额外暴露了一个「已归一化文本」参数，导致两处调用点参数写反：
     * `matchesSecondary` 把原文当成了归一化文本，于是 caseSensitive=false 时
     * 整词/子串分支实际区分大小写（"Magic Missile" 匹配不到 "magic"）。
     * 去掉该参数即可从结构上杜绝这类错误。
     *
     * @param keyword 已按 caseSensitive 归一化
     */
    private fun matchOne(keyword: String, text: String): Boolean {
        val compareText = if (caseSensitive) text else text.lowercase()

        if (useRegex) {
            return try {
                val regex = if (caseSensitive) Regex(keyword) else Regex(keyword, RegexOption.IGNORE_CASE)
                regex.containsMatchIn(text)
            } catch (_: Exception) { false }
        }
        if (wholeWords && keyword.isNotBlank() && !keyword.contains(' ') && !containsCjk(keyword)) {
            // 酒馆 Match whole words（默认开）：单词边界匹配；
            // 含中文的关键词不做边界匹配（中文无空格分词，酒馆文档亦建议中文关闭）
            val escaped = Regex.escape(keyword)
            return Regex("(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])").containsMatchIn(compareText)
        }
        return compareText.contains(keyword)
    }

    private fun containsCjk(s: String): Boolean = s.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
}
