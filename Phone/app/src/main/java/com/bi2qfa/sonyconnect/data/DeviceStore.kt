package com.bi2qfa.sonyconnect.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf









object DeviceStore {

    var info by mutableStateOf<CameraInfo?>(null)
        private set

    



















    fun apply(i: CameraInfo?) {
        if (i == null) {
            info = null
            return
        }
        val cur = info
        info = if (cur == null || !cur.guid.equals(i.guid, ignoreCase = true)) {
            i
        } else {
            i.copy(
                batteryPct = if (i.batteryPct in 0..100) i.batteryPct else cur.batteryPct,
                batteryRemainMin = if (i.batteryRemainMin >= 0) i.batteryRemainMin
                else cur.batteryRemainMin,
                lens = if (i.lens.isNotBlank()) i.lens else cur.lens,
                region = if (i.region.isNotBlank()) i.region else cur.region,
                apiVersion = if (i.apiVersion.isNotBlank()) i.apiVersion else cur.apiVersion,
                androidVersion = if (i.androidVersion.isNotBlank()) i.androidVersion
                else cur.androidVersion,
                androidSdk = if (i.androidSdk >= 0) i.androidSdk else cur.androidSdk,
                sdTotalBytes = if (i.sdTotalBytes > 0) i.sdTotalBytes else cur.sdTotalBytes,
                sdUsedBytes = if (i.sdUsedBytes >= 0) i.sdUsedBytes else cur.sdUsedBytes,
            )
        }
    }

    



    fun applyPing(batteryPct: Int, lens: String) {
        val cur = info ?: return
        val next = cur.applyPing(batteryPct, lens)
        if (next != cur) info = next
    }

    fun clear() {
        info = null
    }
}
