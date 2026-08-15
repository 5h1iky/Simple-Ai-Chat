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
    }

    /**
     * 检查配置是否完整
     */
    fun isValid(): Boolean {
        return apiUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }
}
