package www.cetool.com.model

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
         * OkHttp 的 `Request.Builder().url(...)` 对缺 scheme 的地址抛
         * `IllegalArgumentException`，而聊天发送全链路无 try/catch → 进程被杀。
         * 这里在保存前与发送前两道拦截。
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
            return try {
                val uri = android.net.Uri.parse(trimmed)
                val host = uri.host
                if (host.isNullOrBlank()) "API 地址缺少主机名"
                else null
            } catch (e: Exception) {
                "API 地址格式不正确"
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
