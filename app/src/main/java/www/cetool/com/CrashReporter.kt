package www.cetool.com

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashReporter : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.apply(base))
    }

    companion object {
        private const val FILE_NAME = "crash_log.txt"
        private const val BILIBILI_URL = "https://space.bilibili.com/432122433"

        fun getCrashLog(context: Context): String? {
            val file = File(context.filesDir, FILE_NAME)
            return if (file.exists()) file.readText() else null
        }

        fun clearCrashLog(context: Context) {
            File(context.filesDir, FILE_NAME).delete()
        }

        fun copyAndReport(context: Context, crashInfo: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashInfo))
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BILIBILI_URL))
            context.startActivity(intent)
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

            try {
                File(filesDir, FILE_NAME).writeText(report)
            } catch (_: Exception) {}

            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
