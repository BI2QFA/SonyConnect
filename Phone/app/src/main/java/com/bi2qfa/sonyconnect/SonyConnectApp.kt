package com.bi2qfa.sonyconnect

import android.app.Application
import com.bi2qfa.sonyconnect.core.ConnectionCenter
import com.bi2qfa.sonyconnect.data.IdentityRepo
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.transfer.TransferStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SonyConnectApp : Application() {

    lateinit var appScope: CoroutineScope
        private set

    override fun onCreate() {
        super.onCreate()
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        SettingsRepo.init(this)
        // ★ 顺序要求：IdentityRepo 提供 guid16 / friendlyName（UDP 探测与握手都要用），
        //   PairingStore 提供自动连接匹配表，必须先于 ConnectionCenter.init。
        IdentityRepo.init(this)
        PairingStore.init(this)
        TransferStore.load(this)
        ThumbStore.init(this)
        ConnectionCenter.init(appScope, this)
    }
}
