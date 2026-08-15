package www.cetool.com.manager

import com.google.gson.JsonPrimitive
import www.cetool.com.model.Message
import www.cetool.com.model.WorldEntry
import www.cetool.com.model.WorldInfo
import www.cetool.com.network.MessageItem

object WorldInfoEngine {

    private const val DEFAULT_BUDGET = 1500

    fun scan(
        messages: List<Message>,
        worldInfo: WorldInfo,
        promptItems: List<MessageItem>
    ): List<WorldEntry> {
        if (!worldInfo.enabled) return emptyList()

        val hits = mutableListOf<WorldEntry>()

        for (entry in worldInfo.entries) {
            if (!entry.enabled) continue

            if (entry.constantActive) {
                hits.add(entry)
                continue
            }

            val searchSpace = buildSearchSpace(messages, promptItems, entry)
            for (text in searchSpace) {
                if (entry.matches(text)) {
                    hits.add(entry)
                    break
                }
            }
        }

        return hits
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

    fun sortAndBudget(
        entries: List<WorldEntry>,
        maxChars: Int = DEFAULT_BUDGET
    ): List<WorldEntry> {
        val sorted = entries.sortedByDescending { it.priority }
        val result = mutableListOf<WorldEntry>()
        var used = 0
        for (entry in sorted) {
            val cost = entry.content.length + 50
            if (used + cost > maxChars) break
            result.add(entry)
            used += cost
        }
        return result
    }

    data class InjectionResult(
        val prefix: String,
        val suffix: String
    )

    fun buildInjection(entries: List<WorldEntry>, existingSystemPrompt: String): InjectionResult {
        val prefixSb = StringBuilder()
        val suffixSb = StringBuilder()
        val sorted = entries.sortedBy { it.injectDepth }

        for (entry in sorted) {
            if (entry.content.isBlank()) continue
            when (entry.position) {
                "after_system_prompt" -> {
                    if (prefixSb.isNotEmpty()) prefixSb.append("\n")
                    prefixSb.append(entry.content)
                }
                "at_depth" -> {
                    if (suffixSb.isNotEmpty()) suffixSb.append("\n")
                    suffixSb.append(entry.content)
                }
            }
        }

        val prefix = if (prefixSb.isNotEmpty()) "\n[World Info]\n$prefixSb" else ""
        val suffix = if (suffixSb.isNotEmpty()) "\n\n$suffixSb" else ""

        return InjectionResult(prefix, suffix)
    }
}
