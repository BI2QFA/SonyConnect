package com.bi2qfa.sonyconnect.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bi2qfa.sonyconnect.R
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.core.StorageSink
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ptpip.ObjectRepository
import com.bi2qfa.sonyconnect.ptpip.PtpCodec
import java.util.concurrent.atomic.AtomicBoolean














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

        
        private fun mainIntent(context: Context) = android.app.PendingIntent.getActivity(
            context, 0,
            Intent(context, com.bi2qfa.sonyconnect.ui.MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @Volatile
    private var stopAll = false

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
                stopAll = false 
                if (runningFlag.compareAndSet(false, true)) {
                    Thread({ drainSafely() }, "TransferDrain").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    




    private fun drainSafely() {
        try {
            drainQueue()
        } catch (t: Throwable) {
            android.util.Log.e("DownloadService", "drainQueue crashed", t)
            runCatching {
                
                
                TransferStore.runningQueue?.let { q ->
                    TransferStore.requeueRunning(q)
                    TransferStore.persistNow()
                }
            }
        } finally {
            
            
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
            boundHost = null
            batchStarted = false
            
            
            
            runningFlag.set(false)
            stopSelf()
        }
    }

    





    @Volatile
    private var boundHost: String? = null

    
    @Volatile
    private var batchStarted = false

    private fun currentDeviceKey(): String =
        ConnectionCenter.camera?.guidHex.orEmpty().lowercase()

    private fun drainQueue() {
        
        
        
        
        val q = TransferStore.currentQueue
        val boundGuid = currentDeviceKey()
        boundHost = ConnectionCenter.host

        
        
        
        if (boundHost == null || boundGuid.isEmpty() || boundGuid != q.guid) {
            android.util.Log.d(
                "DownloadService",
                "未开始：host=${boundHost} 会话设备=$boundGuid 队列设备=${q.guid}",
            )
            return
        }

        
        ThumbStore.pauseForTransfer()
        boundHost?.let { h ->
            runCatching { ObjectRepository.thumbQueue(h, PtpCodec.OP_THUMB_QUEUE_PAUSE, emptyList()) }
        }

        TransferStore.markBatchStarted(q)
        batchStarted = true
        var lastNotify = 0L
        notifyBatch(null)

        while (!stopAll) {
            
            
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
            val item = next.copy(state = TransferState.RUNNING)
            TransferStore.update(q, item)
            TransferStore.updateBatchProgress(q)

            var attempt = 0
            var result: ObjectRepository.PumpResult
            
            
            var lastTotal = 0L
            
            
            
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
                
                
                
                
                val base = if (item.doneBytes <= 0L) 0L
                else sink.partBytes().coerceIn(0, item.size)
                lastTotal = base
                val out = if (base == 0L) sink.truncateStream() else sink.appendStream()
                result = try {
                    ObjectRepository.pumpToStream(
                        h, item.path, base, out,
                        cancelled = { stopAll || currentDeviceKey() != boundGuid },
                        
                        
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
                    
                    
                    runCatching { sink.commit() }
                        .onFailure { result = ObjectRepository.PumpResult.FAILED }
                }
                if (result == ObjectRepository.PumpResult.FAILED) {
                    runCatching { sink.discard() }
                }
                attempt++
            } while (result == ObjectRepository.PumpResult.FAILED && !stopAll && attempt < 4)

            val doneItem = item.copy(
                state = when {
                    result == ObjectRepository.PumpResult.COMPLETED -> TransferState.DONE
                    stopAll -> TransferState.QUEUED 
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
            
            val q = TransferStore.runningQueue ?: TransferStore.currentQueue
            TransferStore.requeueRunning(q)
            TransferStore.persistNow()
        }
    }

    




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
