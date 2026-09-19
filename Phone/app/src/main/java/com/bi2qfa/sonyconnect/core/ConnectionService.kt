package com.bi2qfa.sonyconnect.core

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
import com.bi2qfa.sonyconnect.data.DeviceStore

/**
 * 连接保活前台服务（修复：切后台后 MIUI 冻结/限网导致心跳失联断开）。
 * 连接成功时启动、断开时停止；通知常驻低优先级，不带进度不吵人。
 */
class ConnectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIF_ID = 41
        private const val ACTION_START = "com.bi2qfa.sonyconnect.CONN_START"
        private const val ACTION_STOP = "com.bi2qfa.sonyconnect.CONN_STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ConnectionService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ConnectionService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "相机连接", NotificationManager.IMPORTANCE_LOW)
            )
        }
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val model = DeviceStore.info?.model
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle("SonyConnect")
            .setContentText(if (model.isNullOrBlank()) "已连接" else "$model 已连接")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(mainActivityIntent())
            .build()
    }

    /** 点击通知直接进入软件 */
    private fun mainActivityIntent() = android.app.PendingIntent.getActivity(
        this, 0,
        Intent(this, com.bi2qfa.sonyconnect.ui.MainActivity::class.java),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
