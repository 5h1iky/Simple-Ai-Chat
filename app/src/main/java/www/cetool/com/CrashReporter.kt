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
         *
         * L13：剪贴板不再放完整 crashInfo（堆栈+设备信息）—— 低版本 Android 上
         * 任意前台应用可静默读取剪贴板，异常 message 可能含敏感内容。
         * 只放简短摘要；完整报告仍写入日志文件（崩溃处理器已写盘）。
         */
        fun copyAndReport(context: Context, crashInfo: String) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val logPath = File(context.filesDir, FILE_NAME).absolutePath
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", buildCrashSummary(crashInfo, logPath)))
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

        /**
         * L13：生成可放进剪贴板的简短摘要 —— 时间 + 异常类名 + 一行说明 + 日志文件路径。
         * 不含堆栈、设备信息与异常 message（可能含敏感内容），完整报告只留在日志文件里。
         */
        private fun buildCrashSummary(crashInfo: String, logPath: String): String {
            val lines = crashInfo.lines()
            // 报告头里的"崩溃时间："行，取不到就用当前时间兜底
            val time = lines.firstOrNull { it.startsWith("崩溃时间") }
                ?.substringAfter("：")?.trim().orEmpty()
                .ifEmpty { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) }
            // 堆栈首行形如 "java.lang.OutOfMemoryError: ..."，只取冒号前的类名
            val throwableLine = lines.firstOrNull {
                it.isNotBlank() && !it.startsWith("崩溃时间") && !it.startsWith("设备") && !it.startsWith("SDK")
            }.orEmpty()
            val exceptionName = throwableLine.substringBefore(':').trim().ifEmpty { "未知异常" }
            return buildString {
                append("崩溃时间：").append(time).append('\n')
                append("异常：").append(exceptionName).append('\n')
                append("说明：应用发生崩溃，完整报告（堆栈与设备信息）已写入本地日志文件\n")
                append("日志文件：").append(logPath)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        // M12：处理器主体整体 try/catch (Throwable) 兜底 —— 原实现没有任何保护，
        // 原崩溃是 OOM 时处理器内（StringWriter/拼接/写盘）再抛 Error 会从
        // uncaughtException 逃逸：previousHandler 永不执行（进程僵尸态）且崩溃日志丢失。
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()

                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val header = "崩溃时间：$time\n设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nSDK：${android.os.Build.VERSION.SDK_INT}\n\n"
                val report = header + sw.toString()

                // E11：崩溃日志是唯一线索，写入要尽量可靠（原实现裸 writeText 且无捕获）
                www.cetool.com.util.SafeFiles.writeAtomic(File(filesDir, FILE_NAME), report)
            } catch (t: Throwable) {
                // M12：兜底里绝不能再抛（OOM 时连这里都可能失败，吞掉即可）
            } finally {
                // M12：原处理器必须执行，否则系统杀不掉进程（僵尸态）——放进 finally 保证执行
                try {
                    previousHandler?.uncaughtException(thread, throwable)
                } catch (t: Throwable) {
                    // 原处理器自身再崩也不能让异常从本处理器逃逸
                }
            }
        }

        // E12：ConversationManager 原本只由部分 Activity 调用 init，BondActivity /
        // AdventureActivity 等入口没初始化 —— 进程被回收后从这些页面恢复会显示「空列表」
        // （无崩溃，但数据像是丢了）。改由 Application 统一初始化一次；
        // init() 已做成幂等（见 A4），各 Activity 里的重复调用无害。
        // M12：必须放在安装处理器之后 —— init 期间崩溃同样要有日志可查。
        ConversationManager.init(this)
    }
}
