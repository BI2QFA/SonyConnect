package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bi2qfa.sonyconnect.ptpip.ObjectRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缩略图管线。
 *
 * 相机端预取（OP_THUMB_QUEUE_BEGIN）只是热身；真正的数据通路是手机端按需经
 * `OP_GET_OBJECT` 拉取内嵌 JPEG：列表用 KIND_THUMB（160x120 ~6KB），
 * 点开全屏用 KIND_PREVIEW（1616x1080 ~0.4MB）。两层缓存：内存 Lru + 磁盘
 * （键 = path 的 MD5）。
 *
 * 文件传输开始时 paused=true（DownloadService 调 pauseForTransfer），拉取
 * 线程挂起等待；传输结束自动恢复（resumeAfterTransfer）。进度（done/total）
 * 供顶栏芯片显示。
 */
object ThumbStore {

    enum class ThumbState { NONE, LOADING, READY, FAILED }

    data class ObjectCacheKey(
        val cameraGuid: String,
        val path: String,
        val size: Long,
        val mtime: Long,
    ) {
        val token: String
            get() = cameraGuid.lowercase() + "\u0000" + path + "\u0000" + size + "\u0000" + mtime
    }

    /** 磁盘缓存上限（按张数；每台相机一份）。小图约 6 KB，所以 600 张约 4 MB。 */
    private const val KEEP_THUMBS = 600

    /** 大预览约 425 KB，限量更要紧：60 张约 25 MB。 */
    private const val KEEP_PREVIEWS = 60

    /**
     * 小图磁盘缓存**总字节**上限。
     *
     * <p>★ 为什么光有条数上限不够：条数假设了"每张差不多大"，而实际差异很大
     * （小图几 KB，大预览几百 KB 到 1 MB 都有）。60 张"大"预览完全可能比
     * 600 张小图更占地方 —— 只按条数删，实际占用就不可控。
     * 两个上限**都要**：条数挡住"一堆极小文件"（inode 也是开销），
     * 字节挡住"几张超大文件"。
     */
    private const val MAX_THUMBS_BYTES = 24L * 1024 * 1024

    /** 预览磁盘缓存总字节上限（预览单张就大，给得比小图宽）。 */
    private const val MAX_PREVIEWS_BYTES = 96L * 1024 * 1024

    private lateinit var appContext: Context
    private lateinit var smallDiskDir: File
    private lateinit var previewDiskDir: File

    /** 完整对象身份 → 状态 */
    val states = mutableStateMapOf<String, ThumbState>()

    var paused by mutableStateOf(false)
        private set

    /** 批次进度：本批已处理数 / 批次总数 */
    var batchDone by mutableStateOf(0)
        private set
    var batchTotal by mutableStateOf(0)
        private set

    private val queue = ArrayDeque<ObjectCacheKey>()
    private val queuedSet = HashSet<String>()
    private val lock = Object()
    private val started = AtomicBoolean(false)
    private var host: String? = null

    /**
     * 当前缩略图归属的相机设备码。磁盘缓存按它分目录 ——
     * 缓存键原来只有路径，而 `/DCIM/100MSDCF/DSC00001.ARW` 在任何相机上都一样，
     * 换相机（或同一台相机换 SD 卡）后就会直接命中旧缓存、**显示出别的相机的照片**。
     */
    @Volatile
    private var camId: String = ""

