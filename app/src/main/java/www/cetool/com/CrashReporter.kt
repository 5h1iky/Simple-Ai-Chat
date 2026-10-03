package www.cetool.com

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReporter : Application() {

    override fun attachBaseContext(base: Context) {
        // E9：必须在任何 Locale.setDefault 之前先把系统 locale 快照下来
        LocaleHelper.captureSystemLocale()
        super.attachBaseContext(LocaleHelper.apply(base))
    }

    companion object {
        private const val FILE_NAME = "crash_log.txt"
        private const val BILIBILI_URL = "https://space.bilibili.com/432122433"

        fun getCrashLog(context: Context): String? {
            val file = File(context.filesDir, FILE_NAME)
            // E11：原来直接 readText 且调用点在 Compose 组合期（主线程读盘）。
            // 这里统一走带异常保护的读取。
            return www.cetool.com.util.SafeFiles.readText(file)
        }

        fun clearCrashLog(context: Context) {
            File(context.filesDir, FILE_NAME).delete()
        }

        /**
         * 复制崩溃信息并打开发布者主页。
         *
         * E10：隐式 ACTION_VIEW 原本没有 ActivityNotFoundException 保护
         * → 无浏览器设备上崩溃，而且这恰好发生在崩溃上报流程里（二次崩溃）。
         */
        fun copyAndReport(context: Context, crashInfo: String) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashInfo))
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BILIBILI_URL)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(context, R.string.crash_no_browser, Toast.LENGTH_LONG).show()
            } catch (e: SecurityException) {
                Toast.makeText(context, R.string.crash_no_browser, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            throwable.printStackTrace(pw)
            pw.flush()

            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val header = "崩溃时间：$time\n设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nSDK：${android.os.Build.VERSION.SDK_INT}\n\n"
            val report = header + sw.toString()

            // E11：崩溃日志是唯一线索，写入要尽量可靠（原实现裸 writeText 且无捕获）
            www.cetool.com.util.SafeFiles.writeAtomic(File(filesDir, FILE_NAME), report)

            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
