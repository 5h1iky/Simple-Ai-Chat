package www.cetool.com

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import www.cetool.com.model.ApiConfig
import www.cetool.com.ui.theme.SAChatTheme
import www.cetool.com.ui.theme.SplashBackground
import www.cetool.com.ui.theme.SplashText

/**
 * 启动页（Compose 版）
 * 视觉与旧版一致：纯色背景 + 居中等宽字体打字机动画；
 * 仅提速：原约 6.6s → 约 3.3s（减少闪烁次数与各段停顿，动画序列不变）。
 */
class SplashActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    private val brandText = "Simple AI Chat"
    // 走 strings.xml（此前硬编码中文，是全应用 i18n 的漏网字符串，切英文后启动页仍显示中文）
    private val initText: String get() = getString(R.string.splash_init_text)
    // 提速参数（原 80/50ms、闪烁 4 次、停留 600/400/1200ms）
    private val typeSpeed = 45L
    private val deleteSpeed = 40L
    private val holdAfterTyping = 300L
    private val holdAfterDelete = 200L
    private val holdAfterInit = 500L
    private val blinkCount = 2
    private val blinkInterval = 150L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // E11 / m1：崩溃日志必须在**组合之前、主线程之外的时机**读一次，
        // 且只读到内存里 —— 原实现在组合期 `remember { getCrashLog(...) }` 读盘，
        // 并在用户点按之前就 clearCrashLog：转屏或进程被杀即永久丢失报告，
        // 而且空文件时 readText 返回 ""（非 null）会让弹窗显示一份空报告。
        val crashLogState = androidx.compose.runtime.mutableStateOf<String?>(null)
        val crashLogLoaded = androidx.compose.runtime.mutableStateOf(false)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.execute {
            // minor：必须用 try/finally 兜住 —— 若读盘抛的是 Error（例如日志过大导致 OOM）
            // 而只 catch Exception，crashLogLoaded 永远不会置 true，
            // LaunchedEffect 会一直早退 → 启动页永久空白、既不放动画也不跳转。
            try {
                val log = CrashReporter.getCrashLog(this)?.takeIf { it.isNotBlank() }
                runOnUiThread { crashLogState.value = log }
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                runOnUiThread { crashLogLoaded.value = true }
                executor.shutdown()
            }
        }

        setContent {
            SAChatTheme {
                var text by remember { mutableStateOf("") }
                var showCrash by remember { mutableStateOf(false) }
                val crashLog = crashLogState.value
                val loaded = crashLogLoaded.value

                LaunchedEffect(loaded, crashLog) {
                    if (!loaded) return@LaunchedEffect   // 还在后台读取，先什么都不做
                    if (crashLog != null) {
                        showCrash = true                  // 清除推迟到用户处理之后
                    } else {
                        playAnimation { text = it }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SplashBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = text,
                        color = SplashText,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                if (showCrash) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.splash_crash_title)) },
                        text = { Text(stringResource(R.string.splash_crash_msg)) },
                        confirmButton = {
                            TextButton(onClick = {
                                CrashReporter.copyAndReport(this@SplashActivity, crashLog ?: "")
                                // m1：用户已处理，这时才清除（原实现在展示前就删，
                                // 转屏/被杀会让报告永久丢失）
                                CrashReporter.clearCrashLog(this@SplashActivity)
                                showCrash = false
                                lifecycleScope.launch { playAnimation { text = it } }
                            }) { Text(stringResource(R.string.splash_crash_copy)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                // 用户选择忽略 → 同样在此时才丢弃
                                CrashReporter.clearCrashLog(this@SplashActivity)
                                showCrash = false
                                lifecycleScope.launch { playAnimation { text = it } }
                            }) { Text(stringResource(R.string.splash_crash_ignore)) }
                        }
                    )
                }
            }
        }
    }

    /** 打字机动画序列（与旧版一致：打字 → 光标闪烁 → 删除 → 初始化 → 跳转） */
    private suspend fun playAnimation(onText: (String) -> Unit) {
        for (i in 1..brandText.length) {
            onText(brandText.substring(0, i))
            delay(typeSpeed)
        }

        onText("${brandText}_")
        delay(holdAfterTyping)

        for (i in 0 until blinkCount) {
            onText(brandText)
            delay(blinkInterval)
            onText("${brandText}_")
            delay(blinkInterval)
        }

        for (i in brandText.length downTo 1) {
            onText(brandText.substring(0, i))
            delay(deleteSpeed)
        }
        onText("")

        delay(holdAfterDelete)

        for (i in 1..initText.length) {
            onText(initText.substring(0, i))
            delay(typeSpeed)
        }

        delay(holdAfterInit)
        navigateToNext()
    }

    private fun navigateToNext() {
        // Free Gateway Integration: 每次进入软件时刷新"选择模型"提示标记
        getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(SettingsKeys.KEY_KILO_MODEL_TIP_SHOWN)
            .apply()

        val sp = getSharedPreferences(ApiConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val apiUrl = sp.getString(ApiConfig.KEY_URL, "") ?: ""
        val apiKey = sp.getString(ApiConfig.KEY_KEY, "") ?: ""
        val model = sp.getString(ApiConfig.KEY_MODEL, "") ?: ""

        // Free Gateway: OpenKilo / OpenCode Zen 免费路由无需手动配置，直接进入聊天页
        val settingsSp = getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val providerMode = settingsSp.getString(SettingsKeys.KEY_PROVIDER_MODE, SettingsKeys.PROVIDER_CUSTOM)
            ?: SettingsKeys.PROVIDER_CUSTOM
        val isFreeMode = providerMode == SettingsKeys.PROVIDER_OPEN_KILO ||
            providerMode == SettingsKeys.PROVIDER_OPEN_CODE_ZEN
        val isValid = isFreeMode ||
            (apiUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank())

        val target = if (isValid) {
            Intent(this, ChatActivity::class.java)
        } else {
            Intent(this, SettingsActivity::class.java)
        }
        startActivity(target)
        finish()
    }
}
