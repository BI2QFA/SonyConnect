package com.bi2qfa.sonyconnect.ptpip

import java.io.IOException

/**
 * 配对流程客户端（手机端）—— 对应相机端 `PtpIpServer.pairingExchange`。
 *
 * 配对走**同一条控制连接**的握手期明文阶段：Init Cmd Ack 之后、控制循环之前。
 * 成功后相机端已经落表，这条连接直接进控制循环 —— 手机端不需要重连。
 *
 * 线格式（与相机端逐字对齐）：
 * ```
 * ① PAIR_BEGIN    req blob = 本机设备名(UTF-8)
 *                 rsp blob = devIdC(8)
 * ② PAIR_EXCHANGE req blob = 6 位配对码(ASCII)
 *                 rsp      = RC_OK / RC_PAIRING_FAILED
 * ```
 * ③ 相机侧已有本机记录时 ① 直接回 `RC_NOT_SUPPORTED` —— 那是"已配对"而不是失败，
 *    [run] 会带着 `alreadyPaired` 返回，不进入 ②（详见 [run] 里的说明）。
 *
 * ★ 加解密已整体移除，配对码因此是**明文过网**的：同一网段抓包就能看到。它的
 *   作用退化为"证明操作者看得到相机屏幕上那个码"；真正的准入依据始终是两端的
 *   配对表（相机端 `serveControl` 那道门就是查表）。
 *
 * 收发通过构造参数注入（[sendReq] / [recvRsp]），本类不含任何 socket 逻辑，
 * 只负责"按什么顺序发什么" —— 这样它既能在 [PtpIpClient] 里跑真连接，
 * 也能被单独驱动做验证。
 */
