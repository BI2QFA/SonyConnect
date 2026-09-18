package com.bi2qfa.sonyconnect.ptpip

import android.net.Network
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

























object ObjectRepository {

    private const val TAG = "ObjectRepository"

    
    private const val CONNECT_TIMEOUT_MS = 5000

    



    private const val PAIR_TIMEOUT_MS = 20_000

    
    private const val IO_TIMEOUT_MS = 30_000

    
    const val VIRTUAL_THUMB_PREFIX = "/.sonyconnect/th"
    const val VIRTUAL_PREVIEW_PREFIX = "/.sonyconnect/pv"

    



    @Volatile
    var boundNetwork: Network? = null

    






    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PtpIpIo").apply { isDaemon = true }
    }

    











    private val xferExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PtpIpXfer").apply { isDaemon = true }
    }

    
    class Session internal constructor(
        val host: String,
        val protoPort: Int,
        val client: PtpIpClient,
    ) : Closeable {
        val guid16: ByteArray get() = client.cameraGuid16
        val cameraName: String get() = client.cameraName
        override fun close() = client.close()
    }

    
    enum class PairOutcome {
        
        FAILED,

        
        BUSY,

        
        PAIRED,

        







        ALREADY_PAIRED,
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    private fun socketFactoryOrNull() =
        runCatching { boundNetwork?.socketFactory }.getOrNull()

    
    
    

    






    enum class ConnectOutcome {
        OK,

        
        NOT_PAIRED,

        
        BUSY,

        
        FAILED,
    }

    





    fun connect(
        host: String,
        protoPort: Int,
        guid16: ByteArray,
        friendlyName: String,
        onEvent: PtpIpClient.EventListener? = null,
    ): ConnectOutcome {
        sessions[host]?.let { if (it.client.isConnected) return ConnectOutcome.OK else disconnect(host) }
        return try {
            ioExecutor.submit<ConnectOutcome> {
                val client = PtpIpClient(
                    host = host,
                    protoPort = protoPort,
                    guid16 = guid16,
                    friendlyName = friendlyName,
                    socketFactory = socketFactoryOrNull(),
                )
                try {
                    client.connect(CONNECT_TIMEOUT_MS)
                    if (onEvent != null) {
                        runCatching { client.openEventChannel(onEvent, CONNECT_TIMEOUT_MS) }
                    }
                    sessions[host] = Session(host, protoPort, client)
                    ConnectOutcome.OK
                } catch (e: InitFailedException) {
                    
                    Log.d(TAG, "connect rejected: reason=0x${Integer.toHexString(e.reason)}")
                    runCatching { client.close() }
                    when (e.reason) {
                        PtpCodec.FAIL_NOT_PAIRED -> ConnectOutcome.NOT_PAIRED
                        PtpCodec.FAIL_BUSY -> ConnectOutcome.BUSY
                        else -> ConnectOutcome.FAILED
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "connect failed: ${e.javaClass.simpleName} ${e.message}")
                    runCatching { client.close() }
                    ConnectOutcome.FAILED
                }
            }.get()
        } catch (e: Exception) {
            Log.d(TAG, "connect submit failed: ${e.message}")
            ConnectOutcome.FAILED
        }
    }

    








    fun pair(
        host: String,
        protoPort: Int,
        guid16: ByteArray,
        friendlyName: String,
        code: String,
        onEvent: PtpIpClient.EventListener? = null,
    ): PairOutcome {
        sessions[host]?.let { if (it.client.isConnected) disconnect(host) }
        return try {
            ioExecutor.submit<PairOutcome> {
                val client = PtpIpClient(
                    host = host,
                    protoPort = protoPort,
                    guid16 = guid16,
                    friendlyName = friendlyName,
                    socketFactory = socketFactoryOrNull(),
                )
                try {
                    val codeChecked = client.pairAndConnect(code, PAIR_TIMEOUT_MS)
                    if (onEvent != null) {
                        runCatching { client.openEventChannel(onEvent, CONNECT_TIMEOUT_MS) }
                    }
                    sessions[host] = Session(host, protoPort, client)
                    if (codeChecked) PairOutcome.PAIRED else PairOutcome.ALREADY_PAIRED
                } catch (e: PairingClient.DeviceBusyException) {
                    Log.d(TAG, "pair busy: ${e.message}")
                    runCatching { client.close() }
                    PairOutcome.BUSY
                } catch (e: Exception) {
                    Log.d(TAG, "pair failed: ${e.javaClass.simpleName} ${e.message}")
                    runCatching { client.close() }
                    PairOutcome.FAILED
                }
            }.get()
        } catch (e: Exception) {
            Log.d(TAG, "pair submit failed: ${e.message}")
            PairOutcome.FAILED
        }
    }

    fun sessionOf(host: String): Session? = sessions[host]

    fun isConnected(host: String): Boolean = sessions[host]?.client?.isConnected == true

    fun disconnect(host: String) {
        sessions.remove(host)?.let { runCatching { it.close() } }
    }

    fun closeAll() {
        for (h in sessions.keys.toList()) disconnect(h)
    }

    
    fun cameraNameOf(host: String): String? = sessions[host]?.cameraName

    
    
    

    data class FtpEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val timestamp: Long,
    ) {
        val ext: String
            get() = name.substringAfterLast('.', "").lowercase()
    }

    
    @Throws(IOException::class)
    fun list(host: String, dir: String): List<FtpEntry> = try {
        ioExecutor.submit<List<FtpEntry>> {
            val client = requireClient(host)
            val json = client.listDir(dir)
            parseEntries(String(json, Charsets.UTF_8), dir)
        }.get()
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "list failed: ${e.message}")
        throw IOException("目录读取失败", e)
    }

    
    @Throws(IOException::class)
    fun stat(host: String, path: String): PtpIpClient.StatInfo = try {
        ioExecutor.submit<PtpIpClient.StatInfo> { requireClient(host).stat(path) }.get()
    } catch (e: Exception) {
        Log.d(TAG, "stat failed: ${e.message}")
        throw IOException("对象信息读取失败", e)
    }

    
    @Throws(IOException::class)
    fun ping(host: String): PtpIpClient.PingInfo = try {
        ioExecutor.submit<PtpIpClient.PingInfo> { requireClient(host).ping() }.get()
    } catch (e: Exception) {
        Log.d(TAG, "ping failed: ${e.message}")
        throw IOException("心跳失败", e)
    }

    
    fun deviceInfo(host: String): JSONObject? = try {
        ioExecutor.submit<JSONObject?> {
            val bytes = requireClient(host).deviceInfo()
            runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
        }.get()
    } catch (e: Exception) {
        Log.d(TAG, "deviceInfo failed: ${e.message}")
        null
    }

    



    fun pairRemove(host: String): Boolean = try {
        ioExecutor.submit<Boolean> { requireClient(host).pairRemove() }.get()
    } catch (e: Exception) {
        Log.d(TAG, "pairRemove failed: ${e.message}")
        false
    }

    
    fun thumbQueue(host: String, op: Int, paths: List<String>): Boolean = try {
        ioExecutor.submit<Boolean> { requireClient(host).thumbQueue(op, paths) }.get()
    } catch (e: Exception) {
        Log.d(TAG, "thumbQueue failed: ${e.message}")
        false
    }

    fun recEnter(host: String) {
        ioExecutor.submit<Boolean> { requireClient(host).recEnter(); true }.get()
    }

    fun recLeave(host: String) {
        try {
            ioExecutor.submit<Boolean> { requireClient(host).recLeave(); true }.get()
        } catch (e: Exception) {
            Log.d(TAG, "recLeave failed: ${e.message}")
        }
    }

    fun recState(host: String): JSONObject? = try {
        ioExecutor.submit<JSONObject?> {
            val bytes = requireClient(host).recState()
            runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
        }.get()
    } catch (e: Exception) {
        Log.d(TAG, "recState failed: ${e.message}")
        null
    }

    fun recLvStart(host: String): Int =
        ioExecutor.submit<Int> { requireClient(host).recLvStart() }.get()

    fun recLvStop(host: String) {
        try {
            ioExecutor.submit<Boolean> { requireClient(host).recLvStop(); true }.get()
        } catch (e: Exception) {
            Log.d(TAG, "recLvStop failed: ${e.message}")
        }
    }

    fun recShoot(host: String): String =
        ioExecutor.submit<String> { requireClient(host).recShoot() }.get()

    fun recAf(host: String, on: Boolean) {
        ioExecutor.submit<Boolean> { requireClient(host).recAf(on); true }.get()
    }

    fun recZoom(host: String, dir: Int, speed: Int) {
        ioExecutor.submit<Boolean> { requireClient(host).recZoom(dir, speed); true }.get()
    }

    fun recSetProp(host: String, key: String, value: String) {
        ioExecutor.submit<Boolean> { requireClient(host).recSetProp(key, value); true }.get()
    }

    fun recTouchAf(host: String, xMilli: Int, yMilli: Int) {
        ioExecutor.submit<Boolean> { requireClient(host).recTouchAf(xMilli, yMilli); true }.get()
    }

    fun recMovie(host: String, start: Boolean) {
        ioExecutor.submit<Boolean> { requireClient(host).recMovie(start); true }.get()
    }

    
    internal fun parseEntries(json: String, dir: String): List<FtpEntry> {
        val text = json.trim()
        if (text.isEmpty()) return emptyList()
        val arr: JSONArray = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            JSONObject(text).optJSONArray("entries") ?: JSONArray()
        }
        val out = ArrayList<FtpEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isEmpty() || name == "." || name == "..") continue
            out.add(
                FtpEntry(
                    name = name,
                    path = joinPath(dir, name),
                    isDir = o.optBoolean("dir", false),
                    size = o.optLong("size", 0L),
                    timestamp = o.optLong("mtime", 0L),
                )
            )
        }
        return out.sortedWith(
            compareByDescending<FtpEntry> { it.isDir }.thenBy { it.name.lowercase() }
        )
    }

    
    
    

    
    fun fetchVirtualThumb(host: String, cameraPath: String): ByteArray? =
        fetchObject(host, normalize(cameraPath), PtpCodec.KIND_THUMB, IO_TIMEOUT_MS)

    
    fun fetchVirtualPreview(host: String, cameraPath: String): ByteArray? =
        fetchObject(host, normalize(cameraPath), PtpCodec.KIND_PREVIEW, IO_TIMEOUT_MS)

    private fun normalize(cameraPath: String): String =
        if (cameraPath.startsWith("/")) cameraPath else "/$cameraPath"

    private fun fetchObject(host: String, path: String, kind: Int, timeoutMs: Int): ByteArray? = try {
        xferExecutor.submit<ByteArray?> {
            val client = requireClient(host)
            val ticket = client.getObjectWhole(path, kind)
            val out = ByteArrayOutputStream(if (kind == PtpCodec.KIND_THUMB) 16 * 1024 else 512 * 1024)
            var total = -1L
            val received = DataChannel(
                host = host,
                filePort = ticket.filePort,
                guid16 = client.cameraGuid16,
                connNo = client.connNo,
                socketFactory = socketFactoryOrNull(),
            ).use { ch ->
                ch.download(ticket.token, timeoutMs, onTotal = { total = it }) { buf, off, len ->
                    out.write(buf, off, len)
                }
            }
            
            
            
            
            
            if (total >= 0 && received != total) {
                Log.d(TAG, "fetchObject 短收 kind=$kind path=$path 收到 $received / 共 $total")
                null
            } else {
                out.toByteArray()
            }
        }.get()
    } catch (e: Exception) {
        Log.d(TAG, "fetchObject failed kind=$kind path=$path: ${e.message}")
        null
    }

    
    
    

    enum class PumpResult { COMPLETED, FAILED, CANCELLED }

    









    fun pumpToStream(
        host: String,
        path: String,
        startOffset: Long,
        out: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): PumpResult {
        var total = -1L
        return try {
            xferExecutor.submit<PumpResult> {
                val client = requireClient(host)
                val ticket = client.getObject(path, PtpCodec.KIND_ORIGINAL, startOffset, -1)

                var written = startOffset
                
                
                
                
                
                var lastReported = written
                var lastTick = 0L
                fun report(now: Long) {
                    if (now - lastReported < 512 * 1024 &&
                        System.currentTimeMillis() - lastTick < 120
                    ) {
                        return
                    }
                    lastReported = now
                    lastTick = System.currentTimeMillis()
                    onProgress(now)
                }
                val received = DataChannel(
                    host = host,
                    filePort = ticket.filePort,
                    guid16 = client.cameraGuid16,
                    connNo = client.connNo,
                    socketFactory = socketFactoryOrNull(),
                ).use { ch ->
                    ch.download(
                        token = ticket.token,
                        timeoutMs = IO_TIMEOUT_MS,
                        onTotal = { total = it },
                        cancelled = cancelled,
                        onChunk = { buf, off, len ->
                            out.write(buf, off, len)
                            written += len
                            report(written)
                        },
                    )
                }
                
                onProgress(written)

                when {
                    cancelled() -> PumpResult.CANCELLED
                    total >= 0 && received == total -> PumpResult.COMPLETED
                    else -> {
                        Log.d(TAG, "pump 短收: received=$received total=$total")
                        PumpResult.FAILED
                    }
                }
            }.get()
        } catch (e: Exception) {
            Log.d(TAG, "pump failed: ${e.message}")
            if (cancelled()) PumpResult.CANCELLED else PumpResult.FAILED
        }
    }

    
    
    

    @Throws(IOException::class)
    private fun requireClient(host: String): PtpIpClient {
        val s = sessions[host] ?: throw IOException("未连接相机 $host")
        if (!s.client.isConnected) {
            disconnect(host)
            throw IOException("相机连接已断开 $host")
        }
        return s.client
    }

    
    
    

    fun joinPath(dir: String, name: String): String {
        val d = if (dir.endsWith("/")) dir else "$dir/"
        return "$d$name"
    }

    fun parentOf(path: String): String {
        val p = path.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }

    fun breadcrumbOf(path: String): List<Pair<String, String>> {
        
        val out = mutableListOf<Pair<String, String>>("/" to "/")
        var acc = ""
        path.trim('/').split("/").filter { it.isNotBlank() }.forEach { seg ->
            acc += "/$seg"
            out.add(seg to acc)
        }
        return out
    }
}
