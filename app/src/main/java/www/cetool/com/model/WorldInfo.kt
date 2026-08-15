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
    var constantActive: Boolean = false
) {
    fun matches(text: String): Boolean {
        if (!enabled) return false
        val compareText = if (caseSensitive) text else text.lowercase()
        return keywords.any { kw ->
            val keyword = if (caseSensitive) kw else kw.lowercase()
            if (useRegex) {
                try {
                    val regex = if (caseSensitive) Regex(keyword) else Regex(keyword, RegexOption.IGNORE_CASE)
                    regex.containsMatchIn(text)
                } catch (_: Exception) { false }
            } else {
                compareText.contains(keyword)
            }
        }
    }
}
