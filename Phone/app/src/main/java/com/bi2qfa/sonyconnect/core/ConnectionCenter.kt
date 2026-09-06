package com.bi2qfa.sonyconnect.core

import android.content.Context
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.bi2qfa.sonyconnect.data.DeviceStore
import com.bi2qfa.sonyconnect.data.SettingsRepo
import com.bi2qfa.sonyconnect.data.ThumbStore
import com.bi2qfa.sonyconnect.ftp.FtpRepository
import com.bi2qfa.sonyconnect.protocol.ConnectClient
import com.bi2qfa.sonyconnect.protocol.Scanner
import com.bi2qfa.sonyconnect.transfer.TransferStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ConnectionCenter {

    enum class State { DISCONNECTED, SCANNING, CONNECTED }

    const val HEARTBEAT_INTERVAL_MS = 3000L

    const val INFO_INTERVAL_MS = 5000L

    private const val AUTO_SCAN_TIMEOUT_MS = 30_000L

    var state by mutableStateOf(State.DISCONNECTED)
        private set

    var scanning by mutableStateOf(false)
        private set

    var connecting by mutableStateOf(false)
        private set

    var scanResults by mutableStateOf<List<Scanner.Candidate>>(emptyList())
        private set

    var host by mutableStateOf<String?>(null)
        private set

    var boundNetwork: Network? = null
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    private var heartbeatJob: Job? = null
    private var infoJob: Job? = null
    private var autoScanJob: Job? = null
    private lateinit var scope: CoroutineScope
    private var appContext: Context? = null

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
        FtpRepository.boundNetwork = net
        ConnectClient.boundNetwork = net
    }

    private fun unpinNetwork() {
        try {
            val c = appContext ?: return
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            cm?.let { runCatching { it.bindProcessToNetwork(null) } }
        } catch (e: Exception) {

        }
        boundNetwork = null
        FtpRepository.boundNetwork = null
        ConnectClient.boundNetwork = null
    }

    fun startAutoScan(context: Context, force: Boolean = false) {
        if (state != State.DISCONNECTED) return
        if (autoScanJob?.isActive == true) {
            if (!force) return
            autoScanJob?.cancel()
        }
        autoScanJob = scope.launch {
            val deadline = System.currentTimeMillis() + AUTO_SCAN_TIMEOUT_MS
            scanning = true
            scanResults = emptyList()
            lastError = null
            while (state == State.DISCONNECTED && !connecting &&
                System.currentTimeMillis() < deadline
            ) {
                val found = try {
                    pinWifiNetwork(context)
                    withContext(kotlinx.coroutines.Dispatchers.IO) { Scanner.scan(context) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (state != State.DISCONNECTED || connecting) break
                scanResults = found ?: emptyList()
                if (!found.isNullOrEmpty()) {
                    tryAutoConnect(context, found)
                    break
                }
                val remain = deadline - System.currentTimeMillis()
                if (remain > 0) delay(minOf(3000L, remain))
            }
            scanning = false

            if (state == State.DISCONNECTED && !connecting && scanResults.isEmpty()) {
                lastError = "未找到设备"
            }
        }
    }

    private fun tryAutoConnect(context: Context, found: List<Scanner.Candidate>) {
        val pref = SettingsRepo.preferredDevice
        if (pref.isBlank()) return
        val hit = found.firstOrNull {
            it.serial.isNotBlank() && "${it.model}|${it.serial}" == pref
        } ?: return
        connectTo(context, hit.host)
    }

    fun connectTo(context: Context, hostCandidate: String) {
        if (state == State.CONNECTED || connecting) return
        scope.launch {
            connecting = true
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {

                    pinWifiNetwork(context)

                    FtpRepository.boundNetwork = boundNetwork
                    if (!FtpRepository.connect(hostCandidate)) return@withContext false

                    ConnectClient.boundNetwork = boundNetwork
                    if (!ConnectClient.connect(hostCandidate)) return@withContext false
                    val info = ConnectClient.info() ?: return@withContext false
                    DeviceStore.apply(info)

                    SettingsRepo.recordDevice(info.model, info.serial)
                    true
                } catch (e: Exception) {
                    rollback()
                    false
                }
            }
            connecting = false
            if (ok) {
                host = hostCandidate
                state = State.CONNECTED

                appContext?.let { runCatching { ConnectionService.start(it) } }
                startHeartbeat()
                startInfoPoll()
                startThumbBatch()
            } else {
                rollback()
                lastError = "连接失败"
            }
        }
    }

    private fun rollback() {
        FtpRepository.closeAll()
        ConnectClient.close()
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
                    ConnectClient.thumbBegin(entries)
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
                FtpRepository.list(host, d)
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
                val hb = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ConnectClient.heartbeat() }.getOrNull()
                }
                if (hb == null) {
                    misses++
                    if (misses >= 3) {
                        disconnect("失去与相机的连接")
                        return@launch
                    }
                    continue
                }
                misses = 0

                DeviceStore.applyHeartbeat(hb.batteryPct, hb.lens)
            }
        }
    }

    private fun startInfoPoll() {
        infoJob?.cancel()
        infoJob = scope.launch {
            while (state == State.CONNECTED) {
                delay(INFO_INTERVAL_MS)
                val info = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ConnectClient.info() }.getOrNull()
                }
                if (info != null) DeviceStore.apply(info)
            }
        }
    }

    fun disconnect(reason: String? = null) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        infoJob?.cancel()
        infoJob = null
        autoScanJob?.cancel()
        autoScanJob = null
        connecting = false

        TransferStore.onConnectionLost()
        rollback()
        ThumbStore.reset()
        unpinNetwork()
        appContext?.let { runCatching { ConnectionService.stop(it) } }
        host = null
        state = State.DISCONNECTED
        if (reason != null) lastError = reason
    }
}
