package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * **本地已下载文件**的照片读取（传输页点开用）。
 *
 * 与相机端预览的区别：这里读的是**手机上的完整文件**，所以
 * <ul>
 *   <li><b>JPG</b>：直接解码（按屏幕需要降采样，别把 6000×4000 整张铺进内存）；</li>
 *   <li><b>ARW</b>：Android 解不了 RAW。用相机写进 ARW 里的**内嵌大预览 JPEG**
 *       （1616×1080 级、约 480KB）来显示 —— 走 TIFF 的 IFD0 / IFD1 / SubIFD
 *       把候选 JPEG 找出来，取最大的那张。这条路径是离线可用的：
 *       不需要连相机、也不需要之前的预览缓存。</li>
 * </ul>
 *
 * EXIF 一律用 androidx 的 [ExifInterface] 从**原文件**读 —— 它支持 TIFF 系 RAW
 * （含索尼 ARW），所以 RAW 也能拿到真正的拍摄参数，而不是内嵌预览图那份精简副本。
 *
 * 全程只读、不开临时文件（用 FileChannel 随机定位，ARW 不必整份读进内存）。
 */
object LocalPhoto {

    /** 内嵌预览的最小尺寸门槛：比这更小的都是 160×120 的小缩略图，点开看会糊。 */
    private const val MIN_PREVIEW_WIDTH = 800

    /** 单张内嵌图最多读多少字节（防畸形文件把内存吃光）。 */
    private const val MAX_EMBEDDED = 8 * 1024 * 1024

    /** 解码上限边长：预览图本来就不大，但 JPG 可能是原图，按 2560 降采样封顶。 */
    private const val MAX_DECODE_DIM = 2560

    /** 可在此查看器里显示的扩展名。 */
    val OPENABLE = setOf("jpg", "jpeg", "arw")

