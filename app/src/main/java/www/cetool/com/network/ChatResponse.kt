package www.cetool.com.network

/**
 * OpenAI 兼容 API 的聊天响应体
 * 用于解析流式（SSE）和非流式响应
 */
data class ChatResponse(
    val id: String?,
    val `object`: String?,
    val created: Long?,
    val model: String?,
    val provider: String? = null,
    val choices: List<Choice>?,
    val error: ErrorDetail?
)

data class Choice(
    val index: Int,
    val message: MessageItem?,
    /** 流式响应中每个 chunk 的内容在 delta 里 */
    val delta: Delta?,
    val finish_reason: String?
)

/** 流式响应时每个 chunk 的内容片段 */
data class Delta(
    val role: String?,
    val content: String?,
    val reasoning_content: String? = null
)

data class ErrorDetail(
    val message: String?,
    val type: String?,
    val code: String?
)
