package www.cetool.com.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * AI API 配置
 */
data class ApiConfig(
    val apiUrl: String = "",
    val apiKey: String = "",
    val model: String = "gpt-3.5-turbo"
) {
    companion object {
        const val PREFS_NAME = "ai_config"
        const val KEY_URL = "api_url"
        const val KEY_KEY = "api_key"
        const val KEY_MODEL = "model"

        /**
         * A2：校验 API 地址是否是合法的 http(s) 地址。
         *
         * OkHttp 的 `Request.Builder().url(...)` 对形态非法的地址抛
         * `IllegalArgumentException`，而聊天发送全链路无 try/catch → 进程被杀。
         *
         * M8：必须用 **OkHttp 自己的解析器**（`toHttpUrlOrNull`）判定，不能用
         * `android.net.Uri` —— Uri 明显更宽松，像
         * `http://host:abc/v1`（端口非数字）、`https://exa mple.com/v1`（含空格）、
         * `http://ho%20st/v1` 这类输入在 Uri 下 host 非空、能通过校验，
         * 却会让 OkHttp 抛异常，崩溃路径依然存在。
         *
         * @return null 表示合法；否则返回可直接展示给用户的错误原因
         */
        @JvmStatic
        fun validateUrl(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isBlank()) return "API 地址不能为空"
            val lower = trimmed.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return "API 地址必须以 http:// 或 https:// 开头"
            }
            // 与 OkHttp 完全一致的判定
            return if (trimmed.toHttpUrlOrNull() == null) {
                "API 地址格式不正确（请检查主机名、端口与特殊字符）"
            } else {
                null
            }
        }
    }

    /**
     * 检查配置是否完整
     */
    fun isValid(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    /** 地址是否可用（形态合法 + 内容完整） */
    fun isUsable(): Boolean = isValid() && validateUrl(apiUrl) == null
}
