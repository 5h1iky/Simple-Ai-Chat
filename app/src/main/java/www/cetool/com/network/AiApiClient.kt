package www.cetool.com.network

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import www.cetool.com.SettingsKeys
import www.cetool.com.model.ApiConfig
import java.util.concurrent.TimeUnit

class AiApiClient(
    private val config: ApiConfig,
    private val thinkingLevel: String? = null,
    // Free Gateway: OpenKilo / OpenCode Zen 预设服务商模式
    private val providerMode: String = SettingsKeys.PROVIDER_CUSTOM,
    // Free Gateway: OpenKilo 模型选项（auto 等，后续扩展）
    private val kiloModelOption: String = SettingsKeys.KILO_MODEL,
    // Free Gateway: OpenCode Zen 模型 ID（纯名称，不加 opencode/ 前缀）
    private val zenModelOption: String = SettingsKeys.ZEN_MODEL_DEFAULT
) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val reasoningTagRegex = Regex(
        "(<think>|<thinking>|<reason>|<reasoning>|<thought>|<\\|begin_of_thought\\|>|" +
        "</think>|</thinking>|</reason>|</reasoning>|</thought>|<\\|end_of_thought\\|>)",
        RegexOption.IGNORE_CASE
    )

    // Free Gateway: OpenKilo 模型选项 → 实际模型名映射（新版本直接存模型名，仅兼容旧版 "auto"）
    private fun resolveKiloModel(option: String): String {
        return if (option == SettingsKeys.KILO_MODEL_AUTO) SettingsKeys.KILO_MODEL else option
    }

    /** 解析后的请求端点信息（流式/非流式共用） */
    data class Endpoint(
        val url: String,
        val model: String,
        val authHeader: String?,
        val temperature: Double?
    )

    /** 按预设模式解析请求地址 / 模型 / 认证头 / temperature */
    private fun resolveEndpoint(): Endpoint {
        return when (providerMode) {
            SettingsKeys.PROVIDER_OPEN_KILO -> {
                // Free Gateway: OpenKilo 免费路由：无密钥，不发送 apiKey / Authorization 头；
                // 不发送 temperature，但正常透传 reasoning_effort（思考模式）
                Endpoint(
                    url = SettingsKeys.KILO_BASE_URL,
                    model = resolveKiloModel(kiloModelOption),
                    authHeader = null,
                    temperature = null
                )
            }
            SettingsKeys.PROVIDER_OPEN_CODE_ZEN -> {
                // Free Gateway: OpenCode Zen：固定 Base URL + 强制 Authorization: Bearer public；
                // 模型 ID 为纯名称（如 deepseek-v4-flash-free），不加 opencode/ 前缀
                Endpoint(
                    url = SettingsKeys.ZEN_BASE_URL,
                    model = zenModelOption,
                    authHeader = "Bearer ${SettingsKeys.ZEN_PUBLIC_KEY}",
                    temperature = null
                )
            }
            else -> {
                Endpoint(
                    url = config.apiUrl,
                    model = config.model,
                    authHeader = "Bearer ${config.apiKey}",
                    temperature = 0.7
                )
            }
        }
    }

    fun sendChatStream(
        messages: List<MessageItem>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onModelInfo: ((model: String?, provider: String?) -> Unit)? = null,
        onFinish: () -> Unit,
        onError: (String) -> Unit
    ): EventSource {
        val endpoint = resolveEndpoint()

        val chatRequest = ChatRequest(
            model = endpoint.model,
            messages = messages,
            temperature = endpoint.temperature,
            reasoning_effort = thinkingLevel
        )

        val jsonBody = gson.toJson(chatRequest)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        // 确保 Base URL 无重复斜杠后拼接 /chat/completions
        val url = "${endpoint.url.trimEnd('/')}/chat/completions"

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
        if (endpoint.authHeader != null) {
            requestBuilder.addHeader("Authorization", endpoint.authHeader)
        }
        val request = requestBuilder.post(requestBody).build()

        val factory = EventSources.createFactory(client)

        var inReasoning = false
        var tagBuffer = ""
        var modelReported = false

        // Free Gateway: 流结束时若未收到过 model 字段，回调 (null, null) 表示获取失败
        fun finishStream() {
            if (!modelReported) onModelInfo?.invoke(null, null)
            onFinish()
        }

        val eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource, id: String?, type: String?, data: String
            ) {
                if (type != null && type != "message") return
                if (data == "[DONE]") {
                    finishStream()
                    return
                }
                try {
                    val chunk = gson.fromJson(data, ChatResponse::class.java)
                    if (chunk == null) return

                    // Free Gateway: 回调实际使用的模型名/服务商
                    if (chunk.model != null) {
                        onModelInfo?.invoke(chunk.model, chunk.provider)
                        modelReported = true
                    }

                    val choice = chunk.choices?.firstOrNull()
                    if (choice == null) { if (chunk.error != null) onError(chunk.error.message ?: "未知错误"); return }

                    if (choice.delta?.reasoning_content != null) {
                        onReasoning(choice.delta.reasoning_content!!)
                    }

                    val content = choice.delta?.content ?: choice.message?.content?.asJsonPrimitive?.asString ?: ""
                    if (content.isEmpty()) {
                        if (choice.finish_reason == "stop") finishStream()
                        return
                    }

                    val fullInput = tagBuffer + content
                    tagBuffer = ""
                    val tagMatch = reasoningTagRegex.find(fullInput)
                    if (tagMatch != null) {
                        val tag = tagMatch.value.lowercase()
                        val tagStart = tagMatch.range.first
                        val tagEnd = tagMatch.range.last + 1

                        val isClosing = tag.contains("/")
                        if (tagStart > 0) {
                            val beforeTag = fullInput.substring(0, tagStart)
                            if (inReasoning) onReasoning(beforeTag) else onText(beforeTag)
                        }
                        if (isClosing) {
                            inReasoning = false
                        } else {
                            inReasoning = true
                        }
                        val afterTag = fullInput.substring(tagEnd)
                        if (afterTag.isNotEmpty()) {
                            if (inReasoning) onReasoning(afterTag) else onText(afterTag)
                        }
                    } else {
                        if (inReasoning) onReasoning(fullInput) else onText(fullInput)
                    }

                    if (choice.finish_reason == "stop") finishStream()
                } catch (_: Exception) {}
            }

            override fun onFailure(
                eventSource: EventSource, t: Throwable?, response: okhttp3.Response?
            ) {
                val errorMsg = if (response != null) {
                    "HTTP ${response.code}: ${response.message}"
                } else {
                    t?.message ?: "未知网络错误"
                }
                onError(errorMsg)
            }

            override fun onClosed(eventSource: EventSource) {
                finishStream()
            }
        })
        return eventSource
    }

    /**
     * 非流式 JSON 请求（记忆封存 / 提取类功能用）。
     * @return Result(content)：成功时返回模型输出的原始文本（调用方自行解析 JSON）
     */
    fun requestJson(
        messages: List<MessageItem>,
        temperature: Double = 0.0,
        maxOutputTokens: Int = 4096,
        timeoutSeconds: Long = 120
    ): Result<String> {
        return try {
            val endpoint = resolveEndpoint()

            val chatRequest = ChatRequest(
                model = endpoint.model,
                messages = messages,
                temperature = if (endpoint.temperature != null) temperature else null,
                stream = false,
                reasoning_effort = null,
                max_tokens = maxOutputTokens
            )

            val jsonBody = gson.toJson(chatRequest)
            val requestBody = jsonBody.toRequestBody(jsonMediaType)
            val url = "${endpoint.url.trimEnd('/')}/chat/completions"

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
            if (endpoint.authHeader != null) {
                requestBuilder.addHeader("Authorization", endpoint.authHeader)
            }
            val request = requestBuilder.post(requestBody).build()

            val httpClient = client.newBuilder()
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val body = response.body?.string() ?: return Result.failure(Exception("空响应"))
                val parsed = gson.fromJson(body, ChatResponse::class.java)
                val content = parsed.choices?.firstOrNull()
                    ?.message?.content?.asJsonPrimitive?.asString
                if (content.isNullOrBlank()) {
                    // 兼容返回在 delta 的情况（部分服务商非流式也用 delta）
                    val delta = parsed.choices?.firstOrNull()?.delta?.content
                    if (!delta.isNullOrBlank()) {
                        return Result.success(delta)
                    }
                    return Result.failure(Exception("响应中没有内容"))
                }
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
