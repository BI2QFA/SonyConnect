package com.bi2qfa.sonyconnect

import android.app.Application
import com.bi2qfa.sonyconnect.core.ConnectionCenter
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
        TransferStore.load(this)
        ThumbStore.init(this)
        ConnectionCenter.init(appScope, this)
    }
}
