package www.cetool.com

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import www.cetool.com.model.ApiConfig

class SplashActivity : AppCompatActivity() {

    private lateinit var tvSplash: TextView

    private val brandText = "Simple AI Chat"
    private val initText = "正在初始化环境..."
    private val typeSpeed = 80L
    private val deleteSpeed = 50L
    private val holdAfterTyping = 600L
    private val holdAfterDelete = 400L
    private val holdAfterInit = 1200L
    private val blinkCount = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        tvSplash = findViewById(R.id.tvSplashText)

        val crashLog = CrashReporter.getCrashLog(this)
        if (crashLog != null) {
            CrashReporter.clearCrashLog(this)
            showCrashDialog(crashLog)
        } else {
            lifecycleScope.launch { playAnimation() }
        }
    }

    private fun showCrashDialog(crashLog: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("上次使用出现问题")
            .setMessage("软件上次运行时出现了异常。你可以复制详细信息发给我，我来修复。")
            .setPositiveButton("复制并反馈") { _, _ ->
                CrashReporter.copyAndReport(this, crashLog)
                lifecycleScope.launch { playAnimation() }
            }
            .setNegativeButton("忽略") { _, _ ->
                lifecycleScope.launch { playAnimation() }
            }
            .setCancelable(false)
            .show()
    }

    private suspend fun playAnimation() {
        for (i in 1..brandText.length) {
            tvSplash.text = brandText.substring(0, i)
            delay(typeSpeed)
        }

        tvSplash.text = "${brandText}_"
        delay(holdAfterTyping)

        for (i in 0 until blinkCount) {
            tvSplash.text = brandText
            delay(200)
            tvSplash.text = "${brandText}_"
            delay(200)
        }

        for (i in brandText.length downTo 1) {
            tvSplash.text = brandText.substring(0, i)
            delay(deleteSpeed)
        }
        tvSplash.text = ""

        delay(holdAfterDelete)

        for (i in 1..initText.length) {
            tvSplash.text = initText.substring(0, i)
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
