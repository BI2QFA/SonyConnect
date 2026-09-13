package com.bi2qfa.sonyconnect.ptpip

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory















class DataChannel(
    private val host: String,
    private val filePort: Int,
    
    private val guid16: ByteArray,
    
    private val connNo: Int,
    
    private val socketFactory: SocketFactory? = null,
) : Closeable {

    private var socket: Socket? = null
    private val closed = AtomicBoolean(false)

    












    @Throws(IOException::class)
    fun download(
        token: Long,
        timeoutMs: Int = 8000,
        onTotal: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
        onChunk: (ByteArray, Int, Int) -> Unit,
    ): Long {
        val s = socketFactory?.createSocket() ?: Socket()
        s.tcpNoDelay = true
        s.soTimeout = timeoutMs
        
        
        
        runCatching { s.receiveBufferSize = 1024 * 1024 }
        s.connect(InetSocketAddress(host, filePort), timeoutMs)
        socket = s

        val input = s.getInputStream()
        val output = s.getOutputStream()

        
        
        
        
        
        
        
        
        

        try {
            
            output.write(PtpCodec.dataOpen(guid16, connNo, token))
            output.flush()

            val ack = PtpCodec.read(input) ?: throw PtpProtocolException("文件端口未回 DATA_OPEN_ACK")
            if (ack.type != PtpCodec.T_DATA_OPEN_ACK) {
                throw PtpProtocolException("文件端口期望 DATA_OPEN_ACK，收到 type=0x${Integer.toHexString(ack.type)}")
            }

            
            var received = 0L
            var total = -1L

            while (!closed.get() && !cancelled()) {
                val m = PtpCodec.read(input) ?: break
                when (m.type) {
                    PtpCodec.T_START_DATA -> {
                        total = PtpCodec.totalOf(m.body)
                        onTotal(total)
                    }
                    PtpCodec.T_DATA -> {
                        val n = dataLen(m.body)
                        if (n > 0) {
                            onChunk(m.body, 4, n)
                            received += n
                        }
                    }
                    PtpCodec.T_END_DATA -> {
                        val n = dataLen(m.body)
                        if (n > 0) {
                            onChunk(m.body, 4, n)
                            received += n
                        }
                        break
                    }
                    PtpCodec.T_CANCEL -> break
                    else -> {
                        
                    }
                }
            }
            return received
        } finally {
            close()
        }
    }

    
    private fun dataLen(body: ByteArray): Int =
        if (body.size <= 4) 0 else body.size - 4

    override fun close() {
        closed.set(true)
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        socket = null
    }
}
