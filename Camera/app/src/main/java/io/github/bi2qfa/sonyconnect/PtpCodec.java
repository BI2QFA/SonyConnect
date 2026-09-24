package io.github.bi2qfa.sonyconnect;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * PTP/IP（CIPA DC-X005）线格式编解码。
 *
 * 设计约束：
 * - 纯逻辑、零 Android 依赖 → 桌面 desktop-test 可直接编译与断言。
 * - 源码兼容 Java 1.6（相机老工具链）：不用泛型钻石、不用 try-with-resources、不用 multi-catch。
 * - 所有多字节整数一律**小端（LE）**，与 PTP/IP 规范一致。
 *
 * ★ 本表是实现依据，不是「已验证事实」。P1 已用固件 libInfraPtpControl.so 交叉验证到：
 *   `AcceptDataSocket` / `AcceptEventSocket`（独立数据/事件 socket）、`SendInitCommandAck`、
 *   `SendInitEventAck` / `SendInitFail`、`SendCancelResponce`、`SendProbeRequest`、`ChangeToUtf16`。
 *   逐字段对规范原文的核对在 P1 内持续进行。
 */
public final class PtpCodec {

    private PtpCodec() {
    }

    /**
     * 本应用的版本号字符串（**日志、关于页、探测包厂商标识共用这一处**）。
     * ★ 升版本时这里 + {@code build.gradle} + {@code AndroidManifest} 一起改。
     */
    public static final String APP_VERSION = "2.6";

    // ===== 通用包头 =====
    // Length(4, LE, 含包头在内的整包字节数) + Type(4, LE)
    public static final int HEADER_LEN = 8;

    /** 单包上限，防坏包 / 恶意超长包把内存吃光（控制面 1 MiB 足够）。 */
    public static final int MAX_PACKET = 1024 * 1024;

    /**
     * 数据通道分块大小（128 KiB）。
     *
     * <p>两头都有代价：太小 → 每块的固定成本（帧头、一次 socket 写、一次进度
     * 回调）被放大；太大 → 峰值内存吃不消（相机端堆很小）且取消响应变迟钝。
     * 取值与 {@link #MAX_PACKET} 留足余量。
     */
    public static final int CHUNK = 128 * 1024;

    // ===== 包类型（标准区 0x01–0x0E）=====
    public static final int T_INIT_CMD_REQ = 0x0001;
    public static final int T_INIT_CMD_ACK = 0x0002;
    public static final int T_INIT_EVENT_REQ = 0x0003;
    public static final int T_INIT_EVENT_ACK = 0x0004;
    public static final int T_INIT_FAIL = 0x0005;
    public static final int T_OPERATION_REQ = 0x0006;
    public static final int T_OPERATION_RSP = 0x0007;
    public static final int T_EVENT = 0x0008;
    public static final int T_START_DATA = 0x0009;
    public static final int T_DATA = 0x000A;
    public static final int T_END_DATA = 0x000B;
    public static final int T_CANCEL = 0x000C;
    /** UDP 发现：探测请求（广播） */
    public static final int T_PROBE_REQ = 0x000D;
    /** UDP 发现：探测应答（单播） */
    public static final int T_PROBE_RESP = 0x000E;

    // ===== 厂商扩展包类型（0x40+，刻意避开标准区）=====
    /** 文件端口首包：数据连接握手。载荷 = GUID(16) + ConnNo(4) + token(8) */
    public static final int T_DATA_OPEN = 0x0040;
    public static final int T_DATA_OPEN_ACK = 0x0041;

    // ===== 数据阶段标记（Operation Request/Response 首字段）=====
    public static final int DP_NONE = 0;
    public static final int DP_DATA_IN = 1;
    public static final int DP_DATA_OUT = 2;

    // ===== Init Fail 原因 =====
    public static final int FAIL_REJECTED = 0x01;
    public static final int FAIL_UNSUPPORTED = 0x02;
    public static final int FAIL_BUSY = 0x03;
    /** 本机未与对方配对（原 FAIL_CRYPTO_REQUIRED；加密层已移除，语义改为"先配对"）。 */
    public static final int FAIL_NOT_PAIRED = 0x04;

