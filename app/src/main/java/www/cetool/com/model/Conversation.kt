package www.cetool.com.model

import java.util.UUID

/**
 * 对话数据模型
 * 每个对话独立管理消息列表和流式状态
 */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String = DEFAULT_TITLE,
    val messages: MutableList<Message> = mutableListOf(),
    var systemPrompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var isStreaming: Boolean = false,
    var characterId: String? = null,
    var worldInfoId: String? = null
) {
    companion object {
        const val DEFAULT_TITLE = "新对话"
        const val MAX_HISTORY = 20

        /**
         * 从第一条用户消息生成对话标题
         */
        fun generateTitle(messages: List<Message>): String {
            val firstUser = messages.firstOrNull { it.role == Message.ROLE_USER }
            if (firstUser != null) {
                val text = firstUser.content.trim()
                return if (text.length <= 20) text else text.take(20) + "…"
            }
            return DEFAULT_TITLE
        }

        /**
         * 替换系统提示词中的变量占位符
         * 支持：{{cur_date}} {{cur_time}} {{cur_datetime}}
         */
        fun resolveSystemPrompt(template: String): String {
            val now = System.currentTimeMillis()
            val sdfDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val sdfTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            val sdfDatetime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

            return template
                .replace("{{cur_date}}", sdfDate.format(now))
                .replace("{{cur_time}}", sdfTime.format(now))
                .replace("{{cur_datetime}}", sdfDatetime.format(now))
        }
    }
}