class PairingClient(
    private val sendReq: (opCode: Int, txId: Int, blob: ByteArray) -> Unit,
    private val recvRsp: () -> PtpCodec.OpBlob,
) {

    /**
     * 配对成功的产物。
     *
     * [alreadyPaired] = true 表示**相机那边早就有本机的记录了**、且它把这个配对请求
     * 当协议错用回绝掉了。
     *
     * <p>★ 2026-09-19 起这条**不会再产生**：相机端已改成"已授权会话里收到 PAIR_BEGIN
     * 也走正常配对流程"，所以手机端只会拿到 RC_OK（要输码）或失败。
     * 字段与分支保留是为了兼容**旧版相机端**（它仍可能回 NOT_SUPPORTED）；
     * 但现在的处理是**明确报错**（提示用户更新相机端），而不是原来那种"跳过输码"——
     * 后者会建出一条状态不对的连接，后续请求全部超时（用户实测复现）。
     */
    class Result(val cameraDeviceId: ByteArray, val alreadyPaired: Boolean = false)

    /** 配对失败（码不对、窗口关了、相机不认等）。消息直接可展示给用户。 */
    class PairingFailedException(message: String) : IOException(message)

    /**
     * 相机被占用：另一台手机正在配对，或相机已连着别的设备。
     *
     * 单独一个类型而不是复用 [PairingFailedException]：这不是"码错了"，用户能做的是
     * 等一会儿或去把另一台断开 —— 提示语完全不同。
     */
    class DeviceBusyException(message: String) : IOException(message)

    /**
     * 走完两步配对（**一次调完的便捷形式**）。
     *
     * <p>新流程（相机端待机不亮码）请用 [begin] + [exchange] 两步调用：相机要收到
     * [begin] 才生成并显示配对码，用户是**看着相机屏输码**的，本方法把两步连在一起
     * 会导致"码还没显示、用户已经在输了"。
     *
     * <p>保留它是为了兼容"相机端已经把码亮出来了"的场景（例如相机端窗口本来就开着
     * 的时候重试），以及既有测试。
     *
     * @param code       用户在手机端输入的 6 位数字（相机屏上显示的那串）
     * @param deviceName 本机设备名（相机端配对页要显示，例如 `Xiaomi 15`）
     */
    @Throws(IOException::class)
    fun run(code: String, deviceName: String): Result {
        val beginResult = begin(deviceName)
        if (beginResult.alreadyPaired) {
            return beginResult
        }
        exchange(code)
        return beginResult
    }

    /**
     * **第一步**：报上设备名，换回相机设备码；相机收到这一条才会生成并显示配对码。
     *
     * <p>调用后连接**保持打开**，等用户看完相机屏再把码交给 [exchange]。
     *
     * @param deviceName 本机设备名（相机端配对页要显示）
     */
    @Throws(IOException::class)
    fun begin(deviceName: String): Result {
        // ---- ① PAIR_BEGIN：报上设备名，换回相机设备码 ----
        sendReq(PtpCodec.OP_PAIR_BEGIN, TX_BEGIN, deviceName.toByteArray(Charsets.UTF_8))
        val r1 = recvRsp()
        if (r1.code == PtpCodec.RC_PAIRING_FAILED) {
            throw PairingFailedException("相机未开启配对模式")
        }
        if (r1.code == PtpCodec.RC_DEVICE_BUSY) {
            // 相机被占用（另一台在配对中，或已连着别的设备）：相机端刻意用这个码
            // 把"现在不行"与"码不对"分开，提示语完全不同。
            throw DeviceBusyException("相机正被其他设备占用")
        }
        if (r1.code == PtpCodec.RC_NOT_SUPPORTED) {
            // ★ 2026-09-19 改：这里原来**直接当作"已配对"放行**（不输码就建连接），
            //   理由是"相机认识我、我不认识相机"。用户实测发现那条路是坏的：
            //   相机在已授权会话里收到 PAIR_BEGIN 一律回 NOT_SUPPORTED，手机于是
            //   既不输码、连接状态又不对，后续每个请求都超时。
            //
            //   用户定版："这种情况应该走正常的配对输码流程。"
            //   相机端已改成：已授权会话里收到 PAIR_BEGIN 时**真正走配对流程**
            //   （亮码 → 等 PAIR_EXCHANGE 验码）。所以现在回 NOT_SUPPORTED
            //   只可能是**相机端还是旧版本**（旧版才会在这里回绝）。
            throw PairingFailedException("相机端版本过旧，无法完成配对，请更新相机端")
        }
        if (r1.code != PtpCodec.RC_OK) {
            throw PairingFailedException("配对被拒绝（0x${Integer.toHexString(r1.code)}）")
        }
        if (r1.blob.size < DEVICE_ID_LEN) {
            throw PairingFailedException("PAIR_BEGIN 应答过短: ${r1.blob.size}")
        }
        return Result(r1.blob.copyOfRange(0, DEVICE_ID_LEN))
    }

    /**
     * **第二步**：把用户看着相机屏输入的 6 位码交给相机核对。
     *
     * <p>必须在 [begin] 之后、同一条连接上调用。
     */
    @Throws(IOException::class)
    fun exchange(code: String) {
        val clean = code.trim()
        if (clean.length != CODE_LEN || !clean.all { it in '0'..'9' }) {
            throw PairingFailedException("配对码必须是 $CODE_LEN 位数字")
        }
        sendReq(PtpCodec.OP_PAIR_EXCHANGE, TX_EXCHANGE, clean.toByteArray(Charsets.US_ASCII))
        val r2 = recvRsp()
        if (r2.code != PtpCodec.RC_OK) {
            throw PairingFailedException("配对码不正确或已失效")
        }
    }

    /** 中止本次配对（相机端会放弃这次尝试）。best-effort：失败无所谓，连接反正要关。 */
    fun abort() {
        runCatching { sendReq(PtpCodec.OP_PAIR_ABORT, TX_ABORT, ByteArray(0)) }
    }

    companion object {
        const val CODE_LEN = 6
        const val DEVICE_ID_LEN = 8

        /** 配对阶段固定事务号（与相机端 `TX_PAIR_*` 同值）。 */
        const val TX_BEGIN = 0x41
        const val TX_EXCHANGE = 0x42
        const val TX_ABORT = 0x44
    }
}
