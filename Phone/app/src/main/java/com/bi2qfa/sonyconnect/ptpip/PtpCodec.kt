package com.bi2qfa.sonyconnect.ptpip

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * PTP/IP（CIPA DC-X005）线格式编解码 —— **相机端 `PtpCodec.java` 的逐函数镜像**。
 *
 * 凡相机端有的常量与构造/解析函数，这里都有，且字节布局完全一致。
 * 所有多字节整数一律**小端（LE）**。
 */
object PtpCodec {

    // ===== 通用包头 =====
    const val HEADER_LEN = 8

    /** 单包上限（控制面 1 MiB 足够）。 */
    const val MAX_PACKET = 1024 * 1024

    /**
     * 数据通道分块大小（128 KiB）—— 与相机端 `PtpCodec.CHUNK` 同值。
     * 接收侧其实由帧长自带、不依赖这个数，留着只为两端对照时一眼可见。
     */
    const val CHUNK = 128 * 1024

    // ===== 包类型（标准区 0x01–0x0E）=====
    const val T_INIT_CMD_REQ = 0x0001
    const val T_INIT_CMD_ACK = 0x0002
    const val T_INIT_EVENT_REQ = 0x0003
    const val T_INIT_EVENT_ACK = 0x0004
    const val T_INIT_FAIL = 0x0005
    const val T_OPERATION_REQ = 0x0006
    const val T_OPERATION_RSP = 0x0007
    const val T_EVENT = 0x0008
    const val T_START_DATA = 0x0009
    const val T_DATA = 0x000A
    const val T_END_DATA = 0x000B
    const val T_CANCEL = 0x000C
    const val T_PROBE_REQ = 0x000D
    const val T_PROBE_RESP = 0x000E

    // ===== 厂商扩展包类型（0x40+）=====
    const val T_DATA_OPEN = 0x0040
    const val T_DATA_OPEN_ACK = 0x0041

    // ===== 数据阶段标记 =====
    const val DP_NONE = 0
    const val DP_DATA_IN = 1
    const val DP_DATA_OUT = 2

    // ===== Init Fail 原因 =====
    const val FAIL_REJECTED = 0x01
    const val FAIL_UNSUPPORTED = 0x02
    const val FAIL_BUSY = 0x03
    /** 本机未与对方配对（原 FAIL_CRYPTO_REQUIRED；加密层已移除，语义改为"先配对"）。 */
    const val FAIL_NOT_PAIRED = 0x04

    // ===== 厂商操作码（0x9000–0x9FFF）=====
    const val OP_PAIR_BEGIN = 0x9001
    const val OP_PAIR_EXCHANGE = 0x9002
    const val OP_PAIR_ABORT = 0x9004
    /** 已认证会话内请求相机删掉本机那条配对记录（手机端「解除配对」）。 */
    const val OP_PAIR_REMOVE = 0x9005
    // 0x9010 / 0x9011 原为双向认证的两个操作码；加密层整体移除后不再使用，号段留空。
    const val OP_PING = 0x9012
    const val OP_DEVICE_INFO = 0x9013
    const val OP_LIST_DIR = 0x9020
    const val OP_STAT = 0x9021
    const val OP_GET_OBJECT = 0x9022
    const val OP_THUMB_QUEUE_BEGIN = 0x9023
    const val OP_THUMB_QUEUE_PAUSE = 0x9024
    const val OP_THUMB_QUEUE_RESUME = 0x9025
    const val OP_THUMB_QUEUE_CANCEL = 0x9026
    const val OP_REC_ENTER = 0x9030
    const val OP_REC_LEAVE = 0x9031
    const val OP_REC_GET_STATE = 0x9032
    const val OP_LV_START = 0x9033
    const val OP_LV_STOP = 0x9034
    const val OP_SHOOT = 0x9035
    const val OP_AF_HALF = 0x9036
    const val OP_AF_CANCEL = 0x9037
    const val OP_ZOOM = 0x9038
    const val OP_SET_PROP = 0x9039
    const val OP_TOUCH_AF = 0x903A
    const val OP_MOVIE_START = 0x903B
    const val OP_MOVIE_STOP = 0x903C

    // ===== 厂商事件码 =====
    const val EV_THUMB_PROGRESS = 0x9041

