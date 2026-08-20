package www.cetool.com.manager

import com.google.gson.JsonPrimitive
import www.cetool.com.model.Message
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo
import www.cetool.com.network.MessageItem
import kotlin.random.Random

/**
 * 世界书引擎（对标 SillyTavern World Info）：
 * - 匹配：关键词/regex/whole words（中文降级）/次级关键词选择性过滤/角色过滤/常驻/概率
 * - 预算：token 估算（中英文混合启发式），常驻条目优先，order 升序（大 order 更靠后，酒馆语义）
 * - 注入：位置 0 角色定义前 / 1 角色定义后 / 2 示例前 / 3 示例后 / @D 深度注入
 */
object WorldInfoEngine {

    /** 默认预算（tokens），可在设置中调整 */
    const val DEFAULT_BUDGET = 1500

    // 酒馆 selectiveLogic：0=AND ANY 1=AND ALL 2=NOT ANY 3=NOT ALL
    private const val LOGIC_AND_ANY = 0
    private const val LOGIC_AND_ALL = 1
    private const val LOGIC_NOT_ANY = 2
    private const val LOGIC_NOT_ALL = 3

    fun scan(
        messages: List<Message>,
        worldInfo: WorldInfo,
        promptItems: List<MessageItem>
    ): List<WorldEntry> {
        if (!worldInfo.enabled) return emptyList()

        val hits = mutableListOf<WorldEntry>()

        for (entry in worldInfo.entries) {
            if (!entry.enabled) continue

            // 常驻条目：无条件命中（仍受概率影响）
            if (entry.constantActive) {
                if (passesProbability(entry)) hits.add(entry)
                continue
            }

            val searchSpace = buildSearchSpace(messages, promptItems, entry)
            for (text in searchSpace) {
                if (entry.matches(text) && passesSelective(entry, text) && passesProbability(entry)) {
                    hits.add(entry)
                    break
                }
            }
        }

        return hits
    }

    /** 选择性（次级关键词）过滤：只在 selective 启用时生效 */
    private fun passesSelective(entry: WorldEntry, primaryHitText: String): Boolean {
        if (!entry.selective) return true
        if (entry.secondaryKeys.isEmpty()) return true
        val anyHit = entry.matchesSecondary(primaryHitText)
        return when (entry.selectiveLogic) {
            LOGIC_AND_ANY -> anyHit
            LOGIC_AND_ALL -> entry.secondaryKeys.all { secondaryMatches(entry, it, primaryHitText) }
            LOGIC_NOT_ANY -> !anyHit
            LOGIC_NOT_ALL -> entry.secondaryKeys.any { !secondaryMatches(entry, it, primaryHitText) }
            else -> anyHit
        }
    }

    private fun secondaryMatches(entry: WorldEntry, keyword: String, text: String): Boolean {
        val compareText = if (entry.caseSensitive) text else text.lowercase()
        val kw = if (entry.caseSensitive) keyword else keyword.lowercase()
        return if (entry.useRegex) {
            try {
                val regex = if (entry.caseSensitive) Regex(kw) else Regex(kw, RegexOption.IGNORE_CASE)
                regex.containsMatchIn(text)
            } catch (_: Exception) { false }
        } else {
            compareText.contains(kw)
        }
    }

    /** 概率过滤（酒馆 Probability / Trigger %） */
    private fun passesProbability(entry: WorldEntry): Boolean {
        if (!entry.useProbability) return true
        if (entry.probability >= 100) return true
        if (entry.probability <= 0) return false
        return Random.nextInt(100) < entry.probability
    }

    private fun buildSearchSpace(
        messages: List<Message>,
        promptItems: List<MessageItem>,
        entry: WorldEntry
    ): List<String> {
        val results = mutableListOf<String>()
        val maxScan = entry.scanDepth.coerceAtLeast(1)

        val msgStart = (messages.size - maxScan).coerceAtLeast(0)
        for (i in msgStart until messages.size) {
            val msg = messages[i]
            if (entry.role != null && msg.role != entry.role) continue
            results.add(msg.content)
        }

        val promptStart = (promptItems.size - maxScan).coerceAtLeast(0)
        for (i in promptStart until promptItems.size) {
            val content = promptItems[i].content
            val text = if (content is JsonPrimitive && content.isString) content.asString else content.toString()
            results.add(text)
        }

        return results
    }

    /**
     * 排序 + token 预算（酒馆语义）：
     * 常驻条目优先插入，其余按 order（priority）升序——order 大者更靠近上下文末尾、影响更大。
     */
    fun sortAndBudget(
        entries: List<WorldEntry>,
        maxTokens: Int = DEFAULT_BUDGET
    ): List<WorldEntry> {
        val constants = entries.filter { it.constantActive }.sortedBy { it.priority }
        val others = entries.filter { !it.constantActive }.sortedBy { it.priority }
        val ordered = constants + others

        val result = mutableListOf<WorldEntry>()
        var used = 0
        for (entry in ordered) {
            val cost = estimateTokens(entry.content) + 30
            if (used + cost > maxTokens) break
            result.add(entry)
            used += cost
        }
        return result
    }

    /** 中英文混合 token 估算：CJK 每字 ~1 token，其他每 4 字符 ~1 token */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0x3040..0x30FF) {
                cjk++
            } else {
                other++
            }
        }
        return cjk + (other + 3) / 4
    }

    data class InjectionResult(
        /** 位置 0：角色定义之前 */
        val beforeCharDefs: String,
        /** 位置 1：角色定义之后（原 after_system_prompt） */
        val afterCharDefs: String,
        /** 位置 2：示例对话之前 */
        val beforeExamples: String,
        /** 位置 3：示例对话之后 */
        val afterExamples: String,
        /** @D 深度注入条目（按 injectDepth 插入消息列表） */
        val atDepth: List<WorldEntry>
    )

    fun buildInjection(entries: List<WorldEntry>): InjectionResult {
        val beforeCharDefs = StringBuilder()
        val afterCharDefs = StringBuilder()
        val beforeExamples = StringBuilder()
        val afterExamples = StringBuilder()
        val atDepth = mutableListOf<WorldEntry>()

        for (entry in entries) {
            if (entry.content.isBlank()) continue
            when (entry.position) {
                "before_char_defs" -> appendLine(beforeCharDefs, entry.content)
                "after_system_prompt", "after_char_defs" -> appendLine(afterCharDefs, entry.content)
                "before_example_messages" -> appendLine(beforeExamples, entry.content)
                "after_example_messages" -> appendLine(afterExamples, entry.content)
                "at_depth" -> atDepth.add(entry)
            }
        }

        return InjectionResult(
            beforeCharDefs = wrapSection(beforeCharDefs),
            afterCharDefs = wrapSection(afterCharDefs),
            beforeExamples = wrapSection(beforeExamples),
            afterExamples = wrapSection(afterExamples),
            atDepth = atDepth.sortedBy { it.injectDepth }
        )
    }

    private fun appendLine(sb: StringBuilder, content: String) {
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append(content)
    }

    private fun wrapSection(sb: StringBuilder): String {
        if (sb.isEmpty()) return ""
        return "\n[World Info]\n$sb"
    }
}
