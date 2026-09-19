package com.bi2qfa.sonyconnect.ftp

import android.net.Network
import android.util.Log
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * FTP 访问层（2121）。沿用上一代验证过的两阶段 connect 模式：
 * setSoTimeout 必须在 connect 之后调用（commons-net 在连接前调用会 NPE），
 * 以及 Network 绑定（热点无互联网标记时防止流量甩到蜂窝）。
 *
 * 新增：虚拟缩略图路径拉取（/.sonyconnect/th|pv/）。
 */
object FtpRepository {

    private const val TAG = "FtpRepository"
    private const val CONNECT_TIMEOUT_MS = 3000

    @Volatile
    var boundNetwork: Network? = null

    private val browseExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FtpBrowse").apply { isDaemon = true }
    }

    private var browseClient: FTPClient? = null
    private val browseLock = Any()

    // ===== 生命周期 =====

    fun closeAll() {
        synchronized(browseLock) {
            disconnectQuiet(browseClient)
            browseClient = null
        }
    }

    private fun disconnectQuiet(c: FTPClient?) {
        if (c == null) return
        runCatching {
            if (c.isConnected) {
                c.logout()
                c.disconnect()
            }
        }
    }

    // ===== 连接 =====

    private fun newClient(host: String): FTPClient {
        val c = FTPClient()
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.defaultTimeout = CONNECT_TIMEOUT_MS
        c.controlEncoding = "UTF-8"
        boundNetwork?.let { n -> runCatching { c.setSocketFactory(n.socketFactory) } }
        c.connect(host, 2121)
        // 两阶段：连接成功后才设读超时（连接前设会 NPE）
        runCatching { c.soTimeout = 30_000 }
        return c
    }

    private fun login(c: FTPClient): Boolean {
        return c.login("anonymous", "sonyconnect@")
    }

    private fun ensureBrowseClient(host: String): FTPClient {
        browseClient?.let { existing ->
            if (runCatching { existing.isAvailable }.getOrDefault(false)) return existing
            disconnectQuiet(existing)
            browseClient = null
        }
        val c = newClient(host)
        if (!login(c)) {
            runCatching { c.disconnect() }
            throw IOException("FTP 登录失败")
        }
        c.enterLocalPassiveMode()
        c.setFileType(FTPClient.BINARY_FILE_TYPE)
        browseClient = c
        return c
    }

    fun connect(host: String): Boolean = try {
        browseExecutor.submit<Boolean> {
            synchronized(browseLock) {
                ensureBrowseClient(host)
                true
            }
        }.get()
    } catch (e: Exception) {
        Log.d(TAG, "connect failed: ${e.message}")
        false
    }

    // ===== 目录浏览 =====

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

    fun list(host: String, dir: String): List<FtpEntry> = try {
        browseExecutor.submit<List<FtpEntry>> {
            synchronized(browseLock) {
                val c = ensureBrowseClient(host)
                if (!c.changeWorkingDirectory(dir)) throw IOException("无法进入目录 $dir")
                val files: Array<FTPFile> = c.listFiles() ?: emptyArray()
                files.filter { it.name != "." && it.name != ".." }
                    .map {
                        FtpEntry(
                            name = it.name,
                            path = joinPath(dir, it.name),
                            isDir = it.isDirectory,
                            size = it.size,
                            timestamp = it.timestamp?.timeInMillis ?: 0L,
                        )
                    }
                    .sortedWith(compareByDescending<FtpEntry> { it.isDir }.thenBy { it.name.lowercase() })
            }
        }.get()
    } catch (e: Exception) {
        Log.d(TAG, "list failed: ${e.message}")
        throw IOException("目录读取失败", e)
    }

    // ===== 缩略图 / 预览（虚拟路径，短连接） =====

    /** 小缩略图：/.sonyconnect/th/<相机相对路径>（~6KB，毫秒级） */
    fun fetchVirtualThumb(host: String, cameraPath: String): ByteArray? =
        fetchVirtual(host, "/.sonyconnect/th" + normalize(cameraPath))

    /** 大预览：/.sonyconnect/pv/<相机相对路径>（1616x1080，~0.4MB） */
    fun fetchVirtualPreview(host: String, cameraPath: String): ByteArray? =
        fetchVirtual(host, "/.sonyconnect/pv" + normalize(cameraPath))

    private fun normalize(cameraPath: String): String {
        val p = if (cameraPath.startsWith("/")) cameraPath else "/$cameraPath"
        return p
    }

    private fun fetchVirtual(host: String, ftpPath: String): ByteArray? {
        var c: FTPClient? = null
        try {
            c = newClient(host)
            if (!login(c)) return null
            c.enterLocalPassiveMode()
            c.setFileType(FTPClient.BINARY_FILE_TYPE)
            val stream = c.retrieveFileStream(ftpPath) ?: return null
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (stream.read(buf).also { n = it } > 0) out.write(buf, 0, n)
            stream.close()
            return if (c.completePendingCommand()) out.toByteArray() else null
        } catch (e: Exception) {
            Log.d(TAG, "fetchVirtual failed: ${e.message}")
            return null
        } finally {
            disconnectQuiet(c)
        }
    }

    // ===== 下载（REST 断点续传） =====

    enum class PumpResult { COMPLETED, FAILED, CANCELLED }

    data class PumpStats(var bytes: Long = 0)

    /**
     * 从 startOffset 续传下载到 out；cancelled() 每 chunk 检查。
     * 复用上一代语义：226=COMPLETED；中断=CANCELLED（进度保留可续传）；其余=FAILED。
     */
    fun pumpToStream(
        host: String,
        path: String,
        startOffset: Long,
        out: OutputStream,
        cancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
    ): PumpResult {
        var c: FTPClient? = null
        try {
            c = newClient(host)
            if (!login(c)) return PumpResult.FAILED
            c.enterLocalPassiveMode()
            c.setFileType(FTPClient.BINARY_FILE_TYPE)
            if (startOffset > 0) {
                c.restartOffset = startOffset
            }
            val stream = c.retrieveFileStream(path) ?: return PumpResult.FAILED
            val buf = ByteArray(64 * 1024)
            var total = startOffset
            var n: Int
            while (stream.read(buf).also { n = it } > 0) {
                if (cancelled()) {
                    runCatching { stream.close() }
                    runCatching { c.abort() }
                    runCatching { c.completePendingCommand() }
                    return PumpResult.CANCELLED
                }
                out.write(buf, 0, n)
                total += n
                onProgress(total)
            }
            stream.close()
            return if (c.completePendingCommand()) PumpResult.COMPLETED else PumpResult.FAILED
        } catch (e: Exception) {
            Log.d(TAG, "pump failed: ${e.message}")
            return PumpResult.FAILED
        } finally {
            disconnectQuiet(c)
        }
    }

    // ===== 路径小件 =====

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
        // [("根", "/"), ("DCIM", "/DCIM"), ...]
        val out = mutableListOf<Pair<String, String>>("/" to "/")
        var acc = ""
        path.trim('/').split("/").filter { it.isNotBlank() }.forEach { seg ->
            acc += "/$seg"
            out.add(seg to acc)
        }
        return out
    }
}
