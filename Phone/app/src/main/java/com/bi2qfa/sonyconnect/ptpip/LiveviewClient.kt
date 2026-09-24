package com.bi2qfa.sonyconnect.ptpip

import android.net.Network
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

class LiveviewClient(
    private val host: String,
    private val port: Int,
    private val network: Network? = null,
) : AutoCloseable {

    fun interface FrameListener {
        fun onJpeg(jpeg: ByteArray)
    }

    /** 连接状态回调：connected / failed:原因 / closed。真机排障用。 */
    fun interface StateListener {
        fun onState(state: String)
    }

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var thread: Thread? = null

    fun start(listener: FrameListener, stateListener: StateListener? = null) {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({
            try {
                readLoop(listener, stateListener)
            } catch (_: IOException) {
            } finally {
                running.set(false)
                closeQuietly()
            }
        }, "liveview-client")
        t.isDaemon = true
        thread = t
        t.start()
    }

    override fun close() {
        running.set(false)
        closeQuietly()
        thread = null
    }

    private fun readLoop(listener: FrameListener, stateListener: StateListener?) {
        val factory: SocketFactory = network?.socketFactory ?: SocketFactory.getDefault()
        val s = try {
            factory.createSocket().also {
                it.tcpNoDelay = true
                it.soTimeout = 8000
                it.connect(InetSocketAddress(host, port), 5000)
            }
        } catch (t: Throwable) {
            stateListener?.onState("failed:${t.message ?: t.javaClass.simpleName}")
            return
        }
        socket = s
        stateListener?.onState("connected")
        val input = s.getInputStream()
        try {
            while (running.get() && !s.isClosed) {
                val jpeg = readFrame(input) ?: break
                if (jpeg.isNotEmpty()) listener.onJpeg(jpeg)
            }
        } finally {
            stateListener?.onState("closed")
        }
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val common = ByteArray(8)
        readFully(input, common) ?: return null
        if (common[0] != 0xFF.toByte()) return null
        val payloadType = common[1].toInt() and 0xFF
        val header = ByteArray(128)
        readFully(input, header) ?: return null
        val size = ((header[4].toInt() and 0xFF) shl 16) or
            ((header[5].toInt() and 0xFF) shl 8) or
            (header[6].toInt() and 0xFF)
        val pad = header[7].toInt() and 0xFF
        if (size <= 0 || size > 2_000_000) return ByteArray(0)
        val jpeg = ByteArray(size)
        readFully(input, jpeg) ?: return null
        if (pad > 0) skip(input, pad)
        return if (payloadType == 0x01) jpeg else ByteArray(0)
    }

    private fun readFully(input: InputStream, buf: ByteArray): ByteArray? {
        var off = 0
        while (off < buf.size) {
            val n = try {
                input.read(buf, off, buf.size - off)
            } catch (_: IOException) {
                return null
            }
            if (n < 0) return null
            off += n
        }
        return buf
    }

    private fun skip(input: InputStream, n: Int) {
        var left = n
        val buf = ByteArray(256)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size))
            if (r < 0) throw EOFException()
            left -= r
        }
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}
