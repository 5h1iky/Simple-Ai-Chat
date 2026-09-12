package www.cetool.com.model

data class Message(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val reasoningContent: String = "",
    val attachmentType: String? = null,
    val attachmentData: String? = null,
    val attachmentName: String? = null
) {
    /**
     * 用户实际输入的文字（已剥离旧版附件标记）。
     *
     * 旧实现把文本附件全文直接拼进正文：
     * `"[文件: 名]\n<文件全文>\n\n<用户输入>"`。
     * 新实现把文件作为独立 file 内容块发送，因此凡是消费正文的地方
     * （发送给模型的 API 路径、界面渲染）都必须先用本方法清洗，
     * 否则文件全文会被发送两次（正文一次 + file 块一次）。
     */
    fun displayText(): String {
        if (attachmentType != ATTACH_TYPE_TEXT || attachmentData == null) return content
        return stripLegacyAttachmentPrefix(content)
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        const val ATTACH_TYPE_TEXT = "text"
        const val ATTACH_TYPE_IMAGE = "image"

        /** 旧版附件标记前缀，形如 `[文件: 名]` 后跟换行 */
        private const val LEGACY_PREFIX = "[文件: "

        /**
         * 剥离旧版 `"[文件: 名]\n"` 头部标记，返回其后的正文。
         * 非旧版格式（不以该标记开头 / 没有换行结束）时原样返回，避免误伤正常消息。
         */
        fun stripLegacyAttachmentPrefix(content: String): String {
            if (!content.startsWith(LEGACY_PREFIX)) return content
            val headerEnd = content.indexOf("]\n")
            if (headerEnd < 0) return content
            return content.substring(headerEnd + 2)
        }
    }
}
