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

    /** 进程内记录一次原始系统 locale（只记录第一次） */
    private fun systemLocale(): Locale {
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
            // 跟随系统：用进程启动时快照下来的系统 locale。
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