    /**
     * 相机已解除本机的配对（相机端在配对页删掉了这台手机）。
     *
     * 相机 → 手机的单向通知：收到就清本地配对记录并断开当前会话，否则本机还留着
     * 一个相机已不认的旧记录（下次连接会被 `Init Fail(NOT_PAIRED)` 挡回来）。
     */
    const val EV_PAIRED_REMOVED = 0x9042

    /**
     * 相机端正在退出（退出软件 / 被会话强收）—— 收到即断开当前会话。
     *
     * 相机在拆连接**之前**发这条，所以手机端能立刻显示"相机端已退出"；
     * 少了它就只能等 3 次心跳失联（约 9 秒）才自己发现，界面一直显示"已连接"。
     *
     * ★ **切换连接方式不走这条**：那条路是 [EV_MODE_SWITCHING]。相机端切换时会抑制
     *   这条（切换前先发方式通知），否则本机显示"相机端已退出"就是句假话。
     */
    const val EV_APP_EXITING = 0x9043

    /**
     * 相机端正在**切换连接方式**（相机 → 手机的单向通知）。
     *
     * 参数 `params[0]`：0 = Wi-Fi 网络（接入点），1 = 相机热点。
     *
     * 切换要重配无线电 → 本机这条链路**一定会断**，处理上与 [EV_APP_EXITING] 一样是
     * 收掉会话；差别只在给用户的那句话：不是"相机退出了"，而是"相机换了连接方式"。
     */
    const val EV_MODE_SWITCHING = 0x9044
    const val EV_REC = 0x9046

    /**
     * 相机端**主动断开本机连接**的通用声明（相机 → 手机单向通知）。
     *
     * 参数 `params[0]`：断开原因，见 [DISC_REASON_PAIRING] 等。
     *
     * ★ 用户定版："相机端协议应针对所有相机端主动断连的行为做出声明，在手机端显示；
     *   如果属于意外断连（也就是**没收到任何声明**），则统一按失去与相机的连接处理。"
     *
     * 与另外三条的关系：[EV_APP_EXITING]（退出）、[EV_MODE_SWITCHING]（切换方式）、
     * [EV_PAIRED_REMOVED]（解除配对）各自表达更具体的语义、继续保留；本条补的是
     * 它们**没覆盖到**的原因（进配对模式、服务异常…）。收齐四条任一都算"有交代"。
     */
    const val EV_DISCONNECTING = 0x9045

    /** [EV_DISCONNECTING] 原因：相机端要进配对模式，腾出连接给新手机。 */
    const val DISC_REASON_PAIRING = 0

    /** [EV_DISCONNECTING] 原因：相机端服务异常，无法继续提供服务。 */
    const val DISC_REASON_ERROR = 1

    /** [EV_DISCONNECTING] 原因：其它（未细分的主动断开情形）。 */
    const val DISC_REASON_OTHER = 2

    // ===== 响应码 =====
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_INVALID_TX = 0x2004
    const val RC_NOT_SUPPORTED = 0x2005
    const val RC_ACCESS_DENIED = 0x2006
    const val RC_NOT_FOUND = 0x2007
    const val RC_DEVICE_BUSY = 0x2008
    const val RC_PAIRING_FAILED = 0x2009
    const val RC_CANCELED = 0x200B

    /** GET_OBJECT 的 kind 参数。 */
    const val KIND_THUMB = 0
    const val KIND_PREVIEW = 1
    const val KIND_ORIGINAL = 2

    /** 厂商友好名标记。 */
    const val VENDOR_TAG = "SonyConnect/2.0"

    // ============================================================
    // 基础字节读写（小端）
    // ============================================================

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun i32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun i64(b: ByteArray, off: Int): Long {
        val lo = i32(b, off).toLong() and 0xFFFFFFFFL
        val hi = i32(b, off + 4).toLong() and 0xFFFFFFFFL
        return lo or (hi shl 32)
    }

    fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun put32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun put64(b: ByteArray, off: Int, v: Long) {
        put32(b, off, (v and 0xFFFFFFFFL).toInt())
        put32(b, off + 4, ((v ushr 32) and 0xFFFFFFFFL).toInt())
    }

    // ============================================================
    // 组帧 / 解帧
    // ============================================================

