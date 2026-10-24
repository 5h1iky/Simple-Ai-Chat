package www.cetool.com.network

import com.google.gson.Gson
import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val zenModelOption: String = SettingsKeys.ZEN_MODEL_DEFAULT,
    // Zen 免费档需要 x-session-id（见 companion.sessionId）；自定义模式可传 null
    private val appContext: Context? = null
) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // D6：OkHttpClient 自带连接池与线程池，原本每个实例都新建 client、
    // 而调用方每发一条消息都会 `AiApiClient(...)`，等于不断丢弃连接池与线程。
    // 改成共享单例，超时差异用 newBuilder() 派生（会复用连接池与线程池）。
    private val client by lazy { SHARED_CLIENT }

    /**
     * P2（跨分片标签）：返回文本尾部「可能是某个思维链标签前缀」的长度（0 = 无）。
     * 这部分字符要缓存到下一片再判定，否则半截标签会以字面文本泄漏进正文，
     * 且被切断的开标签不翻转 inReasoning → 整段思维链按正文输出。
     * （与下方 reasoningTagRegex 的标签集合保持一致。）
     */
    private val reasoningTags = listOf(
        "<think>", "<thinking>", "<reason>", "<reasoning>", "<thought>", "<|begin_of_thought|>",
        " ...", "</thinking>", "</reason>", "</reasoning>", "</thought>", "<|end_of_thought|>"
    )

    private fun partialTagSuffix(text: String): Int {
        if (text.isEmpty()) return 0
        val lower = text.lowercase()
        val maxLen = reasoningTags.maxOf { it.length }
        for (k in 1 until maxLen) {
            if (k > lower.length) break
            val suffix = lower.takeLast(k)
            if (reasoningTags.any { it.length > k && it.lowercase().startsWith(suffix) }) return k
        }
        return 0
    }

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
        val temperature: Double?,
        /** 需要额外携带的请求头（如 Zen 免费档要求的 x-session-id） */
        val extraHeaders: Map<String, String> = emptyMap()
    )

    companion object {
        private const val KEY_SESSION_ID = "zen_session_id"

        /**
         * P2：Base URL 带 query/fragment 时字符串拼接会把追加路径拼进查询串/片段
         *（如 `https://host/v1?api-version=x` → `...?api-version=x/chat/completions`，
         * 请求仍打到 /v1 且 404）。用 HttpUrl 在**路径段**上追加，query/fragment 保持原位。
         */
        fun appendPath(baseUrl: String, path: String): String {
            val httpUrl = baseUrl.toHttpUrlOrNull()
                ?: return "${baseUrl.trimEnd('/')}/$path"
            return httpUrl.newBuilder()
                .addPathSegments(path.trim('/'))
                .build()
                .toString()
        }

        /** D6：进程内共享的 OkHttpClient（连接池 / 线程池复用） */
        private val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** A2：地址非法时返回的空 EventSource（无需 OkHttp 线程，也无需真实请求） */
        private val EMPTY_EVENT_SOURCE = object : EventSource {
            override fun request(): Request = Request.Builder().url("http://localhost/").build()
            override fun cancel() = Unit
        }

        @Volatile
        private var sessionIdCache: String? = null

        /**
         * Zen 免费档的会话占位头。
         *
         * 实测（2026-09）：zen 免费档要求请求携带非空的 `x-session-id`，否则返回
         * `MissingSessionID: OpenCode's free tier can only be used in OpenCode`。
         * 服务端只校验该头是否存在（随机 UUID、"12345" 均可通过，空串被拒），
         * 因此这里生成一次 UUID 并持久化。若提供商将来收紧校验，需要重新评估。
         */
        fun sessionId(context: Context): String {
            sessionIdCache?.let { return it }
            synchronized(AiApiClient::class.java) {
                sessionIdCache?.let { return it }
                val prefs = context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
                val existing = prefs.getString(KEY_SESSION_ID, null)
                val id = if (!existing.isNullOrBlank()) {
                    existing
                } else {
                    java.util.UUID.randomUUID().toString().also {
                        prefs.edit().putString(KEY_SESSION_ID, it).apply()
                    }
                }
                sessionIdCache = id
                return id
            }
        }

        /**
         * 拉取模型列表（GET /models），供免费网关的模型列表动态化使用。
         * 两个提供商的响应结构一致（OpenAI 风格 `{data:[{id,...}]}`），字段差异用可选字段兼容。
         */
        fun fetchModels(context: Context, providerMode: String): Result<List<ModelItem>> {
            return try {
                val url: String
                val headers: Map<String, String>
                when (providerMode) {
                    SettingsKeys.PROVIDER_OPEN_KILO -> {
                        url = SettingsKeys.KILO_BASE_URL
                        headers = emptyMap()   // OpenKilo 的 /models 无需认证
                    }
                    SettingsKeys.PROVIDER_OPEN_CODE_ZEN -> {
                        url = SettingsKeys.ZEN_BASE_URL
                        headers = mapOf(
                            "Authorization" to "Bearer ${SettingsKeys.ZEN_PUBLIC_KEY}",
                            "x-session-id" to sessionId(context)
                        )
                    }
                    else -> return Result.failure(IllegalArgumentException("该模式不支持模型列表"))
                }

                val requestBuilder = Request.Builder()
                    .url(appendPath(url, "models"))
                    .get()
                headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

                // D6：原来这里每次都 new 一个 client 再丢掉。改为从共享实例派生，
                // newBuilder() 会复用同一个连接池与线程池，只是覆盖超时设置。
                val listClient = SHARED_CLIENT.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                listClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                    }
                    val body = response.body?.string()
                        ?: return Result.failure(Exception("空响应"))
                    val parsed = Gson().fromJson(body, ModelListResponse::class.java)
                    Result.success(parsed?.data ?: emptyList())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

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
                // 模型 ID 为纯名称（如 nemotron-3-ultra-free），不加 opencode/ 前缀。
                // 免费档必须带 x-session-id，否则返回 MissingSessionID（见 companion.sessionId）。
                Endpoint(
                    url = SettingsKeys.ZEN_BASE_URL,
                    model = zenModelOption,
                    authHeader = "Bearer ${SettingsKeys.ZEN_PUBLIC_KEY}",
                    temperature = null,
                    extraHeaders = appContext?.let {
                        mapOf("x-session-id" to sessionId(it))
                    } ?: emptyMap()
                )
            }
            else -> {
                Endpoint(
                    url = config.apiUrl,
                    model = config.model,
                    // L2：apiKey 为空（Ollama/LM Studio 等免密钥端点）时不发 Authorization 头，
                    // 空令牌 `Bearer ` 会被部分服务端直接 401。
                    authHeader = config.apiKey.trim().takeIf { it.isNotEmpty() }?.let { "Bearer $it" },
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

        // A2：OkHttp 在 URL 形态非法时（如缺少 http(s):// scheme）会抛
        // IllegalArgumentException。这里在构建 request 之前拦住并通过 onError 上报，
        // 否则异常会穿透到 Compose 点击回调 → 主线程崩溃。
        val urlError = ApiConfig.validateUrl(endpoint.url)
        if (urlError != null) {
            // 同样要报告模型获取失败，否则顶栏会停在「正在获取模型…」（B12 同源问题）
            onModelInfo?.invoke(null, null)
            onError(urlError)
            return EMPTY_EVENT_SOURCE
        }

        val chatRequest = ChatRequest(
            model = endpoint.model,
            messages = messages,
            temperature = endpoint.temperature,
            reasoning_effort = thinkingLevel
        )

        val jsonBody = gson.toJson(chatRequest)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        // 确保 Base URL 无重复斜杠后拼接 /chat/completions
        val url = appendPath(endpoint.url, "chat/completions")

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
        if (endpoint.authHeader != null) {
            requestBuilder.addHeader("Authorization", endpoint.authHeader)
        }
        endpoint.extraHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val request = requestBuilder.post(requestBody).build()

        val factory = EventSources.createFactory(client)

        var inReasoning = false
        var tagBuffer = ""
        var modelReported = false
        // B13：`[DONE]` 分支、`finish_reason == "stop"` 分支、`onClosed` 回调都会走到这里，
        // 正常一轮至少触发两次 → onFinish 被调用多次 → save() 重复 + UI 多次全量刷新。
        // 用标志位保证一轮请求只落一次。
        var finished = false

        // Free Gateway: 流结束时若未收到过 model 字段，回调 (null, null) 表示获取失败
        fun finishStream() {
            if (finished) return
            finished = true
            // 流结束时残留的「半截标签」按原文输出，避免静默丢字
            // L7：残留内容按当前通道分发，inReasoning 时不应塞进正文
            if (tagBuffer.isNotEmpty()) {
                val rest = tagBuffer
                tagBuffer = ""
                if (inReasoning) onReasoning(rest) else onText(rest)
            }
            if (!modelReported) onModelInfo?.invoke(null, null)
            onFinish()
        }

        /**
         * B12：失败路径必须自己收尾 —— OkHttp 的 `onFailure` 是终止路径，
         * 之后**不会再回调 `onClosed`**，所以 `finishStream()` 永远不会被调用，
         * `onModelInfo(null, null)` 也就不触发 → 顶栏一直停在「模型：正在获取模型…」，
         * 用户只在气泡里看到错误，顶栏却像还在加载。
         *
         * 这里显式补上「模型信息获取失败」的回调，再上报错误（onError 由
         * ConversationManager 负责把 isStreaming 复位并保存）。
         */
        fun failStream(message: String) {
            if (finished) return
            finished = true
            // L7：同 finishStream，残留按当前通道分发
            if (tagBuffer.isNotEmpty()) {
                val rest = tagBuffer
                tagBuffer = ""
                if (inReasoning) onReasoning(rest) else onText(rest)
            }
            if (!modelReported) onModelInfo?.invoke(null, null)
            onError(message)
        }

        val eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource, id: String?, type: String?, data: String
            ) {
                if (finished) return
                if (type != null && type != "message") {
                    // P2：带 event: 头的错误帧（如 event: error）不能静默丢弃，
                    // 否则连接关闭后走 onClosed→finishStream，一轮被当作正常完成收尾。
                    if (type.equals("error", ignoreCase = true)) {
                        failStream(data.ifBlank { "服务端错误事件" })
                        eventSource.cancel()
                    }
                    return
                }
                if (data == "[DONE]") {
                    finishStream()
                    return
                }
                try {
                    val chunk = try {
                        gson.fromJson(data, ChatResponse::class.java)
                    } catch (e: Exception) {
                        // D7：分片 JSON 解析失败仅跳过该分片并记录，不影响后续
                        android.util.Log.w("AiApiClient", "SSE 分片解析失败: ${e.message}", e)
                        return
                    }
                    if (chunk == null) return

                    // Free Gateway: 回调实际使用的模型名/服务商
                    if (chunk.model != null) {
                        onModelInfo?.invoke(chunk.model, chunk.provider)
                        modelReported = true
                    }

                    val choice = chunk.choices?.firstOrNull()
                    if (choice == null) {
                        // P1：SSE 错误事件必须走 failStream（置 finished + 补报模型获取失败）并停掉流 ——
                        // 原实现直调 onError：顶栏卡「正在获取模型…」、后续分片继续追加到错误文本之后、
                        // onClosed 还会再触发 onFinish"成功收尾"一次。
                        if (chunk.error != null) {
                            failStream(chunk.error.message ?: "未知错误")
                            eventSource.cancel()
                        }
                        return
                    }

                    if (choice.delta?.reasoning_content != null) {
                        onReasoning(choice.delta.reasoning_content!!)
                    }

                    // P2：message.content 可能是 JsonNull/JsonArray —— asJsonPrimitive 对
                    // 非原始元素抛 IllegalStateException，会把整个 chunk（含 finish_reason）静默丢掉。
                    val content = choice.delta?.content
                        ?: choice.message?.content?.takeIf { it.isJsonPrimitive }?.asString
                        ?: ""
                    if (content.isEmpty()) {
                        if (choice.finish_reason == "stop") finishStream()
                        return
                    }

                    // P2：tagBuffer 必须真正发挥作用 —— 跨分片被切断的标签前缀先缓存，
                    // 与下一片拼接后再判定（原实现只赋空串，是从未生效的死代码）。
                    // M2：整段恰好是标签前缀（holdLen == length）时同样要缓存 ——
                    // 原来的 `holdLen < fullInput.length` 守卫会让落在分片边界上的
                    // 半截标签（如单独一片 "<"）以字面文本泄漏、状态永不翻转。
                    // 全缓存时 fullInput 为空，直接等下一片；流结束由 finishStream 冲刷。
                    var fullInput = tagBuffer + content
                    tagBuffer = ""
                    val holdLen = partialTagSuffix(fullInput)
                    if (holdLen > 0) {
                        tagBuffer = fullInput.takeLast(holdLen)
                        fullInput = fullInput.dropLast(holdLen)
                    }
                    if (fullInput.isEmpty()) return
                    // M1：一个分片里可能出现多个标签（如 "<think>ok</thinking>Done"），
                    // 原实现只处理 find() 的第一个，闭合标签与正文被整段吐进 reasoning。
                    // 改为遍历所有匹配、按序逐段分发（小型状态机）。
                    val tagMatches = reasoningTagRegex.findAll(fullInput).toList()
                    if (tagMatches.isEmpty()) {
                        if (inReasoning) onReasoning(fullInput) else onText(fullInput)
                    } else {
                        var pos = 0
                        for (tagMatch in tagMatches) {
                            val tagStart = tagMatch.range.first
                            val tagEnd = tagMatch.range.last + 1
                            if (tagStart > pos) {
                                val segment = fullInput.substring(pos, tagStart)
                                if (inReasoning) onReasoning(segment) else onText(segment)
                            }
                            // P2：闭合判定不能只看 "/" —— <|end_of_thought|> 不含 "/" 却是闭标签，
                            // 原实现把它当开标签 → 其后正文全部进 reasoning 折叠区。
                            val tag = tagMatch.value.lowercase()
                            val isClosing = tag.contains("/") || tag.startsWith("<|end")
                            inReasoning = !isClosing
                            pos = tagEnd
                        }
                        if (pos < fullInput.length) {
                            val tail = fullInput.substring(pos)
                            if (inReasoning) onReasoning(tail) else onText(tail)
                        }
                    }

                    if (choice.finish_reason == "stop") finishStream()
                } catch (e: Exception) {
                    // L8：解析已在上方单独兜住；走到这里是 onText/onReasoning 回调
                    // 自身抛错 —— 旧实现一并静默吞掉（与注释相反），分片丢失且无任何提示。
                    // 现在上报错误并停流，避免用户看到缺字而不知情。
                    android.util.Log.w("AiApiClient", "SSE 分片处理失败: ${e.message}", e)
                    failStream("处理响应时出错: ${e.message ?: e.javaClass.simpleName}")
                    eventSource.cancel()
                }
            }

            override fun onFailure(
                eventSource: EventSource, t: Throwable?, response: okhttp3.Response?
            ) {
                if (finished) return
                // P1：okhttp 在「读流中途失败 / cancel() / Content-Type 非法」时同样带着
                // **非空 response**（200）回调本方法。原实现见 response 非空就只报
                // "HTTP 200: OK"、把 t 整个丢弃 —— 断流/超时/取消全部显示成 HTTP 200。
                // 真实 HTTP 错误码优先报状态行，其余优先报 t 的真实原因。
                val errorMsg = when {
                    response != null && !response.isSuccessful ->
                        "HTTP ${response.code}: ${response.message}"
                    t?.message != null -> t!!.message!!
                    response != null -> "流意外中断（HTTP ${response.code}）"
                    else -> "未知网络错误"
                }
                failStream(errorMsg)
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
            val url = appendPath(endpoint.url, "chat/completions")

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
            if (endpoint.authHeader != null) {
                requestBuilder.addHeader("Authorization", endpoint.authHeader)
            }
            endpoint.extraHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
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
                // L3：content 可能是 JsonNull/JsonArray —— 裸 asJsonPrimitive 会抛异常
                // 绕过下面的 delta 兜底；先判型再取值。
                val content = parsed?.choices?.firstOrNull()
                    ?.message?.content?.takeIf { it.isJsonPrimitive }?.asString
                if (content.isNullOrBlank()) {
                    // 兼容返回在 delta 的情况（部分服务商非流式也用 delta）
                    val delta = parsed?.choices?.firstOrNull()?.delta?.content
                    if (!delta.isNullOrBlank()) {
                        return Result.success(delta)
                    }
                    // L3：响应体是 {"error":{...}} 时优先透传服务端真实错误
                    val serverError = parsed?.error?.message
                    return Result.failure(Exception(serverError ?: "响应中没有内容"))
                }
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