    // ===== 厂商操作码（0x9000–0x9FFF）=====
    public static final int OP_PAIR_BEGIN = 0x9001;
    public static final int OP_PAIR_EXCHANGE = 0x9002;
    public static final int OP_PAIR_ABORT = 0x9004;
    /** 手机解除配对：请求相机删掉自己那条配对记录（在已认证会话内执行）。 */
    public static final int OP_PAIR_REMOVE = 0x9005;
    // 0x9010 / 0x9011 原为双向认证的两个操作码；加密层整体移除后不再使用，号段留空。
    /**
     * 实时数据包（心跳）。**唯一**的周期性操作，一次往返带回全部会变的东西：
     * 参数 {@code {batteryPct, hasLens?1:0}} + blob 镜头名（UTF-8，空串=未装镜头）。
     * <p>之所以把镜头名并进这里：旧设计里电量走 3s 的 PING、镜头名走 5s 的
     * DEVICE_INFO，两个短周期定时器各自往返一次，纯属浪费。现在实时数据只有这一个包。
     */
    public static final int OP_PING = 0x9012;
    /**
     * 静态快照（连接时拉一次即可）。只含**不会变**的字段：
     * {@code name / model / serial / firmware / mode / ssid}。
     * <p>电量与镜头名属于实时数据，已移到 {@link #OP_PING}，这里不再重复下发。
     */
    public static final int OP_DEVICE_INFO = 0x9013;
    public static final int OP_LIST_DIR = 0x9020;
    public static final int OP_STAT = 0x9021;
    public static final int OP_GET_OBJECT = 0x9022;
    public static final int OP_THUMB_QUEUE_BEGIN = 0x9023;
    public static final int OP_THUMB_QUEUE_PAUSE = 0x9024;
    public static final int OP_THUMB_QUEUE_RESUME = 0x9025;
    public static final int OP_THUMB_QUEUE_CANCEL = 0x9026;
    /**
     * 批量取件（2.6）：一次换一个**批令牌**，随后在同一条数据连接上连续发多张，
     * 省掉"每张一次控制往返 + 每批一次 TCP 三握"。载荷 = 每行 {@code "T\t<path>"} 或
     * {@code "P\t<path>"}（T=小缩略图、P=大预览），最多 32 行。
     */
    public static final int OP_GET_OBJECT_BATCH = 0x9027;
    // 0x9030 原为 OP_EXIT_APP（手机请相机端退出，用于"传输完成后自动关闭相机端"）。
    // 该功能已按用户要求**两端一起彻底删除**，号段留空不再使用。
    // 相机端的退出只剩**手动**一条路（相机自己选项菜单里的「退出应用程序」）。

    // ===== 厂商事件码 =====
    public static final int EV_THUMB_PROGRESS = 0x9041;
    /**
     * 相机已解除本机的配对（相机端在配对页删掉了这台手机）。
     *
     * 相机 → 手机的单向通知：收到就该清本地配对记录并断开当前会话，否则手机侧
     * 还留着一个相机已不认的旧记录。对方不在线时送不到，由下次连接的
     * {@code Init Fail(NOT_PAIRED)} 兜底（手机端收到该拒绝码会做同样的事）。
     */
    public static final int EV_PAIRED_REMOVED = 0x9042;
    /**
     * 相机端正在退出（退出软件 / 切换连接方式 / 被会话强收）—— 手机端收到即断开。
     *
     * 相机 → 手机的单向通知，由 {@code PtpIpServer.stop()} 在拆连接**之前**发出：
     * 没有它，手机端只能等 3 次心跳失联（约 9 秒）才自己断，期间界面还显示"已连接"，
     * 用户看着是一个连不上也不报错的死界面。
     */
    public static final int EV_APP_EXITING = 0x9043;
    /**
     * 相机端**正在切换连接方式**（相机 → 手机的单向通知）。
     *
     * <p>参数 {@code {mode}}：0 = Wi-Fi 网络（接入点），1 = 相机热点。
     *
     * <p>为什么要有这条：切换方式要重配无线电，手机那侧的链路**一定会断**，
     * 但断的原因和"退出软件"完全不同 —— 拆连接时 {@code PtpIpServer.stop()} 只会发
     * {@link #EV_APP_EXITING}，手机端于是显示"相机端已退出"，用户会跑去相机上看它
     * 是不是真的关了（其实相机正开着、界面还写着"正在切换模式···"）。
     * 切换**开始前**先发这条，手机端才说得出刚发生的事。
     *
     * <p>发出后本次会话的 {@code EV_APP_EXITING} 会被**抑制**（见
     * {@code PtpIpServer#pushAppExiting()}）—— 两条都发的话，后一条会把前一条的
     * 说明覆盖掉，等于白说。
     */
    public static final int EV_MODE_SWITCHING = 0x9044;
    /** {@link #EV_MODE_SWITCHING} 参数：切到 Wi-Fi 网络（接入点） */
    public static final int MODE_CODE_WIFI = 0;
    /** {@link #EV_MODE_SWITCHING} 参数：切到相机热点 */
    public static final int MODE_CODE_HOTSPOT = 1;

