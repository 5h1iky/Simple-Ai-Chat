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
            // B26：跳过加载阶段产生的损坏哨兵条目，避免把错误信息注入提示词
            if (entry.name == BROKEN_ENTRY_NAME) continue

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

        // B31：分组在此前完全没实现 —— `groupName` 在界面上可见可填，
        // 但引擎与 ConversationManager 里 0 命中，用户会以为同组互斥生效。
        // 酒馆语义：同组条目至多注入一条（若已开运送 groupWeight，则按权重挑选）。
        return applyGroups(hits)
    }

    /**
     * B31：同组（groupName 非空）最多保留一条。
     * 组内按 groupWeight 降序、其次按 insertion_order（priority）升序挑选，
     * 忠实酒馆「权重高的更易被选中」的行为。
     */
    /**
     * B31：同组（groupName 非空且相同）最多保留一条命中的条目。
     *
     * 酒馆语义：组内互斥，选中谁由 groupWeight / insertion_order 决定。
     * 这里按 `groupWeight` 降序、同权重按 `priority` 升序挑选，其余丢弃。
     * 未分组条目不受影响，且**保持原始命中顺序**（避免分组挑选打乱注入次序）。
     */
    private fun applyGroups(hits: List<WorldEntry>): List<WorldEntry> {
        if (hits.size <= 1) return hits
        if (hits.none { it.groupName.isNotBlank() }) return hits

        // 先算出每个分组的胜者
        val bestOfGroup = LinkedHashMap<String, WorldEntry>()
        for (entry in hits) {
            val group = entry.groupName.trim()
            if (group.isBlank()) continue
            val current = bestOfGroup[group]
            bestOfGroup[group] = when {
                current == null -> entry
                entry.groupWeight > current.groupWeight -> entry
                entry.groupWeight < current.groupWeight -> current
                entry.priority < current.priority -> entry
                else -> current
            }
        }

        // 再按原始命中顺序输出：未分组的全留，分组的**只留胜者**。
        //
        // M2：这里绝不能再用「已发射组」集合做去重 —— 曾经写成
        // `emittedGroups.add(group) && bestOfGroup[group] === entry`，
        // 而 `add()` 对**落选条目**同样返回 true 并留下副作用（把组标记成已发射），
        // 于是后面真正的胜者被短路掉 → 该组一条都不注入（比不分组还糟）。
        // 胜者在 `hits` 里只出现一次（每个条目实例唯一），因此按身份过滤即可。
        return hits.filter { entry ->
            val group = entry.groupName.trim()
            group.isBlank() || bestOfGroup[group] === entry
        }
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
        // m4 / B2：outlet 条目从不自动注入（只由 {{outlet::名称}} 手动拉取），
        // buildInjection 会把它丢掉 —— 但它此前照常参与预算累加，
        // 一条大 outlet 条目会把真正要注入的条目挤掉。这里直接排除在预算之外。
        val injectable = entries.filter { it.position != "outlet" }

        val constants = injectable.filter { it.constantActive }.sortedBy { it.priority }
        val others = injectable.filter { !it.constantActive }.sortedBy { it.priority }
        val ordered = constants + others

        val result = mutableListOf<WorldEntry>()
        var used = 0
        for (entry in ordered) {
            val cost = estimateTokens(entry.content) + 30
            // B7：酒馆语义是「逐条尝试、装不下就跳过该条继续试后续」。
            // 原实现用 break，一旦遇到超预算的大条目就整体终止，
            // 后面所有能装下的小条目全被丢弃（默认预算 1500，一条 2000 token 就废掉整本书）。
            if (used + cost > maxTokens) continue
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
                // B2：酒馆 Outlet（position 7）从不自动注入，只由 {{outlet::名称}} 手动拉取
                "outlet" -> Unit
                else -> {
                    // B3：原实现没有 else 分支 —— 位置字符串不在白名单里的条目会
                    // 静默从提示词里消失且无任何提示（character_book 的 "before_char" 即受害者）。
                    appendLine(afterCharDefs, entry.content)
                }
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
