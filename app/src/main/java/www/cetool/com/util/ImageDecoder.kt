package www.cetool.com.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 图片解码统一入口。
 *
 * D3：全项目原本到处直接 `decodeStream` / `decodeByteArray` 做**全尺寸**解码，
 * 一张 8000×6000 的照片按 ARGB_8888 约 192MB，且 `catch (e: Exception)`
 * **抓不住 OutOfMemoryError（它是 Error，不是 Exception）** → 选图即崩。
 *
 * 这里统一：
 * - 先用 `inJustDecodeBounds` 读尺寸，再按目标边长算 `inSampleSize` 二次采样；
 * - 同时捕获 Exception 与 OutOfMemoryError。
 */
object ImageDecoder {

    /**
     * 从 URI 采样解码。
     * @param maxEdge 目标最长边；为 0 时不缩放（仅做 BackupActivity 等需要原图的场景）
     * @return 解码后的 Bitmap，失败返回 null
     */
    fun decode(
        context: Context,
        uri: Uri,
        maxEdge: Int = 1024
    ): Bitmap? {
        return try {
            val bounds = readBounds(context, uri) ?: return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (maxEdge > 0) {
                    inSampleSize = calculateInSampleSize(bounds.first, bounds.second, maxEdge)
                }
            }
            val decoded = open(context, uri).use { stream ->
                stream?.let { BitmapFactory.decodeStream(it, null, options) }
            }
            // M14：采样解码结果最长边仍可能落在 [maxEdge, 2×maxEdge)，解码后精确缩放一次收口
            scaleToMaxEdge(decoded, maxEdge)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从字节数组采样解码（H3/M13：SharedPreferences 里的 base64 图可能存的是全尺寸原图）。
     * 整体 `catch (Throwable)`：OOM 是 Error 不是 Exception。
     * @return 解码后的 Bitmap，失败返回 null
     */
    fun decodeByteArray(data: ByteArray, maxEdge: Int = 1024): Bitmap? {
        return try {
            if (data.isEmpty()) return null
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, boundsOptions)
            val w = boundsOptions.outWidth
            val h = boundsOptions.outHeight
            if (w <= 0 || h <= 0) return null

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                if (maxEdge > 0) inSampleSize = calculateInSampleSize(w, h, maxEdge)
            }
            // M14：解码后精确等比缩放，确保最长边不超过 maxEdge
            scaleToMaxEdge(BitmapFactory.decodeByteArray(data, 0, data.size, options), maxEdge)
        } catch (_: Throwable) {
            null
        }
    }

    /** 读图片原始宽高（只读头，不分配像素内存） */
    fun readBounds(context: Context, uri: Uri): Pair<Int, Int>? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(context, uri).use { stream -> stream?.let { BitmapFactory.decodeStream(it, null, options) } }
            val w = options.outWidth
            val h = options.outHeight
            if (w > 0 && h > 0) w to h else null
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun open(context: Context, uri: Uri) = context.contentResolver.openInputStream(uri)

    /** 计算 2 的幂次采样率，使最长边不超过 maxEdge */
    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return 1
        var sample = 1
        // 采样到「略大于」目标的一半即可，避免过采样导致模糊
        while (longest / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * M14：二次采样只能落到 [maxEdge, 2×maxEdge) 区间，解码后按 maxEdge 精确等比缩放一次，
     * 使最终最长边满足 calculateInSampleSize 的注释契约（不超过 maxEdge）。
     * 仅当 createScaledBitmap 返回了新对象时才回收旧 bitmap。
     */
    private fun scaleToMaxEdge(bitmap: Bitmap?, maxEdge: Int): Bitmap? {
        if (bitmap == null || maxEdge <= 0) return bitmap
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * 缩放并压缩为字节。
     * @param format 目标格式；注意 PNG 是无损格式，quality 无效
     */
    fun compress(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 85,
        maxEdge: Int = 2048
    ): ByteArray? {
        return try {
            val scale = if (maxEdge > 0) {
                minOf(1f, maxEdge.toFloat() / bitmap.width, maxEdge.toFloat() / bitmap.height)
            } else 1f
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            ByteArrayOutputStream().use { out ->
                scaled.compress(format, quality, out)
                out.toByteArray()
            }
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
