package com.bi2qfa.sonyconnect.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.bi2qfa.sonyconnect.protocol.ConnectClient

object DeviceStore {

    var info by mutableStateOf<ConnectClient.CameraInfo?>(null)
        private set

    fun apply(i: ConnectClient.CameraInfo?) {
        info = i
    }

    fun applyHeartbeat(batteryPct: Int?, lens: String?) {
        val cur = info ?: return
        if (batteryPct == null && lens == null) return
        info = cur.copy(
            batteryPct = batteryPct ?: cur.batteryPct,
            lens = lens?.takeIf { it.isNotBlank() } ?: cur.lens,
        )
    }

    fun clear() {
        info = null
    }
}
