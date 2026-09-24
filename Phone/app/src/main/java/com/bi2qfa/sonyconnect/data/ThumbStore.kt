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
import com.bi2qfa.sonyconnect.ptpip.PtpCodec
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缩略图 / 预览管线。
 *
 * 相机端预取（OP_THUMB_QUEUE_BEGIN）只是热身；真正的数据通路是手机端按需经
 * `OP_GET_OBJECT` 拉取内嵌 JPEG：列表用 KIND_THUMB（160x120 ~6KB），
 * 点开全屏用 KIND_PREVIEW（1616x1080 ~0.4MB）。两层缓存：内存 Lru + 磁盘
 * （键 = 相机GUID+path+size+mtime 的 MD5，见 {@link ObjectCacheKey}）。
 *
 * 文件传输开始时 paused=true（DownloadService 调 pauseForTransfer），拉取
 * 线程挂起等待；传输结束自动恢复（resumeAfterTransfer）。进度（done/total）
 * 供顶栏芯片显示。
 *
 * ★ 2.6 新增两块（用户定版，见 PLAN-AutoPreview-Cache.md）：
 * <ul>
 *   <li><b>自动传输预览图</b>：设置开启后，连接完成的预热名单会进
 *       [startAutoFetch] 的独立队列（[autoFetchLoop] 线程），按"文件周期"推进：
 *       一个周期内小图与大预览**并发**拉取，两者都收场才进入下一个文件；
 *       用户点开某张时自动队列在周期边界让位（[userOpenAt]）。</li>
 *   <li><b>持久缓存可配置</b>：字节上限改为 [SettingsRepo.cacheLimitBytes]
 *       （设置页可调），两目录 + 所有相机子目录**合并**按 LRU 修剪（[pruneAll]）；
 *       所有落盘走原子写（[atomicWrite]）；解码失败的缓存文件删掉回源。</li>
 * </ul>
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

    /**
     * 小图磁盘缓存的**条数**闸（每台相机一份）。小图约 6 KB，600 张约 4 MB。
     *
     * <p>2.6 起字节闸不再按目录各算，而是**两目录合并**受
     * [SettingsRepo.cacheLimitBytes] 约束（见 [pruneAll]）；条数闸保留，
     * 只为挡住"一堆极小文件"的 inode 开销。
     */
    private const val KEEP_THUMBS = 600

    /**
     * 预览磁盘缓存的**条数**闸（每台相机一份）。
     *
     * <p>★ 2.6 从 60 放大到 2000：自动传输开启后会把整个目录（几百张）都拉下来，
     * 60 张的闸会变成"滑动窗口"——传到第 61 张开始边拉边删，用户回看前面的
     * 又没了，自动传输等于白做。现在条数闸只防 inode 异常，真正的约束是
     * 字节总闸（设置页可调）。
     */
    private const val KEEP_PREVIEWS = 2000

    /**
     * 自动传输的**让位窗口**：用户点开大预览后的这段时间里，自动队列不发起新请求。
     *
     * <p>为什么需要它：所有网络拉取（点开、自动、小图队列）在 ObjectRepository 里
     * 共用**单线程** xferExecutor 排队 —— 不做让位的话，用户点开一张没缓存的图，
     * 要排在自动传输当前那张（1-2 秒）后面。窗口取 5 秒：够覆盖"翻页看下一张"
     * 的间隔，又不会让自动传输被偶尔点一下永久卡住。
     */
    private const val YIELD_WAIT_MS = 5_000L

    /**
     * 写盘路径的联合修剪节流阈值（字节）：累计写入达到这个量才真正遍历一遍
     * 目录做 LRU 修剪（见 [noteWritten]）。取 32 MB —— 相对最小档位 256 MB
     * 是 1/8 的粒度，短暂超额无感，而目录遍历次数降到 1/60。
     */
    private const val PRUNE_EVERY_BYTES = 32L * 1024 * 1024

    /**
     * 小图通道**同时允许的批数**（2.7.0 批量流式后 = 1）。
     *
     * ★ 并发不再是手段：延迟已由“批量 + 单流闸”解决（每批 8 张摊薄固定开销），
     * 而**多条流在 2.4GHz 弱电台上会互相争抢** —— 实测 3 条并发时单批速率被压到
     * 318KB/s、聚合反降。故两个通道统一为 **1 批在飞**，跨通道由 [bulkGate] 互斥。
     */
    private const val SMALL_CONCURRENCY = 1

    /**
     * 自动通道**同时允许的批数**（2.7.0 批量流式后 = 1）。
     *
     * 同上：批量已把固定开销摊销，真正要保的是**单流**（多流争抢实测更慢）。
     * 批内不会空转：相机端的向前看解析把下一项的解析藏在发送背后。
     */
    private const val AUTO_CONCURRENCY = 1

    /**
     * 自动通道**批大小**（2.7.0 批量流式）：一次把 K 个文件的小图+预览（≤2K 项）
     * 在**一条数据连接**上取回。
     *
     * <p>为什么批量：实测每对象的固定成本 = 控制往返 + TCP 建连/慢启动 + DATA_OPEN
     * 往返，把 423KB 的预览切碎；且 3~4 条并发流在 2.4GHz 弱电台上互相争抢，
     * 聚合反而**低于**单流（25MB 单流实测 2.23MB/s）。批量把固定开销摊销、
     * 让数据面回到单流。**回退开关**：改 1 = 逐文件老路径。
     */
    private const val AUTO_BATCH_K = 8

    /**
     * 小图通道**批大小**（2.7.0 批量流式）：一次在一连接上取 K 张小图。
     * 小图 6~10KB，串行时几乎全是握手成本；批量后小图通道对空口的抢占也大幅收敛。
     * **回退开关**：改 1 = 逐张老路径。
     */
    private const val THUMB_BATCH_K = 8

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

    // ===== 2.6 自动传输预览图（见类注释） =====

    /** 自动队列：连接后按预热名单顺序推进，每个文件一个周期（小图+大预览并发，都完成才下一张）。 */
    private val autoQueue = ArrayDeque<ObjectCacheKey>()
    private val autoQueuedSet = HashSet<String>()

    /**
     * 自动队列的"代次"：每次 [startAutoFetch]/[stopAutoFetch]/[reset] 递增。
     *
     * <p>线程在取到条目时记住当时的代次；让位等待中被唤醒后发现代次变了，
     * 说明用户关了开关或重连换了名单 —— 本条作废直接跳过。
     * （不能用"队列是否为空"判断：正常处理最后一条时队列本来就是空的。）
     */
    @Volatile
    private var autoGen = 0

    /** 距上次联合修剪的累计写入量（字节，见 [noteWritten]）。写盘路径多线程访问。 */
    private val writtenSincePrune = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 自动传输的**并发下载池**：一个周期里小图与大预览各占一条线程，
     * 各自开自己的数据连接（相机端每连接一个服务线程，真并行）。
     *
     * <p>为什么池里恰好 2 条：周期内最多两个任务（小图+预览）；池大了也只是
     * 排给同一周期的任务，不会更快。守护线程，进程退出不拖。
     */
    /**
     * **全局单流闸**（2.7.0 批量流式）：所有批量下载（自动通道 + 小图通道）都从这里过，
     * 同一时刻**只有一条数据连接**在搬 —— 这就是“回到单流”的实现。
     *
     * 实测：25MB 单流 2.23MB/s，而 3~4 条并发流聚合反而掉到 ~0.9MB/s（弱电台上
     * 多流互相争抢/重传）。批量 + 单流闸让链路跑在单流最优点上。
     */
    private val bulkGate = java.util.concurrent.Semaphore(1)

    /** 待落盘项（[batchWriteLoop] 消费）。 */
    private class PendingWrite(val key: ObjectCacheKey, val kind: Int, val bytes: ByteArray)

    /**
     * **落盘队列**（2.7.0）：收包回调只把字节排进来就立刻回去收下一项 ——
     * 实测收包节奏正好卡在 ~200ms/项（与项大小无关），就是"收包时停下写 460KB +
     * listFiles 修剪目录"把 TCP 流反压住了。落盘交给 [batchWriteLoop] 后，
     * "收"与"写"流水进行。
     */
    private val writeQueue = java.util.concurrent.LinkedBlockingQueue<PendingWrite>()

    /**
     * **落盘线程**（2.7.0）：逐项做原来的后处理 —— 小图：解码 + 内存缓存 + 写盘 + READY；
     * 预览：拆捆绑包 + 写 JPEG + 写 EXIF sidecar。异常只记日志，绝不带死线程。
     */
    private fun batchWriteLoop() {
        while (true) {
            val w = runCatching { writeQueue.take() }.getOrNull() ?: continue
            try {
                if (w.kind == PtpCodec.KIND_THUMB) {
                    val bmp = runCatching { decodeSmallKeyed(w.bytes, w.key) }.getOrNull()
                    if (bmp == null) {
                        states[w.key.token] = ThumbState.FAILED
                        continue
                    }
                    memoryCache.put(w.key.token, bmp)
                    runCatching {
                        atomicWrite(smallDiskFile(w.key), w.bytes)
                        smallDiskFile(w.key).parentFile?.let { pruneDirCount(it, KEEP_THUMBS) }
                        noteWritten(w.bytes.size)
                    }
                    states[w.key.token] = ThumbState.READY
                } else {
                    runCatching {
                        // 捆绑包拆开：JPEG 进预览缓存，EXIF JSON 进 sidecar
                        //（sidecar 一到 writeExifSidecar 就发方向信号 → 小图跟着转正）
                        val (jpg, json) = splitPreviewBundle(w.bytes)
                        atomicWrite(previewDiskFile(w.key), jpg)
                        writeExifSidecar(w.key, json)
                        previewDiskFile(w.key).parentFile?.let { pruneDirCount(it, KEEP_PREVIEWS) }
                        noteWritten(jpg.size)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("ThumbStore", "batch write failed", t)
            }
        }
    }

    /** 周期调度池（2.7.0）：批数由 [AUTO_CONCURRENCY] 限，跨通道再由 [bulkGate] 互斥。 */

    /** 周期调度池（2.7.0）：N 个文件周期同时在飞，各自等自己的两个下载收场。 */
    private val autoCyclePool = java.util.concurrent.Executors.newFixedThreadPool(AUTO_CONCURRENCY) { r ->
        Thread(r, "AutoPreviewCycle").apply { isDaemon = true }
    }

    /** 小图下载池（2.7.0 并发化，见 [fetchLoop]）。 */
    private val smallDlPool = java.util.concurrent.Executors.newFixedThreadPool(SMALL_CONCURRENCY) { r ->
        Thread(r, "ThumbFetchDl").apply { isDaemon = true }
    }

    /** 自动传输进度（控制中心面板显示）。 */
    var autoDone by mutableStateOf(0)
        private set
    var autoTotal by mutableStateOf(0)
        private set

    /**
     * 用户最近一次**点开大预览**的时刻（elapsedRealtime）。
     *
     * <p>自动队列在每张开拉前检查：`now - userOpenAt < YIELD_WAIT_MS` 就让位等待。
     * 点开行为即"用户注意力信号"，不区分这次点开是否命中缓存 —— 命中也说明
     * 用户正在看图，自动传输不该再抢单线程的网络队列。
     */
    @Volatile
    private var userOpenAt = 0L

    /**
     * 最近一次连接时提交的**完整预热名单**（[rememberWarmup] 存，断开清空）。
     *
     * <p>用途：**"以当前状态重启传输流程"**（用户定版）—— 传输过程中开关
     * 自动传输、或清除缓存之后，要能立刻按当前模式重新跑一遍，而不必等重连。
     */
    @Volatile
    private var lastWarmupKeys: List<ObjectCacheKey> = emptyList()

    /** 连接预热时登记名单（[com.bi2qfa.sonyconnect.core.ConnectionCenter.startThumbBatch]）。 */
    fun rememberWarmup(keys: List<ObjectCacheKey>) {
        lastWarmupKeys = keys
    }

    /**
     * **以当前状态重启传输流程**（用户定版）：
     * <ul>
     *   <li>自动传输**开** + 已连接 + 有名单 → 重摆自动队列（[startAutoFetch] 整体替换；
     *       磁盘已缓存的条目在各自周期里瞬间跳过，只补缺的）；</li>
     *   <li>自动传输**关** → 清掉自动队列，只把**还缺的小图**重新入队（[request] 会跳过
     *       内存 READY 的；配合磁盘命中检查，清了缓存就真拉、没清就是秒过）；</li>
     *   <li>断开 / 无名单 → 等价 [stopAutoFetch]（清空、进度归零）。</li>
     * </ul>
     *
     * 调用点：设置里开关自动传输、设置里"清除缓存"（见 SettingsRepo / SettingsScreen）。
     * 周期原子性：正在跑的那个周期按既有纪律传到完，随后队列按新状态继续。
     */
    fun restartAutoFetch() {
        val keys = lastWarmupKeys
        val connected = !(host ?: com.bi2qfa.sonyconnect.core.ConnectionCenter.host).isNullOrBlank()
        if (keys.isEmpty() || !connected) {
            stopAutoFetch()
            return
        }
        if (SettingsRepo.autoPreviewFetch) {
            startAutoFetch(keys)
        } else {
            stopAutoFetch()
            // 只重摆"还缺的"小图：进度计数与入队对齐（都只看内存 READY 判定；
            // 真缺的会走磁盘命中检查 → 网络拉取）
            val pending = keys.filter { states[it.token] != ThumbState.READY }
            synchronized(lock) {
                batchTotal = pending.size
                batchDone = 0
            }
            request(pending)
        }
    }

    /**
     * 当前缩略图归属的相机设备码。磁盘缓存按它分目录 ——
     * 缓存键原来只有路径，而 `/DCIM/100MSDCF/DSC00001.ARW` 在任何相机上都一样，
     * 换相机（或同一台相机换 SD 卡）后就会直接命中旧缓存、**显示出别的相机的照片**。
     */
    @Volatile
    private var camId: String = ""

    /**
     * 小图内存缓存（24MB）。
     *
     * <p>★ 2.6：预热名单不再截断（全量名单可能上千张），小图位图解码后总量可能超过
     * 本容量（160×120 ARGB_8888 ≈ 75 KB/张，361 张 ≈ 27 MB）—— 超出部分由 LruCache
     * 自动淘汰，miss 时 [smallThumb] 从磁盘缓存读并回填。功能与内存都安全，属预期行为，
     * **不需要**跟着调大（调大只会挤占其余内存，淘汰本就是它的职责）。
     */
    private val memoryCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** token → 已学到的方向（见 [orientationOf]）。 */
    private val orientCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * "方向已学到"的信号（2.7.0 需求 7）：大预览 sidecar 落盘后 +1。
     *
     * <p>方向是**解码时**施加的（小图字节里没有 EXIF），而 `memoryCache` 不是可观察状态 ——
     * FilesScreen 的两处缩略图调用点读一下本值订阅它，epoch 一变那一行重组、
     * [smallThumb] 按新方向重解重画。
     */
    var orientationEpoch by androidx.compose.runtime.mutableIntStateOf(0)
        private set

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
            // 自动传输线程**常驻**（daemon，空队列时挂在 lock 上零开销）：
            // 是否真正干活由 ConnectionCenter 决定要不要调 startAutoFetch —— 
            // 开关关闭时队列恒空，线程只是睡着。
            Thread({
                autoFetchLoop()
            }, "AutoPreviewFetch").apply {
                isDaemon = true
                start()
            }
            Thread({
                batchWriteLoop()
            }, "BatchWrite").apply {
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

    /**
     * 列表按需请求：必须带远端对象身份，防止同名新文件命中旧缓存。
     *
     * <p>★ 2.7.0：入队前按 path 排序 —— 与相机端的**微批顺序读**同构
     * （同目录文件名连续，DSC02310、311、312…；SD 顺序读远快于随机读），
     * 相机预取器与本次请求两侧都吃到顺序读红利。
     */
    fun request(keys: List<ObjectCacheKey>) {
        syncCamId()
        val ordered = keys.sortedBy { it.path }
        synchronized(lock) {
            host = com.bi2qfa.sonyconnect.core.ConnectionCenter.host
            var added = false
            for (key in ordered) {
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
            // ★ 2.7.0 需求 7：小图字节里没有 EXIF，方向来自大预览 sidecar
            val bmp = runCatching { decodeFileOriented(f) }.getOrNull()
                ?.let { rotateByOrientation(it, orientationOf(key)) }
            if (bmp != null) {
                memoryCache.put(key.token, bmp)
                return bmp.asImageBitmap()
            }
            // 解码失败 = 这个缓存文件坏了（半截/被外部改坏）。删掉它，让调用方
            // 的下一轮 request 走网络回源；留着的话每次点开都先白试一遍解码。
            runCatching { f.delete() }
        }
        return null
    }

    /** 全屏大预览：磁盘缓存 → PTP/IP 拉取（阻塞，须在 IO 线程） */
    fun fetchPreviewBlocking(key: ObjectCacheKey): ImageBitmap? {
        // 用户点开的"注意力信号"（自动队列让位用）：入口就记，不区分命不命中。
        userOpenAt = android.os.SystemClock.elapsedRealtime()
        syncCamId()
        val f = previewDiskFile(key)
        if (f.isFile) {
            val bmp = runCatching { decodeFileOriented(f) }.getOrNull()
            // ★ 2.7.0 需求 6：按 sidecar 的方向自动转正（缓存 jpg 自身没有 EXIF）
            if (bmp != null) return rotateByOrientation(bmp, orientationOf(key)).asImageBitmap()
            runCatching { f.delete() }     // 坏缓存文件：删掉回源（见 smallThumb 同款说明）
        }
        val h = host ?: com.bi2qfa.sonyconnect.core.ConnectionCenter.host ?: return null
        val bytes = ObjectRepository.fetchVirtualPreview(h, key.path) ?: return null
        // ★ 2.7.0：预览是捆绑包（JPEG + EXIF JSON）——拆开后各写各的，解码只用 JPEG 段
        val (jpg, json) = splitPreviewBundle(bytes)
        runCatching {
            atomicWrite(f, jpg)
            writeExifSidecar(key, json)
            noteWritten(jpg.size)
        }
        val bmp = decodeOriented(jpg) ?: return null
        return rotateByOrientation(bmp, orientationOf(key)).asImageBitmap()
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

    private fun smallDiskFile(key: ObjectCacheKey) =
        File(subDir(smallDiskDir, key.cameraGuid), cacheKey(key.token) + ".jpg")

    private fun previewDiskFile(key: ObjectCacheKey) =
        File(subDir(previewDiskDir, key.cameraGuid), cacheKey(key.token) + ".jpg")

    // ===== 大预览捆绑包 + EXIF sidecar（2.7.0，用户定版：EXIF 随大预览一并传输/缓存） =====

    /**
     * 拆相机端下发的预览捆绑包 `[4B 大端 JSON 长][JSON][JPEG]`（见相机端
     * `PtpCameraHandler.bundlePreview`）。
     *
     * <p>兜底：解不出合法结构（旧格式纯 JPEG、数据异常）→ 整包当纯 JPEG、exif=null。
     * 纯 JPEG 首字节 0xFF 解出的长度必然越界，所以"没有捆绑"自动落进兜底，不会坏图。
     */
    private fun splitPreviewBundle(b: ByteArray): Pair<ByteArray, String?> {
        if (b.size > 4) {
            val n = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                    ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            if (n in 0..(b.size - 4)) {
                val jpg = b.copyOfRange(4 + n, b.size)
                if (jpg.size > 2 && jpg[0] == 0xFF.toByte() && jpg[1] == 0xD8.toByte()) {
                    return jpg to (if (n > 0) String(b, 4, n, Charsets.UTF_8) else null)
                }
            }
        }
        return b to null
    }

    /** EXIF sidecar：与预览 jpg 同目录同键（`<md5>.json`），同刻原子写 → LRU 成对淘汰。 */
    private fun exifDiskFile(key: ObjectCacheKey) =
        File(subDir(previewDiskDir, key.cameraGuid), cacheKey(key.token) + ".json")

    /** 弹窗用：预览 sidecar 的原始 JSON 文本（没拉过预览 / 无 EXIF → null）。 */
    fun readExifJson(key: ObjectCacheKey): String? =
        runCatching { exifDiskFile(key).takeIf { it.isFile }?.readText() }.getOrNull()

    /** 从 sidecar JSON 里取方向（标准 EXIF 1..8）；未知 → 0。 */
    private fun orientationIn(json: String?): Int = runCatching {
        if (json.isNullOrBlank()) 0 else org.json.JSONObject(json).optInt("o", 0)
    }.getOrDefault(0)

    /**
     * 某张图的方向（标准 EXIF 1..8；未知 0）。
     *
     * <p>内存 [orientCache] 挡热路径：小图每次解码都要问方向（需求 7），已学到方向的
     * 不再重复读/解析 sidecar。**只缓存非 0 值** —— 没 sidecar 时不缓存 0，
     * 否则大预览稍后落地后就永远读不到新方向了。
     */
    fun orientationOf(key: ObjectCacheKey): Int {
        orientCache[key.token]?.let { return it }
        val o = orientationIn(readExifJson(key))
        if (o != 0) orientCache[key.token] = o
        return o
    }

    /** 学到新方向：更新缓存 + 发信号（需求 7：外显小图跟着大预览一起转正）。 */
    private fun learnOrientation(key: ObjectCacheKey, o: Int) {
        if (o == 0) return
        if (orientCache.put(key.token, o) != o) {
            // 小图内存里那份可能是旧方向的：evict 让它按新方向重解；
            // epoch 让 UI（FilesScreen 两个缩略图调用点）重画。
            memoryCache.remove(key.token)
            orientationEpoch++
        }
    }

    /** 原子写 sidecar + 学方向 + 节流记账（预览两条写盘路径共用）。 */
    private fun writeExifSidecar(key: ObjectCacheKey, json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            atomicWrite(exifDiskFile(key), json.toByteArray(Charsets.UTF_8))
            learnOrientation(key, orientationIn(json))
            noteWritten(json.length)
        }
    }

    private fun cacheKey(value: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * 修剪**单个目录**的条数闸（防一堆极小文件撑爆 inode）。
     *
     * <p>用 `lastModified` 当"最近使用"的近似：读取缓存时我们不去 touch 文件
     * （那会给每次看图加一次写盘），所以它更接近"最近写入"。对本场景够用 ——
     * 缩略图是"看过就会留一阵"，按写入时间淘汰与按访问时间淘汰的差别很小，
     * 而省下的写盘开销是每张图一次。
     *
     * @param keep 条数上限
     */
    private fun pruneDirCount(d: File, keep: Int) {
        // 2.7.0：EXIF sidecar（.json）不占条数（条目按"张"算），随 jpg 成对删除
        val fs = runCatching { d.listFiles()?.filter { !it.name.endsWith(".json") } }.getOrNull() ?: return
        if (fs.size <= keep) return
        val ordered = runCatching { fs.sortedBy { it.lastModified() } }.getOrNull() ?: return
        val toDelete = fs.size - keep
        if (toDelete <= 0) return
        runCatching {
            ordered.take(toDelete).forEach { f ->
                f.delete()
                if (f.name.endsWith(".jpg")) {
                    File(f.parentFile, f.nameWithoutExtension + ".json").delete()
                }
            }
        }
    }

    /**
     * **联合字节闸**（2.6）：小图目录 + 预览目录、所有相机子目录**合并成一个
     * LRU 池**，总占用超过 [SettingsRepo.cacheLimitBytes] 就从最旧开始删。
     *
     * <p>★ 为什么是联合而不是各算各的：原来小图 24MB / 预览 96MB 各自为政，
     * 换一台相机（新子目录）时上限**无声翻倍**；总量也不受控（120MB 起步、
     * 每多一台相机再加一份）。合并成一个池之后，"这台手机最多为图像缓存花多少
     * 空间"才真正等于设置里那个数。
     *
     * <p>顺带做两件卫生：清掉原子写的游离 `.tmp`；空相机子目录删掉。
     *
     * <p>★ 调用要过 [pruneAllThrottled]（写盘路径）—— 全量遍历两个目录的
     * `listFiles` 是几百次 syscall，自动传输每写一张就做一次的话，几百张要白付
     * 几十秒 IO。只有"上限变更/启动"这类低频路径才直接调本函数。
     */
    private fun pruneAll() {
        val limit = SettingsRepo.cacheLimitBytes
        val all = ArrayList<File>(256)
        for (base in arrayOf(smallDiskDir, previewDiskDir)) {
            val subs = runCatching { base.listFiles() }.getOrNull() ?: continue
            for (sub in subs) {
                if (sub.isDirectory) {
                    val fs = runCatching { sub.listFiles() }.getOrNull() ?: continue
                    for (f in fs) {
                        if (f.name.endsWith(".tmp")) {
                            runCatching { f.delete() }      // 原子写的残留（进程被杀）
                        } else if (f.name.endsWith(".json")) {
                            // 2.7.0：EXIF sidecar —— 只在其 jpg 还在时保留（孤儿一并清掉），
                            // 字节也计入总闸（每张几百 B，可忽略）
                            if (File(f.parentFile, f.nameWithoutExtension + ".jpg").isFile) {
                                all.add(f)
                            } else {
                                runCatching { f.delete() }
                            }
                        } else if (f.isFile) {
                            all.add(f)
                        }
                    }
                    if (fs.isEmpty()) runCatching { sub.delete() }   // 空相机目录
                } else if (sub.isFile) {
                    all.add(sub)
                }
            }
        }
        if (all.isEmpty()) return
        var total = all.sumOf { it.length() }
        if (total <= limit) return
        val ordered = runCatching { all.sortedBy { it.lastModified() } }.getOrNull() ?: return
        for (f in ordered) {
            if (total <= limit) break
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                total -= len
                // 2.7.0：jpg 被淘汰时它的 EXIF sidecar 一并删（成对进出）
                if (f.name.endsWith(".jpg")) {
                    runCatching { File(f.parentFile, f.nameWithoutExtension + ".json").delete() }
                }
            }
        }
    }

    /**
     * 写盘路径用的**节流记账**：累计写入超过 [PRUNE_EVERY_BYTES] 才真跑一次
     * [pruneAll]。
     *
     * <p>节流的安全性：闸是"总量上限"，滞后一点修剪只意味着**短暂超额**（最多
     * 多存一个阈值的量），下一次必然收敛；而换来的是自动传输全程只做几次
     * 目录遍历而不是几百次。小图单张 6 KB、预览单张约 500 KB —— 攒到 32 MB
     * 大约要 60 张预览，即"自动传输平均每 60 张才修剪一次"。
     * 上限变更（[onLimitChanged]）与启动收敛不走节流，直接全量跑。
     */
    private fun noteWritten(bytes: Int) {
        if (writtenSincePrune.addAndGet(bytes.toLong()) >= PRUNE_EVERY_BYTES) {
            writtenSincePrune.set(0)
            pruneAll()
        }
    }

    /** 设置页调小上限后立刻收敛（由 [SettingsRepo.updateCacheLimitBytes] 触发）。 */
    fun onLimitChanged() {
        // 删除是幂等的（不存在就跳过），所以不与拉取线程做重量级互斥；
        // 放到后台线程做，避免设置页主线程被几百个文件的 listFiles 卡住。
        Thread({ runCatching { pruneAll() } }, "CachePrune").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * **原子写**：先写同目录 `.tmp` 再改名。
     *
     * <p>直接 `writeBytes` 的话，进程在写一半时被杀/断电会留下一个**永久半截文件**，
     * 而缓存键（path+size+mtime）仍指向它 —— 下次点开解码失败，还得先白试一遍。
     * 改名在同一文件系统内是原子的，要么旧文件要么新文件，不存在中间态。
     */
    private fun atomicWrite(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                // renameTo 在目标已存在时个别实现会失败（Android 上通常覆盖成功）：
                // 删掉目标再来一次；再失败就把临时文件也清掉，不占地。
                f.delete()
                if (!tmp.renameTo(f)) tmp.delete()
            }
        }
    }

    /** 缓存占用（小图 + 预览，字节），设置页显示用。 */
    fun cacheSizeBytes(): Long {
        fun dirSize(d: File): Long =
            runCatching { d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        return dirSize(smallDiskDir) + dirSize(previewDiskDir)
    }

    /** 清空全部图像缓存（设置页"清除缓存"）：内存 + 磁盘，队列状态复位。 */
    fun clearCache() {
        synchronized(lock) {
            queue.clear(); queuedSet.clear()
            autoQueue.clear(); autoQueuedSet.clear()
            batchDone = 0; batchTotal = 0
            autoDone = 0; autoTotal = 0
            autoGen++
        }
        states.clear()
        memoryCache.evictAll()
        orientCache.clear()
        orientationEpoch++   // 方向随缓存归零：发信号让 UI 重画（未转正的原始朝向）
        runCatching { smallDiskDir.deleteRecursively() }
        runCatching { previewDiskDir.deleteRecursively() }
        smallDiskDir.mkdirs()
        previewDiskDir.mkdirs()
    }

    fun reset() {
        synchronized(lock) {
            queue.clear()
            queuedSet.clear()
            autoQueue.clear()
            autoQueuedSet.clear()
            batchDone = 0
            batchTotal = 0
            autoDone = 0
            autoTotal = 0
            autoGen++          // 断开重连：在途的自动条目作废（见 autoFetchLoop）
            paused = false
            lock.notifyAll()
        }
        states.clear()
        memoryCache.evictAll()
        camId = ""
        lastWarmupKeys = emptyList()   // 断开：名单作废，restartAutoFetch 会走"清空"分支
    }

    // ===== 拉取线程 =====

    private fun fetchLoop() {
        // 启动时先做一次全量收敛：上次退出后上限可能被调小过，先把超额的清掉。
        runCatching { pruneAll() }
        // ★ 2.7.0 批量流式：**成批取**（[THUMB_BATCH_K] 张/批，一条数据连接一次取完）。
        //   只有本线程做"入飞"，inFlight.size 判定无竞争；完成由池线程自己摘除。
        val inFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<*>>()
        while (true) {
            if (inFlight.size >= SMALL_CONCURRENCY) {
                Thread.sleep(10)                       // 在飞满：等一个收尾腾位
                continue
            }
            val batch = ArrayList<ObjectCacheKey>(THUMB_BATCH_K)
            while (batch.size < THUMB_BATCH_K) {
                val k = pollNext() ?: break
                states[k.token] = ThumbState.LOADING
                batch.add(k)
            }
            if (batch.isEmpty()) {
                if (inFlight.isEmpty()) idleWait() else Thread.sleep(10)
                continue
            }
            val id = "b" + batch[0].token
            inFlight[id] = smallDlPool.submit {
                try {
                    processThumbBatch(batch)
                } finally {
                    inFlight.remove(id)
                    synchronized(lock) {
                        batchDone = (batchDone + batch.size).coerceAtMost(batchTotal)
                    }
                }
            }
        }
    }

    /**
     * 取一批小图（在 [smallDlPool] 线程上跑，2.7.0）：
     * 逐张先查磁盘（命中即 READY）→ 缺口拼成**一次批量请求** → 一条连接取回 →
     * 逐张解码/写盘/收敛状态。加固：任何异常只标 FAILED，绝不带死线程。
     */
    private fun processThumbBatch(batch: List<ObjectCacheKey>) {
        try {
            // ★ 磁盘命中检查（2.6 修复，原样保留）：重启/重连后 states 为空、
            //   request() 会把整份名单重新入队 —— 没有这道检查时磁盘里已有的缓存
            //   会被原样重拉重写。用解码而不只是 isFile：解不开=坏文件，落回网络自愈。
            val need = ArrayList<ObjectCacheKey>(batch.size)
            for (key in batch) {
                val cached = runCatching { decodeFileOriented(smallDiskFile(key)) }.getOrNull()
                    ?.let { rotateByOrientation(it, orientationOf(key)) }
                if (cached != null) {
                    memoryCache.put(key.token, cached)
                    states[key.token] = ThumbState.READY
                } else {
                    need.add(key)
                }
            }
            if (need.isEmpty()) return
            val h = host ?: com.bi2qfa.sonyconnect.core.ConnectionCenter.host
            if (h == null) {
                for (k in need) states[k.token] = ThumbState.FAILED
                return
            }
            val items = need.map { it.path to PtpCodec.KIND_THUMB }
            val t = ObjectRepository.openBatchTicket(h, items)
            if (t == null) {
                for (k in need) states[k.token] = ThumbState.FAILED
                return
            }
            val labels = need.map { it.path }
            bulkGate.acquireUninterruptibly()
            try {
            ObjectRepository.downloadBatch(t, need.size, labels = labels) { i, bytes ->
                val key = need.getOrNull(i) ?: return@downloadBatch
                if (bytes == null) {
                    states[key.token] = ThumbState.FAILED
                    return@downloadBatch
                }
                // ★ 2.7.0：解码/写盘挪到 [batchWriteLoop]，收包线程立刻回去收下一项
                writeQueue.put(PendingWrite(key, PtpCodec.KIND_THUMB, bytes))
            }
            } finally {
                bulkGate.release()
            }
        } catch (t: Throwable) {
            android.util.Log.w("ThumbStore", "thumb batch failed", t)
            for (k in batch) {
                if (states[k.token] == ThumbState.LOADING) {
                    runCatching { states[k.token] = ThumbState.FAILED }
                }
            }
        }
    }

    /** 非阻塞取一条小图任务：队列空或暂停中返回 null（等待逻辑在 [fetchLoop] 调度器里）。 */
    private fun pollNext(): ObjectCacheKey? = synchronized(lock) {
        if (paused) return@synchronized null
        val next = queue.removeFirstOrNull() ?: return@synchronized null
        queuedSet.remove(next.token)
        next
    }

    /**
     * 调度器无事可做时的等待（队列空 + 无在飞，或暂停中）：由
     * `request()/resume()/reset()` 的 `notifyAll` 唤醒；1 秒超时是兜底 ——
     * 万一哪条唤醒路径漏了 notify，也不会永久睡死。
     */
    private fun idleWait() {
        synchronized(lock) {
            runCatching { lock.wait(1000) }
        }
    }

    // ===== 自动传输预览图（2.6，独立线程，见类注释） =====

    /**
     * 连接预热时调用（[com.bi2qfa.sonyconnect.core.ConnectionCenter.startThumbBatch]）：
     * 整体替换自动队列。
     *
     * <p>这里**不做**已缓存过滤：`autoTotal` 要如实等于列表图片数（进度"N 张全部
     * 就绪"才是用户能理解的语义），已缓存的条目在 [autoFetchLoop] 里靠磁盘存在性
     * 判定"瞬间跳过"（两次 `isFile`，可忽略）。
     */
    fun startAutoFetch(keys: List<ObjectCacheKey>) {
        // 调用点是"连接/配对成功"（ConnectionCenter.startThumbBatch），那里也判过一次开关；
        // 这里再判一次是堵住"开关恰好在连接那一刻被关掉"的毫秒级竞争。
        if (!SettingsRepo.autoPreviewFetch) return
        syncCamId()
        synchronized(lock) {
            host = com.bi2qfa.sonyconnect.core.ConnectionCenter.host
            autoQueue.clear()
            autoQueuedSet.clear()
            for (key in keys.sortedBy { it.path }) {
                if (autoQueuedSet.add(key.token)) autoQueue.add(key)
            }
            autoTotal = keys.size
            autoDone = 0
            autoGen++
            lock.notifyAll()
        }
    }

    /** 关掉开关时调用：丢弃队列（正在拉的那张让它拉完，不用中断）。 */
    fun stopAutoFetch() {
        synchronized(lock) {
            autoQueue.clear()
            autoQueuedSet.clear()
            autoTotal = 0
            autoDone = 0
            autoGen++
            lock.notifyAll()
        }
    }

    /** 非阻塞取一条自动任务：队列空或暂停中返回 null（等待逻辑在 [autoFetchLoop] 调度器里）。 */
    private fun autoPollNext(): ObjectCacheKey? = synchronized(lock) {
        if (paused) return@synchronized null
        val next = autoQueue.removeFirstOrNull() ?: return@synchronized null
        autoQueuedSet.remove(next.token)
        next
    }

    /**
     * 自动传输**调度器**（2.7.0：多周期并发；用户定版"同时传多个文件周期"）。
     *
     * <p>一个文件 = 一个周期：周期内**并发**拉这张图的小图与大预览（各走一条数据连接），
     * **两者都收场（成功或失败）之后**该周期才算完成（进度 n/N 仍按周期计）。
     * 与 2.6 的唯一区别：**AUTO_CONCURRENCY 个周期可以同时在飞** —— 串行时代
     * 周期尾部的等待（换令牌、落盘、取下一个）被叠掉了。
     *
     * <p>纪律（每条都对应一个真实约束，改这里之前先读）：
     * <ul>
     *   <li><b>让位</b>：用户点开大图（[userOpenAt] 5 秒窗口）→ 调度器**不再放新周期**，
     *       在飞的 ≤N 个照常收场（"让位只在周期边界生效"的语义不变，边界从
     *       "取下一个"变成"放下一批"）。</li>
     *   <li><b>暂停</b>：[paused] 同上 —— 只挡新周期入飞。</li>
     *   <li><b>批量单流</b>（2.7.0）：每批 [AUTO_BATCH_K] 个文件的小图+预览在**一条连接**上取回；
     *       跨通道由 [bulkGate] 互斥 —— 同一时刻只有一条数据流（多流在 2.4GHz 上争抢实测更慢）。</li>
     *   <li><b>不重试</b>：一轮里每个条目只处理一次（入队即去重，见 [startAutoFetch]）——
     *       失败只写 states（列表的加载态收敛），不会在轮内重试；下一轮重新给机会。</li>
     *   <li><b>孤立</b>：任何异常都只影响这一个周期（带不死线程）。</li>
     *   <li><b>代次</b>：开关关闭/清缓存/重连（autoGen 变）→ 在飞周期不计进度、不接着跑。</li>
     * </ul>
     */
    private fun autoFetchLoop() {
        // 只有本线程做"入飞"，inFlight.size 判定无竞争；完成由周期线程自己摘除。
        val inFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Future<*>>()
        while (true) {
            if (inFlight.size >= AUTO_CONCURRENCY) {
                Thread.sleep(10)                       // 在飞满：等一个周期收尾腾位
                continue
            }
            // ===== 让位窗口：窗口内不放新周期（在飞的照常收场） =====
            val rest = YIELD_WAIT_MS - (android.os.SystemClock.elapsedRealtime() - userOpenAt)
            if (rest > 0) {
                synchronized(lock) { runCatching { lock.wait(rest) } }
                continue
            }
            // ★ 代次必须在**取任务前**快照：stopAutoFetch（开关关掉）会 gen++ 并清队列，
            //   若先取后读，这个刚取出的条目会被当成"新一轮"的任务跑下去。
            //   先读后取：期间代次一旦变了，runAutoBatch 的边界检查会立刻作废它。
            // ★ 2.7.0 批量流式：攒够 AUTO_BATCH_K 个文件（或队列取空）成一批 ——
            //   这批文件的小图+预览在**一条数据连接**上连续取回（见 [runAutoBatch]）。
            val gen = autoGen
            val batch = ArrayList<ObjectCacheKey>(AUTO_BATCH_K)
            while (batch.size < AUTO_BATCH_K) {
                val k = autoPollNext() ?: break
                batch.add(k)
            }
            if (batch.isEmpty()) {
                if (inFlight.isEmpty()) idleWait() else Thread.sleep(10)
                continue
            }
            val id = "b" + batch[0].token
            inFlight[id] = autoCyclePool.submit {
                try {
                    runAutoBatch(batch, gen)
                } finally {
                    inFlight.remove(id)
                    // 只有"本代"的条目才计进度：作废的旧条目不能给新一轮的 n/N 记数
                    if (autoGen == gen) {
                        synchronized(lock) {
                            autoDone = (autoDone + batch.size).coerceAtMost(autoTotal)
                        }
                    }
                }
            }
        }
    }

    /**
     * 跑**一批**文件周期（在 [autoCyclePool] 线程上；纪律见 [autoFetchLoop]，2.7.0 批量流式）。
     *
     * <p>批内把各文件缺的对象（小图/预览）拼成**一次批量请求**，在一条数据连接上
     * 连续取回；逐项落盘与单文件路径完全一致（小图 → 缓存 + READY；预览 → 拆捆绑包
     * + sidecar + 方向信号）。**周期语义不变**：这批文件的"该取的都取到"之后，
     * 整批一起计入进度（autoDone += 批大小）。
     */
    private fun runAutoBatch(batch: List<ObjectCacheKey>, gen: Int) {
        try {
            // 组装请求项：逐文件判磁盘命中，只把缺的放进批量请求
            val reqKeys = ArrayList<ObjectCacheKey>()
            val reqKinds = ArrayList<Int>()
            for (key in batch) {
                if (!smallDiskFile(key).isFile) {
                    reqKeys.add(key)
                    reqKinds.add(PtpCodec.KIND_THUMB)
                }
                if (!previewDiskFile(key).isFile) {
                    reqKeys.add(key)
                    reqKinds.add(PtpCodec.KIND_PREVIEW)
                }
            }
            if (reqKeys.isEmpty()) return          // 全命中：整批瞬跳（进度照计）

            // ===== 批边界：让位窗口（窗口内等待；开关被关/重连换名单则作废） =====
            while (true) {
                if (autoGen != gen) return
                val rest = YIELD_WAIT_MS - (android.os.SystemClock.elapsedRealtime() - userOpenAt)
                if (rest <= 0) break
                synchronized(lock) { runCatching { lock.wait(rest) } }
            }
            val h = host ?: com.bi2qfa.sonyconnect.core.ConnectionCenter.host ?: return

            val items = reqKeys.mapIndexed { i, k -> k.path to reqKinds[i] }
            val t = ObjectRepository.openBatchTicket(h, items) ?: return
            val labels = reqKeys.mapIndexed { i, k ->
                (if (reqKinds[i] == PtpCodec.KIND_PREVIEW) "P " else "T ") + k.path
            }
            bulkGate.acquireUninterruptibly()
            try {
            ObjectRepository.downloadBatch(t, items.size, labels = labels) { idx, bytes ->
                val key = reqKeys.getOrNull(idx) ?: return@downloadBatch
                val kind = reqKinds[idx]
                if (bytes == null) {
                    // 该项失败：小图写 FAILED 让列表加载态收敛；预览无 states 语义
                    if (kind == PtpCodec.KIND_THUMB) states[key.token] = ThumbState.FAILED
                    return@downloadBatch
                }
                // ★ 2.7.0：解码/写盘挪到 [batchWriteLoop]（见 [writeQueue] 的说明）
                writeQueue.put(PendingWrite(key, kind, bytes))
            }
            } finally {
                bulkGate.release()
            }
        } catch (t: Throwable) {
            android.util.Log.w("ThumbStore", "auto batch failed", t)
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
     * 小图解码 + 方向（2.7.0 需求 7）：缩略图字节里没有 EXIF，方向来自**大预览
     * sidecar**（同一张图拉过大预览后，"外显的小缩略图"也要跟着转正）。
     * sidecar 未就绪 → 方向 0 → 行为与旧版一致（不转）。
     */
    private fun decodeSmallKeyed(bytes: ByteArray, key: ObjectCacheKey): Bitmap? {
        val bmp = decodeSmall(bytes) ?: return null
        return rotateByOrientation(bmp, orientationOf(key))
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

    /**
     * 按**标准 EXIF orientation 值**（1..8）旋转/镜像位图；无需变换时原样返回。
     *
     * <p>数值就是 TIFF 0x0112 的原始值（= androidx ExifInterface 的 ORIENTATION_* 常量值）：
     * 3=180°、6=顺 90°、8=顺 270°、2/4=纯镜像、5/7=镜像+旋转。相机端 JSON 的 "o"
     * 与从字节里读出的 EXIF 都直接喂这里 —— **预览（需求 6）与小图（需求 7）共用这一份变换**。
     */
    private fun rotateByOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = android.graphics.Matrix()
        when (orientation) {
            6 -> m.postRotate(90f)
            3 -> m.postRotate(180f)
            8 -> m.postRotate(270f)
            2 -> m.setScale(-1f, 1f)
            4 -> m.setScale(1f, -1f)
            5 -> {
                m.postRotate(90f); m.setScale(-1f, 1f)
            }
            7 -> {
                m.postRotate(270f); m.setScale(-1f, 1f)
            }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return if (out != bmp) out else bmp
    }

    /** 按**字节里**的 EXIF Orientation 旋转/镜像（旧入口，内部转调 [rotateByOrientation]）。 */
    private fun applyExifRotation(bmp: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                )
        }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
        return rotateByOrientation(bmp, orientation)
    }
}
