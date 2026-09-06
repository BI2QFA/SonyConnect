package com.bi2qfa.sonyconnect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.bi2qfa.sonyconnect.ftp.FtpRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object ThumbStore {

    enum class ThumbState { NONE, LOADING, READY, FAILED }

    private lateinit var appContext: Context
    private lateinit var smallDiskDir: File
    private lateinit var previewDiskDir: File

    val states = mutableStateMapOf<String, ThumbState>()

    var paused by mutableStateOf(false)
        private set

    var batchDone by mutableStateOf(0)
        private set
    var batchTotal by mutableStateOf(0)
        private set

    private val queue = ArrayDeque<String>()
    private val queuedSet = HashSet<String>()
    private val lock = Object()
    private val started = AtomicBoolean(false)
    private var host: String? = null

    private val memoryCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        smallDiskDir = File(appContext.cacheDir, "thumbs").apply { mkdirs() }
        previewDiskDir = File(appContext.cacheDir, "previews").apply { mkdirs() }
        if (started.compareAndSet(false, true)) {
            Thread({
                fetchLoop()
            }, "ThumbFetch").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun markBatchQueued(paths: List<String>) {
        synchronized(lock) {
            host = com.bi2qfa.sonyconnect.core.ConnectionCenter.host
            batchTotal = paths.size
            batchDone = 0
            for (p in paths) {
                if (states[p] == null) states[p] = ThumbState.NONE
                if (queuedSet.add(p)) queue.add(p)
            }
            lock.notifyAll()
        }
    }

    fun request(paths: List<String>) {
        synchronized(lock) {
            host = com.bi2qfa.sonyconnect.core.ConnectionCenter.host
            var added = false
            for (p in paths) {
                val st = states[p]
                if (st == null || st == ThumbState.FAILED) {
                    states[p] = ThumbState.NONE
                    if (queuedSet.add(p)) {
                        queue.add(p)
                        added = true
                    }
                }
            }
            if (added) lock.notifyAll()
        }
    }

    fun pauseForTransfer() {
        paused = true
    }

    fun resumeAfterTransfer() {
        paused = false
        synchronized(lock) { lock.notifyAll() }
    }

    fun smallThumb(path: String): ImageBitmap? {
        memoryCache.get(path)?.let { return it.asImageBitmap() }
        val f = smallDiskFile(path)
        if (f.isFile) {
            runCatching {
                BitmapFactory.decodeFile(f.absolutePath)?.let {
                    memoryCache.put(path, it)
                    return it.asImageBitmap()
                }
            }
        }
        return null
    }

    fun fetchPreviewBlocking(path: String): ImageBitmap? {
        val f = previewDiskFile(path)
        if (f.isFile) {
            runCatching {
                BitmapFactory.decodeFile(f.absolutePath)?.let { return it.asImageBitmap() }
            }
        }
        val h = host ?: com.bi2qfa.sonyconnect.core.ConnectionCenter.host ?: return null
        val bytes = FtpRepository.fetchVirtualPreview(h, path) ?: return null
        runCatching { f.writeBytes(bytes) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }

    private fun smallDiskFile(path: String) = File(smallDiskDir, cacheKey(path) + ".jpg")

    private fun previewDiskFile(path: String) = File(previewDiskDir, cacheKey(path) + ".jpg")

    private fun cacheKey(path: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun reset() {
        synchronized(lock) {
            queue.clear()
            queuedSet.clear()
            batchDone = 0
            batchTotal = 0
            paused = false
            lock.notifyAll()
        }
        states.clear()
        memoryCache.evictAll()
    }

    private fun fetchLoop() {
        while (true) {

            val p = takeNext() ?: continue

            try {
                states[p] = ThumbState.LOADING
                val h = host ?: com.bi2qfa.sonyconnect.core.ConnectionCenter.host
                val bytes = if (h == null) null else FtpRepository.fetchVirtualThumb(h, p)
                val bmp = bytes?.let { b -> runCatching { decodeSmall(b) }.getOrNull() }
                if (bmp != null) {
                    memoryCache.put(p, bmp)
                    runCatching { smallDiskFile(p).writeBytes(bytes) }
                    states[p] = ThumbState.READY
                } else {
                    states[p] = ThumbState.FAILED
                }
            } catch (t: Throwable) {
                android.util.Log.w("ThumbStore", "thumb fetch failed: $p", t)
                runCatching { states[p] = ThumbState.FAILED }
            }
            synchronized(lock) {
                if (batchDone < batchTotal) batchDone++
            }
        }
    }

    private fun takeNext(): String? {
        synchronized(lock) {
            while (true) {
                if (paused) {
                    runCatching { lock.wait() }
                    continue
                }
                val next = queue.removeFirstOrNull()
                if (next != null) {
                    queuedSet.remove(next)
                    return next
                }
                runCatching { lock.wait() }
            }
        }
    }

    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= 320 && h / 2 >= 320) {
            sample *= 2
            w /= 2
            h /= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
