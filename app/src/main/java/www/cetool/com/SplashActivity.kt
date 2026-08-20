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

    private val brandText = "Simple AI Chat"
    private val initText = "正在初始化环境..."
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
        setContent {
            SAChatTheme {
                var text by remember { mutableStateOf("") }
                var showCrash by remember { mutableStateOf(false) }
                val crashLog = remember { CrashReporter.getCrashLog(this@SplashActivity) }

                LaunchedEffect(Unit) {
                    if (crashLog != null) {
                        CrashReporter.clearCrashLog(this@SplashActivity)
                        showCrash = true
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
                        title = { Text("上次使用出现问题") },
                        text = { Text("软件上次运行时出现了异常。你可以复制详细信息发给我，我来修复。") },
                        confirmButton = {
                            TextButton(onClick = {
                                CrashReporter.copyAndReport(this@SplashActivity, crashLog ?: "")
                                showCrash = false
                                lifecycleScope.launch { playAnimation { text = it } }
                            }) { Text("复制并反馈") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showCrash = false
                                lifecycleScope.launch { playAnimation { text = it } }
                            }) { Text("忽略") }
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
