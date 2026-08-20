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

    fun resolveLocale(pref: String): Locale {
        return when (pref) {
            SettingsKeys.LANGUAGE_ZH -> Locale.SIMPLIFIED_CHINESE
            SettingsKeys.LANGUAGE_EN -> Locale.ENGLISH
            // 跟随系统：直接用系统默认 locale
            else -> Locale.getDefault()
        }
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
