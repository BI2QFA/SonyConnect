package com.bi2qfa.sonyconnect.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.core.StorageSink
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ptpip.ObjectRepository
import com.bi2qfa.sonyconnect.ptpip.PtpCodec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 传输排空服务（前台、串行）：逐文件 `OP_GET_OBJECT` 断点续传下载（独立文件端口）。
 *
 * 与缩略图管线的带宽联动（需求 5）：
 * - 批次开始：通知相机 OP_THUMB_QUEUE_PAUSE（停占带宽），记录缩略图进度后开始
 * - 批次结束：OP_THUMB_QUEUE_RESUME 自动恢复缩略图
 *
 * ★ 会话语义：本服务**不碰**相机会话。控制连接是常驻会话，生杀归 [ConnectionCenter]
 *   （心跳保活、断开收尾、退出链都在那边），这里只在批次开始/结束各发一个缩略图
 *   队列操作。旧版收尾时的 `closeAll()`（"停止/结束时关掉全部相机会话再 stopSelf"）
 *   是 FTP 时代的遗留 —— FTP 会话必须显式释放，照搬到 PTP/IP 会把用户正在用的
 *   会话一起收走：一回到文件页就"目录读取失败"，心跳连失三次后自动断开。
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "transfer"
        private const val NOTIF_ID = 42
        private const val NOTIF_ID_RESULT = 43
        private const val ACTION_START = "com.bi2qfa.sonyconnect.START"
        private const val ACTION_STOP_CURRENT = "com.bi2qfa.sonyconnect.STOP_CURRENT"

        private val runningFlag = AtomicBoolean(false)

        fun isActive(): Boolean = runningFlag.get()

        fun start(context: Context) {
            val i = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stopCurrent(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).setAction(ACTION_STOP_CURRENT)
            )
        }

        /** 点击通知直接进入软件 */
        private fun mainIntent(context: Context) = android.app.PendingIntent.getActivity(
            context, 0,
            Intent(context, com.bi2qfa.sonyconnect.ui.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @Volatile
    private var stopAll = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "文件传输", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = buildNotification("准备传输…", "", 0, 0)
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CURRENT -> {
                stopAll = true
                return START_NOT_STICKY
            }
            else -> {
                stopAll = false // 新批次开始：清除上一次"停止"残留（否则排空线程立即退出）
                if (runningFlag.compareAndSet(false, true)) {
                    acquireTransferLocks()
                    Thread({ drainSafely() }, "TransferDrain").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 加固（用户反馈：大队列小概率闪退）：排空线程跑在默认未捕获异常处理器
     * 下，任何 Throwable 都会直接杀掉整个进程。这里整体兜底——异常只作废
     * 当前批次并完整走清理，进程存活、队列可重试。
     */
    private fun drainSafely() {
        try {
            drainQueue()
        } catch (t: Throwable) {
            android.util.Log.e("DownloadService", "drainQueue crashed", t)
            runCatching {
                // 收回的必须是**这一批钉住的那份队列**（见 drainQueue 顶部）：用"当前这台"
                // 会把状态写到用户刚切过去的那台设备的桶里
                TransferStore.runningQueue?.let { q ->
                    TransferStore.requeueRunning(q)
                    TransferStore.persistNow()
                }
            }
        } finally {
            // 一次都没真正开跑（比如没连相机就点了"开始传输"）就不发结果通知：
            // "传输结束 完成 0 · 失败 0" 只会让人以为任务被弄丢了
            if (batchStarted) runCatching { notifyBatchResult() }
            runCatching {
                ThumbStore.resumeAfterTransfer()
                boundHost?.let { h ->
                    runCatching {
                        ObjectRepository.thumbQueue(h, PtpCodec.OP_THUMB_QUEUE_RESUME, emptyList())
                    }
                }
            }
            TransferStore.markBatchEnded()
            releaseTransferLocks()
            boundHost = null
            batchStarted = false
            // ★ 绝不在这里 closeAll()：传完就关会话 = 用户刚回到文件页就看到
            //   "目录读取失败"，随后心跳三次失联自动断开。会话的生死只在
            //   ConnectionCenter（心跳/断开/退出链）那一处裁决。
            runningFlag.set(false)
            stopSelf()
        }
    }

    /**
     * 本批钉住的主机名。
     *
     * 收尾时**不能**再看 `ConnectionCenter.host`：那期间用户可能已经切到别的相机，
     * 拿它去恢复缩略图队列就是让 B 去干 A 的活（B 上根本没有 A 那批缩略图任务）。
     */
    @Volatile
    private var boundHost: String? = null

    /** 本批是否真的开跑过（决定收尾要不要发"传输结束"通知）。 */
    @Volatile
    private var batchStarted = false

    private fun currentDeviceKey(): String =
        ConnectionCenter.camera?.guidHex.orEmpty().lowercase()

    /**
     * 批量传输可能持续数小时：前台服务只负责“不被随意杀”，不能保证锁屏后
     * Wi-Fi 与 CPU 不被省电策略压制。锁只在真实排空批次期间持有，finally 必释放。
     */
    private fun acquireTransferLocks() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                packageName + ":transfer",
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                packageName + ":transfer-wifi",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseTransferLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseTransferLocks()
        super.onDestroy()
    }

    private fun drainQueue() {
        // ★★ 整批**钉死一台设备**：队列引用 + 设备码 + 主机，三者都在这里定下，
        //    循环里只认它们。理由见 TransferStore 的类型注释 —— 相机内路径两台完全
        //    一样（/DCIM/100MSDCF/DSC00001.ARW），跟着"当前设备"走就会拿 A 的路径去
        //    B 上取文件、写进 B 的目录，而且没有任何校验能发现。
        val q = TransferStore.currentQueue
        val boundGuid = currentDeviceKey()
        boundHost = ConnectionCenter.host

        // 没连相机（或在看另一台设备的队列）时点了"开始传输"：**什么都不做**。
        // 绝不能往下走 —— 那样会把 q 里每一条都标成 FAILED（"host == null → 失败"），
        // 用户只是点早了一下，却看到整页任务全红。
        if (boundHost == null || boundGuid.isEmpty() || boundGuid != q.guid) {
            android.util.Log.d(
                "DownloadService",
                "未开始：host=${boundHost} 会话设备=$boundGuid 队列设备=${q.guid}",
            )
            return
        }

        // ① 暂停缩略图传输（让出带宽），记录进度（ThumbStore 批次计数即记录）
        ThumbStore.pauseForTransfer()
        boundHost?.let { h ->
            runCatching { ObjectRepository.thumbQueue(h, PtpCodec.OP_THUMB_QUEUE_PAUSE, emptyList()) }
        }

        TransferStore.markBatchStarted(q)
        batchStarted = true
        var lastNotify = 0L
        notifyBatch(null)

        while (!stopAll) {
            // 会话换设备了（用户切设备 / 断开后重连到另一台）：本批立刻停。
            // 走"整批暂停"而不是"失败"：进度全保留，用户在原来那台设备上还能接着传。
            if (currentDeviceKey() != boundGuid) {
                android.util.Log.d(
                    "DownloadService",
                    "设备已切换（$boundGuid → ${currentDeviceKey()}），本批暂停",
                )
                stopAll = true
                break
            }
            val next = q.items.firstOrNull { it.state == TransferState.QUEUED }
                ?: break
            var item = next.copy(state = TransferState.RUNNING)
            TransferStore.update(q, item)
            TransferStore.updateBatchProgress(q)

            var attempt = 0
            var result: ObjectRepository.PumpResult = ObjectRepository.PumpResult.FAILED
            var identityReset = false
            // 本文件最新落盘字节数（进度回调持续更新）；
            // 结算时用它，绝不回写循环外的陈旧 item（此前 0B/0% bug 的根源）
            var lastTotal = 0L
            // 落盘目录用**条目自己记着的**那一份（入队时就钉好了）：为空是 1.0 时代的
            // 老条目（那时没有设备子目录，文件在根下），按老布局落盘才对得上它可能
            // 已存在的 .part；这里**不**改成"当前设备目录"——那两件事是冲突的。
            val deviceDir = item.deviceDir
            do {
                val h = boundHost
                if (h == null) {
                    result = ObjectRepository.PumpResult.FAILED
                    break
                }
                val sink = StorageSink.resolve(
                    this,
                    SettingsRepo.downloadTreeUri,
                    StorageSink.localRelPath(deviceDir, item.path),
                )
                val remote = try {
                    ObjectRepository.stat(h, item.path)
                } catch (e: Exception) {
                    result = ObjectRepository.PumpResult.FAILED
                    break
                }
                val identityChanged = remote.size != item.size || remote.mtime != item.mtime
                if (identityChanged) {
                    if (identityReset) {
                        result = ObjectRepository.PumpResult.FAILED
                        break
                    }
                    identityReset = true
                    // 同名对象已换成另一份内容：旧 .part 不能续，明确从零开始。
                    runCatching { sink.discard() }
                    item = item.copy(size = remote.size, mtime = remote.mtime, doneBytes = 0L)
                    TransferStore.update(q, item)
                    android.util.Log.d(
                        "DownloadService",
                        "远端身份变化，重置断点：${item.path} size=${remote.size} mtime=${remote.mtime}",
                    )
                    attempt++
                    continue
                }

                val diskPart = sink.partBytes()
                // 远端身份已经核对一致，磁盘上的 .part 就是可信断点；不能要求
                // item.doneBytes > 0 —— 进程被杀时最后一次进度可能还没持久化，
                // 但已落盘的字节仍然有效，强制截断会让 Resume 白费。
                val canResume = diskPart in 1..item.size
                if (diskPart > item.size) {
                    runCatching { sink.discard() }
                    android.util.Log.w(
                        "DownloadService",
                        "断点超过目标大小，丢弃异常 .part：${item.path} part=$diskPart size=${item.size}",
                    )
                }
                val base = if (canResume) diskPart else 0L
                if (base == 0L && diskPart > 0L) {
                    // 旧记录没有可用断点，或刚刚判定异常，必须截断残留。
                    runCatching { sink.truncateStream().close() }
                }
                lastTotal = base
                val out = if (base == 0L) sink.truncateStream() else sink.appendStream()
                result = try {
                    ObjectRepository.pumpToStream(
                        h, item.path, base, out,
                        cancelled = { stopAll || currentDeviceKey() != boundGuid },
                        // 回调值已是绝对字节数（REST 基址 + 已传），切勿再加 base
                        // —— 加第二次就是"续传后 39.8MB/23.9MB"超量显示的根源
                        onProgress = { total ->
                            lastTotal = total
                            TransferStore.updateProgress(q, item.id, total)
                            TransferStore.updateBatchProgress(q)
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 400) {
                                lastNotify = now
                                notifyBatch(null)
                            }
                        },
                    )
                } catch (e: Exception) {
                    ObjectRepository.PumpResult.FAILED
                } finally {
                    runCatching { out.close() }
                }
                if (result == ObjectRepository.PumpResult.COMPLETED) {
                    lastTotal = item.size
                    runCatching { sink.commit(item.size) }
                        .onFailure {
                            android.util.Log.e("DownloadService", "commit failed: ${item.path}", it)
                            result = ObjectRepository.PumpResult.FAILED
                        }
                }
                // 网络失败自动重试保留 .part；只有身份变化/断点异常路径才已显式丢弃。
                attempt++
            } while (result == ObjectRepository.PumpResult.FAILED && !stopAll && attempt < 4)

            val doneItem = item.copy(
                state = when {
                    result == ObjectRepository.PumpResult.COMPLETED -> TransferState.DONE
                    stopAll -> TransferState.QUEUED // 停止=整批暂停，进度保留
                    else -> TransferState.FAILED
                },
                doneBytes = lastTotal,
            )
            TransferStore.update(q, doneItem)
            TransferStore.updateBatchProgress(q)
            notifyBatch(null)
        }

        notifyBatch(if (stopAll) "传输已停止" else "传输完成")

        if (stopAll) {
            // 整批暂停：RUNNING/QUEUED 保持进度，批次统计归零
            val q = TransferStore.runningQueue ?: TransferStore.currentQueue
            TransferStore.requeueRunning(q)
            TransferStore.persistNow()
        }
    }

    /**
     * 批次级通知（用户定版）：标题 = "正在传输" + 按秒循环的省略号动画
     * （. / .. / ... 循环）；副标题 = 总进度百分比 + 剩余时间；进度条 =
     * 批次总进度（不再是单文件）。finalTitle 非空时显示收尾状态。
     */
    private fun notifyBatch(finalTitle: String?) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val total = TransferStore.batchTotalBytes
        val done = TransferStore.batchDoneBytes.coerceIn(0, if (total > 0) total else 0)
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val title = finalTitle ?: "正在传输" + ".".repeat(((System.currentTimeMillis() / 1000) % 3).toInt() + 1)
        val subtitle = buildString {
            append(pct).append('%')
            if (finalTitle == null) {
                val speed = TransferStore.batchSpeedBps
                append(" · 剩余 ")
                append(if (speed > 0) formatDuration((total - done) / speed) else "—")
            }
        }
        nm.notify(NOTIF_ID, buildNotification(title, subtitle, done, total))
    }

    private fun buildNotification(title: String, subtitle: String, progress: Long, max: Long): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(mainIntent(this))
        // 通知进度条是 Int：总量超过 2GB（约 86 个 25MB 文件）时直接 toInt()
        // 会截断成负数 → coerceIn 抛异常 → 批次被兜底立即结束。
        // 超限时按比例缩放到 Int.MAX_VALUE，进度条视觉不变。
        if (max > 0) {
            if (max <= Int.MAX_VALUE) {
                b.setProgress(max.toInt(), progress.toInt().coerceIn(0, max.toInt()), false)
            } else {
                val scaled = ((progress.toDouble() / max) * Int.MAX_VALUE).toInt()
                b.setProgress(Int.MAX_VALUE, scaled.coerceIn(0, Int.MAX_VALUE), false)
            }
        }
        return b.build()
    }

    /**
     * 传输结束的独立通知（用户定版：完成 n · 失败 n，点击进入软件，可划走）。
     *
     * 计数认"这一批钉住的那份队列"：用户可能已经切到另一台设备了，界面那台的任务
     * 与刚结束的这批没关系，按它计数会报出"完成 0 · 失败 0"这种假结果。
     */
    private fun notifyBatchResult() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val q = TransferStore.runningQueue ?: TransferStore.currentQueue
        val done = TransferStore.countBy(q, TransferState.DONE)
        val failed = TransferStore.countBy(q, TransferState.FAILED)
        nm.notify(
            NOTIF_ID_RESULT,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_transfer)
                .setContentTitle("传输结束")
                .setContentText("完成 $done · 失败 $failed")
                .setContentIntent(mainIntent(this))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun formatDuration(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        val m = s / 60
        val h = m / 60
        return when {
            h > 0 -> "%d:%02d:%02d".format(h, m % 60, s % 60)
            else -> "%d:%02d".format(m, s % 60)
        }
    }
}
