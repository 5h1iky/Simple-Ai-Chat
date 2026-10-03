package www.cetool.com.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * B20：Web 搜索客户端。
 *
 * 审计确认 `KEY_WEB_SEARCH_URL` / `KEY_WEB_SEARCH_ENABLED` 此前**只被读写和用来显示菜单文案**，
 * 全项目没有任何一处发起搜索请求，也没有把结果拼进提示词的代码 —— 开关点了只改变自己和 prefs。
 *
 * 这里接上真正的一环：开关打开且配置了 URL 时，用用户**最后一条输入**作为 query 去问一次，
 * 把结果作为系统消息注入上下文（见 ConversationManager 的 onSearch 钩子）。
 *
 * 协议假设：用户配置的是一个 OpenAI 兼容的搜索/问答端点（POST chat/completions），
 * 也可以是任何返回下述任一种形状的端点：
 * - OpenAI 风格：`choices[0].message.content`
 * - 简化风格：`{ "results": "..." }` / `{ "result": "..." }` / `{ "answer": "..." }` / `{ "text": "..." }`
 */
object WebSearchClient {

    private const val TAG = "WebSearchClient"
    private const val MAX_RESULT_CHARS = 4000

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8"

    private val client: OkHttpClient by lazy {
        // D6：避免每次请求新建 OkHttpClient（连接池/线程池会被白白丢弃）
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 执行一次搜索。失败不抛异常，返回 null（由调用方决定是否忽略）。
     *
     * @param endpoint 用户配置的地址（不含路径时按 chat/completions 处理）
     * @param model    可选模型名
     * @param query    搜索词（用户的最后一条输入）
     */
    fun search(
        endpoint: String,
        model: String?,
        query: String
    ): Result<String> {
        return try {
            val trimmed = endpoint.trim()
            val err = www.cetool.com.model.ApiConfig.validateUrl(trimmed)
                ?.let { return Result.failure(IllegalArgumentException(it)) }

            val body = JsonObject().apply {
                if (!model.isNullOrBlank()) addProperty("model", model)
                addProperty("stream", false)
                val msgs = com.google.gson.JsonArray()
                msgs.add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", query)
                })
                add("messages", msgs)
                addProperty("query", query)
                addProperty("q", query)
            }

            val url = if (trimmed.contains("/chat/completions")) trimmed
            else "${trimmed.trimEnd('/')}/chat/completions"

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(gson.toJson(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
                val raw = response.body?.string()
                    ?: return Result.failure(Exception("空响应"))
                Result.success(extractText(raw))
            }
        } catch (e: Exception) {
            Log.w(TAG, "web 搜索失败", e)
            Result.failure(e)
        }
    }

    /** 从响应中提取文本，兼容 OpenAI 风格与几种简化形状 */
    private fun extractText(raw: String): String {
        val text = try {
            val obj = gson.fromJson(raw, JsonObject::class.java)
            val fromChoices = obj?.getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.let { it.getAsJsonObject("message") ?: it.getAsJsonObject("delta") }
                ?.get("content")?.asString
            fromChoices
                ?: listOf("results", "result", "answer", "text", "content", "data")
                    .firstNotNullOfOrNull { key ->
                        obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                    }
                ?: obj?.get("data")?.toString()
                ?: ""
        } catch (_: Exception) {
            raw
        }
        return text.trim().let {
            if (it.length > MAX_RESULT_CHARS) it.take(MAX_RESULT_CHARS) + "…" else it
        }
    }
}
