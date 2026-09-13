package com.bi2qfa.sonyconnect.core

import android.content.Context
import android.net.Network
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.bi2qfa.sonyconnect.data.CameraInfo
import com.bi2qfa.sonyconnect.data.DeviceStore
import com.bi2qfa.sonyconnect.data.IdentityRepo
import com.bi2qfa.sonyconnect.data.PairingStore
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ptpip.Discovery
import com.bi2qfa.sonyconnect.ptpip.ObjectRepository
import com.bi2qfa.sonyconnect.ptpip.PtpCodec
import com.bi2qfa.sonyconnect.ptpip.PtpIpClient
import com.bi2qfa.sonyconnect.transfer.TransferStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext




















object ConnectionCenter {

    private const val TAG = "ConnectionCenter"

    

    enum class State { DISCONNECTED, CONNECTED }

    const val HEARTBEAT_INTERVAL_MS = 3000L
    
    private const val AUTO_SCAN_TIMEOUT_MS = 30_000L
    
    private const val HEARTBEAT_MAX_MISSES = 3

    
    private const val AUTO_CONNECT_ROUNDS = 3
    private const val AUTO_CONNECT_RETRY_MS = 1500L

    var state by mutableStateOf(State.DISCONNECTED)
        private set

    var scanning by mutableStateOf(false)
        private set

    
    var connecting by mutableStateOf(false)
        private set

    
    var scanResults by mutableStateOf<List<Discovery.DiscoveredCamera>>(emptyList())
        private set

    var host by mutableStateOf<String?>(null)
        private set

    
    var camera by mutableStateOf<Discovery.DiscoveredCamera?>(null)
        private set

    






    var targetGuid by mutableStateOf<String?>(null)
        private set

    var boundNetwork: Network? = null
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    

    
    var pairingTarget by mutableStateOf<Discovery.DiscoveredCamera?>(null)
        private set

    
    var pairingBusy by mutableStateOf(false)
        private set

    
    var pairingError by mutableStateOf<String?>(null)
        private set

    private var heartbeatJob: Job? = null
    private var autoScanJob: Job? = null
    private var autoConnectJob: Job? = null
    private lateinit var scope: CoroutineScope
    private var appContext: Context? = null

    










    private val eventListener = PtpIpClient.EventListener { code, txId, params ->
        Log.d(TAG, "event code=$code tx=$txId n=${params?.size ?: 0}")
        when (code) {
            PtpCodec.EV_PAIRED_REMOVED -> onPairRemovedByCamera()
            PtpCodec.EV_APP_EXITING -> onCameraExiting()
            PtpCodec.EV_MODE_SWITCHING -> onCameraModeSwitching(params)
        }
    }

    




    private fun onCameraExiting() {
        if (!::scope.isInitialized) return
        scope.launch {
            Log.d(TAG, "相机端已退出，收掉当前会话")
            disconnect("相机端已退出")
        }
    }

    












    private fun onCameraModeSwitching(params: IntArray?) {
        if (!::scope.isInitialized) return
        scope.launch {
            val mode = params?.firstOrNull()
            Log.d(TAG, "相机切换连接方式（mode=$mode）")
            disconnect("相机切换了连接模式，请重新连接")
        }
    }

    





    private fun onPairRemovedByCamera() {
        if (!::scope.isInitialized) return
        val g = camera?.guidHex ?: return
        scope.launch {
            Log.d(TAG, "相机解除了本机配对，清本地记录：$g")
            PairingStore.remove(g)
            disconnect("相机已解除与本机的配对")
        }
    }

    fun init(scope: CoroutineScope, context: Context) {
        this.scope = scope
        this.appContext = context.applicationContext
    }

    

    