    // ===== 响应码（★ 严格对齐方案 §4.6 表，勿再改动数值）=====
    public static final int RC_OK = 0x2001;
    /** 未分类失败 */
    public static final int RC_GENERAL_ERROR = 0x2002;
    /** 未认证 / 会话已失效 */
    public static final int RC_SESSION_NOT_OPEN = 0x2003;
    /** 事务号不匹配 */
    public static final int RC_INVALID_TX = 0x2004;
    /** 未知操作码 */
    public static final int RC_NOT_SUPPORTED = 0x2005;
    /** 路径逃逸 / 越权 */
    public static final int RC_ACCESS_DENIED = 0x2006;
    /** 路径不存在 */
    public static final int RC_NOT_FOUND = 0x2007;
    /** 正在传输，拒绝新会话 */
    public static final int RC_DEVICE_BUSY = 0x2008;
    /** 配对码错 / 窗口超时 */
    public static final int RC_PAIRING_FAILED = 0x2009;
    /** 厂商扩展：传输被取消（非规范值，仅本实现内部使用） */
    public static final int RC_CANCELED = 0x200B;

    /** GET_OBJECT 的 kind 参数 */
    public static final int KIND_THUMB = 0;
    public static final int KIND_PREVIEW = 1;
    public static final int KIND_ORIGINAL = 2;

    /**
     * 厂商友好名标记：带此后缀的探测请求，相机才回扩展应答（保护标准互操作）。
     * ★ 它是**协议契约**：手机端 {@code PtpCodec.VENDOR_TAG} 必须与本处逐字一致，
     *   不一致时手机端就"扫不到设备"——改版本号时两端必须一起改、一起装。
     */
    public static final String VENDOR_TAG = "SonyConnect/" + APP_VERSION;

    // ============================================================
    // 基础字节读写（小端）
    // ============================================================

    public static int u8(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    public static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static int i32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    public static long i64(byte[] b, int off) {
        long lo = i32(b, off) & 0xFFFFFFFFL;
        long hi = i32(b, off + 4) & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    public static void put16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    public static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    public static void put64(byte[] b, int off, long v) {
        put32(b, off, (int) (v & 0xFFFFFFFFL));
        put32(b, off + 4, (int) ((v >>> 32) & 0xFFFFFFFFL));
    }

    // ============================================================
    // 组帧 / 解帧
    // ============================================================

    /** 只生成包头（Length 由 payloadLen 推得）。 */
    public static byte[] header(int type, int payloadLen) {
        byte[] h = new byte[HEADER_LEN];
        put32(h, 0, HEADER_LEN + payloadLen);
        put32(h, 4, type);
        return h;
    }

    /** 包 = 包头 + 载荷。 */
    public static byte[] frame(int type, byte[] payload) {
        int n = payload == null ? 0 : payload.length;
        byte[] out = new byte[HEADER_LEN + n];
        put32(out, 0, out.length);
        put32(out, 4, type);
        if (n > 0) {
            System.arraycopy(payload, 0, out, HEADER_LEN, n);
        }
        return out;
    }

    /** 一个已解析的 PTP/IP 包。 */
    public static final class Msg {
        public int type;
        public byte[] body;

        public Msg(int type, byte[] body) {
            this.type = type;
            this.body = body;
        }
    }

    /**
     * 从流里读一个完整包。
     * @return null 表示对端干净关闭（第一个字节就读到 EOF）；截断 / 坏长度抛 IOException。
     */
    public static Msg read(InputStream in) throws IOException {
        byte[] h = new byte[HEADER_LEN];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        h[0] = (byte) first;
        readFully(in, h, 1, HEADER_LEN - 1);

        int len = i32(h, 0);
        int type = i32(h, 4);
        if (len < HEADER_LEN || len > MAX_PACKET) {
            throw new IOException("坏包长度: " + len + " (type=" + type + ")");
        }
        byte[] body = new byte[len - HEADER_LEN];
        if (body.length > 0) {
            readFully(in, body, 0, body.length);
        }
        return new Msg(type, body);
    }

    /** 读满 buffer 的 [off, off+len)，EOF 抛 EOFException（区别于「读到 0 字节」）。 */
    public static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int r = in.read(buf, off + got, len - got);
            if (r < 0) {
                throw new EOFException("包体截断: 还差 " + (len - got) + " 字节");
            }
            got += r;
        }
    }

