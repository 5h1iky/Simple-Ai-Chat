package www.cetool.com.util

import android.content.Context
import io.noties.markwon.Markwon

/**
 * D5：Markwon 实例共享。
 *
 * Markwon 的构造会创建插件表与若干缓存，原本每条聊天气泡各自
 * `remember { Markwon.builder(context)...build() }` —— N 条消息 N 个实例，
 * 列表滚动与每次流式刷新都要重复付出这份成本。
 *
 * 这里按 applicationContext 缓存一个实例（用 App context 避免持有 Activity 引用）。
 */
object MarkwonProvider {

    @Volatile
    private var instance: Markwon? = null

    fun get(context: Context): Markwon {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val created = Markwon.builder(app)
                .usePlugin(www.cetool.com.NoItalicPlugin())
                .build()
            instance = created
            return created
        }
    }
}
