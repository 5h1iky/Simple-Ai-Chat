package www.cetool.com

object SettingsKeys {
    const val PREFS_NAME = "app_settings"

    const val KEY_FONT_SIZE = "font_size"

    // 记忆历史消息轮数（全局生效）
    const val KEY_MAX_HISTORY = "max_history"
    const val MAX_HISTORY_MIN = 5
    const val MAX_HISTORY_MAX = 100

    const val KEY_BG_MODE = "bg_mode"
    const val KEY_BG_IMAGE = "bg_image"
    const val KEY_BG_SCALE = "bg_scale"

    const val KEY_WEB_SEARCH_URL = "web_search_url"
    const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"

    const val KEY_API_CONFIGS = "api_configs"
    const val KEY_ACTIVE_API = "active_api"

    const val KEY_USER_NAME = "user_name"
    const val KEY_AI_NAME = "ai_name"
    const val KEY_USER_AVATAR = "user_avatar"
    const val KEY_AI_AVATAR = "ai_avatar"

    const val KEY_THINKING_ENABLED = "thinking_enabled"
    const val KEY_THINKING_LEVEL = "thinking_level"

    // 世界书 token 预算（酒馆 Context %/Budget 对齐，默认 1500 tokens）
    const val KEY_WORLDINFO_BUDGET = "worldinfo_budget"

    // ─── Free Gateway Integration ──────────────────────────────────
    const val KEY_PROVIDER_MODE = "pref_provider_mode"
    const val KEY_PROVIDER_MANUAL_SNAPSHOT = "pref_provider_manual_snapshot"
    const val KEY_PROVIDER_MANUAL_ACTIVE_INDEX = "pref_provider_manual_active_index"
    const val KEY_PROVIDER_AUTO_CREATED = "pref_provider_auto_created"

    const val PROVIDER_CUSTOM = "CUSTOM"
    const val PROVIDER_OPEN_KILO = "OPEN_KILO"

    // Free Gateway: OpenCode Zen
    const val PROVIDER_OPEN_CODE_ZEN = "OPEN_CODE_ZEN"
    const val KEY_ZEN_MODEL = "pref_zen_model"
    const val ZEN_BASE_URL = "https://opencode.ai/zen/v1"
    const val ZEN_PUBLIC_KEY = "public"

    // Free Gateway: OpenKilo 模型选择（pref_kilo_model 直接存实际模型名，旧值 "auto" 兼容为自动路由）
    const val KEY_KILO_MODEL = "pref_kilo_model"
    const val KEY_KILO_MODEL_TIP_SHOWN = "pref_kilo_model_tip_shown"
    const val KILO_MODEL_AUTO = "auto"

    const val KILO_BASE_URL = "https://api.kilo.ai/api/gateway"
    const val KILO_MODEL = "kilo-auto/free"
    const val KILO_MODEL_NEMOTRON_ULTRA = "nvidia/nemotron-3-ultra-550b-a55b:free"
    const val KILO_MODEL_STEPFUN = "stepfun/step-3.7-flash:free"
    const val KILO_MODEL_LING = "inclusionai/ling-3.0-flash:free"
    const val KILO_MODEL_NEMOTRON_SUPER = "nvidia/nemotron-3-super-120b-a12b:free"
    const val KILO_MODEL_LAGUNA = "poolside/laguna-s-2.1:free"

    // Free Gateway: OpenCode Zen 模型选择（模型 ID 为纯名称，调用时不加 opencode/ 前缀）
    const val ZEN_MODEL_MIMO = "mimo-v2.5-free"
    const val ZEN_MODEL_LING = "ling-3.0-flash-free"
    const val ZEN_MODEL_DEEPSEEK = "deepseek-v4-flash-free"
    const val ZEN_MODEL_NEMOTRON = "nemotron-3-ultra-free"
    const val ZEN_MODEL_NORTH = "north-mini-code-free"
    const val ZEN_MODEL_LAGUNA = "laguna-s-2.1-free"
    const val ZEN_MODEL_DEFAULT = ZEN_MODEL_DEEPSEEK

    data class ApiEntry(
        val label: String = "",
        val url: String = "",
        val key: String = "",
        val model: String = ""
    )
}