    fun header(type: Int, payloadLen: Int): ByteArray {
        val h = ByteArray(HEADER_LEN)
        put32(h, 0, HEADER_LEN + payloadLen)
        put32(h, 4, type)
        return h
    }

    fun frame(type: Int, payload: ByteArray?): ByteArray {
        val n = payload?.size ?: 0
        val out = ByteArray(HEADER_LEN + n)
        put32(out, 0, out.size)
        put32(out, 4, type)
        if (n > 0) System.arraycopy(payload!!, 0, out, HEADER_LEN, n)
        return out
    }

    /** 一个已解析的 PTP/IP 包。 */
    class Msg(val type: Int, val body: ByteArray)

    /**
     * 从流里读一个完整包。
     *
     * ⚠ **不要给它加"复用缓冲"参数**：全工程（本文件、[PtpIpClient]、[DataChannel]）
     * 都拿 `Msg.body.size` 当"这一帧带了多少数据"的权威判据。复用一块固定大小的缓冲后
     * `body.size` 会恒等于缓冲大小，判据立刻失真 —— 曾经这么改过一次，后果是
     * "进度跑满又归零、循环往复最后失败" + "缩略图/预览图出现坏块"。见 DataChannel 里的长注释。
     *
     * @return null 表示对端干净关闭；截断 / 坏长度抛 [IOException]
     */
    @Throws(IOException::class)
    fun read(input: InputStream): Msg? {
        val h = ByteArray(HEADER_LEN)
        // 包头一次性读完，不再先单字节 read() 探 EOF：数据通道是 128 KiB 一块的
        // 大帧，每帧多一次单字节系统调用在这个量级上就是白付的开销。
        // 干净关闭由"首个 read 就返回 0 字节"识别。
        if (readHeader(input, h) == 0) return null

        val len = i32(h, 0)
        val type = i32(h, 4)
        if (len < HEADER_LEN || len > MAX_PACKET) {
            throw IOException("坏包长度: $len (type=$type)")
        }
        val body = ByteArray(len - HEADER_LEN)
        if (body.isNotEmpty()) readFully(input, body, 0, body.size)
        return Msg(type, body)
    }

    /**
     * 读满包头。
     * @return 实际读到的字节数：0 = 对端干净关闭；1..7 = 截断，抛 [IOException]
     */
    @Throws(IOException::class)
    private fun readHeader(input: InputStream, h: ByteArray): Int {
        var got = 0
        while (got < h.size) {
            val r = input.read(h, got, h.size - got)
            if (r < 0) break
            got += r
        }
        if (got in 1 until h.size) {
            throw IOException("包头截断: 还差 ${h.size - got} 字节")
        }
        return got
    }