    private fun pinWifiNetwork(context: Context) {
        val net = try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            var found: Network? = null
            if (cm != null) {
                for (n in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(n) ?: continue
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        found = n
                        break
                    }
                }
                runCatching { cm.bindProcessToNetwork(found) }
            }
            found
        } catch (e: Exception) {
            null
        }
        boundNetwork = net
        ObjectRepository.boundNetwork = net
    }

    
    private fun unpinNetwork() {
        try {
            val c = appContext ?: return
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.let { runCatching { it.bindProcessToNetwork(null) } }
        } catch (e: Exception) {
            
        }
        boundNetwork = null
        ObjectRepository.boundNetwork = null
    }

    

    









    fun startPairingScan(context: Context, force: Boolean = false) {
        
        
        
        if (connecting) return
        if (autoScanJob?.isActive == true) {
            if (!force) return
            autoScanJob?.cancel()
        }
        autoScanJob = scope.launch {
            val deadline = System.currentTimeMillis() + AUTO_SCAN_TIMEOUT_MS
            scanning = true
            scanResults = emptyList()
            lastError = null
            while (!connecting && System.currentTimeMillis() < deadline) {
                val found = try {
                    pinWifiNetwork(context) 
                    
                    Discovery.scan(context, boundNetwork, knownCameraHosts(), thorough = force)
                        .filter { it.pairingMode }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.d(TAG, "scan failed: ${e.message}")
                    null
                }
                if (connecting) break
                scanResults = found ?: emptyList()
                
                if (!found.isNullOrEmpty()) break
                val remain = deadline - System.currentTimeMillis()
                if (remain > 0) delay(minOf(3000L, remain))
            }
            scanning = false
            
            if (!connecting && scanResults.isEmpty()) {
                lastError = "未发现处于配对模式的相机"
            }
        }
    }

    




    private fun knownCameraHosts(): List<String> =
        PairingStore.all().mapNotNull { it.lastIp?.takeIf { ip -> ip.isNotBlank() } }.distinct()

    

    
    fun hasPairedDevice(): Boolean = PairingStore.all().isNotEmpty()

    





    private fun lastTargetGuid(): String? {
        val saved = SettingsRepo.lastConnectedDevice
        if (saved.isNotBlank() && PairingStore.contains(saved)) return saved
        return PairingStore.mostRecent()?.peerDeviceId
    }

    
    fun displayGuid(): String? = camera?.guidHex ?: targetGuid

    








    private fun selectQueue(guidHex: String?) {
        guidHex?.takeIf { it.isNotBlank() }?.let { TransferStore.onCameraChanged(it) }
    }

    
    fun labelOf(guidHex: String?): String {
        val p = guidHex?.let { PairingStore.find(it) } ?: return "相机"
        val model = p.peerModel.ifBlank { p.peerName }
        if (model.isBlank()) return p.peerDeviceId
        return if (p.peerSerial.isNotBlank()) "$model SN:${p.peerSerial}" else model
    }

    








    fun shortCode(guidHex: String?): String =
        guidHex.orEmpty().trim().lowercase().take(8)

    






    private suspend fun scanFor(
        context: Context,
        guidHex: String,
        thorough: Boolean,
        sweep: Boolean = true,
    ): Discovery.DiscoveredCamera? =
        try {
            pinWifiNetwork(context)
            
            val prefer = listOfNotNull(PairingStore.find(guidHex)?.lastIp).filter { it.isNotBlank() }
            Discovery.scan(context, boundNetwork, prefer, thorough = thorough, sweep = sweep)
                .firstOrNull { it.guidHex == guidHex }
        } catch (e: Exception) {
            Log.d(TAG, "scanFor failed guid=$guidHex: ${e.message}")
            null
        }

    






    fun autoConnectLast(context: Context) = startConnect(context, lastTargetGuid())

    





    fun reconnect(context: Context) = startConnect(context, targetGuid ?: lastTargetGuid())

    private fun startConnect(context: Context, guid: String?) {
        if (state != State.DISCONNECTED || connecting) return
        if (guid.isNullOrBlank()) return
        
        if (autoConnectJob?.isActive == true) return
        targetGuid = guid
        lastError = null
        selectQueue(guid)   
        autoConnectJob = scope.launch {
            connecting = true
            var target: Discovery.DiscoveredCamera? = null
            for (round in 0 until AUTO_CONNECT_ROUNDS) {
                target = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    
                    scanFor(context, guid, thorough = false, sweep = round == AUTO_CONNECT_ROUNDS - 1)
                }
                if (target != null) break
                
                if (targetGuid != guid) {
                    connecting = false
                    return@launch
                }
                delay(AUTO_CONNECT_RETRY_MS)
            }
            connecting = false
            if (targetGuid != guid) return@launch
            if (target == null) {
                lastError = "未找到 ${labelOf(guid)}，请确认相机端 SonyConnect 服务正常运行并与本机在同一网络"
                return@launch
            }
            connectTo(context, target)
        }
    }

    

    





















    fun switchTo(context: Context, guidHex: String) {
        if (guidHex.isBlank()) return
        targetGuid = guidHex
        SettingsRepo.updateLastDevice(guidHex)   
        selectQueue(guidHex)   
        if (camera?.guidHex == guidHex && state == State.CONNECTED) return
        if (connecting) return
        scope.launch {
            lastError = null
            autoConnectJob?.cancel()
            if (state == State.CONNECTED) disconnect()
            connecting = true
            
            var target = withContext(kotlinx.coroutines.Dispatchers.IO) {
                scanFor(context, guidHex, thorough = false, sweep = false)
            }
            
            if (target == null && targetGuid == guidHex) {
                target = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scanFor(context, guidHex, thorough = true, sweep = true)
                }
            }
            connecting = false
            
            if (targetGuid != guidHex) {
                Log.d(TAG, "扫描期间目标改为 $targetGuid，放弃这次切换")
                return@launch
            }
            if (target == null) {
                lastError = "未找到 ${labelOf(guidHex)}（请确认相机端 SonyConnect 服务正常运行并与本机在同一网络）"
                return@launch
            }
            connectTo(context, target)
        }
    }

    




    fun connectTo(context: Context, cam: Discovery.DiscoveredCamera) {
        if (state == State.CONNECTED || connecting) return
        lastError = null   
        targetGuid = cam.guidHex   
        scope.launch {
            connecting = true
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    
                    pinWifiNetwork(context)
                    ObjectRepository.boundNetwork = boundNetwork

                    
                    if (!PairingStore.contains(cam.guidHex)) {
                        Log.d(TAG, "未配对，需要先配对：${cam.guidHex}")
                        lastError = "该相机尚未配对，请先配对"
                        return@withContext false
                    }

                    when (ObjectRepository.connect(
                        host = cam.host,
                        protoPort = cam.protoPort,
                        guid16 = cam.cameraGuid16,
                        friendlyName = IdentityRepo.friendlyName,
                        onEvent = eventListener,
                    )) {
                        ObjectRepository.ConnectOutcome.OK -> Unit

                        ObjectRepository.ConnectOutcome.NOT_PAIRED -> {
                            
                            
                            
                            Log.d(TAG, "相机已解除本机配对，清本地记录：${cam.guidHex}")
                            PairingStore.remove(cam.guidHex)
                            lastError = "相机已解除与本机的配对，请重新配对"
                            return@withContext false
                        }

                        ObjectRepository.ConnectOutcome.BUSY -> {
                            lastError = "相机正被其他设备占用（配对中或已连接），请稍后重试"
                            return@withContext false
                        }

                        ObjectRepository.ConnectOutcome.FAILED -> {
                            Log.d(TAG, "connect failed host=${cam.host}:${cam.protoPort}")
                            return@withContext false
                        }
                    }
                    
                    DeviceStore.apply(
                        CameraInfo.placeholder(cam.guidHex, cam.name, cam.protoPort, cam.filePort)
                    )

                    
                    
                    val info = pullDeviceInfo(cam)
                    if (info != null) {
                        DeviceStore.apply(info)
                    }

                    
                    
                    
                    
                    applyLivePing(cam.host)

                    
                    
                    TransferStore.onCameraChanged(cam.guidHex)

                    
                    PairingStore.touch(cam.guidHex, cam.host, cam.protoPort)
                    ObjectRepository.sessionOf(cam.host)?.let { s ->
                        PairingStore.updateProtoVersion(cam.guidHex, s.client.protoVersion)
                    }
                    true
                } catch (e: Exception) {
                    Log.d(TAG, "connectTo exception: ${e.javaClass.simpleName} ${e.message}")
                    rollback()
                    false
                }
            }
            connecting = false
            if (ok) {
                camera = cam
                
                
                host = cam.host
                state = State.CONNECTED
                
                appContext?.let { SettingsRepo.updateLastDevice(cam.guidHex) }
                
                appContext?.let { runCatching { ConnectionService.start(it) } }
                startHeartbeat()
                startThumbBatch()
            } else {
                rollback()
                if (lastError == null) lastError = "连接失败"
            }
            
            
            
            val wanted = targetGuid
            if (wanted != null && !wanted.equals(cam.guidHex, ignoreCase = true)) {
                Log.d(TAG, "握手期间目标改为 $wanted，接力切换")
                switchTo(context, wanted)
            }
        }
    }

    
    private fun pullDeviceInfo(cam: Discovery.DiscoveredCamera): CameraInfo? =
        CameraInfo.fromDeviceInfo(
            json = ObjectRepository.deviceInfo(cam.host),
            guid = cam.guidHex,
            fallbackName = cam.name,
            protoPort = cam.protoPort,
            filePort = cam.filePort,
        )

    






    private fun applyLivePing(host: String) {
        val p = runCatching { ObjectRepository.ping(host) }.getOrNull()
        if (p == null) {
            Log.d(TAG, "连上后首拉实时信息失败，等下一个心跳补")
            return
        }
        DeviceStore.applyPing(p.batteryPct, p.lens)
        Log.d(TAG, "连上后首拉实时信息：电量 ${p.batteryPct}%，镜头 ${p.lens}")
    }

    
    private fun rollback() {
        ObjectRepository.closeAll()
        DeviceStore.clear()
    }

    
    private fun startThumbBatch() {
        scope.launch {
            runCatching {
                val h = host ?: return@launch
                val entries = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    collectImagePaths(h, "/DCIM", 200)
                }
                if (entries.isNotEmpty()) {
                    ObjectRepository.thumbQueue(h, PtpCodec.OP_THUMB_QUEUE_BEGIN, entries)
                }
                ThumbStore.markBatchQueued(entries)
            }
        }
    }

    
    private fun collectImagePaths(host: String, dir: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val queue = ArrayDeque<String>()
        queue.add(dir)
        var depth = 0
        while (queue.isNotEmpty() && out.size < max && depth < 4) {
            val d = queue.removeFirst()
            depth++
            val entries = try {
                ObjectRepository.list(host, d)
            } catch (e: Exception) {
                continue
            }
            for (e in entries) {
                if (e.isDir) queue.add(e.path)
                else {
                    val ext = e.ext
                    if (ext == "jpg" || ext == "jpeg" || ext == "arw") out.add(e.path)
                }
                if (out.size >= max) break
            }
        }
        return out
    }

    

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var misses = 0
            while (state == State.CONNECTED) {
                delay(HEARTBEAT_INTERVAL_MS)
                val h = host ?: continue
                val ping = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ObjectRepository.ping(h) }.getOrNull()
                }
                if (ping == null) {
                    misses++
                    if (misses >= HEARTBEAT_MAX_MISSES) {
                        disconnect("失去与相机的连接")
                        return@launch
                    }
                    continue
                }
                misses = 0
                
                DeviceStore.applyPing(ping.batteryPct, ping.lens)
            }
        }
    }

    

    






    fun requestPair(cam: Discovery.DiscoveredCamera) {
        if (connecting || pairingBusy) return
        pairingTarget = cam
        pairingError = null
    }

    
    fun cancelPair() {
        if (pairingBusy) return
        pairingTarget = null
        pairingError = null
    }

    






    fun submitPairCode(context: Context, code: String) {
        val cam = pairingTarget ?: return
        if (pairingBusy) return
        lastError = null
        pairingError = null
        pairingBusy = true
        scope.launch {
            val outcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    pinWifiNetwork(context)
                    ObjectRepository.boundNetwork = boundNetwork
                    ObjectRepository.pair(
                        host = cam.host,
                        protoPort = cam.protoPort,
                        guid16 = cam.cameraGuid16,
                        friendlyName = IdentityRepo.friendlyName,
                        code = code,
                        onEvent = eventListener,
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "pair exception: ${e.javaClass.simpleName} ${e.message}")
                    ObjectRepository.PairOutcome.FAILED
                }
            }
            pairingBusy = false
            
            
            
            
            if (outcome == ObjectRepository.PairOutcome.FAILED) {
                pairingError = "配对失败：配对码不正确，或相机未开启配对模式"
                return@launch
            }
            if (outcome == ObjectRepository.PairOutcome.BUSY) {
                
                
                pairingError = "相机正被其他设备占用（配对中或已连接），请稍后重试"
                return@launch
            }
            if (outcome == ObjectRepository.PairOutcome.ALREADY_PAIRED) {
                
                
                
                Log.d(TAG, "相机已认识本机，改为补齐本地配对记录")
            }
            
            
            val record = PairingStore.PairedCamera(
                peerDeviceId = cam.guidHex,
                peerName = cam.name,
                pairedAt = System.currentTimeMillis(),
                lastSeenAt = System.currentTimeMillis(),
                lastIp = cam.host,
                lastPort = cam.protoPort,
            )
            PairingStore.upsert(record)
            val info = withContext(kotlinx.coroutines.Dispatchers.IO) { pullDeviceInfo(cam) }
            if (info != null) {
                PairingStore.upsert(
                    record.copy(peerName = cam.name, peerModel = info.model, peerSerial = info.serial)
                )
                DeviceStore.apply(info)
            } else {
                DeviceStore.apply(
                    CameraInfo.placeholder(cam.guidHex, cam.name, cam.protoPort, cam.filePort)
                )
            }
            
            
            
            withContext(kotlinx.coroutines.Dispatchers.IO) { applyLivePing(cam.host) }
            TransferStore.onCameraChanged(cam.guidHex)
            ObjectRepository.sessionOf(cam.host)?.let { s ->
                PairingStore.updateProtoVersion(cam.guidHex, s.client.protoVersion)
            }
            pairingTarget = null
            
            
            
            
            if (state == State.CONNECTED && camera?.guidHex != cam.guidHex) {
                disconnect("切换到新配对的相机")
            }
            
            camera = cam
            targetGuid = cam.guidHex
            host = cam.host
            state = State.CONNECTED
            
            SettingsRepo.updateLastDevice(cam.guidHex)
            appContext?.let { runCatching { ConnectionService.start(it) } }
            startHeartbeat()
            startThumbBatch()
        }
    }

    





    fun unpair(peerDeviceId: String) {
        val h = host
        val isCurrent = camera?.guidHex == peerDeviceId
        scope.launch {
            if (isCurrent && h != null) {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ObjectRepository.pairRemove(h) }
                }
            }
            PairingStore.remove(peerDeviceId)
            
            
            if (targetGuid.equals(peerDeviceId, ignoreCase = true)) {
                targetGuid = null
                
                
                PairingStore.mostRecent()?.peerDeviceId?.let {
                    targetGuid = it
                    selectQueue(it)
                }
            }
            
            if (isCurrent && state == State.CONNECTED) {
                disconnect("已解除与该相机的配对")
            }
        }
    }

    





    fun disconnect(reason: String? = null) {
        
        Log.d(TAG, "disconnect: ${reason ?: "用户主动断开"}")
        heartbeatJob?.cancel()
        heartbeatJob = null
        autoScanJob?.cancel()
        autoScanJob = null
        autoConnectJob?.cancel()
        autoConnectJob = null
        connecting = false
        
        TransferStore.onConnectionLost()
        rollback()
        ThumbStore.reset()
        unpinNetwork()
        appContext?.let { runCatching { ConnectionService.stop(it) } }
        host = null
        camera = null
        state = State.DISCONNECTED
        if (reason != null) lastError = reason
    }
}
