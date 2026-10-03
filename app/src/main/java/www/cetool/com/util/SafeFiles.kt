package www.cetool.com.util

import java.io.File
import java.io.IOException

/**
 * 文件写入的统一保护。
 *
 * C4：全项目的编辑器保存路径都直接 `File.writeText(...)`：
 * - 磁盘满 / 权限异常时抛 IOException，而调用方没有 try/catch → 崩溃且用户编辑内容全丢；
 * - 写入不是原子的（无临时文件 + rename），中途进程被杀会留下半个文件，
 *   而对应的 load 遇到坏文件返回 null → 书/卡直接从列表消失且无任何提示。
 */
object SafeFiles {

    /**
     * 原子写入。
     *
     * 先写同目录临时文件，再 `renameTo` 覆盖目标。rename 在同一文件系统上是原子操作，
     * 因此不会留下半截内容。
     *
     * @return true 表示成功；false 表示失败（原因通过 [onError] 上报，不抛给调用方）
     */
    fun writeAtomic(file: File, content: String, onError: ((IOException) -> Unit)? = null): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val tmp = File(parent, "${file.name}.tmp")
        return try {
            tmp.writeText(content)
            // fsync 尽力而为，失败不回滚（rename 后内容已完整）
            runCatching { tmp.outputStream().fd.sync() }
            if (tmp.renameTo(file)) true
            else {
                // 某些文件系统 renameTo 跨挂载点会失败，退回直接覆盖
                file.writeText(content)
                true
            }
        } catch (e: IOException) {
            onError?.invoke(e)
            false
        } catch (e: SecurityException) {
            onError?.invoke(IOException(e.message))
            false
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    /** 读取文本文件，失败返回 null 而不是抛给调用方 */
    fun readText(file: File): String? = try {
        if (!file.exists()) null else file.readText()
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}
