package www.cetool.com

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言切换（UI 中英文）
 *
 * - 偏好存于 SharedPreferences（SettingsKeys.KEY_LANGUAGE：system / zh / en，默认跟随系统）
 * - 每个 Activity / Application 在 attachBaseContext 中调用 [apply] 注入 locale，
 *   Compose 的 stringResource 自动跟随注入后的 Context
 * - 切换语言后需重建页面（设置页选择后清任务栈回聊天页）生效
 */
object LocaleHelper {

    fun getLanguagePref(context: Context): String {
        return context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsKeys.KEY_LANGUAGE, SettingsKeys.LANGUAGE_SYSTEM)
            ?: SettingsKeys.LANGUAGE_SYSTEM
    }

    /**
     * E9：进程启动时抓取的「系统 locale」快照。
     *
     * `apply()` 会调用 `Locale.setDefault(...)` **覆盖全局默认 locale**，
     * 于是切到 zh/en 之后，`resolveLocale()` 里拿到的 `Locale.getDefault()`
     * 已经是被我们自己改过的值 —— 用户在进程存活期间再切回"跟随系统"时，
     * 得到的其实是上一次手动设置的语言，而不是真正的系统语言。
     */
    @Volatile
    private var systemLocaleSnapshot: Locale? = null

    /**
     * M17：未被本应用 wrap 的 base context（Application 级，进程内只记录第一次）。
     * 用它读到的 `resources.configuration` 始终是**真实系统配置** ——
     * 本应用 wrap 过的 Context（createConfigurationContext）里是被改写过的 locale，
     * 而 Locale.setDefault 又污染 Locale.getDefault()，两者都不能用来"跟随系统"。
     */
    @Volatile
    private var rawBaseContext: Context? = null

    /** 进程内记录一次原始系统 locale（只记录第一次；M17 后仅作兜底回退） */
    private fun systemLocale(): Locale {
        // M17：优先读「当前」系统配置的 locales[0] —— 系统语言可能在进程存活期间被改掉，
        // 快照只反映进程启动那一刻，"跟随系统"必须跟着变。
        rawBaseContext?.let { base ->
            try {
                // 拷贝一份 Configuration 再取值：避免被本应用 setLocale/setDefault
                // 写回的值污染（坑见上方 E9 注释）
                val config = Configuration(base.resources.configuration)
                val current = config.locales.get(0)
                if (current != null) return current
            } catch (t: Throwable) {
                // 读不到就走快照兜底
            }
        }
        systemLocaleSnapshot?.let { return it }
        synchronized(this) {
            systemLocaleSnapshot?.let { return it }
            val snapshot = Locale.getDefault()
            systemLocaleSnapshot = snapshot
            return snapshot
        }
    }

    fun resolveLocale(pref: String): Locale {
        return when (pref) {
            SettingsKeys.LANGUAGE_ZH -> Locale.SIMPLIFIED_CHINESE
            SettingsKeys.LANGUAGE_EN -> Locale.ENGLISH
            // 跟随系统：M17 改读未被 wrap 的 base context 的当前系统配置（实时），
            // 进程启动时的快照只作兜底。
            // E9：不能用 Locale.getDefault() —— apply() 已经把它覆盖掉了，
            // 那样"跟随系统"在进程存活期间永远无法恢复。
            else -> systemLocale()
        }
    }

    /**
     * 进程启动时先落一次快照，必须在任何 apply() 之前调用。
     * Application.attachBaseContext 是唯一可靠的时机。
     */
    fun captureSystemLocale() {
        systemLocale()
    }

    /** attachBaseContext 中调用：按偏好包装 Context 并同步全局默认 locale */
    fun apply(context: Context): Context {
        // M17：记住未被本应用 wrap 的 base context（首次来自 Application.attachBaseContext，
        // 是进程级 ContextImpl，无泄漏）；resolveLocale 的"跟随系统"分支用它读真实系统配置。
        // 必须在 resolveLocale 之前记录。
        if (rawBaseContext == null) {
            synchronized(this) {
                if (rawBaseContext == null) rawBaseContext = context
            }
        }
        val locale = resolveLocale(getLanguagePref(context))
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /** 当前语言显示名（设置页语言行） */
    fun languageLabel(context: Context): String {
        return when (getLanguagePref(context)) {
            SettingsKeys.LANGUAGE_ZH -> "中文"
            SettingsKeys.LANGUAGE_EN -> "English"
            else -> "跟随系统 / System"
        }
    }
}
