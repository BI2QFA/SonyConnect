package com.bi2qfa.sonyconnect.ptpip

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory


class InitFailedException(val reason: Int) :
    IOException("相机拒绝连接：Init Fail reason=0x${Integer.toHexString(reason)}")


class PtpProtocolException(message: String) : IOException(message)















class PtpIpClient(
    private val host: String,
    private val protoPort: Int,
    
    private val guid16: ByteArray,
    
    private val friendlyName: String,
    



    private val socketFactory: SocketFactory? = null,
) : Closeable {

    
    fun interface EventListener {
        fun onEvent(code: Int, txId: Int, params: IntArray)
    }

    





    class PingInfo(val batteryPct: Int, val lens: String)

    class StatInfo(val size: Long, val mtime: Long)

    
    class TransferTicket(val token: Long, val filePort: Int)

    
    var cameraGuid16: ByteArray = ByteArray(16)
        private set
    var cameraName: String = ""
        private set
    var protoVersion: Int = 0
        private set

    
    var connNo: Int = 0
        private set

    private var control: Link? = null
    private var event: Link? = null

    private val txCounter = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<OpResult>>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var eventListener: EventListener? = null

    
    
    

    
    @Throws(IOException::class)
    fun connect(timeoutMs: Int = 8000) {
        val link = openLink(timeoutMs)
        withHandshakeTimeout(link, timeoutMs) { initHandshake(link) }
        link.startReader { m -> onControlMessage(m) }
    }

    









    @Throws(IOException::class)
    fun pairAndConnect(code: String, timeoutMs: Int = 20000): Boolean {
        val link = openLink(timeoutMs)
        withHandshakeTimeout(link, timeoutMs) { initHandshake(link) }
        val pairing = PairingClient(
            sendReq = { op, tx, blob -> link.send(PtpCodec.opReqBlob(op, tx, blob)) },
            recvRsp = { link.recvPlainOpBlob() },
        )
        val result = try {
            pairing.run(code, friendlyName)
        } catch (e: Exception) {
            
            pairing.abort()
            link.closeQuietly()
            throw e
        }
        link.startReader { m -> onControlMessage(m) }
        return !result.alreadyPaired
    }

    private fun openLink(timeoutMs: Int): Link {
        val socket = socketFactory?.createSocket() ?: Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, protoPort), timeoutMs)
        val link = Link(socket, "control")
        control = link
        return link
    }

    










    private fun withHandshakeTimeout(link: Link, timeoutMs: Int, body: () -> Unit) {
        link.soTimeoutOf(timeoutMs)
        try {
            body()
        } finally {
            link.soTimeoutOf(0)
        }
    }

    
    @Throws(IOException::class)
    private fun initHandshake(link: Link) {
        link.send(PtpCodec.initCmdReq(guid16, friendlyName, PROTO_VERSION))
        val first = link.recvMsg() ?: throw PtpProtocolException("相机在握手前关闭连接")

        if (first.type == PtpCodec.T_INIT_FAIL) {
            val reason = if (first.body.size >= 4) PtpCodec.i32(first.body, 0) else -1
            link.closeQuietly()
            throw InitFailedException(reason)
        }
        if (first.type != PtpCodec.T_INIT_CMD_ACK) {
            link.closeQuietly()
            throw PtpProtocolException("期望 Init Command Ack，收到 type=0x${Integer.toHexString(first.type)}")
        }
        if (first.body.size < 21) {
            link.closeQuietly()
            throw PtpProtocolException("Init Command Ack 过短: ${first.body.size}")
        }
        connNo = PtpCodec.i32(first.body, 0)
        cameraGuid16 = first.body.copyOfRange(4, 20)
        val next = IntArray(1)
        cameraName = PtpCodec.readName(first.body, 20, next)
        protoVersion = if (next[0] + 4 <= first.body.size) PtpCodec.i32(first.body, next[0]) else 0
    }

    
    

    





    @Throws(IOException::class)
    fun openEventChannel(listener: EventListener, timeoutMs: Int = 8000) {
        eventListener = listener
        val socket = socketFactory?.createSocket() ?: Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, protoPort), timeoutMs)
        val link = Link(socket, "event")
        event = link

        link.send(PtpCodec.initEventReq(connNo))
        val ack = link.recvMsg() ?: throw PtpProtocolException("事件连接：相机未回 Init Event Ack")
        if (ack.type != PtpCodec.T_INIT_EVENT_ACK) {
            link.closeQuietly()
            throw PtpProtocolException("事件连接：期望 Init Event Ack，收到 type=0x${Integer.toHexString(ack.type)}")
        }
        link.startReader { m ->
            if (m.type != PtpCodec.T_EVENT) return@startReader
            try {
                val ev = PtpCodec.parseEvent(m.body)
                eventListener?.onEvent(ev.code, ev.txId, ev.params)
            } catch (ignored: IOException) {
                
            }
        }
    }

    
    
    

    private fun onControlMessage(m: PtpCodec.Msg) {
        when (m.type) {
            PtpCodec.T_OPERATION_RSP -> {
                val res = try {
                    parseResponse(m.body)
                } catch (e: IOException) {
                    return
                }
                pending.remove(res.txId)?.offer(res)
            }
            PtpCodec.T_EVENT -> {
                try {
                    val ev = PtpCodec.parseEvent(m.body)
                    eventListener?.onEvent(ev.code, ev.txId, ev.params)
                } catch (ignored: IOException) {
                }
            }
            else -> {
                
            }
        }
    }

    
    
    

    private class OpResult(
        val code: Int,
        val txId: Int,
        val params: IntArray,
        val blob: ByteArray,
    )

    
    @Throws(IOException::class)
    private fun parseResponse(plain: ByteArray): OpResult {
        if (plain.size >= 4 && PtpCodec.i32(plain, 0) == PtpCodec.DP_DATA_IN) {
            val b = PtpCodec.parseOpBlob(plain)
            return OpResult(b.code, b.txId, b.params, b.blob)
        }
        val o = PtpCodec.parseOp(plain)
        return OpResult(o.code, o.txId, o.params, ByteArray(0))
    }

    




    @Throws(IOException::class)
    private fun request(frame: ByteArray, timeoutMs: Int = 8000): OpResult {
        val link = control ?: throw PtpProtocolException("控制连接未建立")
        val txId = PtpCodec.i32(frame, PtpCodec.HEADER_LEN + 6)
        val q = ArrayBlockingQueue<OpResult>(1)
        pending[txId] = q
        try {
            link.send(frame)
            val res = q.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                ?: throw PtpProtocolException("操作超时（tx=$txId）")
            return res
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PtpProtocolException("操作被中断（tx=$txId）")
        } finally {
            pending.remove(txId)
        }
    }

    private fun nextTx(): Int = txCounter.incrementAndGet()

    private fun pathBytes(path: String): ByteArray = path.toByteArray(Charsets.UTF_8)

    
    
    

    
    @Throws(IOException::class)
    






    fun ping(): PingInfo {
        val tx = nextTx()
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_PING, tx, null))
        checkOk(r)
        val bat = if (r.params.isNotEmpty()) r.params[0] else -1
        val lens = r.blob.toString(Charsets.UTF_8)
        return PingInfo(bat, lens)
    }

    
    fun pairRemove(): Boolean {
        val tx = nextTx()
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_PAIR_REMOVE, tx, null))
        return r.code == PtpCodec.RC_OK
    }

    
    @Throws(IOException::class)
    fun deviceInfo(): ByteArray {
        val tx = nextTx()
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_DEVICE_INFO, tx, null))
        checkOk(r)
        return r.blob
    }

    
    @Throws(IOException::class)
    fun listDir(path: String): ByteArray {
        val tx = nextTx()
        val r = request(PtpCodec.opReqBlob(PtpCodec.OP_LIST_DIR, tx, pathBytes(path)))
        checkOk(r)
        return r.blob
    }

    
    @Throws(IOException::class)
    fun stat(path: String): StatInfo {
        val tx = nextTx()
        val r = request(PtpCodec.opReqBlob(PtpCodec.OP_STAT, tx, pathBytes(path)))
        checkOk(r)
        if (r.params.size < 4) throw PtpProtocolException("STAT 参数不足: ${r.params.size}")
        val size = (r.params[0].toLong() and 0xFFFFFFFFL) or ((r.params[1].toLong() and 0xFFFFFFFFL) shl 32)
        val mtime = (r.params[2].toLong() and 0xFFFFFFFFL) or ((r.params[3].toLong() and 0xFFFFFFFFL) shl 32)
        return StatInfo(size, mtime)
    }

    






    @Throws(IOException::class)
    fun getObject(path: String, kind: Int, offset: Long, length: Int): TransferTicket {
        val tx = nextTx()
        val params = intArrayOf(
            kind,
            (offset and 0xFFFFFFFFL).toInt(),
            ((offset ushr 32) and 0xFFFFFFFFL).toInt(),
            length,
        )
        val r = request(PtpCodec.opReqBlobParams(PtpCodec.OP_GET_OBJECT, tx, params, pathBytes(path)))
        checkOk(r)
        if (r.params.size < 2) throw PtpProtocolException("GET_OBJECT 参数不足: ${r.params.size}")
        val token = r.params[0].toLong() and 0xFFFFFFFFL
        val filePort = r.params[1]
        return TransferTicket(token, filePort)
    }

    


    @Throws(IOException::class)
    






    fun getObjectWhole(path: String, kind: Int): TransferTicket =
        getObject(path, kind, 0L, -1)

    
    @Throws(IOException::class)
    fun thumbQueue(op: Int, paths: List<String>): Boolean {
        val tx = nextTx()
        val text = paths.joinToString("\n")
        val r = request(PtpCodec.opReqBlob(op, tx, pathBytes(text)))
        return r.code == PtpCodec.RC_OK
    }

    @Throws(IOException::class)
    fun recEnter() {
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_REC_ENTER, nextTx(), null), 15000)
        checkOk(r)
    }

    @Throws(IOException::class)
    fun recLeave() {
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_REC_LEAVE, nextTx(), null))
        checkOk(r)
    }

    @Throws(IOException::class)
    fun recState(): ByteArray {
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_REC_GET_STATE, nextTx(), null))
        checkOk(r)
        return r.blob
    }

    @Throws(IOException::class)
    fun recLvStart(): Int {
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_LV_START, nextTx(), null))
        checkOk(r)
        if (r.params.isEmpty()) throw PtpProtocolException("LV_START 未返回端口")
        return r.params[0]
    }

    @Throws(IOException::class)
    fun recLvStop() {
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_LV_STOP, nextTx(), null))
        checkOk(r)
    }

    @Throws(IOException::class)
    fun recShoot(): String {
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_SHOOT, nextTx(), null), 25000)
        checkOk(r)
        return r.blob.toString(Charsets.UTF_8)
    }

    @Throws(IOException::class)
    fun recAf(on: Boolean) {
        val op = if (on) PtpCodec.OP_AF_HALF else PtpCodec.OP_AF_CANCEL
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, op, nextTx(), null), 12000)
        checkOk(r)
    }

    @Throws(IOException::class)
    fun recZoom(dir: Int, speed: Int) {
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_ZOOM, nextTx(), intArrayOf(dir, speed)))
        checkOk(r)
    }

    @Throws(IOException::class)
    fun recSetProp(key: String, value: String) {
        val r = request(PtpCodec.opReqBlob(PtpCodec.OP_SET_PROP, nextTx(), pathBytes("$key=$value")))
        checkOk(r)
    }

    @Throws(IOException::class)
    fun recTouchAf(xMilli: Int, yMilli: Int) {
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_TOUCH_AF, nextTx(), intArrayOf(xMilli, yMilli)))
        checkOk(r)
    }

    @Throws(IOException::class)
    fun recMovie(start: Boolean) {
        val op = if (start) PtpCodec.OP_MOVIE_START else PtpCodec.OP_MOVIE_STOP
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, op, nextTx(), null), 15000)
        checkOk(r)
    }

    private fun checkOk(r: OpResult) {
        if (r.code != PtpCodec.RC_OK) {
            if (r.code == CODE_LOCAL_CLOSED) {
                throw PtpProtocolException("连接已关闭，未收到相机应答")
            }
            val extra = r.blob.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
            val hex = "0x${Integer.toHexString(r.code)}"
            throw PtpProtocolException(if (extra.isNullOrBlank()) "相机回错误码 $hex" else extra)
        }
    }

    
    
    

    val isConnected: Boolean
        get() = !closed.get() && control?.isAlive == true

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        
        try {
            event?.closeQuietly()
        } catch (ignored: Exception) {
        }
        try {
            control?.closeQuietly()
        } catch (ignored: Exception) {
        }
        event = null
        control = null
        
        
        
        
        val code = CODE_LOCAL_CLOSED
        for (key in pending.keys.toList()) {
            pending.remove(key)?.offer(OpResult(code, key, IntArray(0), ByteArray(0)))
        }
        eventListener = null
    }

    
    
    

    





    private inner class Link(private val socket: Socket, private val label: String) {

        
        fun soTimeoutOf(ms: Int) {
            runCatching { socket.soTimeout = ms }
        }

        val input: InputStream = socket.getInputStream()
        val output: OutputStream = socket.getOutputStream()

        private val sendLock = Any()

        private val alive = AtomicBoolean(true)

        val isAlive: Boolean get() = alive.get() && !socket.isClosed

        fun send(framed: ByteArray) {
            synchronized(sendLock) {
                output.write(framed)
                output.flush()
            }
        }

        
        fun recvMsg(): PtpCodec.Msg? = PtpCodec.read(input)

        




        fun recvPlainOpBlob(): PtpCodec.OpBlob {
            val m = recvMsg() ?: throw PtpProtocolException("配对阶段连接被关闭")
            if (PtpCodec.i32(m.body, 0) == PtpCodec.DP_DATA_IN) {
                return PtpCodec.parseOpBlob(m.body)
            }
            val o = PtpCodec.parseOp(m.body)
            val out = PtpCodec.OpBlob()
            out.dataPhase = o.dataPhase
            out.code = o.code
            out.txId = o.txId
            out.params = o.params
            out.blob = ByteArray(0)
            return out
        }

        
        fun startReader(onMsg: (PtpCodec.Msg) -> Unit) {
            val t = Thread({
                try {
                    while (alive.get() && !socket.isClosed) {
                        val m = PtpCodec.read(input) ?: break
                        onMsg(m)
                    }
                } catch (ignored: IOException) {
                    
                } catch (ignored: SecurityException) {
                    
                } finally {
                    alive.set(false)
                }
            }, "ptpip-$label-reader")
            t.isDaemon = true
            t.start()
        }

        fun closeQuietly() {
            alive.set(false)
            try {
                socket.close()
            } catch (ignored: IOException) {
            }
        }
    }


    companion object {
        
        const val PROTO_VERSION = (1 shl 16)

        



        private const val CODE_LOCAL_CLOSED = -1
    }
}

