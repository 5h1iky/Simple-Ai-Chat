package www.cetool.com.manager

import android.content.Context
import com.google.gson.Gson
import www.cetool.com.SettingsKeys
import www.cetool.com.network.AiApiClient

/**
 * 免费网关的模型目录（动态化）。
 *
 * 背景：免费网关的模型会随提供商政策变化而增删（实测 OpenKilo 硬编码的
 * `inclusionai/ling-3.0-flash:free` 已 404、OpenCode Zen 免费档模型也已轮换），
 * 因此不能再把模型列表硬编码进字符串资源。
 *
 * 三层兜底，保证任何网络状况下都有列表可用：
 *   1. 内存/磁盘缓存（TTL 24 小时，未过期直接用）
 *   2. 网络拉取（GET /models）成功则刷新缓存
 *   3. 拉取失败 → 保留旧缓存；连旧缓存都没有 → 回落 SettingsKeys 里的硬编码常量
 *
 * 只读操作，不写业务数据；缓存写在 SettingsKeys.PREFS_NAME 下。
 */
object ModelCatalog {

    /** 一个可选模型：id 用于请求，label 用于界面展示 */
    data class FreeModel(val id: String, val label: String)

    private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val KEY_CACHE_KILO = "free_models_cache_kilo"
    private const val KEY_CACHE_ZEN = "free_models_cache_zen"

    /** 磁盘缓存结构（注意：Gson 直解 Kotlin data class 时缺省字段为 0/null，故做空值兜底） */
    private data class CacheEntry(
        val fetchedAt: Long = 0L,
        val models: List<FreeModel>? = null
    )

    private val gson = Gson()

    /**
     * 获取当前模式下的免费模型列表。
     *
     * @param forceRefresh 为 true 时忽略 TTL 强制走网络（界面上的「刷新」按钮用）
     */
    fun freeModels(context: Context, mode: String, forceRefresh: Boolean = false): List<FreeModel> {
        val prefs = context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val cacheKey = cacheKeyOf(mode)

        val cached = readCache(prefs.getString(cacheKey, null))
        val fresh = cached != null &&
            cached.models!!.isNotEmpty() &&
            (forceRefresh.not() && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS)
        if (fresh) return cached!!.models!!

        val fetched = fetchRemote(context, mode)
        if (fetched != null && fetched.isNotEmpty()) {
            prefs.edit()
                .putString(cacheKey, gson.toJson(CacheEntry(System.currentTimeMillis(), fetched)))
                .apply()
            return fetched
        }

        // 网络失败：保留旧缓存（哪怕已过期，也比没有强）
        if (cached != null && !cached.models!!.isEmpty()) return cached.models!!

        return fallbackModels(mode)
    }

    /** 该模式下已选中的模型是否仍在最新列表中（用于提示「该模型已不可用」） */
    fun isStillAvailable(models: List<FreeModel>, modelId: String): Boolean {
        return models.any { it.id == modelId }
    }

    // ───────────────────────── 内部实现 ─────────────────────────

    private fun cacheKeyOf(mode: String): String {
        return if (mode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) KEY_CACHE_ZEN else KEY_CACHE_KILO
    }

    private fun readCache(json: String?): CacheEntry? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, CacheEntry::class.java)?.takeIf { !it.models.isNullOrEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** 网络拉取 + 免费过滤；任何异常都返回 null（由调用方走兜底） */
    private fun fetchRemote(context: Context, mode: String): List<FreeModel>? {
        val result = AiApiClient.fetchModels(context, mode)
        val items = result.getOrNull() ?: return null
        return items
            .filter { it.id.isNotBlank() && isFreeModel(mode, it.isFree, it.id) }
            .map { FreeModel(it.id, it.name?.takeIf { n -> n.isNotBlank() } ?: it.id) }
            .distinctBy { it.id }
    }

    /**
     * 免费判定：
     * - OpenKilo 直接给 `isFree` 标记，最可靠
     * - OpenCode Zen 无该标记，退回按 id 里的 "free" 约定过滤
     */
    private fun isFreeModel(mode: String, isFree: Boolean?, id: String): Boolean {
        return if (mode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) {
            id.contains("free", ignoreCase = true)
        } else {
            isFree == true
        }
    }

    /**
     * 硬编码兜底列表（离线 / 首次拉取失败时使用，不再作为主数据源）。
     * 纯内存操作，**不读写磁盘、不联网**，因此可以安全地在 Compose 组合期调用。
     *
     * ⚠️ 维护要求：这里只放**实测仍可用**的模型。已剔除实测失效项：
     * - `inclusionai/ling-3.0-flash:free`（HTTP 404，已下架）
     * - `ling-3.0-flash-fin-free`（模型在列但上游端点不可用）
     * - `north-mini-code-free`（已不在 Zen /models 列表中）
     */
    fun fallbackModels(mode: String): List<FreeModel> {
        val ids = if (mode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN) {
            listOf(
                SettingsKeys.ZEN_MODEL_NEMOTRON,   // nemotron-3-ultra-free ✅ 实测可用
                SettingsKeys.ZEN_MODEL_MIMO,       // mimo-v2.5-free ✅
                SettingsKeys.ZEN_MODEL_BIG_PICKLE, // big-pickle（官方定价表 Free）✅
                SettingsKeys.ZEN_MODEL_DEEPSEEK    // deepseek-v4-flash-free（可能因轮换失效，保留在后）
            )
        } else {
            listOf(
                SettingsKeys.KILO_MODEL,                   // kilo-auto/free ✅
                SettingsKeys.KILO_MODEL_NEMOTRON_ULTRA,    // ✅
                SettingsKeys.KILO_MODEL_STEPFUN,           // ✅
                SettingsKeys.KILO_MODEL_NEMOTRON_SUPER,    // ✅
                SettingsKeys.KILO_MODEL_LAGUNA             // 偶发 429 限流，非下架
            )
        }
        return ids.distinct().map { FreeModel(it, it) }
    }
}