    private val memoryCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // v2: 旧目录只按 path 命名，已实锤会串图；直接隔离，不再读取旧格式。
        smallDiskDir = File(appContext.cacheDir, "thumbs-v2").apply { mkdirs() }
        previewDiskDir = File(appContext.cacheDir, "previews-v2").apply { mkdirs() }
        // 旧目录不再可能被正确恢复，升级时一次性清掉，避免永久占空间。
        runCatching { File(appContext.cacheDir, "thumbs").deleteRecursively() }
        runCatching { File(appContext.cacheDir, "previews").deleteRecursively() }
        if (started.compareAndSet(false, true)) {
            Thread({
                fetchLoop()
            }, "ThumbFetch").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 相机端预取只是让相机先做 CPU/IO 热身；手机端仍按完整对象身份按需拉取和
     * 落缓存，避免不知道 size/mtime 时写出一个可能串图的缓存。
     */
    fun markBatchQueued(paths: List<String>) {
        // 只更新批次计数；实际入队由 FilesScreen 携带完整身份调用 request()。
        synchronized(lock) {
            batchTotal = paths.size
            batchDone = 0
        }
    }

    /** 列表按需请求：必须带远端对象身份，防止同名新文件命中旧缓存。 */
    fun request(keys: List<ObjectCacheKey>) {
        syncCamId()
        synchronized(lock) {
            host = com.bi2qfa.sonyconnect.core.ConnectionCenter.host
            var added = false
            for (key in keys) {
                val st = states[key.token]
                if (st == null || st == ThumbState.FAILED) {
                    states[key.token] = ThumbState.NONE
                    if (queuedSet.add(key.token)) {
                        queue.add(key)
                        added = true
                    }
                }
            }
            if (added) lock.notifyAll()
        }
    }

    // ===== 传输暂停联动 =====

    fun pauseForTransfer() {
        paused = true
    }

    fun resumeAfterTransfer() {
        paused = false
        synchronized(lock) { lock.notifyAll() }
    }

    // ===== 位图获取（UI 调用） =====

    fun smallThumb(key: ObjectCacheKey): ImageBitmap? {
        syncCamId()
        memoryCache.get(key.token)?.let { return it.asImageBitmap() }
        val f = smallDiskFile(key)
        if (f.isFile) {
            runCatching {
                decodeFileOriented(f)?.let {
                    memoryCache.put(key.token, it)
                    return it.asImageBitmap()
                }
            }
        }
        return null
    }

    /** 全屏大预览：磁盘缓存 → PTP/IP 拉取（阻塞，须在 IO 线程） */
    fun fetchPreviewBlocking(key: ObjectCacheKey): ImageBitmap? {
        syncCamId()
        val f = previewDiskFile(key)
        if (f.isFile) {
            runCatching { decodeFileOriented(f)?.let { return it.asImageBitmap() } }
        }
        val h = host ?: com.bi2qfa.sonyconnect.core.ConnectionCenter.host ?: return null
        val bytes = ObjectRepository.fetchVirtualPreview(h, key.path) ?: return null
        runCatching {
            f.writeBytes(bytes)
            f.parentFile?.let { prune(it, KEEP_PREVIEWS, MAX_PREVIEWS_BYTES) }
        }
        return decodeOriented(bytes)?.asImageBitmap()
    }

    /** 记住当前相机（断开时清空，避免下次错用上一台的目录）。 */
    private fun syncCamId() {
        val g = com.bi2qfa.sonyconnect.core.ConnectionCenter.camera?.guidHex
        if (!g.isNullOrBlank()) camId = g
    }

    private fun subDir(base: File, id: String): File {
        val d = File(base, if (id.isBlank()) "unknown" else id)
        if (!d.isDirectory) runCatching { d.mkdirs() }
        return d
    }

    private fun keyFor(path: String, size: Long, mtime: Long): ObjectCacheKey =
        ObjectCacheKey(camId, path, size, mtime)

    private fun smallDiskFile(key: ObjectCacheKey) =
        File(subDir(smallDiskDir, key.cameraGuid), cacheKey(key.token) + ".jpg")

    private fun previewDiskFile(key: ObjectCacheKey) =
        File(subDir(previewDiskDir, key.cameraGuid), cacheKey(key.token) + ".jpg")

    private fun cacheKey(value: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * 按数量裁掉最旧的缓存文件。
     *
     * 原来这两个目录**从不回收**：一个 361 张的目录，previews 单张约 425 KB，
     * 累计就能到 150 MB 量级，而且要一直躺在 cacheDir 里。
     */
    /**
     * 修剪一个缓存目录：**条数与字节双上限**，都按"最久未访问"（LRU）删。
     *
     * <p>用 `lastModified` 当"最近使用"的近似：读取缓存时我们不去 touch 文件
     * （那会给每次看图加一次写盘），所以它更接近"最近写入"。对本场景够用 ——
     * 缩略图是"看过就会留一阵"，按写入时间淘汰与按访问时间淘汰的差别很小，
     * 而省下的写盘开销是每张图一次。
     *
     * @param keep 条数上限（防一堆极小文件撑爆 inode）
     * @param maxBytes 字节上限（防几张超大文件；≤0 表示不限）
     */
    private fun prune(d: File, keep: Int, maxBytes: Long) {
        val fs = runCatching { d.listFiles() }.getOrNull() ?: return
        if (fs.isEmpty()) return
        // 从最旧到最新排，先按条数算出必须删几个，再看字节数是否还要多删
        val ordered = runCatching { fs.sortedBy { it.lastModified() } }.getOrNull() ?: return
        var toDelete = (fs.size - keep).coerceAtLeast(0)
        if (maxBytes > 0) {
            var total = fs.sumOf { it.length() }
            var i = 0
            while (total > maxBytes && i < ordered.size) {
                total -= ordered[i].length()
                // 已经删过的（条数那部分）不重复计，但 here 的 i 是从 0 开始数，
                // 所以 toDelete 取两者较大值即可
                toDelete = maxOf(toDelete, i + 1)
                i++
            }
        }
        if (toDelete <= 0) return
        runCatching { ordered.take(toDelete).forEach { it.delete() } }
    }

    /** 预览图的元信息（"照片信息"弹窗用）：宽高按 EXIF 方向转正后的显示尺寸。 */
    class PreviewMeta(val width: Int, val height: Int, val exif: Map<String, String>)

    /**
     * 读预览缓存文件的元信息（EXIF）。文件还没拉回来时返回 null，
     * 弹窗那边显示 "—" 即可（元信息是"看过才有"的附属品，不为它单独拉图）。
     */
    fun previewMeta(key: ObjectCacheKey): PreviewMeta? {
        val f = previewDiskFile(key)
        if (!f.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            val ex = androidx.exifinterface.media.ExifInterface(f)
            val o = ex.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
            )
            var w = bounds.outWidth
            var h = bounds.outHeight
            // 竖向的两档方向：显示宽高要对调
            if (o == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                o == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 ||
                o == androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE ||
                o == androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE
            ) {
                val t = w; w = h; h = t
            }
            fun tag(name: String): String? =
                ex.getAttribute(name)?.takeIf { it.isNotBlank() }
            val exif = buildMap {
                tag(androidx.exifinterface.media.ExifInterface.TAG_MAKE)?.let { put("相机厂商", it) }
                tag(androidx.exifinterface.media.ExifInterface.TAG_MODEL)?.let { put("相机型号", it) }
                tag(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?.let { put("原始时间", it) }
                tag(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)
                    ?.let { v -> put("快门", formatExposure(v)) }
                tag(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER)
                    ?.let { put("光圈", "f/$it") }
                tag(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?.let { put("ISO", it) }
                tag(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH)
                    ?.let { put("焦距", "${it}mm") }
            }
            PreviewMeta(w, h, exif)
        }.getOrNull()
    }

    /** EXIF 快门值（"0.004"）→ 习惯写法（"1/250"）。 */
    private fun formatExposure(v: String): String {
        val d = v.toFloatOrNull() ?: return v
        return if (d >= 1f) {
            "%.0fs".format(d)
        } else {
            "1/${Math.round(1f / d)}"
        }
    }

    fun reset() {
        synchronized(lock) {
            queue.clear()
            queuedSet.clear()
            batchDone = 0
            batchTotal = 0
            paused = false
            lock.notifyAll()
        }
        states.clear()
        memoryCache.evictAll()
        camId = ""
    }

    // ===== 拉取线程 =====

    private fun fetchLoop() {
        while (true) {
            // 暂停或空队列时在此等待；恢复/新任务由 notifyAll 唤醒
            val key = takeNext() ?: continue
            // 加固：单个条目的任何异常只标记 FAILED，绝不带死拉取线程
            // （否则队列里一个坏文件会让后面所有缩略图永远停在加载中）
            try {
                states[key.token] = ThumbState.LOADING
                val h = host ?: com.bi2qfa.sonyconnect.core.ConnectionCenter.host
                val bytes = if (h == null) null else ObjectRepository.fetchVirtualThumb(h, key.path)
                val bmp = bytes?.let { b -> runCatching { decodeSmall(b) }.getOrNull() }
                if (bmp != null) {
                    memoryCache.put(key.token, bmp)
                    runCatching {
                        smallDiskFile(key).writeBytes(bytes)
                        smallDiskFile(key).parentFile?.let { prune(it, KEEP_THUMBS, MAX_THUMBS_BYTES) }
                    }
                    states[key.token] = ThumbState.READY
                } else {
                    states[key.token] = ThumbState.FAILED
                }
            } catch (t: Throwable) {
                android.util.Log.w("ThumbStore", "thumb fetch failed: ${key.path}", t)
                runCatching { states[key.token] = ThumbState.FAILED }
            }
            synchronized(lock) {
                if (batchDone < batchTotal) batchDone++
            }
        }
    }

    private fun takeNext(): ObjectCacheKey? {
        synchronized(lock) {
            while (true) {
                if (paused) {
                    runCatching { lock.wait() }
                    continue
                }
                val next = queue.removeFirstOrNull()
                if (next != null) {
                    queuedSet.remove(next.token)
                    return next
                }
                runCatching { lock.wait() }
            }
        }
    }

    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= 320 && h / 2 >= 320) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return decodeOriented(bytes, sample)
    }

    /**
     * 解码并按 **EXIF 方向**转正（用户要求：竖屏照片在缩略图与预览里都正着显示）。
     *
     * 相机生成的缩略图 / 预览图是**传感器横向**的：竖拍的图它也按横着画，
     * 只在 EXIF 的 Orientation 标记里写"该转 90°/270°"。不解码这一标记，
     * 竖拍照片在界面上就是横躺的。
     */
    private fun decodeOriented(bytes: ByteArray, sample: Int = 1): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return applyExifRotation(bmp, bytes)
    }

    private fun decodeFileOriented(f: File): Bitmap? {
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        return runCatching {
            applyExifRotation(bmp, f.readBytes())
        }.getOrDefault(bmp)
    }

    /** 按 EXIF Orientation 旋转/镜像位图；无需变换时原样返回。 */
    private fun applyExifRotation(bmp: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                )
        }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
        val m = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                m.setScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                m.setScale(1f, -1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f); m.setScale(-1f, 1f)
            }
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f); m.setScale(-1f, 1f)
            }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return if (out != bmp) out else bmp
    }
}
