package com.bi2qfa.sonyconnect.ptpip

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/**
 * 数据通道（文件端口）—— 对应相机端 `PtpIpServer` 的 `serveFile` / `transfer`。
 *
 * 时序（**全程明文**，加解密层已整体移除）：
 * ```
 * 我 ── DATA_OPEN{GUID(16), ConnNo(4), token(8)} ──▶ 相机（文件端口）
 * 我 ◀── DATA_OPEN_ACK ───────────────────────── 相机
 * 我 ◀── Start Data{tx=1, total(8)} ───────────── 相机 ┐
 * 我 ◀── Data{tx=1, chunk}* ──────────────────── 相机 ├ 明文帧
 * 我 ◀── End Data{tx=1, 末块} ────────────────── 相机 ┘
 * ```
 *
 * ★ 取消语义：由本端断开 socket 表达（相机写侧抛 IOException 即收敛）。
 */
class DataChannel(
    private val host: String,
    private val filePort: Int,
    /** 本机 16 字节 GUID（与控制连接一致）。 */
    private val guid16: ByteArray,
    /** 控制连接的会话号：相机据此找回所属控制会话，确认它已获准使用。 */
    private val connNo: Int,
    /** 见 [PtpIpClient] 同名参数：手机连相机热点时把连接钉在相机网络上。 */
    private val socketFactory: SocketFactory? = null,
) : Closeable {

    private var socket: Socket? = null
    private val closed = AtomicBoolean(false)

    /**
     * 拉取一个对象。
     *
     * @param token     控制连接 `GET_OBJECT` 登记的一次性凭据
     * @param onTotal   收到 Start Data 时回调对象总长
     * @param onChunk   每收到一块明文回调 `(buf, off, len)` —— **顺序**、
     *                  **阻塞式**，实现方负责写盘。给区间而不是新数组，是为了
     *                  让落盘直接 `out.write(buf, off, len)`，每块少一次拷贝。
     *                  ★ 这个 buf 是**复用的收包缓冲**：必须在回调里用完（写盘），
     *                  **不能留引用** —— 下一帧会覆盖它。
     * @param cancelled 轮询式取消检查
     * @return 实际收到的字节数
     */
    @Throws(IOException::class)
    fun download(
        token: Long,
        expectedTxId: Int = 1,
        expectedBytes: Long = -1L,
        timeoutMs: Int = 8000,
        onTotal: (Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
        onChunk: (ByteArray, Int, Int) -> Unit,
    ): Long {
        val s = socketFactory?.createSocket() ?: Socket()
        s.tcpNoDelay = true
        s.soTimeout = timeoutMs
        // ★ 接收缓冲拉大：这条连接是**单向大流量**，Android 默认的 RCVBUF
        //   （几十~256 KiB）直接决定 TCP 窗口的上限 —— 窗口打不满，吞吐就被 RTT 卡住。
        //   必须在 connect() **之前**设：窗口缩放是握手时协商的。
        runCatching { s.receiveBufferSize = 1024 * 1024 }
        s.connect(InetSocketAddress(host, filePort), timeoutMs)
        socket = s

        val input = s.getInputStream()
        val output = s.getOutputStream()

        // ⚠ 这里**故意不复用**收包缓冲（曾经复用过一次，是个真 bug，记在这儿免得再犯）：
        //   本文件用 `dataLen(body) = body.size - 4` 判断"这一帧带了多少数据"，而全工程
        //   （parseOp / parseEvent / PtpIpClient …）也都拿 `body.size` 当权威长度。
        //   复用一块 128 KiB 的缓冲之后，`body.size` 恒等于缓冲大小、不再等于本帧长度 ——
        //   于是每一帧都被当成"带了 128 KiB"，接收方按它累加：进度冲破总长（上层判为
        //   短收/超收 → 失败重试，表现成"进度跑满又归零、循环往复最后失败"），
        //   多写进去的旧帧字节则直接落盘（缩略图/预览图出现坏块）。
        //   要用复用缓冲，就得把"真实长度"从帧头一路带进 Msg，并把所有 `body.size`
        //   的判据换掉 —— 那是整个协议层的改动，收益（省 25 MB/文件的垃圾）不值这个风险。

        try {
            // ── ① DATA_OPEN（明文） ──
            output.write(PtpCodec.dataOpen(guid16, connNo, token))
            output.flush()

            val ack = PtpCodec.read(input) ?: throw PtpProtocolException("文件端口未回 DATA_OPEN_ACK")
            if (ack.type != PtpCodec.T_DATA_OPEN_ACK) {
                throw PtpProtocolException("文件端口期望 DATA_OPEN_ACK，收到 type=0x${Integer.toHexString(ack.type)}")
            }

            // ── ② 收数据帧（明文） ──
            var received = 0L
            var total = -1L
            var sawStart = false
            var sawEnd = false

            while (!closed.get() && !cancelled()) {
                val m = PtpCodec.read(input) ?: break
                when (m.type) {
                    PtpCodec.T_START_DATA -> {
                        if (sawStart) throw PtpProtocolException("重复 START_DATA")
                        if (PtpCodec.txIdOf(m.body) != expectedTxId) {
                            throw PtpProtocolException("START_DATA txId 不匹配")
                        }
                        total = PtpCodec.totalOf(m.body)
                        if (total < 0 || (expectedBytes >= 0 && total != expectedBytes)) {
                            throw PtpProtocolException("START_DATA total=$total，期望 $expectedBytes")
                        }
                        sawStart = true
                        onTotal(total)
                    }
                    PtpCodec.T_DATA -> {
                        if (!sawStart) throw PtpProtocolException("未收到 START_DATA")
                        if (PtpCodec.txIdOf(m.body) != expectedTxId) {
                            throw PtpProtocolException("DATA txId 不匹配")
                        }
                        val n = dataLen(m.body)
                        if (n > 0) {
                            onChunk(m.body, 4, n)
                            received += n
                            if (total >= 0 && received > total) {
                                throw PtpProtocolException("接收字节超过 total")
                            }
                        }
                    }
                    PtpCodec.T_END_DATA -> {
                        if (!sawStart) throw PtpProtocolException("END_DATA 前未收到 START_DATA")
                        if (PtpCodec.txIdOf(m.body) != expectedTxId) {
                            throw PtpProtocolException("END_DATA txId 不匹配")
                        }
                        val n = dataLen(m.body)
                        if (n > 0) {
                            onChunk(m.body, 4, n)
                            received += n
                        }
                        if (total < 0 || received != total) {
                            throw PtpProtocolException("END_DATA 长度不匹配：$received/$total")
                        }
                        sawEnd = true
                        break
                    }
                    PtpCodec.T_CANCEL -> break
                    else -> {
                        throw PtpProtocolException("数据通道收到意外包 type=0x${Integer.toHexString(m.type)}")
                    }
                }
            }
            if (!cancelled() && (!sawStart || !sawEnd)) {
                throw PtpProtocolException("数据通道未完整结束")
            }
            return received
        } finally {
            close()
        }
    }

    /** 数据帧载荷里数据部分的长度（载荷 = 事务号(4) ‖ 数据）。 */
    private fun dataLen(body: ByteArray): Int =
        if (body.size <= 4) 0 else body.size - 4

    /**
     * 批量收件（2.7.0）：一次 DATA_OPEN 之后，按 `txId = 第几项`（1 起）连续收 [count] 项。
     *
     * <p>每项 = `START_DATA(total, txId=i+1)` → `DATA*` → `END_DATA(txId=i+1)`；
     * 空对象（total=0，只有 START 0 + END 0）= 相机端标记"该项不可用"，回调 null。
     *
     * <p>为什么要批量：实测每对象的成本 = 控制往返 + TCP 建连/慢启动 + DATA_OPEN 往返，
     * 423KB 的预览被这些固定开销切碎；且多流并发在 2.4GHz 弱电台上互相争抢，
     * 聚合反而**低于**单流（25MB 单流实测 2.23MB/s）。批量把固定开销摊销、让数据面回到单流。
     *
     * @param onObject (0 基下标, 该项字节或 null)
     * @return 完整收到的项数（对端提前断开时 < count）
     */
    fun downloadMany(
        token: Long,
        count: Int,
        timeoutMs: Int = 8000,
        labels: List<String> = emptyList(),
        /** true = 复用本通道已建好的连接（跨批共用一条 TCP，省建连/慢启动）。 */
        reuse: Boolean = false,
        onObject: (Int, ByteArray?) -> Unit,
    ): Int {
        val beganAt = android.os.SystemClock.elapsedRealtime()
        val existing = socket
        val s: Socket
        if (reuse && existing != null && !existing.isClosed && existing.isConnected) {
            s = existing
            s.soTimeout = timeoutMs
        } else {
            s = socketFactory?.createSocket() ?: Socket()
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs
            runCatching { s.receiveBufferSize = 1024 * 1024 }
            s.connect(InetSocketAddress(host, filePort), timeoutMs)
            socket = s
        }
        val input = s.getInputStream()
        val output = s.getOutputStream()
        try {
            // ── ① DATA_OPEN（明文） ──
            output.write(PtpCodec.dataOpen(guid16, connNo, token))
            output.flush()
            val ack = PtpCodec.read(input)
                ?: throw PtpProtocolException("文件端口未回 DATA_OPEN_ACK")
            if (ack.type != PtpCodec.T_DATA_OPEN_ACK) {
                throw PtpProtocolException(
                    "批量通道期望 DATA_OPEN_ACK，收到 type=0x${Integer.toHexString(ack.type)}")
            }
            // ── ② 逐项收 ──
            var done = 0
            while (!closed.get() && done < count) {
                val m = PtpCodec.read(input) ?: break
                if (m.type != PtpCodec.T_START_DATA) {
                    throw PtpProtocolException(
                        "批量通道期望 START_DATA，收到 type=0x${Integer.toHexString(m.type)}")
                }
                val tx = PtpCodec.txIdOf(m.body)
                val total = PtpCodec.totalOf(m.body)
                val out = java.io.ByteArrayOutputStream(
                    if (total in 1..(1L shl 20)) total.toInt() else 64 * 1024)
                var got = 0L
                var ended = false
                while (!closed.get()) {
                    val f = PtpCodec.read(input) ?: break
                    val n = dataLen(f.body)
                    when (f.type) {
                        PtpCodec.T_DATA -> {
                            if (PtpCodec.txIdOf(f.body) != tx) {
                                throw PtpProtocolException("批量 DATA txId 不匹配")
                            }
                            if (n > 0) {
                                out.write(f.body, 4, n)
                                got += n
                            }
                        }
                        PtpCodec.T_END_DATA -> {
                            if (PtpCodec.txIdOf(f.body) != tx) {
                                throw PtpProtocolException("批量 END_DATA txId 不匹配")
                            }
                            if (n > 0) {
                                out.write(f.body, 4, n)
                                got += n
                            }
                            ended = true
                            break
                        }
                        else -> throw PtpProtocolException(
                            "批量通道收到意外包 type=0x${Integer.toHexString(f.type)}")
                    }
                }
                if (!ended) return done
                // 长度不符（短收）按"该项失败"回调 null —— 与单对象路径同一条纪律
                val arr = if (got == total) out.toByteArray() else null
                // ★ 2.7.0 提速诊断：逐项到达时刻 —— "项与项之间的空档"就是相机端的解析
                //   时间（向前看没盖住的部分）；"项内变慢"才是链路问题。
                android.util.Log.d("DataCh", "item ${tx - 1} "
                        + (labels.getOrNull(tx - 1) ?: "?") + " "
                        + (if (arr == null) 0 else arr.size) + "B @"
                        + (android.os.SystemClock.elapsedRealtime() - beganAt) + "ms")
                onObject(tx - 1, arr)
                done++
            }
            return done
        } finally {
            if (!reuse) close()      // 复用模式：连接留给下一批，由调用方决定何时关
        }
    }

    override fun close() {
        closed.set(true)
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        socket = null
    }
}