    @Throws(IOException::class)
    fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var got = 0
        while (got < len) {
            val r = input.read(buf, off + got, len - got)
            if (r < 0) throw EOFException("包体截断: 还差 ${len - got} 字节")
            got += r
        }
    }

    // ============================================================
    // FriendlyName：1 字节字符数 + UTF-16LE
    // ============================================================

    fun nameLen(s: String): Int = 1 + s.length * 2

    fun writeName(buf: ByteArray, off: Int, s: String): Int {
        val chars = minOf(s.length, 255)
        buf[off] = chars.toByte()
        for (i in 0 until chars) {
            val c = s[i].code
            buf[off + 1 + i * 2] = (c and 0xFF).toByte()
            buf[off + 1 + i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        return 1 + chars * 2
    }

    /** 读 name；nextOff[0] 回写下一偏移。 */
    fun readName(b: ByteArray, off: Int, nextOff: IntArray?): String {
        val chars = u8(b, off)
        val sb = StringBuilder(chars)
        for (i in 0 until chars) {
            val lo = b[off + 1 + i * 2].toInt() and 0xFF
            val hi = b[off + 1 + i * 2 + 1].toInt() and 0xFF
            sb.append(((hi shl 8) or lo).toChar())
        }
        nextOff?.let { if (it.isNotEmpty()) it[0] = off + 1 + chars * 2 }
        return sb.toString()
    }

    // ============================================================
    // 各类包构造器
    // ============================================================

    fun initCmdReq(guid: ByteArray, friendlyName: String, protoVer: Int): ByteArray {
        val nl = nameLen(friendlyName)
        val p = ByteArray(16 + nl + 4)
        System.arraycopy(guid, 0, p, 0, 16)
        writeName(p, 16, friendlyName)
        put32(p, 16 + nl, protoVer)
        return frame(T_INIT_CMD_REQ, p)
    }

    fun initCmdAck(connNo: Int, guid: ByteArray, friendlyName: String, protoVer: Int): ByteArray {
        val nl = nameLen(friendlyName)
        val p = ByteArray(4 + 16 + nl + 4)
        put32(p, 0, connNo)
        System.arraycopy(guid, 0, p, 4, 16)
        writeName(p, 20, friendlyName)
        put32(p, 20 + nl, protoVer)
        return frame(T_INIT_CMD_ACK, p)
    }

    fun initEventReq(connNo: Int): ByteArray {
        val p = ByteArray(4)
        put32(p, 0, connNo)
        return frame(T_INIT_EVENT_REQ, p)
    }

    fun initEventAck(): ByteArray = frame(T_INIT_EVENT_ACK, ByteArray(0))

    fun initFail(reason: Int): ByteArray {
        val p = ByteArray(4)
        put32(p, 0, reason)
        return frame(T_INIT_FAIL, p)
    }

    fun opReq(dataPhase: Int, opCode: Int, txId: Int, params: IntArray?): ByteArray =
        frame(T_OPERATION_REQ, opBody(dataPhase, opCode, txId, params))

    fun opRsp(dataPhase: Int, respCode: Int, txId: Int, params: IntArray?): ByteArray =
        frame(T_OPERATION_RSP, opBody(dataPhase, respCode, txId, params))

    private fun opBody(dataPhase: Int, code: Int, txId: Int, params: IntArray?): ByteArray {
        val n = params?.size ?: 0
        val p = ByteArray(10 + n * 4)
        put32(p, 0, dataPhase)
        put16(p, 4, code)
        put32(p, 6, txId)
        for (i in 0 until n) put32(p, 10 + i * 4, params!![i])
        return p
    }

    /** 操作请求 / 应答的公共解析结果。 */
    class Op {
        var dataPhase = 0
        var code = 0
        var txId = 0
        var params: IntArray = IntArray(0)
    }

    @Throws(IOException::class)
    fun parseOp(body: ByteArray): Op {
        if (body.size < 10) throw IOException("操作包过短: ${body.size}")
        val o = Op()
        o.dataPhase = i32(body, 0)
        o.code = u16(body, 4)
        o.txId = i32(body, 6)
        val n = (body.size - 10) / 4
        o.params = IntArray(n)
        for (i in 0 until n) o.params[i] = i32(body, 10 + i * 4)
        return o
    }

    /**
     * 扩展 Operation Request（内联 blob，paramCount 恒 0）—— 认证 nonce / 路径类请求。
     */
    fun opReqBlob(opCode: Int, txId: Int, blob: ByteArray?): ByteArray =
        opReqBlobParams(opCode, txId, null, blob)

    /**
     * 扩展 Operation Request 通用形式：**参数区 + 内联 blob**（[opRspBlob] 的请求侧镜像）。
     *
     * ```
     * dataPhase(4)=DP_DATA_IN | opCode(2) | txId(4) | paramCount(4) | params(4×n) | blobLen(4) | blob
     * ```
     *
     * ★ 必须整体用 [parseOpBlob] 解析，**不可**与 [parseOp] 混用：
     * 两者参数区偏移相差 4 字节。用途：`GET_OBJECT`（数值参数 + 路径 blob）。
     */
    fun opReqBlobParams(opCode: Int, txId: Int, params: IntArray?, blob: ByteArray?): ByteArray {
        val n = params?.size ?: 0
        val bl = blob?.size ?: 0
        val p = ByteArray(4 + 2 + 4 + 4 + n * 4 + 4 + bl)
        put32(p, 0, DP_DATA_IN)
        put16(p, 4, opCode)
        put32(p, 6, txId)
        put32(p, 10, n)
        for (i in 0 until n) put32(p, 14 + i * 4, params!![i])
        val o = 14 + n * 4
        put32(p, o, bl)
        if (bl > 0) System.arraycopy(blob!!, 0, p, o + 4, bl)
        return frame(T_OPERATION_REQ, p)
    }

    /**
     * 扩展 Operation Response（内联 blob）。
     *
     * ```
     * dataPhase(4)=DP_DATA_IN | code(2) | txId(4) | paramCount(4) | params(4×n) | blobLen(4) | blob
     * ```
     */
    fun opRspBlob(respCode: Int, txId: Int, params: IntArray?, blob: ByteArray?): ByteArray {
        val n = params?.size ?: 0
        val bl = blob?.size ?: 0
        val p = ByteArray(4 + 2 + 4 + 4 + n * 4 + 4 + bl)
        put32(p, 0, DP_DATA_IN)
        put16(p, 4, respCode)
        put32(p, 6, txId)
        put32(p, 10, n)
        for (i in 0 until n) put32(p, 14 + i * 4, params!![i])
        val o = 14 + n * 4
        put32(p, o, bl)
        if (bl > 0) System.arraycopy(blob!!, 0, p, o + 4, bl)
        return frame(T_OPERATION_RSP, p)
    }

    /** 扩展响应体解析结果。 */
    class OpBlob {
        var dataPhase = 0
        var code = 0
        var txId = 0
        var params: IntArray = IntArray(0)
        /** 无 blob 时为空数组，不为 null */
        var blob: ByteArray = ByteArray(0)
    }

    @Throws(IOException::class)
    fun parseOpBlob(body: ByteArray): OpBlob {
        if (body.size < 18) throw IOException("扩展响应包过短: ${body.size}")
        val o = OpBlob()
        o.dataPhase = i32(body, 0)
        o.code = u16(body, 4)
        o.txId = i32(body, 6)
        val n = i32(body, 10)
        if (n < 0 || 14 + n * 4 + 4 > body.size) throw IOException("扩展响应 paramCount 越界: $n")
        o.params = IntArray(n)
        for (i in 0 until n) o.params[i] = i32(body, 14 + i * 4)
        val bl = i32(body, 14 + n * 4)
        if (bl < 0 || 14 + n * 4 + 4 + bl > body.size) throw IOException("扩展响应 blobLen 越界: $bl")
        o.blob = ByteArray(bl)
        if (bl > 0) System.arraycopy(body, 14 + n * 4 + 4, o.blob, 0, bl)
        return o
    }

    /** Event：EventCode(2) + TransactionID(4) + Params(4×n)。 */
    fun event(evCode: Int, txId: Int, params: IntArray?): ByteArray {
        val n = params?.size ?: 0
        val p = ByteArray(6 + n * 4)
        put16(p, 0, evCode)
        put32(p, 2, txId)
        for (i in 0 until n) put32(p, 6 + i * 4, params!![i])
        return frame(T_EVENT, p)
    }

    class Ev {
        var code = 0
        var txId = 0
        var params: IntArray = IntArray(0)
    }

    @Throws(IOException::class)
    fun parseEvent(body: ByteArray): Ev {
        if (body.size < 6) throw IOException("事件包过短: ${body.size}")
        val e = Ev()
        e.code = u16(body, 0)
        e.txId = i32(body, 2)
        val n = (body.size - 6) / 4
        e.params = IntArray(n)
        for (i in 0 until n) e.params[i] = i32(body, 6 + i * 4)
        return e
    }

    // ============================================================
    // 数据阶段
    // ============================================================

    /** Start Data：TransactionID(4) + TotalDataLength(8)。 */
    fun startData(txId: Int, total: Long): ByteArray {
        val p = ByteArray(12)
        put32(p, 0, txId)
        put64(p, 4, total)
        return frame(T_START_DATA, p)
    }

    fun dataPacket(txId: Int, data: ByteArray?): ByteArray {
        val n = data?.size ?: 0
        val p = ByteArray(4 + n)
        put32(p, 0, txId)
        if (n > 0) System.arraycopy(data!!, 0, p, 4, n)
        return frame(T_DATA, p)
    }

    fun endData(txId: Int, data: ByteArray?): ByteArray {
        val n = data?.size ?: 0
        val p = ByteArray(4 + n)
        put32(p, 0, txId)
        if (n > 0) System.arraycopy(data!!, 0, p, 4, n)
        return frame(T_END_DATA, p)
    }

    fun cancel(txId: Int): ByteArray {
        val p = ByteArray(4)
        put32(p, 0, txId)
        return frame(T_CANCEL, p)
    }

    fun txIdOf(body: ByteArray): Int = if (body.size >= 4) i32(body, 0) else 0

    @Throws(IOException::class)
    fun totalOf(body: ByteArray): Long {
        if (body.size < 12) throw IOException("Start Data 载荷过短: ${body.size}")
        return i64(body, 4)
    }

    // ============================================================
    // 厂商扩展：文件端口握手
    // ============================================================

    /** DATA_OPEN：GUID(16) + ConnNo(4) + token(8)。 */
    fun dataOpen(guid: ByteArray, connNo: Int, token: Long): ByteArray {
        val p = ByteArray(28)
        System.arraycopy(guid, 0, p, 0, 16)
        put32(p, 16, connNo)
        put64(p, 20, token)
        return frame(T_DATA_OPEN, p)
    }

    fun dataOpenAck(): ByteArray = frame(T_DATA_OPEN_ACK, ByteArray(0))

    class DataOpen {
        var guid: ByteArray = ByteArray(16)
        var connNo = 0
        var token = 0L
    }

    @Throws(IOException::class)
    fun parseDataOpen(body: ByteArray): DataOpen {
        if (body.size < 28) throw IOException("DATA_OPEN 载荷过短: ${body.size}")
        val d = DataOpen()
        d.guid = body.copyOfRange(0, 16)
        d.connNo = i32(body, 16)
        d.token = i64(body, 20)
        return d
    }

    // ============================================================
    // UDP 发现
    // ============================================================

    /** 探测包：GUID(16) + FriendlyName + 厂商扩展尾(8)。 */
    fun probe(
        request: Boolean,
        guid: ByteArray,
        friendlyName: String,
        protoPort: Int,
        filePort: Int,
        pairingMode: Boolean,
        paired: Boolean,
    ): ByteArray {
        val nl = nameLen(friendlyName)
        val p = ByteArray(16 + nl + 8)
        System.arraycopy(guid, 0, p, 0, 16)
        writeName(p, 16, friendlyName)
        val o = 16 + nl
        put16(p, o, protoPort)
        put16(p, o + 2, filePort)
        p[o + 4] = (if (pairingMode) 1 else 0).toByte()
        p[o + 5] = (if (paired) 1 else 0).toByte()
        put16(p, o + 6, 0)
        return frame(if (request) T_PROBE_REQ else T_PROBE_RESP, p)
    }

    class Probe {
        var guid: ByteArray = ByteArray(16)
        var name: String = ""
        var protoPort = 0
        var filePort = 0
        var pairingMode = false
        var paired = false
    }

    @Throws(IOException::class)
    fun parseProbe(body: ByteArray): Probe {
        if (body.size < 17) throw IOException("探测包过短: ${body.size}")
        val pr = Probe()
        pr.guid = body.copyOfRange(0, 16)
        val next = IntArray(1)
        pr.name = readName(body, 16, next)
        val o = next[0]
        if (o + 8 <= body.size) {
            pr.protoPort = u16(body, o)
            pr.filePort = u16(body, o + 2)
            pr.pairingMode = body[o + 4].toInt() != 0
            pr.paired = body[o + 5].toInt() != 0
        }
        return pr
    }

    /** 紧凑探测包（无厂商扩展尾），对第三方标准客户端使用。 */
    fun probeCompact(request: Boolean, guid: ByteArray, friendlyName: String): ByteArray {
        val nl = nameLen(friendlyName)
        val p = ByteArray(16 + nl)
        System.arraycopy(guid, 0, p, 0, 16)
        writeName(p, 16, friendlyName)
        return frame(if (request) T_PROBE_REQ else T_PROBE_RESP, p)
    }

    fun hasVendorTag(friendlyName: String?): Boolean =
        friendlyName != null && friendlyName.endsWith(VENDOR_TAG)

    // ============================================================
    // 小工具
    // ============================================================

    private const val HEX = "0123456789abcdef"

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 0xF]).append(HEX[x.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun unhex(s: String?): ByteArray {
        require(!(s == null || s.length % 2 != 0)) { "非法十六进制串" }
        val out = ByteArray(s!!.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            require(!(hi < 0 || lo < 0)) { "非法十六进制串" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

}
