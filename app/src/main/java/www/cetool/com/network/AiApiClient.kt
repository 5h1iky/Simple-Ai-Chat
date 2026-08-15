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

    fun sendChatStream(
        messages: List<MessageItem>,
        onText: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onModelInfo: ((model: String?, provider: String?) -> Unit)? = null,
        onFinish: () -> Unit,
        onError: (String) -> Unit
    ): EventSource {
        // Free Gateway Integration: 按预设模式解析请求地址 / 模型 / 认证头 / 可选字段
        val resolvedUrl: String
        val resolvedModel: String
        val authHeader: String?
        val requestTemperature: Double?
        val requestThinkingLevel: String?
        when (providerMode) {
            SettingsKeys.PROVIDER_OPEN_KILO -> {
                // Free Gateway: OpenKilo 免费路由：无密钥，不发送 apiKey / Authorization 头；
                // 不发送 temperature，但正常透传 reasoning_effort（思考模式）
                resolvedUrl = SettingsKeys.KILO_BASE_URL
                resolvedModel = resolveKiloModel(kiloModelOption)
                authHeader = null
                requestTemperature = null
                requestThinkingLevel = thinkingLevel
            }
            SettingsKeys.PROVIDER_OPEN_CODE_ZEN -> {
                // Free Gateway: OpenCode Zen：固定 Base URL + 强制 Authorization: Bearer public；
                // 模型 ID 为纯名称（如 deepseek-v4-flash-free），不加 opencode/ 前缀；
                // 不发送 temperature，但正常透传 reasoning_effort（思考模式）
                resolvedUrl = SettingsKeys.ZEN_BASE_URL
                resolvedModel = zenModelOption
                authHeader = "Bearer ${SettingsKeys.ZEN_PUBLIC_KEY}"
                requestTemperature = null
                requestThinkingLevel = thinkingLevel
            }
            else -> {
                resolvedUrl = config.apiUrl
                resolvedModel = config.model
                authHeader = "Bearer ${config.apiKey}"
                requestTemperature = 0.7
                requestThinkingLevel = thinkingLevel
            }
        }

        val chatRequest = ChatRequest(
            model = resolvedModel,
            messages = messages,
            temperature = requestTemperature,
            reasoning_effort = requestThinkingLevel
        )

        val jsonBody = gson.toJson(chatRequest)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        // 确保 Base URL 无重复斜杠后拼接 /chat/completions
        val url = "${resolvedUrl.trimEnd('/')}/chat/completions"

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
        if (authHeader != null) {
            requestBuilder.addHeader("Authorization", authHeader)
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
}
