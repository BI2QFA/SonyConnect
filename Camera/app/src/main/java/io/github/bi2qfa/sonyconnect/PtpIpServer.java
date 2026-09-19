package io.github.bi2qfa.sonyconnect;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PTP/IP 服务端（相机端）—— 双端口模型。
 *
 * <pre>
 *  ① 协议端口（默认 15740，绑不上按候选回退）—— 承载两条连接：
 *       控制连接：首包 Init Command Request(0x01) → 会话 / 事务号 / 操作码
 *       事件连接：首包 Init Event Request(0x03)   → 服务端主动推送 Event
 *  ② 文件端口（协议端口 +1 起递推）—— 承载数据连接：
 *       首包 DATA_OPEN(厂商 0x40，带一次性 token) → 文件流（Start/Data/End Data）
 *  ③ UDP 探测（与协议端口同号）：
 *       Probe Request(0x0D) 广播 → Probe Response(0x0E) 单播，回报**真实端口**
 * </pre>
 *
 * 关键设计：
 * - **端口不写死**：固件 libInfraPtpControl.so 内含 PtptIp 监听服务（`AcceptDataSocket` /
 *   `AcceptEventSocket`），端口必须能回退；实得端口由探测应答通告手机端。
 * - **事件通道是标准 PTP/IP 机制**，不是自研轮询。
 * - **踢旧语义**：同 deviceId 新控制连接踢旧；不同 deviceId 且正在传输 → `Init Fail(BUSY)`，
 *   **绝不打断正在跑的传输**。
 * - **全程明文**：加解密与密钥协商已整体移除（控制面与文件面都是）。准入只靠
 *   一件事 —— Init 阶段查配对表；链路内容不做任何加密。
 * - **有序关闭**：`stop()` 先停数据连接 → 再停协议/事件连接 → 关监听 → 清令牌表（铁律对应物）。
 * - 纯 Java、零 Android 依赖 → 桌面 desktop-test 可整体拉起做真 socket 断言。
 */
public class PtpIpServer {

    // ============================================================
    // 对外契约
    // ============================================================

    /** 相机侧业务回调（由 MainActivity / DeviceInfo / ThumbPrefetcher 实现）。 */
    public interface Handler {
        /** 本机 deviceId（8 字节）；服务端补零成 16 字节 GUID 落到协议字段。 */
        byte[] guid();

        /** 友好名，如 {@code ILCE-6300 05186914}。 */
        String deviceName();

        /** DEVICE_INFO 的 Data-In 载荷（厂商 JSON，字段并入现有 DeviceInfo）。 */
        byte[] deviceInfo();

        /**
         * 实时数据包（{@code OP_PING}）的应答载荷。
         *
         * @return 参数 {@code {batteryPct, hasLens?1:0}}
         */
        int batteryPct();

        boolean hasLens();

        /**
         * 镜头名（如 {@code E PZ 16-50mm F3.5-5.6 OSS}）。随 {@code OP_PING} 的
         * blob 一起下发 —— 它是实时数据（换镜头要立刻反映），不属于静态快照。
         *
         * @return 未装镜头或读取失败返回 null 或空串
         */
        String lensName();

        /** 是否允许该 initiator 接入 —— 唯一依据是它已经在配对表里。 */
        boolean allowInitiator(byte[] guid);

        /** 是否处于配对模式（发现应答里回报，供手机端筛选"可配对"相机）。 */
        boolean isPairingMode();

        /**
         * 是否已有至少一台已配对设备（发现应答里回报 `paired`）。
         * 手机端据此把相机分成「已配对／待配对」两组。P1 固定预共享阶段可恒返回 false。
         */
        boolean hasPairedInitiator();

        /** 读对象；kind 见 {@link PtpCodec#KIND_THUMB} 等。返回 null 表示不存在。 */
        byte[] readObject(String path, int kind, long offset, int length);

        /**
         * 传输期只读句柄（**可选能力**）：一次 {@code transfer()} 只开一次句柄、
         * 逐块读进去。
         *
         * <p>为什么要这个：相机 SD 上每次 open/close（外加 {@code isFile()} 那次
         * stat）都是毫秒级的，而数据通道是**按块**取的 —— 128 KiB 一块时每 MB 要
         * 付 8 次。几十 MB 的 ARW 光固定开销就吃掉好几秒。返回 null 表示本端不
         * 支持，服务端自动回退到 {@link #readObject} 逐块读（功能不变，只是慢）。
         */
        ObjectReader openObject(String path, int kind) throws IOException;

        /**
         * 传输期只读句柄。实现方负责把「打开」与「关闭」都做掉：服务端保证
         * 在一次传输结束（正常/异常/取消）时恰好调用一次 {@link #close()}。
         */
        interface ObjectReader {
            /**
             * 读 [offset, offset+length) 到 {@code dst[off..)}。
             *
             * @return 实际读到的字节数；0 表示到头；负数表示读失败
             */
            int readInto(long offset, byte[] dst, int off, int length);

            void close();
        }

        /** 对象大小；返回 -1 表示不存在。 */
        long objectSize(String path, int kind);

        /** 对象最后修改时间（毫秒）；返回 -1 表示未知。续传前用它校验对象未变。 */
        long objectMtime(String path);

        /** 目录列表：厂商 JSON 文本（UTF-8）；返回 null 表示路径不存在。 */
        byte[] listDir(String path, int offset, int limit);

        /** 缩略图队列控制：op 见 {@link PtpCodec#OP_THUMB_QUEUE_BEGIN} 等。 */
        void onThumbControl(int op, String[] paths);


    }

    /**
     * 配对阶段（**可选**）。不调用 {@link #setPairingHandler} 时服务器行为与
     * 配对功能引入前**完全一致** —— 这一点是刻意的：既有桌面回归里
     * 「已认证会话中 {@code PAIR_BEGIN} 回 {@code NOT_SUPPORTED}」等断言依赖它。
     *
     * <p>配对走的是**同一条控制连接**的握手期明文阶段：Init Cmd Ack 之后、
     * 控制循环之前。成功后本方法把手机设备名交给业务侧落表，服务器随即在同一条
     * 连接上进入控制循环 —— 手机端不需要重连。
     */
    public interface PairingHandler {
        /** 该手机是否已在配对表里。 */
        boolean isDevicePaired(byte[] guid8);

        /** 配对窗口是否有效（含过期判定）。 */
        boolean isPairingOpen();

        /** 当前 6 位配对码；窗口无效时返回 null。 */
        String pairingCode();

        /**
         * 配对成功（相机侧应落表并关窗）。
         *
         * @param guid8          手机 8 字节设备码
         * @param peerDeviceName 手机设备名（如 {@code Xiaomi 15}），来自 PAIR_BEGIN
         */
        void onPaired(byte[] guid8, String peerDeviceName);

        /**
         * 已配对设备（非配对路径）连上来时的"对名字"。
         *
         * <p>手机在系统设置里改设备名、或换了一台机器沿用同一个设备码，都是正常事；
         * 相机侧表里那份 {@code peerName} 是配对那一刻抄下来的，不跟新就永远显示旧名
         * （用户实测反馈：相机端一直显示旧的手机名）。
         *
         * <p>只更新名字，不影响准入 —— 准入那道门在 {@link #isDevicePaired} 已经过了。
         *
         * @param guid8          手机 8 字节设备码
         * @param peerDeviceName 本次连接带来的手机设备名（Init Command Request 里的）
         */
        void onPairedDeviceSeen(byte[] guid8, String peerDeviceName);

        /** 一次配对失败（窗口侧计次，用满次数自行关窗）。 */
        void onPairingFailed();

        /** 对端主动放弃配对。 */
        void onPairingAborted();

        /**
         * 已配对设备请求解除配对（{@code OP_PAIR_REMOVE}，发生在已认证会话内）。
         * 相机侧应删掉该 deviceId 的记录。
         */
        void onUnpair(byte[] guid8);
    }

