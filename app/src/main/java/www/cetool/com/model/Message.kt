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
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        const val ATTACH_TYPE_TEXT = "text"
        const val ATTACH_TYPE_IMAGE = "image"
    }
}
