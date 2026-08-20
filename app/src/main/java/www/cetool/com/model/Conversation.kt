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
    /** 兼容旧数据的单本世界书字段（新代码统一用 worldInfoIds） */
    var worldInfoId: String? = null,
    /** 绑定的世界书 id 列表：支持同时启用多本世界书 */
    var worldInfoIds: List<String> = emptyList(),
    /** 记忆已封存：会话锁定，不可继续发送（对应 MuseAI 的 isSessionArchived） */
    var isArchived: Boolean = false,
    /** 文字冒险（跑团）：绑定的多个角色卡 id（非空即冒险模式） */
    var adventureRoleIds: List<String> = emptyList()
) {
    /** 当前绑定的世界书 id 列表（worldInfoIds 优先，兼容旧数据 worldInfoId） */
    fun boundWorldIds(): List<String> {
        val ids = worldInfoIds.filter { it.isNotBlank() }
        return if (ids.isNotEmpty()) ids
        else listOfNotNull(worldInfoId?.takeIf { it.isNotBlank() })
    }

    /** 统一写入绑定世界书：同步维护多本列表与兼容字段 */
    fun setBoundWorlds(ids: List<String>) {
        worldInfoIds = ids.distinct().filter { it.isNotBlank() }
        worldInfoId = worldInfoIds.firstOrNull()
    }

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
