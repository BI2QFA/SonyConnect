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

/** 相机回 Init Fail 时抛出，[reason] 见 `PtpCodec.FAIL_*`。 */
class InitFailedException(val reason: Int) :
    IOException("相机拒绝连接：Init Fail reason=0x${Integer.toHexString(reason)}")

/** 协议层格式/时序异常。 */
class PtpProtocolException(message: String) : IOException(message)

/**
 * PTP/IP 控制连接客户端（手机端）—— 对应相机端 `PtpIpServer` 的协议端口。
 *
 * 一次 [connect] 完成：
 * 1. TCP 连协议端口 → 发 `Init Command Request` → 收 `Init Command Ack` / `Init Fail`
 * 2. 起读线程收发操作请求/响应（[request] 同步等响应）
 *
 * **全程明文**：加解密与双向认证已整体移除。准入判定只有一处 —— 相机端在 Init
 * 阶段查配对表；本端也一样（[com.bi2qfa.sonyconnect.data.PairingStore]），
 * 未配对就只能先走 [pairAndConnect]。
 *
 * 事件连接（[openEventChannel]）是同一协议端口上的第二条 TCP，只收推送。
 * 文件传输走独立文件端口，见 [DataChannel]。
 */
class PtpIpClient(
    private val host: String,
    /**
     * 协议端口。**配对路径要读它**（配对成功后 `ObjectRepository` 要用同一个端口
     * 把这条连接登记成正式会话），所以是 public val —— 其余用途都在类内部。
     */
    val protoPort: Int,
    /** 本机 16 字节 GUID（8B deviceId 补零）。 */
    private val guid16: ByteArray,
    /** 本机友好名（相机端配对页显示用）。 */
    private val friendlyName: String,
    /**
     * 可选套接字工厂。手机连相机热点（无互联网）时若不绑定，流量可能被甩到蜂窝，
     * 传入 `Network.socketFactory` 即可把本连接钉在相机网络上。null = 用默认（桌面测试友好）。
     */
    private val socketFactory: SocketFactory? = null,
) : Closeable {

    /** 事件回调（在事件读线程上触发，勿做重活）。 */
    fun interface EventListener {
        fun onEvent(code: Int, txId: Int, params: IntArray)
    }

    /**
     * PING 的实时数据。
     *
     * [lens] 为镜头名（空串 = 未装镜头或读取失败）。早先这里只有 `hasLens` 布尔值、
     * 镜头名要等 5s 一次的 DEVICE_INFO —— 现在两者同一个包，省掉一个定时器。
     */
    class PingInfo(val batteryPct: Int, val lens: String)

    class StatInfo(val size: Long, val mtime: Long)

    /** GET_OBJECT 登记成功后拿到的一次性凭据。 */
    class TransferTicket(val token: Long, val filePort: Int)

    /** 相机身份（Init Command Ack 里带回）。 */
    var cameraGuid16: ByteArray = ByteArray(16)
        private set
    var cameraName: String = ""
        private set
    var protoVersion: Int = 0
        private set

    /** 本连接的会话号（事件连接要用它挂载）。 */
    var connNo: Int = 0
        private set

    private var control: Link? = null
    private var event: Link? = null

    private val txCounter = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<OpResult>>()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var eventListener: EventListener? = null

    /** 控制连接意外断开回调。正常 [close] 不回调。 */
    @Volatile
    var onDisconnected: (() -> Unit)? = null

    // ============================================================
    // 连接与认证
    // ============================================================

    /** 已配对相机的常规连接：Init Cmd → 直接进控制循环。 */
    @Throws(IOException::class)
    fun connect(timeoutMs: Int = 8000) {
        val link = openLink(timeoutMs)
        withHandshakeTimeout(link, timeoutMs) { initHandshake(link) }
        link.startReader(onDisconnected = { notifyControlDisconnected() }) { m -> onControlMessage(m) }
    }

    /**
     * **配对**并直接建立会话（首次连接某台相机时走这条）。
     *
     * Init Cmd → `PAIR_BEGIN → PAIR_EXCHANGE`（明文核对相机屏上的 6 位码）→ 落表。
     * 之所以不重连：相机端在处理 ② 时就落表了，这条连接直接进控制循环，
     * 重连只是白白多一次往返（还多一次掉线机会）。
     *
     * @return true = 相机按 6 位码确认了这次配对；false = 相机早就认识本机
     *         （把配对请求当协议错用回绝了），会话一样建好，只是那个码没被看过。
     */
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
            // 出错要主动告诉相机放弃这次尝试，并关掉这条已经用不了的连接
            pairing.abort()
            link.closeQuietly()
            throw e
        }
        link.startReader(onDisconnected = { notifyControlDisconnected() }) { m -> onControlMessage(m) }
        return !result.alreadyPaired
    }

    private fun notifyControlDisconnected() {
        if (closed.get()) return
        onDisconnected?.invoke()
    }

    // ============================================================
    // 两阶段配对：连接先留着，等用户看完相机屏再交码
    // ============================================================
    //
    // 新流程（相机端待机不亮码）下，手机端必须：
    //   连上 → 发 PAIR_BEGIN（相机**这时才亮码**）→ **保持连接** → 用户输码 →
    //   发 PAIR_EXCHANGE → 这条连接直接转成正式会话。
    // 所以握手之后不能马上起控制读线程（那会把应答吃掉），要留到 finishPairing。

    /**
     * 配对第一步的连接：Init 握手，但**不起控制读线程**——配对期还要手工收发
     * `PAIR_BEGIN` / `PAIR_EXCHANGE` 的应答。
     */
    @Throws(IOException::class)
    fun connectForPairing(timeoutMs: Int = 20000) {
        val link = openLink(timeoutMs)
        withHandshakeTimeout(link, timeoutMs) { initHandshake(link) }
    }

    /** 配对期直接发一帧（`opReqBlob` 造好的那种）。 */
    @Throws(IOException::class)
    fun sendRaw(framed: ByteArray) {
        val link = control ?: throw PtpProtocolException("配对连接已关闭")
        link.send(framed)
    }

    /** 配对期收一个操作应答（自适应 DP_NONE / DP_DATA_IN）。 */
    @Throws(IOException::class)
    fun recvPlainOpBlob(): PtpCodec.OpBlob {
        val link = control ?: throw PtpProtocolException("配对连接已关闭")
        return link.recvPlainOpBlob()
    }

    /**
     * 配对成功：这条连接**转为正式会话** —— 起控制读线程、接上事件与断开回调。
     *
     * <p>不重连（相机端在回 `RC_OK` 之前就落表了，这条连接本来就是可用的会话），
     * 所以配对成功的代价只有两次往返。
     */
    fun finishPairing(onEvent: EventListener? = null) {
        val link = control ?: throw PtpProtocolException("配对连接已关闭")
        eventListener = onEvent
        link.startReader(onDisconnected = { notifyControlDisconnected() }) { m -> onControlMessage(m) }
    }

    private fun openLink(timeoutMs: Int): Link {
        val socket = socketFactory?.createSocket() ?: Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, protoPort), timeoutMs)
        val link = Link(socket, "control")
        control = link
        return link
    }

    /**
     * 握手段的读超时：**只在这几步**给 socket 设 SO_TIMEOUT。
     *
     * ★ 为什么要它：原来这条 socket 只有 connect 超时，握手那几次 `recvMsg()` 是**无限等**的
     *   ——相机要是"接了 TCP 但不应答"（起服务的中途、或它自己卡住），手机就永远钉在
     *   "正在连接"上，而且整个 io 执行器被这一次 `.get()` 占住，连重连都排不进去。
     *   给个上限，超时就当连接失败。
     * ★ 为什么用完要**清掉**（设 0 = 无限等）：控制连接建立之后是"请求-应答"节奏，
     *   应答的等待由调用方自己的超时管；事件通道更是**故意无限等**（它就该一直挂着）。
     *   留着 SO_TIMEOUT 会把"正常的空闲"误判成断线。
     */
    private fun withHandshakeTimeout(link: Link, timeoutMs: Int, body: () -> Unit) {
        link.soTimeoutOf(timeoutMs)
        try {
            body()
        } finally {
            link.soTimeoutOf(0)
        }
    }

    /** Init Command Request / Ack（含 Init Fail 的三种拒绝理由）。 */
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

    // 事件连接
    // ============================================================

    /**
     * 打开事件连接（**可选**）：向协议端口再连一条 TCP，发 `Init Event Request(connNo)`，
     * 收 `Init Event Ack`，随后相机主动推送事件（同样是明文帧）。
     *
     * ★ 必须在 [connect] 之后调用（要用 [connNo] 挂到控制会话上）。
     */
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
                // 坏事件包：忽略，不断连接
            }
        }
    }

    // ============================================================
    // 控制读线程消息分发
    // ============================================================

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
                // 其它包忽略
            }
        }
    }

    // ============================================================
    // 请求 / 响应事务
    // ============================================================

    private class OpResult(
        val code: Int,
        val txId: Int,
        val params: IntArray,
        val blob: ByteArray,
    )

    /** 依 dataPhase 自动选择解析方式（DP_DATA_IN → blob 形式；否则标准形式）。 */
    @Throws(IOException::class)
    private fun parseResponse(plain: ByteArray): OpResult {
        if (plain.size >= 4 && PtpCodec.i32(plain, 0) == PtpCodec.DP_DATA_IN) {
            val b = PtpCodec.parseOpBlob(plain)
            return OpResult(b.code, b.txId, b.params, b.blob)
        }
        val o = PtpCodec.parseOp(plain)
        return OpResult(o.code, o.txId, o.params, ByteArray(0))
    }

    /**
     * 发一个操作请求并等响应（同步阻塞）。
     *
     * @param frame 由 `PtpCodec.opReq` / `opReqBlob` / `opReqBlobParams` 构造好的完整帧
     */
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

    // ============================================================
    // 具体操作
    // ============================================================

    /** 心跳：应答顺带回报电量与镜头。 */
    @Throws(IOException::class)
    /**
     * 唯一的实时包（心跳）：一次往返带回电量与镜头名。
     *
     * 参数 `{batteryPct, hasLens}` + blob 镜头名（UTF-8，空 = 未装镜头）。
     * 会变的数据只有这一个包在传；型号/序列号/固件那些静态字段走 [deviceInfo]，
     * 连接时拉一次就够（不再有第二个短周期定时器）。
     */
    fun ping(): PingInfo {
        val tx = nextTx()
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_PING, tx, null))
        checkOk(r)
        val bat = if (r.params.isNotEmpty()) r.params[0] else -1
        val lens = r.blob.toString(Charsets.UTF_8)
        return PingInfo(bat, lens)
    }

    /** 解除配对：请相机删掉本机那条配对记录（已认证会话内执行）。 */
    fun pairRemove(): Boolean {
        val tx = nextTx()
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_PAIR_REMOVE, tx, null))
        return r.code == PtpCodec.RC_OK
    }

    /** 全量设备信息（厂商 JSON，UTF-8）。 */
    @Throws(IOException::class)
    fun deviceInfo(): ByteArray {
        val tx = nextTx()
        val r = request(PtpCodec.opReq(PtpCodec.DP_NONE, PtpCodec.OP_DEVICE_INFO, tx, null))
        checkOk(r)
        return r.blob
    }

    /** 目录列表（厂商 JSON，UTF-8）。 */
    @Throws(IOException::class)
    fun listDir(path: String, offset: Int = 0, limit: Int = 0): ByteArray {
        val tx = nextTx()
        val r = request(
            PtpCodec.opReqBlobParams(
                PtpCodec.OP_LIST_DIR,
                tx,
                intArrayOf(offset, limit),
                pathBytes(path),
            )
        )
        checkOk(r)
        return r.blob
    }

    /** 对象大小与修改时间。 */
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

    /**
     * 登记一次性传输凭据（[TransferTicket]），随后用 [DataChannel] 走文件端口取数据。
     *
     * @param kind   `PtpCodec.KIND_THUMB` / `KIND_PREVIEW` / `KIND_ORIGINAL`
     * @param offset 起始偏移
     * @param length 请求字节数；-1 表示"从 offset 到对象尾"
     */
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

    /**
     * 登记一次性传输凭据（偏移 0、整对象）。返回 [TransferTicket] 与对象总大小。
     */
    @Throws(IOException::class)
    /**
     * 取整个对象：**只走一次 `OP_GET_OBJECT`**。
     *
     * 早先这里先调 `stat()` 拿长度，纯属多余 —— `GET_OBJECT` 自己会校验存在性并回
     * `NOT_FOUND`，而真正的总长度在文件端口的 Start Data 里带回。省掉这一次往返，
     * 按一个 361 张的目录算就是少 361 个来回（缩略图是逐张拉取的）。
     */
    fun getObjectWhole(path: String, kind: Int): TransferTicket =
        getObject(path, kind, 0L, -1)

    /** 缩略图队列控制（begin/pause/resume/cancel）。 */
    @Throws(IOException::class)
    fun thumbQueue(op: Int, paths: List<String>): Boolean {
        val tx = nextTx()
        val text = paths.joinToString("\n")
        val r = request(PtpCodec.opReqBlob(op, tx, pathBytes(text)))
        return r.code == PtpCodec.RC_OK
    }

    private fun checkOk(r: OpResult) {
        if (r.code != PtpCodec.RC_OK) {
            if (r.code == CODE_LOCAL_CLOSED) {
                throw PtpProtocolException("连接已关闭，未收到相机应答")
            }
            throw PtpProtocolException("相机回错误码 0x${Integer.toHexString(r.code)}")
        }
    }

    // ============================================================
    // 关闭
    // ============================================================

    val isConnected: Boolean
        get() = !closed.get() && control?.isAlive == true

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 先停事件连接，再停控制连接，最后清挂起事务
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
        // 挂起的事务全部唤醒（避免调用方永久阻塞）。
        // ★ 唤醒用本地哨兵码，不能借 RC_SESSION_NOT_OPEN：那是**相机**的协议码，
        //   借它唤醒本地事务会把"本端关了连接"报成"相机回错误码 0x2003"，
        //   排障时会把人骗去查相机端。
        val code = CODE_LOCAL_CLOSED
        for (key in pending.keys.toList()) {
            pending.remove(key)?.offer(OpResult(code, key, IntArray(0), ByteArray(0)))
        }
        eventListener = null
    }

    // ============================================================
    // 单条 TCP 连接的组帧 / 解帧 / 收发
    // ============================================================

    /**
     * 一条 PTP/IP 连接（控制 / 事件 / 文件）。
     *
     * ★ 加解密层已整体移除：这里只做「写完整帧 / 读完整帧」。没有会话密钥、
     *   没有序号空间 —— 到达顺序由 TCP 保证，重放防护也随之不存在了。
     */
    private inner class Link(private val socket: Socket, private val label: String) {

        /** 只在握手段临时设 SO_TIMEOUT 用（见 [withHandshakeTimeout]），其余时候是 0 = 无限等。 */
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

        /** 读一个完整包。 */
        fun recvMsg(): PtpCodec.Msg? = PtpCodec.read(input)

        /**
         * 握手期（明文）操作应答，形式自适应：
         * 成功应答多为 DP_NONE（`opRsp`），带载荷的才是 DP_DATA_IN（`opRspBlob`）。
         * 配对失败走的就是前者，所以两种都必须能吃。
         */
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

        /** 起读线程：逐个读包并回调。 */
        fun startReader(
            onDisconnected: (() -> Unit)? = null,
            onMsg: (PtpCodec.Msg) -> Unit,
        ) {
            val t = Thread({
                try {
                    while (alive.get() && !socket.isClosed) {
                        val m = PtpCodec.read(input) ?: break
                        onMsg(m)
                    }
                } catch (ignored: IOException) {
                    // 连接关闭 / 超时：静默收敛
                } catch (ignored: SecurityException) {
                    // MAC 失败：交由上层处理（onMsg 内部已 close）
                } finally {
                    alive.set(false)
                    if (!socket.isClosed) onDisconnected?.invoke()
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
        /** 协议代次：P1–P4 报 1.0；P5 定稿升 2.0。 */
        const val PROTO_VERSION = (1 shl 16)

        /**
         * 本地唤醒挂起事务的哨兵码。故意取负数：不落在任何 PTP 协议码空间里，
         * 谁把它当相机回码打印出来都会一眼看出不对（协议码都是 0x2xxx）。
         */
        private const val CODE_LOCAL_CLOSED = -1
    }
}
