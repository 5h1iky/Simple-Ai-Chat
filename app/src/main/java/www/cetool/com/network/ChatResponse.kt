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

/**
 * GET /models 响应（OpenAI 风格）。
 *
 * 两个免费网关的字段有差异，用可选字段兼容：
 * - OpenKilo：`{data:[{id, name, isFree, ...}]}`（isFree 为可靠的免费标记）
 * - OpenCode Zen：`{object:"list", data:[{id, object, created, owned_by}]}`（无 isFree，按 id 含 "free" 判定）
 */
data class ModelListResponse(
    val data: List<ModelItem>?
)

data class ModelItem(
    val id: String = "",
    val name: String? = null,
    val isFree: Boolean? = null
)