    // ============================================================
    // FriendlyName：1 字节字符数 + UTF-16LE 字节（规范原生编码）
    // ============================================================

    /** 编码后的字节数（含 1 字节长度前缀）。 */
    public static int nameLen(String s) {
        return 1 + s.length() * 2;
    }

    /** 写入 name 到 buf 的 off 处，返回写入字节数。 */
    public static int writeName(byte[] buf, int off, String s) {
        int chars = Math.min(s.length(), 255);
        buf[off] = (byte) chars;
        for (int i = 0; i < chars; i++) {
            char c = s.charAt(i);
            buf[off + 1 + i * 2] = (byte) (c & 0xFF);
            buf[off + 1 + i * 2 + 1] = (byte) ((c >>> 8) & 0xFF);
        }
        return 1 + chars * 2;
    }

    /** 读 name；结果放 [0]=字符串，holder[0]=下一偏移。 */
    public static String readName(byte[] b, int off, int[] nextOff) {
        int chars = u8(b, off);
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            int lo = b[off + 1 + i * 2] & 0xFF;
            int hi = b[off + 1 + i * 2 + 1] & 0xFF;
            sb.append((char) ((hi << 8) | lo));
        }
        if (nextOff != null && nextOff.length > 0) {
            nextOff[0] = off + 1 + chars * 2;
        }
        return sb.toString();
    }

    // ============================================================
    // 各类包构造器
    // ============================================================

    /**
     * 把协议版本字段格式化成可读的 {@code 主.次.修订}（打日志用）。
     *
     * 与手机端 {@code PtpIpClient.formatProtoVersion} **同一套算法**：高 8 位主版本、
     * 次 8 位次版本、低 8 位修订号。2.5.5 因此显示成 {@code 2.5.5} 而不是 {@code 132357}；
     * 修订号为 0 时**省略第三段**（{@code 2.6.0} 印成 {@code 2.6}）。
     */
    public static String formatProtoVersion(int v) {
        int major = (v >>> 16) & 0xFF;
        int minor = (v >>> 8) & 0xFF;
        int rev = v & 0xFF;
        // 修订号为 0 时省略第三段：2.6.0 印成 "2.6"
        return rev == 0 ? major + "." + minor : major + "." + minor + "." + rev;
    }

    /** Init Command Request：GUID(16) + FriendlyName + ProtocolVersion(4)。 */
    public static byte[] initCmdReq(byte[] guid, String friendlyName, int protoVer) {
        int nl = nameLen(friendlyName);
        byte[] p = new byte[16 + nl + 4];
        System.arraycopy(guid, 0, p, 0, 16);
        writeName(p, 16, friendlyName);
        put32(p, 16 + nl, protoVer);
        return frame(T_INIT_CMD_REQ, p);
    }

    /** Init Command Ack：ConnectionNumber(4) + GUID(16) + FriendlyName + ProtocolVersion(4)。 */
    public static byte[] initCmdAck(int connNo, byte[] guid, String friendlyName, int protoVer) {
        int nl = nameLen(friendlyName);
        byte[] p = new byte[4 + 16 + nl + 4];
        put32(p, 0, connNo);
        System.arraycopy(guid, 0, p, 4, 16);
        writeName(p, 20, friendlyName);
        put32(p, 20 + nl, protoVer);
        return frame(T_INIT_CMD_ACK, p);
    }

    /** Init Event Request：ConnectionNumber(4)。 */
    public static byte[] initEventReq(int connNo) {
        byte[] p = new byte[4];
        put32(p, 0, connNo);
        return frame(T_INIT_EVENT_REQ, p);
    }

    /** Init Event Ack（空载荷）。 */
    public static byte[] initEventAck() {
        return frame(T_INIT_EVENT_ACK, new byte[0]);
    }

    /** Init Fail：Reason(4)。 */
    public static byte[] initFail(int reason) {
        byte[] p = new byte[4];
        put32(p, 0, reason);
        return frame(T_INIT_FAIL, p);
    }

    /** Operation Request：DataPhaseInfo(4) + OperationCode(2) + TransactionID(4) + Params(4×n)。 */
    public static byte[] opReq(int dataPhase, int opCode, int txId, int[] params) {
        return frame(T_OPERATION_REQ, opBody(dataPhase, opCode, txId, params));
    }

    /** Operation Response：DataPhaseInfo(4) + ResponseCode(2) + TransactionID(4) + Params(4×n)。 */
    public static byte[] opRsp(int dataPhase, int respCode, int txId, int[] params) {
        return frame(T_OPERATION_RSP, opBody(dataPhase, respCode, txId, params));
    }

    /** Request 与 Response 的公共体：偏移 4 是 16 位码、偏移 6 是 32 位事务号，布局一致。 */
    private static byte[] opBody(int dataPhase, int code, int txId, int[] params) {
        int n = params == null ? 0 : params.length;
        byte[] p = new byte[10 + n * 4];
        put32(p, 0, dataPhase);
        put16(p, 4, code);
        put32(p, 6, txId);
        for (int i = 0; i < n; i++) {
            put32(p, 10 + i * 4, params[i]);
        }
        return p;
    }

    /** 操作请求 / 应答的公共解析结果。 */
    public static final class Op {
        public int dataPhase;
        public int code;
        public int txId;
        public int[] params;
    }

    public static Op parseOp(byte[] body) throws IOException {
        if (body.length < 10) {
            throw new IOException("操作包过短: " + body.length);
        }
        Op o = new Op();
        o.dataPhase = i32(body, 0);
        o.code = u16(body, 4);
        o.txId = i32(body, 6);
        int n = (body.length - 10) / 4;
        o.params = new int[n];
        for (int i = 0; i < n; i++) {
            o.params[i] = i32(body, 10 + i * 4);
        }
        return o;
    }

    /**
     * 扩展 Operation Request（**厂商内联 blob 形式**，与 {@link #opRspBlob} 对称）：
     *
     * <pre>
     * dataPhase(4)=DP_DATA_IN | opCode(2) | txId(4) | paramCount(4)=0 | blobLen(4) | blob(blobLen)
     * </pre>
     *
     * 与响应体布局一致（偏移 4 是 16 位码、偏移 6 是事务号），故解析共用 {@link #parseOpBlob}。
     * 用途：认证 nonce / 配对 prekey / 带路径的 {@code LIST_DIR}·{@code STAT} 请求。
     */
    public static byte[] opReqBlob(int opCode, int txId, byte[] blob) {
        return opReqBlobParams(opCode, txId, null, blob);
    }

    /**
     * 扩展 Operation Request 通用形式：**参数区 + 内联 blob**（{@link #opRspBlob} 的请求侧镜像）。
     *
     * <pre>
     * dataPhase(4)=DP_DATA_IN | opCode(2) | txId(4) | paramCount(4) | params(4×n) | blobLen(4) | blob(blobLen)
     * </pre>
     *
     * ★ 必须整体用 {@link #parseOpBlob} 解析，**不可**与 {@link #parseOp} 混用：
     * 两者的参数区偏移相差 4 字节（{@code parseOp} 从偏移 10 起、本形式 params 从偏移 14 起），
     * 同一 body 按两种方式解析会得到错位参数。
     *
     * 用途：{@code GET_OBJECT} 需要「数值参数（kind/offset/length）+ 路径 blob」同时出现，
     * 而 {@link #opReqBlob} 的 paramCount 恒为 0，装不下数值参数。
     */
    public static byte[] opReqBlobParams(int opCode, int txId, int[] params, byte[] blob) {
        int n = params == null ? 0 : params.length;
        int bl = blob == null ? 0 : blob.length;
        byte[] p = new byte[4 + 2 + 4 + 4 + n * 4 + 4 + bl];
        put32(p, 0, DP_DATA_IN);
        put16(p, 4, opCode);
        put32(p, 6, txId);
        put32(p, 10, n);
        for (int i = 0; i < n; i++) {
            put32(p, 14 + i * 4, params[i]);
        }
        int o = 14 + n * 4;
        put32(p, o, bl);
        if (bl > 0) {
            System.arraycopy(blob, 0, p, o + 4, bl);
        }
        return frame(T_OPERATION_REQ, p);
    }

    /**
     * 扩展 Operation Response（**厂商内联 blob 形式**，方案 §4.5 授权）：
     *
     * <pre>
     * dataPhase(4)=DP_DATA_IN | code(2) | txId(4) | paramCount(4) | params(4×n) | blobLen(4) | blob(blobLen)
     * </pre>
     *
     * 用途：LIST_DIR 列表 / DEVICE_INFO 全量信息这类"小元数据一次往返"的载荷。
     * ★ 与标准 PTP/IP 的 DataPhase 交错**不同**：本实现把 blob 直接内联在响应体里，
     * 控制连接因此保持纯请求/响应模型（不做 Start/Data/End Data 交错）。
     * 接收端见到 {@code dataPhase == DP_DATA_IN} 即应改用 {@link #parseOpBlob}。
     */
    public static byte[] opRspBlob(int respCode, int txId, int[] params, byte[] blob) {
        int n = params == null ? 0 : params.length;
        int bl = blob == null ? 0 : blob.length;
        byte[] p = new byte[4 + 2 + 4 + 4 + n * 4 + 4 + bl];
        put32(p, 0, DP_DATA_IN);
        put16(p, 4, respCode);
        put32(p, 6, txId);
        put32(p, 10, n);
        for (int i = 0; i < n; i++) {
            put32(p, 14 + i * 4, params[i]);
        }
        int o = 14 + n * 4;
        put32(p, o, bl);
        if (bl > 0) {
            System.arraycopy(blob, 0, p, o + 4, bl);
        }
        return frame(T_OPERATION_RSP, p);
    }

    /** 扩展响应体解析结果（见 {@link #opRspBlob}）。 */
    public static final class OpBlob {
        public int dataPhase;
        public int code;
        public int txId;
        public int[] params;
        /** 无 blob 时为空数组，不为 null */
        public byte[] blob;
    }

    public static OpBlob parseOpBlob(byte[] body) throws IOException {
        if (body.length < 18) {
            throw new IOException("扩展响应包过短: " + body.length);
        }
        OpBlob o = new OpBlob();
        o.dataPhase = i32(body, 0);
        o.code = u16(body, 4);
        o.txId = i32(body, 6);
        int n = i32(body, 10);
        if (n < 0 || 14 + n * 4 + 4 > body.length) {
            throw new IOException("扩展响应 paramCount 越界: " + n);
        }
        o.params = new int[n];
        for (int i = 0; i < n; i++) {
            o.params[i] = i32(body, 14 + i * 4);
        }
        int bl = i32(body, 14 + n * 4);
        if (bl < 0 || 14 + n * 4 + 4 + bl > body.length) {
            throw new IOException("扩展响应 blobLen 越界: " + bl);
        }
        o.blob = new byte[bl];
        if (bl > 0) {
            System.arraycopy(body, 14 + n * 4 + 4, o.blob, 0, bl);
        }
        return o;
    }

    /** Event：EventCode(2) + TransactionID(4) + Params(4×n)。 */
    public static byte[] event(int evCode, int txId, int[] params) {
        int n = params == null ? 0 : params.length;
        byte[] p = new byte[6 + n * 4];
        put16(p, 0, evCode);
        put32(p, 2, txId);
        for (int i = 0; i < n; i++) {
            put32(p, 6 + i * 4, params[i]);
        }
        return frame(T_EVENT, p);
    }

    public static final class Ev {
        public int code;
        public int txId;
        public int[] params;
    }

    public static Ev parseEvent(byte[] body) throws IOException {
        if (body.length < 6) {
            throw new IOException("事件包过短: " + body.length);
        }
        Ev e = new Ev();
        e.code = u16(body, 0);
        e.txId = i32(body, 2);
        int n = (body.length - 6) / 4;
        e.params = new int[n];
        for (int i = 0; i < n; i++) {
            e.params[i] = i32(body, 6 + i * 4);
        }
        return e;
    }

    // ============================================================
    // 数据阶段
    // ============================================================

    /**
     * Start Data：TransactionID(4) + TotalDataLength(**8 字节**)。
     * ★ 64 位总长是 PTP/IP 最常见的实现坑，单测必须覆盖。
     */
    public static byte[] startData(int txId, long total) {
        byte[] p = new byte[12];
        put32(p, 0, txId);
        put64(p, 4, total);
        return frame(T_START_DATA, p);
    }

    /**
     * Data：TransactionID(4) + Data(…)，数据取自 {@code src[off, off+len)}。
     *
     * <p>为什么要有区间版：数据通道按块发送，块就躺在复用的读取缓冲里，区间版
     * 让它**只拷一次**就进最终帧。整块版（整个数组）留给测试与零散调用。
     */
    public static byte[] dataPacket(int txId, byte[] src, int off, int len) {
        byte[] p = new byte[4 + len];
        put32(p, 0, txId);
        if (len > 0) {
            System.arraycopy(src, off, p, 4, len);
        }
        return frame(T_DATA, p);
    }

    /** End Data：TransactionID(4) + Data(末块)，数据取自 {@code src[off, off+len)}。 */
    public static byte[] endData(int txId, byte[] src, int off, int len) {
        byte[] p = new byte[4 + len];
        put32(p, 0, txId);
        if (len > 0) {
            System.arraycopy(src, off, p, 4, len);
        }
        return frame(T_END_DATA, p);
    }

    // ===== 数据帧的"就地"写法（传输热路径专用）=====

    /** 数据帧里载荷的起始下标：包头 8 + TransactionID 4。 */
    public static final int DATA_PAYLOAD_OFFSET = HEADER_LEN + 4;

    /**
     * 「就地」数据帧缓冲要多大：包头 + TransactionID + 一整块 {@link #CHUNK}。
     *
     * <p>★ 为什么要有这套写法：原来每个 128 KiB 的块都走 {@link #dataPacket} ——
     * 那一版先 {@code new byte[4+len]}（载荷副本）、再由 {@link #frame} 拷进
     * {@code new byte[HEADER_LEN+n]}（整个帧），**每块两次分配、每个字节拷两遍**。
     * 几十 MB 的文件就是几百 MB 的垃圾与几百 MB 的 memcpy，全落在相机那个很小的堆上，
     * GC 抖动直接压住吞吐。就地写法只写 12 字节包头，数据由调用方**直接读进帧缓冲**
     * （{@code readInto(..., frame, DATA_PAYLOAD_OFFSET, n)}）—— 一次拷贝都不做，
     * 一次传输只分配这一块缓冲。
     */
    public static int dataFrameCapacity() {
        return DATA_PAYLOAD_OFFSET + CHUNK;
    }

    /** 载荷长 {@code payloadLen} 时，整帧多长。 */
    public static int dataFrameLen(int payloadLen) {
        return DATA_PAYLOAD_OFFSET + payloadLen;
    }

    /**
     * 把包头写进调用方复用的 {@code frame}（载荷此时已就位在
     * {@code frame[DATA_PAYLOAD_OFFSET..]}）。
     *
     * @param type {@link #T_DATA} 或 {@link #T_END_DATA}
     */
    public static void fillDataFrame(byte[] frame, int type, int txId, int payloadLen) {
        put32(frame, 0, DATA_PAYLOAD_OFFSET + payloadLen);
        put32(frame, 4, type);
        put32(frame, HEADER_LEN, txId);
    }

    /** Data：TransactionID(4) + Data(…)。 */
    public static byte[] dataPacket(int txId, byte[] data) {
        int n = data == null ? 0 : data.length;
        byte[] p = new byte[4 + n];
        put32(p, 0, txId);
        if (n > 0) {
            System.arraycopy(data, 0, p, 4, n);
        }
        return frame(T_DATA, p);
    }

    /** End Data：TransactionID(4) + Data(末块)。 */
    public static byte[] endData(int txId, byte[] data) {
        int n = data == null ? 0 : data.length;
        byte[] p = new byte[4 + n];
        put32(p, 0, txId);
        if (n > 0) {
            System.arraycopy(data, 0, p, 4, n);
        }
        return frame(T_END_DATA, p);
    }

    /** Cancel：TransactionID(4)。 */
    public static byte[] cancel(int txId) {
        byte[] p = new byte[4];
        put32(p, 0, txId);
        return frame(T_CANCEL, p);
    }

    /** Start/Data/End Data/Cancel 的载荷头都是 4 字节事务号。 */
    public static int txIdOf(byte[] body) {
        return body.length >= 4 ? i32(body, 0) : 0;
    }

    /** Start Data 载荷里的 64 位总长。 */
    public static long totalOf(byte[] body) throws IOException {
        if (body.length < 12) {
            throw new IOException("Start Data 载荷过短: " + body.length);
        }
        return i64(body, 4);
    }

    // ============================================================
    // 厂商扩展：文件端口握手
    // ============================================================

    /** DATA_OPEN（文件端口首包）：GUID(16) + ConnNo(4) + token(8)。 */
    public static byte[] dataOpen(byte[] guid, int connNo, long token) {
        byte[] p = new byte[28];
        System.arraycopy(guid, 0, p, 0, 16);
        put32(p, 16, connNo);
        put64(p, 20, token);
        return frame(T_DATA_OPEN, p);
    }

    public static byte[] dataOpenAck() {
        return frame(T_DATA_OPEN_ACK, new byte[0]);
    }

    /** 文件端口握手载荷解析结果。 */
    public static final class DataOpen {
        public byte[] guid;
        public int connNo;
        public long token;
    }

    public static DataOpen parseDataOpen(byte[] body) throws IOException {
        if (body.length < 28) {
            throw new IOException("DATA_OPEN 载荷过短: " + body.length);
        }
        DataOpen d = new DataOpen();
        d.guid = new byte[16];
        System.arraycopy(body, 0, d.guid, 0, 16);
        d.connNo = i32(body, 16);
        d.token = i64(body, 20);
        return d;
    }

    // ============================================================
    // UDP 发现（Probe Request / Response）
    // ============================================================

    /**
     * 探测包载荷：GUID(16) + FriendlyName + 厂商扩展尾(8)：
     * protoPort(2) + filePort(2) + pairingMode(1) + paired(1) + 保留(2)。
     * ★ 扩展尾是**厂商私有**的：请求 FriendlyName 不带 {@link #VENDOR_TAG} 时相机不应回扩展。
     */
    public static byte[] probe(boolean request, byte[] guid, String friendlyName,
                               int protoPort, int filePort, boolean pairingMode, boolean paired) {
        int nl = nameLen(friendlyName);
        byte[] p = new byte[16 + nl + 8];
        System.arraycopy(guid, 0, p, 0, 16);
        writeName(p, 16, friendlyName);
        int o = 16 + nl;
        put16(p, o, protoPort);
        put16(p, o + 2, filePort);
        p[o + 4] = (byte) (pairingMode ? 1 : 0);
        p[o + 5] = (byte) (paired ? 1 : 0);
        put16(p, o + 6, 0);
        return frame(request ? T_PROBE_REQ : T_PROBE_RESP, p);
    }

    /** 探测包解析结果。缺厂商扩展时 vendor=false 且端口为 0。 */
    public static final class Probe {
        public byte[] guid;
        public String name;
        public boolean vendor;
        public int protoPort;
        public int filePort;
        public boolean pairingMode;
        public boolean paired;
    }

    public static Probe parseProbe(byte[] body) throws IOException {
        if (body.length < 17) {
            throw new IOException("探测包过短: " + body.length);
        }
        Probe pr = new Probe();
        pr.guid = new byte[16];
        System.arraycopy(body, 0, pr.guid, 0, 16);
        int[] next = new int[1];
        pr.name = readName(body, 16, next);
        int o = next[0];
        if (o + 8 <= body.length) {
            pr.vendor = true;
            pr.protoPort = u16(body, o);
            pr.filePort = u16(body, o + 2);
            pr.pairingMode = body[o + 4] != 0;
            pr.paired = body[o + 5] != 0;
        }
        return pr;
    }

    /**
     * 紧凑探测包：GUID(16) + FriendlyName，**不带厂商扩展尾**。
     * 对未携带 {@link #VENDOR_TAG} 的第三方探测请求回这个，保护标准互操作。
     */
    public static byte[] probeCompact(boolean request, byte[] guid, String friendlyName) {
        int nl = nameLen(friendlyName);
        byte[] p = new byte[16 + nl];
        System.arraycopy(guid, 0, p, 0, 16);
        writeName(p, 16, friendlyName);
        return frame(request ? T_PROBE_REQ : T_PROBE_RESP, p);
    }

    /** 友好名是否携带厂商标记（决定回扩展应答还是紧凑应答）。 */
    public static boolean hasVendorTag(String friendlyName) {
        return friendlyName != null && friendlyName.endsWith(VENDOR_TAG);
    }

    // ============================================================
    // 小工具
    // ============================================================

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 字节 → 小写十六进制。null 返回空串（调用点遍布协议各处，逐个判空太容易漏）。 */
    public static String hex(byte[] b) {
        if (b == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) {
            sb.append(HEX[(b[i] >> 4) & 0xF]).append(HEX[b[i] & 0xF]);
        }
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) {
            throw new IllegalArgumentException("非法十六进制串");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("非法十六进制串");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static boolean eq(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /** 常数时间比较（等长），避免时序侧信道。 */
    public static boolean eqConst(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