    fun openable(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in OPENABLE

    /**
     * 取可显示的位图。取不到返回 null（调用方显示失败文案）。
     *
     * ★ 整个函数体包在 runCatching 里：调用点是协程，异常抛出去就是**崩应用**。
     *   畸形文件、被截断的下载、权限变化的 Uri，都只应该表现为"看不了这一张"。
     *
     * 阻塞 + 读文件，**必须在 IO 线程调用**。
     */
    fun loadPreview(context: Context, uri: Uri, name: String): ImageBitmap? = runCatching {
        val bytes = if (name.endsWith(".arw", ignoreCase = true)) {
            extractArwPreview(context, uri)
        } else {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        } ?: return null
        return runCatching { decodeSampled(bytes)?.asImageBitmap() }.getOrNull()
    }.getOrNull()

    /**
     * EXIF 信息行（**顺序即展示顺序**）：文件名 / 文件格式 / 拍摄日期时间 /
     * 相机型号 / 镜头型号 / 焦距 / 光圈 / 快门速度 / EV / ISO。
     *
     * 读不到的项**不出现在结果里**（不显示"—"占位）；文件名与格式这两行一定有。
     *
     * ★ 2.7.0：本函数是**全应用唯一的行构建器**（用户定版"文件页与传输页的 EXIF 显示
     *   要一样"）—— 传输页本地查看器与文件页相机预览**共用**它，从机制上保证两处永远
     *   一致。两个数据源各自把数据装成"按 ExifInterface 标签名查找"的形态：
     *   本地文件查 [ExifInterface]，相机侧查预览 sidecar 的 JSON（`ThumbStore.readExifJson`）。
     */
    internal fun buildExifRows(name: String, tag: (String) -> String?): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>(12)
        rows.add("文件名" to name)
        rows.add("文件格式" to name.substringAfterLast('.', "").uppercase())
        // 拍摄时间：优先原始拍摄时间，退到文件里的 DateTime
        (tag(ExifInterface.TAG_DATETIME_ORIGINAL) ?: tag(ExifInterface.TAG_DATETIME))
            ?.let { rows.add("拍摄日期/时间" to prettyDateTime(it)) }
        tag(ExifInterface.TAG_MODEL)?.let { rows.add("相机型号" to it) }
        tag(ExifInterface.TAG_LENS_MODEL)?.let { rows.add("镜头型号" to it) }
        tag(ExifInterface.TAG_FOCAL_LENGTH)?.let { v ->
            ratioOrNull(v)?.let { rows.add("焦距" to trimNum(it) + " mm") }
        }
        tag(ExifInterface.TAG_F_NUMBER)?.let { v ->
            ratioOrNull(v)?.let { rows.add("光圈" to "f/" + trimNum(it)) }
        }
        tag(ExifInterface.TAG_EXPOSURE_TIME)?.let { v ->
            prettyExposure(v)?.let { rows.add("快门速度" to it) }
        }
        tag(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let { v ->
            ratioOrNull(v)?.let { rows.add("EV" to prettyEv(it)) }
        }
        tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { rows.add("ISO" to it) }
        return rows
    }

    /** 本地文件（原图）的 EXIF 行：数据源 = androidx [ExifInterface]（RAW 也支持）。 */
    fun readExif(context: Context, uri: Uri, name: String): List<Pair<String, String>> {
        val ex = openExif(context, uri) ?: return buildExifRows(name) { null }
        return runCatching { buildExifRows(name) { key -> tag(ex, key) } }
            .getOrElse { buildExifRows(name) { null } }
    }

    // ============================================================
    // EXIF 打开（InputStream 优先；RAW 读不出来再用可定位的 fd 兜底）
    // ============================================================

    private fun openExif(context: Context, uri: Uri): ExifInterface? {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                ?.let { if (tag(it, ExifInterface.TAG_MODEL) != null
                        || tag(it, ExifInterface.TAG_DATETIME_ORIGINAL) != null) return it }
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor)
            }
        }.getOrNull()
    }

    private fun tag(ex: ExifInterface, key: String): String? =
        runCatching { ex.getAttribute(key)?.takeIf { it.isNotBlank() } }.getOrNull()

    // ============================================================
    // ARW：抽内嵌大预览
    // ============================================================

    /**
     * 从 ARW 里取出**最大的那张内嵌 JPEG**（宽 ≥ [MIN_PREVIEW_WIDTH]）。
     *
     * TIFF 结构（实测这台 A6300 的 ARW）：IFD0 的 0x0201/0x0202 指向大预览
     * （约 480KB），IFD1 的同一对标签指向 160×120 小图，SubIFD 是 RAW 数据本身。
     * 所以把 IFD0、IFD1、SubIFD 都走一遍、收全部候选、按像素总数挑最大。
     */
    private fun extractArwPreview(context: Context, uri: Uri): ByteArray? {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }
            .getOrNull() ?: return null
        return pfd.use { d ->
            runCatching {
                FileInputStream(d.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    val head = readFully(ch, 0, 8) ?: return@use null
                    val le = head[0] == 'I'.code.toByte()
                    val order = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                    val ifd0 = u32(head, 4, order)
                    val cands = ArrayList<LongArray>(8)      // [offset, length]
                    val seen = HashSet<Long>()
                    walkIfds(ch, order, ifd0, cands, seen, 0)
                    pickBest(ch, cands, order)
                }
            }.getOrNull()
        }
    }

    /** 递归遍历 IFD（含 next IFD 与 SubIFD），收集 0x0201/0x0202 指着的 JPEG 段。 */
    private fun walkIfds(
        ch: FileChannel,
        order: ByteOrder,
        ifdOffset: Long,
        out: MutableList<LongArray>,
        seen: MutableSet<Long>,
        depth: Int,
    ) {
        if (depth > 3 || ifdOffset <= 0 || ifdOffset > ch.size() || !seen.add(ifdOffset)) return
        val cntBuf = readFully(ch, ifdOffset, 2) ?: return
        val n = u16(cntBuf, 0, order)
        if (n <= 0 || n > 512) return
        val ent = readFully(ch, ifdOffset + 2, n * 12) ?: return
        var jpegOff = -1L
        var jpegLen = -1L
        val subs = ArrayList<Long>(4)
        for (i in 0 until n) {
            val p = i * 12
            val tag = u16(ent, p, order)
            val type = u16(ent, p + 2, order)
            val count = u32(ent, p + 4, order)
            val value: Long = if (type == 3 && count == 1L) {
                u16(ent, p + 8, order).toLong()
            } else {
                u32(ent, p + 8, order)
            }
            when (tag) {
                0x0201 -> jpegOff = value
                0x0202 -> jpegLen = value
                0x014A -> {   // SubIFDs：可能是单个偏移，也可能是指向偏移数组
                    if (count == 1L) {
                        subs.add(value)
                    } else if (count in 2L..8L) {
                        val b = readFully(ch, value, (count * 4).toInt())
                        if (b != null) {
                            for (k in 0 until count.toInt()) {
                                subs.add(u32(b, k * 4, order))
                            }
                        }
                    }
                }
            }
        }
        if (jpegOff > 0 && jpegLen > 0) {
            out.add(longArrayOf(jpegOff, jpegLen))
        }
        // next IFD
        val nextBuf = readFully(ch, ifdOffset + 2 + n * 12, 4)
        if (nextBuf != null) {
            val next = u32(nextBuf, 0, order)
            if (next > 0) walkIfds(ch, order, next, out, seen, depth + 1)
        }
        for (s in subs) walkIfds(ch, order, s, out, seen, depth + 1)
    }

    /** 在候选里挑最大的那张真 JPEG，读回来。 */
    private fun pickBest(ch: FileChannel, cands: List<LongArray>, order: ByteOrder): ByteArray? {
        var best: ByteArray? = null
        var bestPixels = 0L
        for (c in cands) {
            val off = c[0]
            val len = c[1]
            if (len < 64L * 1024L || len > MAX_EMBEDDED) continue      // 太小的必是缩略图
            if (off <= 0L || off + len > ch.size()) continue
            val head = readFully(ch, off, 64 * 1024) ?: continue
            if (head.size < 4 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) continue
            val dims = sofDims(head) ?: continue
            if (dims[0] < MIN_PREVIEW_WIDTH) continue
            val pixels = dims[0].toLong() * dims[1].toLong()
            if (pixels > bestPixels) {
                val bytes = readFully(ch, off, len.toInt()) ?: continue
                best = bytes
                bestPixels = pixels
            }
        }
        return best
    }

    /** 从 JPEG 头里找 SOF，返回 [宽, 高]；找不到返回 null。 */
    private fun sofDims(b: ByteArray): IntArray? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0xD9 || (m in 0xD0..0xD7)) {
                i += 2
                continue
            }
            if (m == 0xDA) return null                    // 到了扫描数据还没见 SOF
            val ln = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                if (i + 9 >= b.size) return null
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return intArrayOf(w, h)
            }
            i += 2 + ln
        }
        return null
    }

    // ============================================================
    // 解码 / 小工具
    // ============================================================

    /** 按 [MAX_DECODE_DIM] 降采样解码，避免原图 JPG 把内存撑爆。 */
    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w > 0 && h > 0 && (w > MAX_DECODE_DIM || h > MAX_DECODE_DIM)) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun readFully(ch: FileChannel, offset: Long, len: Int): ByteArray? {
        if (len <= 0) return null
        return runCatching {
            val buf = ByteBuffer.allocate(len)
            var pos = offset
            while (buf.hasRemaining()) {
                val r = ch.read(buf, pos)
                if (r < 0) return@runCatching null
                pos += r
            }
            buf.array()
        }.getOrNull()
    }

    private fun u16(b: ByteArray, off: Int, order: ByteOrder): Int {
        if (off + 2 > b.size) return 0
        return if (order == ByteOrder.LITTLE_ENDIAN) {
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        } else {
            ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        }
    }

    private fun u32(b: ByteArray, off: Int, order: ByteOrder): Long {
        if (off + 4 > b.size) return 0
        val v = if (order == ByteOrder.LITTLE_ENDIAN) {
            (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
        } else {
            ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)
        }
        return v and 0xFFFFFFFFL
    }

    /**
     * EXIF 的数值串 → Double；**解析不了返回 null**（调用方直接不显示这一行）。
     *
     * 两种形态都要吃：`"35/10"`（RATIONAL 原样）与 `"3.5"`（androidx 已经化成小数）。
     * 实测两条路真的都会出现 —— 同一个 TAG，不同 tag/不同库版本给的不一样。
     */
    private fun ratioOrNull(s: String): Double? {
        val t = s.trim()
        val i = t.indexOf('/')
        val v = runCatching {
            if (i > 0) {
                val num = t.substring(0, i).trim().toDouble()
                val den = t.substring(i + 1).trim().toDouble()
                if (den == 0.0) return null
                num / den
            } else {
                t.toDouble()
            }
        }.getOrNull() ?: return null
        return if (v.isNaN() || v.isInfinite()) null else v
    }

    /** 去掉多余小数位（16.0 → "16"，3.5 → "3.5"）。 */
    private fun trimNum(v: Double): String {
        if (v.isNaN()) return "—"
        return if (kotlin.math.abs(v - kotlin.math.round(v)) < 0.05) {
            kotlin.math.round(v).toLong().toString()
        } else {
            "%.1f".format(v)
        }
    }

    /**
     * 快门速度：**一律先化成秒数再决定怎么写** —— 快于 1 秒写分数（"1/60 s"），
     * 1 秒及以上写小数（"2.5 s"）。
     *
     * ★ 这里踩过一次坑（用户报"快门始终显示 0 秒"）：原先只有字符串里带 "/" 才走分数分支，
     *   而**androidx 对这种 RATIONAL 字段常常直接给小数**（"0.0166667"）。那种串落到
     *   "小数分支"，再被 trimNum 四舍五入到整数位 → 0.0167 就变成了 **"0 s"**。
     *   现在先 parse 成 Double，小数与分数走同一条判断，0.0167 → "1/60 s"。
     *
     * @return null = 解析不出来 / 非正值（调用方不显示这一行）
     */
    private fun prettyExposure(s: String): String? {
        val v = ratioOrNull(s) ?: return null
        if (v <= 0.0) return null
        if (v >= 1.0) return trimNum(v) + " s"
        // 分母取整：1/60 = 0.0166667 → 60
        return "1/" + kotlin.math.round(1.0 / v).toLong() + " s"
    }

    /** 曝光补偿："+0.3 EV" / "±0 EV"。 */
    private fun prettyEv(v: Double): String {
        val s = if (v > 0) "+" else if (v < 0) "-" else "±"
        return s + trimNum(kotlin.math.abs(v)) + " EV"
    }

    /** "2026:09:21 19:54:06" → "2026-09-21 19:54:06"（EXIF 用冒号分隔日期，看着别扭）。 */
    private fun prettyDateTime(s: String): String {
        val t = s.trim()
        if (t.length < 19) return t
        return t.substring(0, 10).replace(':', '-') + t.substring(10)
    }
}