    // ===== 配对阶段固定事务号（不占用会话事务空间） =====
    static final int TX_PAIR_BEGIN = 0x41;
    static final int TX_PAIR_EXCHANGE = 0x42;
    /** 对端主动放弃本次配对（只在配对阶段有效）。 */
    static final int TX_PAIR_ABORT = 0x44;

    /** ASCII 字面量 → 字节（避开 getBytes(charset) 的受检异常）。 */
    static byte[] ascii(String s) {
        byte[] o = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            o[i] = (byte) (s.charAt(i) & 0xFF);
        }
        return o;
    }

    /** UTF-8 字节 → 字符串（越界自动收缩；解码失败退回平台默认字符集）。 */
    static String utf8(byte[] b, int off, int len) {
        if (b == null || off < 0 || off >= b.length || len <= 0) {
            return "";
        }
        int n = Math.min(len, b.length - off);
        try {
            return new String(b, off, n, "UTF-8");
        } catch (Exception e) {
            return new String(b, off, n);
        }
    }

    /** 字符串 → UTF-8 字节（避开 getBytes(charset) 的受检异常）。 */
    static byte[] utf8(String s) {
        if (s == null) {
            return new byte[0];
        }
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    /** 取 16 字节 GUID 的前 8 字节 —— 规范里 deviceId 就是 8 字节，高 8 字节补零。 */
    static byte[] shortId(byte[] guid16) {
        byte[] o = new byte[8];
        if (guid16 != null) {
            System.arraycopy(guid16, 0, o, 0, Math.min(8, guid16.length));
        }
        return o;
    }

    /** 本机 8 字节 deviceId → 16 字节 GUID。 */
    private byte[] guid16() {
        byte[] g = handler.guid();
        byte[] o = new byte[16];
        if (g != null) {
            System.arraycopy(g, 0, o, 0, Math.min(g.length, 16));
        }
        return o;
    }

    /** UTF-8 行式文本 → 字符串数组（空 blob → 空数组）。用于 THUMB_QUEUE_* 的路径列表。 */
    static String[] splitLines(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return new String[0];
        }
        String s;
        try {
            s = new String(blob, "UTF-8");
        } catch (Exception e) {
            s = new String(blob);
        }
        List<String> out = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0x0A || c == 0x0D) {
                if (i > start) {
                    out.add(s.substring(start, i));
                }
                start = i + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out.toArray(new String[out.size()]);
    }

    /** 协议端口候选（依次尝试，第一个绑上的即为实得端口）。 */
    public static final int[] PROTO_PORT_CANDIDATES = {15740, 15741, 25740, 25741};

    /** 文件端口从协议端口 +1 起递推的最大偏移。 */
    private static final int FILE_PORT_SPAN = 16;

    /** 协议代次：P1–P4 内部仍报 1.0；P5 定稿时统一升 2.0。 */
    public static final int PROTO_VERSION = (1 << 16);

    /** 控制/事件连接空闲超时（毫秒）；手机端心跳 3s × 3 次失联判定，留足余量。 */
    private static final int CONTROL_IDLE_MS = 20000;

    /** 数据连接块间空闲超时（毫秒）。 */
    private static final int DATA_IDLE_MS = 15000;

    /** 一次性令牌有效期（毫秒）。 */
    private static final long TOKEN_TTL_MS = 15000L;

    // ============================================================
    // 实例状态
    // ============================================================

    private static PtpIpServer sActiveInstance;

    public static synchronized void setActiveInstance(PtpIpServer server) {
        sActiveInstance = server;
    }

    /** 强制停止当前存活实例（无实例则空操作），供退出兜底使用。 */
    public static synchronized void killActiveInstance() {
        if (sActiveInstance != null) {
            try {
                sActiveInstance.stop();
            } catch (Throwable t) {
                // 退出兜底路径，不再抛
            }
        }
    }

    private final Handler handler;

    /** 可选配对阶段；为 null 时服务器不提供配对，行为与配对功能引入前完全一致。 */
    private volatile PairingHandler pairingHandler;

    /** 同一时刻只允许一个配对流程（两台手机同时配对会互相串掉各自的 prekey）。 */
    private boolean pairingBusy;

    private ServerSocket protoServer;
    private ServerSocket fileServer;
    private DatagramSocket probeSocket;

    private Thread protoAcceptThread;
    private Thread fileAcceptThread;
    private Thread probeThread;

    private volatile boolean running;

    private int protoPort;
    private int filePort;

    /** 控制连接 + 事件连接 */
    private final List<Conn> conns = new ArrayList<Conn>();
    /** 活动数据连接 */
    private final List<Conn> fileConns = new ArrayList<Conn>();
    /** 一次性传输令牌 → 待发送对象 */
    private final Map<Long, Pending> pending = new HashMap<Long, Pending>();

    private long connCounter;
    private long tokenCounter;

    public PtpIpServer(Handler handler) {
        this.handler = handler;
        setActiveInstance(this);
    }

    /**
     * 挂上配对阶段。**不调用即不提供配对** —— 这是刻意的：既有桌面回归里
     * 「已认证会话中 {@code PAIR_BEGIN} 回 {@code NOT_SUPPORTED}」等断言依赖
     * 「没有 PairingHandler 时行为不变」。
     */
    public void setPairingHandler(PairingHandler h) {
        this.pairingHandler = h;
    }

    /** 取配对流程独占权；已有流程在进行时返回 false（拒绝并发配对）。 */
    private synchronized boolean acquirePairing() {
        if (pairingBusy) {
            return false;
        }
        pairingBusy = true;
        return true;
    }

    private synchronized void releasePairing() {
        pairingBusy = false;
    }

    /**
     * 配对流程是否正被占用（另一台手机正在配）。
     *
     * <p>用于把"设备被占用"明确回给对方：手机端着屏等人输码的这几十秒里，
     * 第二台手机若还能静悄悄进到配对阶段，两边的 prekey/配对码就会互相串掉。
     */
    public synchronized boolean isPairingInProgress() {
        return pairingBusy;
    }

    /** 当前连着的手机数（已授权的控制会话）。 */
    public int connectedClientCount() {
        List<Conn> all = snapshot(conns);
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            Conn c = all.get(i);
            if (c.role == ROLE_CONTROL && c.authorized && !c.closed) {
                n++;
            }
        }
        return n;
    }

    /** 当前连着的手机友好名；没有连接时返回 null（主屏"手机端已连接/未连接"读它）。 */
    public String connectedClientName() {
        List<Conn> all = snapshot(conns);
        for (int i = 0; i < all.size(); i++) {
            Conn c = all.get(i);
            if (c.role == ROLE_CONTROL && c.authorized && !c.closed) {
                return c.peerName == null ? "" : c.peerName;
            }
        }
        return null;
    }

    /**
     * 当前连着的手机的 8 字节设备码（hex）；没有连接时返回 null。
     *
     * <p>相机端在配对页解除某台手机的配对时需要它来判"解除的正是当前连着的这台吗"
     * —— 是的话必须立刻把解除通知推给对方（见 {@link #pushPairRemoved()}）。
     */
    public String connectedClientIdHex() {
        List<Conn> all = snapshot(conns);
        for (int i = 0; i < all.size(); i++) {
            Conn c = all.get(i);
            if (c.role == ROLE_CONTROL && c.authorized && !c.closed) {
                return PtpCodec.hex(shortId(c.deviceId));
            }
        }
        return null;
    }

    public int getProtoPort() {
        return protoPort;
    }

    public int getFilePort() {
        return filePort;
    }

    public boolean isRunning() {
        return running;
    }

    // ============================================================
    // 启停
    // ============================================================

    /** 绑定端口并开始监听；同步返回（绑不上会抛 IOException）。 */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        protoServer = bindPort(PROTO_PORT_CANDIDATES);
        protoPort = protoServer.getLocalPort();

        try {
            fileServer = bindFile(protoPort);
        } catch (IOException e) {
            closeQuietly(protoServer);
            protoServer = null;
            // 端口字段一并归零：否则 getProtoPort() 会报一个根本没在监听的端口，
            // MainActivity 会把死端口写进关于页/探测应答，排查时极具误导性。
            protoPort = 0;
            throw e;
        }
        filePort = fileServer.getLocalPort();

        // UDP 探测端口与协议端口同号。被占不致命：发现降级，手机端仍可手填 IP 直连。
        try {
            probeSocket = new DatagramSocket(null);
            probeSocket.setReuseAddress(true);
            probeSocket.bind(new InetSocketAddress(protoPort));
        } catch (Exception e) {
            closeQuietly(probeSocket);
            probeSocket = null;
        }

        running = true;
        AppLog.i("Net", "服务端启动：协议端口=" + protoPort + " 文件端口=" + filePort
                + (probeSocket != null ? " UDP探测已开" : " UDP探测不可用"));

        final ServerSocket ps = protoServer;
        final ServerSocket fs = fileServer;
        protoAcceptThread = newThread("ptpip-accept", new Runnable() {
            public void run() {
                acceptLoop(ps, false);
            }
        });
        fileAcceptThread = newThread("ptpip-file", new Runnable() {
            public void run() {
                acceptLoop(fs, true);
            }
        });
        if (probeSocket != null) {
            probeThread = newThread("ptpip-probe", new Runnable() {
                public void run() {
                    probeLoop();
                }
            });
        }

    }

    /** 依次尝试候选端口，第一个绑上的即为实得端口。 */
    private static ServerSocket bindPort(int[] candidates) throws IOException {
        IOException last = null;
        for (int i = 0; i < candidates.length; i++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(candidates[i]), 8);
                return ss;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("协议端口全部不可用: " + last);
    }

    /** 文件端口从 base+1 起递推，最多尝试 FILE_PORT_SPAN 个。 */
    private static ServerSocket bindFile(int base) throws IOException {
        IOException last = null;
        for (int off = 1; off <= FILE_PORT_SPAN; off++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(base + off), 4);
                return ss;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("文件端口全部不可用: " + last);
    }

    private static Thread newThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void closeQuietly(Object c) {
        try {
            if (c instanceof ServerSocket) {
                ((ServerSocket) c).close();
            } else if (c instanceof Socket) {
                ((Socket) c).close();
            } else if (c instanceof DatagramSocket) {
                ((DatagramSocket) c).close();
            }
        } catch (Throwable ignored) {
            // 关闭失败不再抛
        }
    }

    /**
     * 有序关闭（铁律对应物）：
     * ① 先停**数据连接**（把 SD 句柄 / 文件流转完或强拆）
     * ② 再停协议/事件连接
     * ③ 关全部监听（协议 + 文件 + UDP 探测）
     * ④ 清一次性令牌表
     * 全程不抛异常 —— 本方法会出现在退出链的 kill 列表里。
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;

        // ⓪ 先告诉已连的手机"相机端在退出"，再拆连接。
        //    顺序不能反：拆完就没通道可送了，对方只能等 3 次心跳失联（约 9 秒）
        //    才自己发现，而这段时间它的界面还写着"已连接"。
        //    stop() 只出现在退出 / 切换连接方式这两条路径上（六条退出路径全部收口
        //    到这里），两种情况下对方都必然要断，所以这条通知永远是实话。
        pushAppExiting();

        AppLog.w("Net", "服务端停止：拆 " + snapshot(fileConns).size() + " 条文件连接 / "
                + snapshot(conns).size() + " 条协议连接");
        // ① 数据连接
        List<Conn> data = snapshot(fileConns);
        for (int i = 0; i < data.size(); i++) {
            data.get(i).close();
        }
        // ② 协议 / 事件连接
        List<Conn> ctrl = snapshot(conns);
        for (int i = 0; i < ctrl.size(); i++) {
            ctrl.get(i).close();
        }
        // ②' 等连接线程真正退出**再往下走**。
        //     连接线程可能正卡在 SD 卡的一次读取里（照片 / 缩略图 / 目录列表），
        //     socket 关掉只是让下一次收发抛异常，**手里的文件句柄要等这一轮读完
        //     才由 finally 关掉**。进程若在这个窗口里被杀（退出链最后会调
        //     DAConnectionManager.finish()），卡上就留下一个没归位的占用 ——
        //     相机把它当成图像数据库失同步，下次开机挂"正在修复数据"。
        //     所以退出前必须等它们跑完，socket 已关，正常是毫秒级。
        joinAll(data);
        joinAll(ctrl);
        // ③ 监听
        closeQuietly(protoServer);
        closeQuietly(fileServer);
        closeQuietly(probeSocket);
        protoServer = null;
        fileServer = null;
        probeSocket = null;
        // 端口字段归零：停掉之后 getProtoPort() 不该再报一个已关闭的端口
        protoPort = 0;
        filePort = 0;
        // 配对流程若被退出流程打断，独占标记必须释放，否则下次启动无法再配对
        releasePairing();
        // ④ 令牌表
        synchronized (pending) {
            pending.clear();
        }
        synchronized (conns) {
            conns.clear();
        }
        synchronized (fileConns) {
            fileConns.clear();
        }
        if (sActiveInstance == this) {
            setActiveInstance(null);
        }
    }

    private static List<Conn> snapshot(List<Conn> src) {
        synchronized (src) {
            return new ArrayList<Conn>(src);
        }
    }

    /**
     * 给一批连接线程收尾的时间上限（毫秒）。
     *
     * <p>连接线程在 socket 被 close 后应当立刻从读写里抛出来、走完 finally ——
     * 正常情况下毫秒级就结束了。这里的上限只是**兜底**：万一某个线程卡在驱动的
     * 一次慢 IO 上，退出流程也不能被它无限拖住。按连接数分摊总预算。
     */
    private static final long EXIT_JOIN_MS = 2000;

    /**
     * 等一批连接线程结束（含捕获线程自己的情况）。
     *
     * <p>存在的理由见 {@link #stop()} 的 ②'：连接线程手里的 SD 文件句柄要等它
     * 跑完当前这轮 IO 才释放，而"释放 SD 卡占用"是退出铁律的一部分。
     */
    private static void joinAll(List<Conn> list) {
        long per = list.size() > 1 ? Math.max(200L, EXIT_JOIN_MS / list.size()) : EXIT_JOIN_MS;
        for (int i = 0; i < list.size(); i++) {
            Thread t = list.get(i).thread;
            // 事件连接的回调可能就在连接线程上跑（配对事件等），join 自己会死等
            if (t == null || t == Thread.currentThread()) continue;
            try {
                t.join(per);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ============================================================
    // 加解密层（原「加密帧」+ CryptoSuite）已整体移除：帧就是 PtpCodec 造好的
    // 明文帧，收发只走下面 Conn.send / Conn.recv。配对码也因此是明文过网 ——
    // 准入判定退化为"查配对表"，见 serveControl 的门。

    // 一次性传输令牌
    // ============================================================

    /** 一次性令牌对应的待发对象（`GET_OBJECT` 在控制连接登记，数据连接凭 token 取）。 */
    static final class Pending {
        /** 登记令牌时的 deviceId —— 令牌只对同一设备有效。 */
        byte[] deviceId;
        String path;
        int kind;
        long offset;
        /** 请求字节数；-1 表示"从 offset 到对象尾"。 */
        int length;
        long expiresAt;
    }

    /** 登记令牌 → 待发对象，返回令牌值。 */
    private long issueToken(byte[] deviceId, String path, int kind, long offset, int length) {
        long t;
        synchronized (pending) {
            pruneTokensLocked();
            t = ++tokenCounter;
            Pending p = new Pending();
            p.deviceId = deviceId;
            p.path = path;
            p.kind = kind;
            p.offset = offset;
            p.length = length;
            p.expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS;
            pending.put(Long.valueOf(t), p);
        }
        return t;
    }

    /** 取出并**立即作废**令牌（用后即废，防重放）。 */
    private Pending consumeToken(long token) {
        synchronized (pending) {
            pruneTokensLocked();
            return pending.remove(Long.valueOf(token));
        }
    }

    private void pruneTokensLocked() {
        long now = System.currentTimeMillis();
        List<Long> dead = new ArrayList<Long>();
        for (Map.Entry<Long, Pending> e : pending.entrySet()) {
            if (e.getValue().expiresAt < now) {
                dead.add(e.getKey());
            }
        }
        for (int i = 0; i < dead.size(); i++) {
            pending.remove(dead.get(i));
        }
    }

    // ============================================================
    // 连接
    // ============================================================

    static final int ROLE_UNKNOWN = 0;
    static final int ROLE_CONTROL = 1;
    static final int ROLE_EVENT = 2;
    static final int ROLE_FILE = 3;

    private final class Conn implements Runnable {

        final long id;
        private final Socket socket;
        private final boolean fileSide;

        private InputStream in;
        private OutputStream out;

        private int role = ROLE_UNKNOWN;
        private byte[] deviceId;
        private String peerName = "";
        private int connNo;
        private int protoVer;

        private volatile boolean closed;

        /**
         * 承载本连接的线程。{@link #stop()} 与 {@link #dropAllConnections()} 靠它
         * join，确保"退出/断连时不再有人握着 SD 句柄"（见 stop() 的 ②'）。
         */
        volatile Thread thread;

        /**
         * 本连接是否已获准使用 —— 认定标准只有一个：对端设备已在配对表里。
         *
         * <p>加解密移除后它就是唯一一道门。原来这里还有个 {@code authed} 表示
         * "双向认证通过"，而认证证明的正是"双方持有同一把会话密钥"；密钥没有了，
         * 那个概念也就不存在了。
         */
        private volatile boolean authorized;

        private long lastRead;

        Conn(Socket s, boolean fileSide) {
            synchronized (PtpIpServer.this) {
                this.id = ++connCounter;
            }
            this.socket = s;
            this.fileSide = fileSide;
            this.lastRead = System.currentTimeMillis();
        }

        // -------- 生命周期 --------

        public void run() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();

                PtpCodec.Msg first = PtpCodec.read(in);
                if (first == null) {
                    return;
                }
                lastRead = System.currentTimeMillis();

                if (fileSide) {
                    // 文件端口只认 DATA_OPEN；其它首包直接断开（不回包）
                    if (first.type == PtpCodec.T_DATA_OPEN) {
                        role = ROLE_FILE;
                        serveFile(first);
                    }
                    return;
                }

                if (first.type == PtpCodec.T_INIT_CMD_REQ) {
                    role = ROLE_CONTROL;
                    socket.setSoTimeout(CONTROL_IDLE_MS);
                    serveControl(first);
                } else if (first.type == PtpCodec.T_INIT_EVENT_REQ) {
                    role = ROLE_EVENT;
                    socket.setSoTimeout(CONTROL_IDLE_MS);
                    serveEvent(first);
                } else {
                    send(PtpCodec.initFail(PtpCodec.FAIL_UNSUPPORTED));
                }
            } catch (Throwable t) {
                // 单条连接的任何异常都只影响它自己：在这里吞掉，收尾交给 finally
            } finally {
                close();
            }
        }

        /** 幂等关闭；从登记表摘除自己。 */
        void close() {
            if (closed) {
                return;
            }
            closed = true;
            AppLog.i("Net", "断开 conn#" + id + (fileSide ? "（文件）" : "（协议）"));
            closeQuietly(socket);
            List<Conn> bucket = fileSide ? fileConns : conns;
            synchronized (bucket) {
                bucket.remove(this);
            }
        }

        // -------- 收发 --------

        /** 写一个完整帧。帧由 {@link PtpCodec} 直接造好，链路上不再有任何封装层。 */
        private synchronized void send(byte[] framed) throws IOException {
            out.write(framed);
            out.flush();
        }

        /**
         * 写帧的一段（传输热路径用）。帧是复用的缓冲，只有前 {@code len} 字节是这一帧。
         *
         * <p>没有 {@code flush()}：{@code out} 是**裸 socket 流**，本来就没有缓冲层
         * （原来那句 flush 是空操作），而每块调一次 flush 反而多一次虚调用。
         */
        private synchronized void send(byte[] framed, int off, int len) throws IOException {
            out.write(framed, off, len);
        }

        /** 收一帧；对端关闭抛 {@link EOFException}（由调用方决定断开）。 */
        private PtpCodec.Msg recv() throws IOException {
            PtpCodec.Msg m = PtpCodec.read(in);
            if (m == null) {
                throw new EOFException("对端关闭连接");
            }
            lastRead = System.currentTimeMillis();
            return m;
        }


        // -------- 控制连接 --------

        private void serveControl(PtpCodec.Msg first) throws IOException {
            if (first.body.length < 17) {
                send(PtpCodec.initFail(PtpCodec.FAIL_UNSUPPORTED));
                return;
            }
            byte[] g = new byte[16];
            System.arraycopy(first.body, 0, g, 0, 16);
            int[] next = new int[1];
            String name = PtpCodec.readName(first.body, 16, next);
            int pv = (next[0] + 4 <= first.body.length) ? PtpCodec.i32(first.body, next[0]) : 0;

            deviceId = g;
            peerName = name;
            protoVer = pv;

            byte[] guid8 = shortId(g);
            AppLog.i("Net", "Init Cmd Req：手机=" + name + " 设备码=" + PtpCodec.hex(guid8)
                    + " 协议版本=" + pv);
            PairingHandler ph = pairingHandler;
            // 走配对路径的条件：装了配对功能 且 这台手机尚未配对 且 配对窗口开着。
            // 三者缺一就落到下面的常规判定，从而在 ph == null 时行为与配对引入前一致。
            boolean pairPath = ph != null && !ph.isDevicePaired(guid8) && ph.isPairingOpen();

            if (!pairPath && !handler.allowInitiator(guid8)) {
                // **全链路唯一的准入判定**：不在配对表里 → 只能走配对，走不了就拒。
                AppLog.w("Net", "拒绝接入：不在配对表（pairPath=" + pairPath + "）");
                send(PtpCodec.initFail(PtpCodec.FAIL_NOT_PAIRED));
                return;
            }

            // 已配对设备的常规连接：把表里那份设备名对一下（手机可能改过名）。
            // 放在准入之后 —— 名字更新不属于准入，出问题也不该影响建连。
            if (!pairPath && ph != null) {
                try {
                    ph.onPairedDeviceSeen(guid8, name);
                } catch (Throwable t) {
                    // 业务侧异常不许挡住连接建立
                }
            }

            if (!evictOrReject(g)) {
                return;
            }

            // 配对是独占的：另一台手机正在配对（或正连着）时，后来的配对请求直接回
            // "设备被占用"。不能只把连接晾着 —— 那样手机端只能等到请求超时，
            // 报出来的是"网络不好"，用户会去反复重试。
            if (pairPath && isPairingInProgress()) {
                send(PtpCodec.initFail(PtpCodec.FAIL_BUSY));
                return;
            }

            connNo = (int) (id & 0x7FFFFFFF);
            send(PtpCodec.initCmdAck(connNo, guid16(), handler.deviceName(), PROTO_VERSION));

            if (pairPath && !pairingLoop(ph)) {
                return;
            }

            // 已授权：加解密与双向认证都已移除，到这里直接进控制循环
            authorized = true;
            controlLoop();
        }


        private boolean evictOrReject(byte[] g) throws IOException {
            List<Conn> others = snapshot(conns);
            for (int i = 0; i < others.size(); i++) {
                Conn o = others.get(i);
                if (o == this || o.role != ROLE_CONTROL) {
                    continue;
                }
                if (PtpCodec.eq(o.deviceId, g)) {
                    o.close();
                } else {
                    send(PtpCodec.initFail(PtpCodec.FAIL_BUSY));
                    return false;
                }
            }
            // 有活动数据传输且来自别的设备 → 同样拒绝
            List<Conn> fcs = snapshot(fileConns);
            for (int i = 0; i < fcs.size(); i++) {
                Conn fc = fcs.get(i);
                if (!PtpCodec.eq(fc.deviceId, g)) {
                    send(PtpCodec.initFail(PtpCodec.FAIL_BUSY));
                    return false;
                }
            }
            return true;
        }

        // -------- 配对阶段（Init Cmd Ack 之后、控制循环之前，全程明文） --------
        //
        //   ① 手机 → 相机 : PAIR_BEGIN    (req, tx=0x41)  手机设备名(UTF-8)
        //      相机 → 手机 : rsp                          devIdC(8)
        //   ② 手机 → 相机 : PAIR_EXCHANGE (req, tx=0x42)  6 位配对码(ASCII)
        //      相机 → 手机 : rsp                          RC_OK / RC_PAIRING_FAILED
        //
        // ★ 加解密已整体移除，配对码因此是**明文过网**的：同一网段抓包就能看到，
        //   它不再有密码学保护，作用退化为"证明操作者看得到相机屏幕上那个码"。
        //   真正的准入依据始终是配对表本身（见 serveControl 那道门）。
        // ★ 落表必须在回 RC_OK **之前**：回包就意味着"我已经记住了"。反过来的话，
        //   相机在回包与落表之间掉电，手机就会以为配对成功而相机什么都没有。

        private boolean pairingLoop(PairingHandler ph) throws IOException {
            if (!acquirePairing()) {
                // 竞态兜底（serveControl 已预检一次）：另一个配对刚刚抢到独占权。
                // 此时 Init Cmd Ack 已经发出，再补一个 Init Fail 会被手机端当成
                // **操作应答**解析（它已进入配对收发），所以改为收下它的
                // PAIR_BEGIN、以 RC_DEVICE_BUSY 明确回绝。
                PtpCodec.OpBlob b = recvPairOp();
                if (b != null) {
                    send(PtpCodec.opRspBlob(PtpCodec.RC_DEVICE_BUSY, b.txId, null, null));
                }
                return false;
            }
            try {
                return pairingExchange(ph);
            } finally {
                releasePairing();
            }
        }

        /** 收一个操作请求；不是 Operation Request 或载荷解析失败返回 null。 */
        private PtpCodec.OpBlob recvPairOp() throws IOException {
            PtpCodec.Msg m = recv();
            if (m.type != PtpCodec.T_OPERATION_REQ) {
                return null;
            }
            try {
                return PtpCodec.parseOpBlob(m.body);
            } catch (IOException e) {
                return null;
            }
        }

        private boolean pairingExchange(PairingHandler ph) throws IOException {
            byte[] devIdA = shortId(deviceId);
            byte[] devIdC = shortId(handler.guid());

            // ---- ① PAIR_BEGIN：收手机设备名，回本机设备码 ----
            PtpCodec.OpBlob b1 = recvPairOp();
            if (b1 == null) {
                return false;
            }
            if (b1.code == PtpCodec.OP_PAIR_ABORT) {
                send(PtpCodec.opRspBlob(PtpCodec.RC_OK, b1.txId, null, null));
                ph.onPairingAborted();
                return false;
            }
            if (b1.code != PtpCodec.OP_PAIR_BEGIN) {
                send(PtpCodec.opRspBlob(PtpCodec.RC_NOT_SUPPORTED, b1.txId, null, null));
                return false;
            }
            String code = ph.pairingCode();
            if (code == null || !ph.isPairingOpen()) {
                // 窗口已关（超时 / 5 次失败用尽 / 用户离开了配对页）
                send(PtpCodec.opRspBlob(PtpCodec.RC_PAIRING_FAILED, b1.txId, null, null));
                return false;
            }
            String peerDeviceName = utf8(b1.blob, 0, b1.blob == null ? 0 : b1.blob.length).trim();
            send(PtpCodec.opRspBlob(PtpCodec.RC_OK, b1.txId, null, devIdC));

            // ---- ② PAIR_EXCHANGE：验 6 位码 ----
            PtpCodec.OpBlob b2 = recvPairOp();
            if (b2 == null) {
                return false;
            }
            if (b2.code == PtpCodec.OP_PAIR_ABORT) {
                send(PtpCodec.opRspBlob(PtpCodec.RC_OK, b2.txId, null, null));
                ph.onPairingAborted();
                return false;
            }
            if (b2.code != PtpCodec.OP_PAIR_EXCHANGE) {
                send(PtpCodec.opRspBlob(PtpCodec.RC_NOT_SUPPORTED, b2.txId, null, null));
                return false;
            }
            String given = utf8(b2.blob, 0, b2.blob == null ? 0 : b2.blob.length).trim();
            if (!PtpCodec.eqConst(ascii(code), ascii(given))) {
                ph.onPairingFailed();
                send(PtpCodec.opRspBlob(PtpCodec.RC_PAIRING_FAILED, b2.txId, null, null));
                return false;
            }
            // 先落表、再回 OK：回包即代表"已存好"
            ph.onPaired(devIdA, peerDeviceName);
            send(PtpCodec.opRspBlob(PtpCodec.RC_OK, b2.txId, null, null));
            return true;
        }


        // -------- 事件连接 --------

        /**
         * 事件连接（方案 §4.3 / §3.1）：手机端发 Init Event Request(0x03)，
         * 服务端回 Init Event Ack 后保持长连接，由 {@link #pushEvent} 主动推送。
         * 事件连接不影响控制连接；断开即静默摘除，不触发退出。
         */
        private void serveEvent(PtpCodec.Msg first) throws IOException {
            if (first.body.length < 4) {
                send(PtpCodec.initFail(PtpCodec.FAIL_UNSUPPORTED));
                return;
            }
            int conn = PtpCodec.i32(first.body, 0);
            Conn owner = findControlByConnNo(conn);
            if (owner == null) {
                send(PtpCodec.initFail(PtpCodec.FAIL_NOT_PAIRED));
                return;
            }
            // 事件连接沿用控制会话的标识（同一 deviceId）
            deviceId = owner.deviceId;
            peerName = owner.peerName;
            connNo = conn;
            authorized = true;

            send(PtpCodec.initEventAck());
            eventLoop();
        }

        private Conn findControlByConnNo(int conn) {
            List<Conn> all = snapshot(conns);
            for (int i = 0; i < all.size(); i++) {
                Conn c = all.get(i);
                if (c.role == ROLE_CONTROL && c.connNo == conn) {
                    return c;
                }
            }
            return null;
        }


        // -------- 控制循环 --------

        private void controlLoop() throws IOException {
            while (running && !closed) {
                PtpCodec.Msg m;
                try {
                    m = recv();
                } catch (SocketTimeoutException te) {
                    return;
                }
                if (m.type == PtpCodec.T_OPERATION_REQ) {
                    dispatch(m);
                } else if (m.type == PtpCodec.T_CANCEL) {
                } else {
                }
            }
        }

        /** 操作码 → 名字（日志里比十六进制好认）。 */
        private String opName(int code) {
            switch (code) {
                case PtpCodec.OP_PING: return "PING(心跳/电量/镜头)";
                case PtpCodec.OP_DEVICE_INFO: return "DEVICE_INFO(设备信息)";
                case PtpCodec.OP_LIST_DIR: return "LIST_DIR(列目录)";
                case PtpCodec.OP_STAT: return "STAT(对象属性)";
                case PtpCodec.OP_GET_OBJECT: return "GET_OBJECT(取对象)";
                case PtpCodec.OP_THUMB_QUEUE_BEGIN: return "THUMB_BEGIN(缩略图预取)";
                case PtpCodec.OP_THUMB_QUEUE_PAUSE: return "THUMB_PAUSE";
                case PtpCodec.OP_THUMB_QUEUE_RESUME: return "THUMB_RESUME";
                case PtpCodec.OP_THUMB_QUEUE_CANCEL: return "THUMB_CANCEL";
                case PtpCodec.OP_PAIR_BEGIN: return "PAIR_BEGIN";
                case PtpCodec.OP_PAIR_EXCHANGE: return "PAIR_EXCHANGE";
                case PtpCodec.OP_PAIR_ABORT: return "PAIR_ABORT";
                case PtpCodec.OP_PAIR_REMOVE: return "PAIR_REMOVE";
                default: return "?";
            }
        }

        /** 单个操作请求 → 响应。解析异常只记录，不打断会话。 */
        private void dispatch(PtpCodec.Msg m) throws IOException {
            PtpCodec.Op op;
            try {
                op = PtpCodec.parseOp(m.body);
            } catch (IOException e) {
                return;
            }
            int tx = op.txId;
            if (op.code != PtpCodec.OP_PING) {   // PING 是 3s 一次的心跳，记了会淹掉日志
                AppLog.i("Net", "命令 0x" + Integer.toHexString(op.code) + " " + opName(op.code));
            }

            switch (op.code) {
                case PtpCodec.OP_PING:

                    // 唯一的实时包：电量 + 有无镜头进参数，镜头名进 blob。
                    // 一次往返带回全部会变的数据，不再让电量与镜头各占一个短周期定时器。
                    rspPing(tx);
                    break;

                case PtpCodec.OP_DEVICE_INFO:
                    rspBlobOk(tx, handler.deviceInfo());
                    break;

                case PtpCodec.OP_THUMB_QUEUE_BEGIN:
                case PtpCodec.OP_THUMB_QUEUE_PAUSE:
                case PtpCodec.OP_THUMB_QUEUE_RESUME:
                case PtpCodec.OP_THUMB_QUEUE_CANCEL:
                    handler.onThumbControl(op.code, splitLines(blobOf(m.body)));
                    rspOk(tx, null);
                    break;

                default:
                    if (!dispatchData(op, tx, m)) {
                        rspCode(tx, PtpCodec.RC_NOT_SUPPORTED);
                    }
                    break;
            }
        }

        // -------- 响应helper --------

        private void rspOk(int tx, int[] params) throws IOException {
            send(PtpCodec.opRsp(PtpCodec.DP_NONE, PtpCodec.RC_OK, tx, params));
        }

        private void rspCode(int tx, int code) throws IOException {
            send(PtpCodec.opRsp(PtpCodec.DP_NONE, code, tx, null));
        }

        private void rspBlobOk(int tx, byte[] blob) throws IOException {
            send(PtpCodec.opRspBlob(PtpCodec.RC_OK, tx, null, blob));
        }

        /**
         * PING 应答：参数 {@code {batteryPct, hasLens?1:0}} + blob 镜头名。
         *
         * <p>镜头名放 blob 是因为它是字符串；空 blob 明确表示"未装镜头或读不到"，
         * 手机端据此清空显示。这是**唯一**的周期性响应，帧长度会随镜头名长短浮动
         * （几十字节量级，可忽略）。
         */
        private void rspPing(int tx) throws IOException {
            String lens = handler.lensName();
            send(PtpCodec.opRspBlob(PtpCodec.RC_OK, tx,
                    new int[]{handler.batteryPct(), handler.hasLens() ? 1 : 0},
                    lens == null ? new byte[0] : utf8(lens)));
        }

        /** 从带 blob 的请求体取 blob；非 blob 形式或解析失败一律返回空数组。 */
        private byte[] blobOf(byte[] plain) {
            try {
                return PtpCodec.parseOpBlob(plain).blob;
            } catch (IOException e) {
                return new byte[0];
            }
        }

        /** 请求 blob → 路径字符串（空 blob 视为根目录）。 */
        private String pathOf(byte[] plain) {
            return pathOfBlob(blobOf(plain));
        }

        /** blob → 路径字符串（空 blob 视为根目录）。 */
        private String pathOfBlob(byte[] b) {
            if (b == null || b.length == 0) {
                return "/";
            }
            try {
                return new String(b, "UTF-8");
            } catch (Exception e) {
                return new String(b);
            }
        }

        /**
         * 数据面操作分发。返回 false 表示"不认识的码"，交给上层回 OperationNotSupported。
         * 元数据内联返回（§4.5）；只有 GET_OBJECT 开数据通道。
         */
        private boolean dispatchData(PtpCodec.Op op, int tx, PtpCodec.Msg m) throws IOException {
            switch (op.code) {
                case PtpCodec.OP_LIST_DIR: {
                    PtpCodec.OpBlob g;
                    try {
                        g = PtpCodec.parseOpBlob(m.body);
                    } catch (IOException e) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    String path = pathOfBlob(g.blob);
                    if (!isPathSafe(path)) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    int offset = g.params.length > 0 ? Math.max(0, g.params[0]) : 0;
                    int limit = g.params.length > 1 ? Math.max(0, g.params[1]) : 0;
                    byte[] json = handler.listDir(path, offset, limit);
                    if (json == null) {
                        rspCode(tx, PtpCodec.RC_NOT_FOUND);
                        return true;
                    }
                    rspBlobOk(tx, json);
                    return true;
                }
                case PtpCodec.OP_STAT: {
                    String path = pathOf(m.body);
                    if (!isPathSafe(path)) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    long size = handler.objectSize(path, PtpCodec.KIND_ORIGINAL);
                    if (size < 0) {
                        rspCode(tx, PtpCodec.RC_NOT_FOUND);
                        return true;
                    }
                    long mtime = handler.objectMtime(path);
                    rspOk(tx, new int[]{
                            (int) size, (int) (size >>> 32),
                            (int) mtime, (int) (mtime >>> 32)});
                    return true;
                }
                case PtpCodec.OP_GET_OBJECT: {
                    // ★ 必须整体按 blob 形式解析：数值参数与路径 blob 同处一个 body，
                    //   参数区偏移 14 起（parseOp 的偏移 10 会错位）。
                    PtpCodec.OpBlob g;
                    try {
                        g = PtpCodec.parseOpBlob(m.body);
                    } catch (IOException e) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    String path = pathOfBlob(g.blob);
                    if (!isPathSafe(path)) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    int kind = g.params.length > 0 ? g.params[0] : PtpCodec.KIND_THUMB;
                    long off = g.params.length > 1 ? (g.params[1] & 0xFFFFFFFFL) : 0L;
                    if (g.params.length > 2) {
                        off |= ((long) g.params[2]) << 32;
                    }
                    int len = g.params.length > 3 ? g.params[3] : -1;
                    if (off < 0) {
                        rspCode(tx, PtpCodec.RC_ACCESS_DENIED);
                        return true;
                    }
                    long size = handler.objectSize(path, kind);
                    if (size < 0 || off > size) {
                        rspCode(tx, PtpCodec.RC_NOT_FOUND);
                        return true;
                    }
                    // 登记一次性令牌，并把**实得文件端口**一并回给手机（端口可能回退过）
                    long token = issueToken(deviceId, path, kind, off, len);
                    rspOk(tx, new int[]{(int) token, filePort});
                    return true;
                }
                case PtpCodec.OP_PAIR_BEGIN:
                case PtpCodec.OP_PAIR_EXCHANGE:
                case PtpCodec.OP_PAIR_ABORT:
                    // 配对只在握手期受理；会话建好之后再提就是协议错用
                    rspCode(tx, PtpCodec.RC_NOT_SUPPORTED);
                    return true;
                case PtpCodec.OP_PAIR_REMOVE: {
                    // 解除配对：已认证会话内执行（手机端「解除配对」）。
                    // 请求者就是会话归属的那台手机，所以直接按会话 deviceId 删。
                    PairingHandler ph = pairingHandler;
                    if (ph == null) {
                        rspCode(tx, PtpCodec.RC_NOT_SUPPORTED);
                        return true;
                    }
                    ph.onUnpair(shortId(deviceId));
                    rspOk(tx, null);
                    return true;
                }
                default:
                    return false;
            }
        }

        // -------- 数据连接（文件端口） --------

        /**
         * 文件端口首包 DATA_OPEN{GUID(16), ConnNo(4), token(8)}：
         * ① 令牌一次性校验（用后即废 + 绑设备 + 15s TTL）
         * ② 必须找得到该设备**已获准**的控制会话 —— 令牌只是取件凭据，
         *    准入判定已经在控制连接上做过一次，这里再确认会话还在
         * ③ 回 DATA_OPEN_ACK → Start Data → Data* → End Data 分块直发
         *
         * ★ 中途取消靠手机端断开 socket 表达（write 侧抛 IOException 即收敛）。
         */
        private void serveFile(PtpCodec.Msg first) throws IOException {
            AppLog.i("Net", "文件端口 DATA_OPEN（conn#" + id + "）");
            PtpCodec.DataOpen d;
            try {
                d = PtpCodec.parseDataOpen(first.body);
            } catch (IOException e) {
                return;
            }
            deviceId = d.guid;

            Pending p = consumeToken(d.token);
            if (p == null) {
                AppLog.w("Net", "DATA_OPEN 令牌无效/过期 → 断开");
                return;
            }
            if (!PtpCodec.eq(p.deviceId, deviceId)) {
                return;
            }

            Conn owner = findControlByDevice(deviceId);
            if (owner == null) {
                AppLog.w("Net", "DATA_OPEN 找不到控制会话 → 断开");
                return;
            }
            if (d.connNo != owner.connNo) {
                AppLog.w("Net", "DATA_OPEN connNo 不匹配："
                        + d.connNo + " != " + owner.connNo);
                return;
            }
            connNo = d.connNo;
            socket.setSoTimeout(DATA_IDLE_MS);
            send(PtpCodec.dataOpenAck());

            authorized = true;
            transfer(p);
        }

        /**
         * 分块读取 + 直接发送（链路上已无加密层）。
         *
         * <p>关键性能取舍：优先用 {@link Handler#openObject} 拿一个**传输期只读
         * 句柄**，整段传输只 open 一次 SD 上的文件。逐块 {@code readObject} 的写法
         * 在相机上是每块一次 open/close 加一次 stat，几十 MB 就是几秒 —— 传输慢
         * 的主因就在这。句柄在正常/异常/取消任何路径上都要关掉，否则 SD 句柄
         * 会攒着不放。
         */
        private void transfer(Pending p) throws IOException {
            AppLog.i("Net", "开始传输 " + p.path + " kind=" + p.kind
                    + " offset=" + p.offset + " len=" + p.length);
            long total = handler.objectSize(p.path, p.kind);
            if (total < 0) {
                return;
            }
            long start = p.offset;
            long end = (p.length < 0) ? total : Math.min(total, start + p.length);
            long remaining = Math.max(0L, end - start);
            int tx = 1;

            Handler.ObjectReader reader = null;
            try {
                reader = handler.openObject(p.path, p.kind);
            } catch (Throwable t) {
                reader = null;
            }

            long sent = 0;
            try {
                send(PtpCodec.startData(tx, remaining));

                // ★ 一整段传输**只分配这一块缓冲**，数据由 readInto 直接读进载荷区，
                //   包头 12 字节就地写 —— 见 PtpCodec.dataFrameCapacity 的注释：
                //   原来每 128 KiB 要分配两次（载荷副本 + 整帧）、每字节拷两遍，
                //   几十 MB 的文件就是几百 MB 的垃圾与 memcpy，全落在相机的小堆上。
                byte[] frame = new byte[PtpCodec.dataFrameCapacity()];
                while (running && !closed && sent < remaining) {
                    int want = (int) Math.min((long) PtpCodec.CHUNK, remaining - sent);
                    int got = readChunk(reader, p, start + sent, frame,
                            PtpCodec.DATA_PAYLOAD_OFFSET, want);
                    if (got <= 0) {
                        // 读失败或已到对象尾：保留断点，手机端按实收长度走续传
                        return;
                    }
                    boolean last = sent + got >= remaining;
                    PtpCodec.fillDataFrame(frame,
                            last ? PtpCodec.T_END_DATA : PtpCodec.T_DATA, tx, got);
                    send(frame, 0, PtpCodec.dataFrameLen(got));
                    sent += got;
                }

                if (remaining == 0) {
                    // 零长度对象也要走一个完整 Start/End 对
                    send(PtpCodec.endData(tx, new byte[0]));
                }
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable ignored) {
                        // 关句柄失败不影响已经发完的数据
                    }
                }
            }
        }

        /** 取一块数据：优先走句柄读进 {@code dst}，没有句柄才回退逐块 readObject。 */
        private int readChunk(Handler.ObjectReader reader, Pending p, long offset,
                              byte[] dst, int dstOff, int want) {
            if (reader != null) {
                return reader.readInto(offset, dst, dstOff, want);
            }
            byte[] chunk = handler.readObject(p.path, p.kind, offset, want);
            if (chunk == null || chunk.length == 0) {
                return 0;
            }
            int n = Math.min(chunk.length, want);
            System.arraycopy(chunk, 0, dst, dstOff, n);
            return n;
        }


        private Conn findControlByDevice(byte[] g) {
            List<Conn> all = snapshot(conns);
            for (int i = 0; i < all.size(); i++) {
                Conn c = all.get(i);
                if (c.role == ROLE_CONTROL && c.authorized && PtpCodec.eq(c.deviceId, g)) {
                    return c;
                }
            }
            return null;
        }

        /**
         * 事件连接循环：本连接**只发不收**（手机端仅用于接收推送）。
         * 空闲超时只续命不断开 —— 事件可能长时间没有内容，断开只应由 TCP 层感知。
         */
        private void eventLoop() throws IOException {
            while (running && !closed) {
                PtpCodec.Msg m;
                try {
                    m = recv();
                } catch (SocketTimeoutException te) {
                    continue;
                }
                if (m.type == PtpCodec.T_INIT_EVENT_REQ) {
                    send(PtpCodec.initEventAck());
                } else {
                }
            }
        }
    }

    /**
     * 接受循环：协议端口与文件端口共用。
     * 只 accept + 起线程，角色判别放在 Conn.run() 里读首包决定。
     */
    private void acceptLoop(ServerSocket ss, boolean isFile) {
        while (running) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (running) {
                }
                break;
            }
            if (!running) {
                closeQuietly(s);
                break;
            }
            try {
                s.setTcpNoDelay(true);
                s.setSendBufferSize(256 * 1024);
                s.setReceiveBufferSize(256 * 1024);
            } catch (Exception e) {
                // 优化项，失败不影响功能
            }
            Conn c = new Conn(s, isFile);
            AppLog.i("Net", (isFile ? "文件" : "协议") + "端口接入 " + s.getInetAddress()
                    + " conn#" + c.id);
            List<Conn> bucket = isFile ? fileConns : conns;
            synchronized (bucket) {
                bucket.add(c);
            }
            // 线程引用回填到 Conn 上（退出链要 join 它，见 stop() 的 ②'）。
            // 先入 bucket 再起线程：起之前没人能 join 到一个半启动的线程。
            c.thread = newThread("ptpip-conn-" + c.id, c);
        }
    }

    // ============================================================
    // UDP 发现（方案 §5）
    // ============================================================

    /**
     * 探测循环：收 Probe Request(0x0D) → 单播回 Probe Response(0x0E)。
     *
     * 应答分两档（方案 §5.2）：
     * - 请求 FriendlyName 带 {@link PtpCodec#VENDOR_TAG}（我们自己的手机端）→ 回**扩展应答**，
     *   尾巴回报 protoPort / filePort / pairingMode / paired，手机端拿它直接连，不必猜端口；
     * - 第三方标准客户端 / 未带标记 → 回**紧凑应答**（仅 GUID + FriendlyName），保互操作。
     *
     * 不校验来源（谁问都答，局域网内本就可见），但**只单播回源地址**，不广播。
     * 探测失败不致命：手机端仍可手填 IP + 协议端口直连。
     */
    private void probeLoop() {
        byte[] buf = new byte[1024];
        while (running) {
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            try {
                probeSocket.receive(pkt);
            } catch (IOException e) {
                if (running) {
                }
                break;
            }
            try {
                byte[] raw = new byte[pkt.getLength()];
                System.arraycopy(pkt.getData(), pkt.getOffset(), raw, 0, pkt.getLength());
                PtpCodec.Msg m = PtpCodec.read(new ByteArrayInputStream(raw));
                if (m == null || m.type != PtpCodec.T_PROBE_REQ) {
                    continue;
                }
                PtpCodec.Probe pr = PtpCodec.parseProbe(m.body);
                byte[] resp;
                if (PtpCodec.hasVendorTag(pr.name)) {
                    resp = PtpCodec.probe(false, guid16(), vendorName(),
                            protoPort, filePort,
                            handler.isPairingMode(), handler.hasPairedInitiator());
                } else {
                    resp = PtpCodec.probeCompact(false, guid16(), handler.deviceName());
                }
                DatagramPacket out = new DatagramPacket(resp, resp.length,
                        pkt.getAddress(), pkt.getPort());
                probeSocket.send(out);
            } catch (Throwable t) {
                // 单包异常绝不能拖垮发现循环
            }
        }
    }

    /** 探测应答里的友好名：业务名 + 厂商标记，供手机端确认对端确为 SonyConnect 相机。 */
    private String vendorName() {
        String n = handler.deviceName();
        if (n == null || n.length() == 0) {
            n = "SonyConnect";
        }
        return n + " " + PtpCodec.VENDOR_TAG;
    }

    // ============================================================
    // 事件推送（方案 §4.5）
    // ============================================================

    /**
     * 向所有已挂载的事件连接推送一条 Event。
     *
     * 事件连接缺席（手机端未挂事件通道）时静默丢弃 —— 事件是锦上添花，
     * 绝不允许因为没人收就阻塞或抛异常到调用方（调用方通常是缩略图预取线程）。
     *
     * @param evCode 事件码，见 {@link PtpCodec#EV_THUMB_PROGRESS} 等
     * @param txId   关联事务号；不关联具体事务时传 0
     * @param params 事件参数；无参数传 null
     */
    public void pushEvent(int evCode, int txId, int[] params) {
        byte[] framed = PtpCodec.event(evCode, txId, params);
        List<Conn> all = snapshot(conns);
        int sent = 0;
        for (int i = 0; i < all.size(); i++) {
            Conn c = all.get(i);
            if (c.role != ROLE_EVENT || c.closed || !c.authorized) {
                continue;
            }
            try {
                c.send(framed);
                sent++;
            } catch (IOException e) {
                c.close();
            }
        }
        if (sent == 0) {
        }
    }

    /**
     * 通知"本机已解除与你的配对"（相机端在配对页删掉某台手机时调用）。
     *
     * <p>这是配对解除**送达**的那条路：对方正连着才有事件通道可送，收到就清本地记录
     * 并断开。对方不在线时无路可送 —— 那种情况由下次连接时的
     * {@code Init Fail(NOT_PAIRED)} 兜底（手机端收到该拒绝码会自动清本地记录，
     * 这就是"未连接时单方解除"的同步机制）。
     *
     * <p>事件不带参数：相机同一时刻只服务一台手机（单客户端语义），"是你在解我"
     * 没有歧义。
     */
    public void pushPairRemoved() {
        pushEvent(PtpCodec.EV_PAIRED_REMOVED, 0, null);
    }

    /**
     * 通知"相机端正在退出"（手机端收到即断开当前会话）。
     *
     * <p>由 {@link #stop()} 在拆连接之前调用，所以退出 / 切换连接方式这两类路径都会
     * 通知到；对方不在线时无路可送，那种情况它本来也没有会话要收。
     *
     * <p>★ 但**切换连接方式**那条路上这句是假话（相机没退出，只是要重配无线电），
     * 所以 {@link #pushModeSwitching(int)} 先把原因说清楚并置位，这里就不再补一句
     * 会把它覆盖掉的"已退出"。
     */
    public void pushAppExiting() {
        if (modeSwitchNotified) {
            modeSwitchNotified = false;
            return;
        }
        pushEvent(PtpCodec.EV_APP_EXITING, 0, null);
    }

    /**
     * 本次拆连接的原因是"切换连接方式"（置位后抑制 {@link #pushAppExiting()}）。
     *
     * <p>只在**同一台服务器实例**内有效：新方式起来时是另一个 {@code PtpIpServer}
     * 实例（见 MainActivity#onPtpReady），所以不必担心标志跨方式残留。
     */
    private volatile boolean modeSwitchNotified = false;

    /**
     * 通知"相机端正在切换连接方式"（手机端收到即断开，但要显示**另一句话**）。
     *
     * <p>必须在 {@code shutdownServicesAndRadio()} 之前调用 —— 那条路会
     * {@link #stop()} 拆连接，拆完就没通道可送了。调用时机见
     * {@code MainActivity#beginModeSwitch}。
     *
     * @param mode 0 = Wi-Fi 网络（接入点），1 = 相机热点，见
     *             {@link PtpCodec#MODE_CODE_WIFI} / {@link PtpCodec#MODE_CODE_HOTSPOT}
     */
    public void pushModeSwitching(int mode) {
        modeSwitchNotified = true;
        pushEvent(PtpCodec.EV_MODE_SWITCHING, 0, new int[] { mode });
    }

    /**
     * 把当前连着的手机请走，但**不停服务器**（进配对模式前调用）。
     *
     * <p>进配对模式的语义是"要配一台新手机"，此时旧会话必须先断 —— 否则那台老手机
     * 还握着一份已经作废的会话，它的界面写着"已连接"，实际什么都做不了。
     *
     * <p>通知用 {@link PtpCodec#EV_APP_EXITING}（手机端收到即断开当前会话、
     * **保留配对记录**）。**不能用** {@link #pushPairRemoved()} —— 那是"解除配对"，
     * 会让手机端把本地记录一起清掉，用户只是要配新手机，没说要解绑旧的。
     *
     * <p>与 {@link #stop()} 的区别：监听端口、配对窗口、令牌表全部保持存活，
     * 新手机照样连得进来。返回前同样 join 连接线程，理由见 stop() 的 ②'。
     */
    public void dropAllConnections() {
        pushAppExiting();
        List<Conn> data = snapshot(fileConns);
        for (int i = 0; i < data.size(); i++) {
            data.get(i).close();
        }
        List<Conn> ctrl = snapshot(conns);
        for (int i = 0; i < ctrl.size(); i++) {
            ctrl.get(i).close();
        }
        joinAll(data);
        joinAll(ctrl);
    }

    // ============================================================
    // 路径校验（方案 §8.4）
    // ============================================================

    /**
     * 路径逃逸拦截。规则：
     * <ul>
     *   <li>null / 空 / 非 {@code '/'} 开头 → 拒（只接受挂载点视角的绝对路径）</li>
     *   <li>含 NUL 或反斜杠 → 拒（防注入与窗口侧分隔符混用）</li>
     *   <li>含 {@code ..} 段 → 拒（防 {@code /mnt/sdcard/../../etc} 逃逸）</li>
     * </ul>
     * 放行前不做归一化 —— 归一化本身就容易出错，直接拒绝可疑形态更安全。
     * 以 {@code static} 暴露，便于 desktop-test 单测直接断言。
     */
    static boolean isPathSafe(String path) {
        if (path == null || path.length() == 0 || path.charAt(0) != '/') {
            return false;
        }
        if (path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        String[] segs = path.split("/");
        for (int i = 0; i < segs.length; i++) {
            if ("..".equals(segs[i]) || ".".equals(segs[i])) {
                return false;
            }
        }
        return true;
    }
}
